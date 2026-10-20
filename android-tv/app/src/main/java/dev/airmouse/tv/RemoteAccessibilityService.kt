package dev.airmouse.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/** Android 不允许普通应用注入全局鼠标 HID；本服务用系统授权的触屏手势模拟鼠标操作。 */
class RemoteAccessibilityService : AccessibilityService(), ControlServer.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pointerIdleTimeoutMs = 2_000L
    private val hidePointerRunnable = Runnable { if (::overlay.isInitialized) overlay.hidePointer() }
    private lateinit var overlay: PointerOverlay
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private var screenBounds = Rect()
    private var pointerX = 0f
    private var pointerY = 0f
    private var dragPath: Path? = null
    private var dragStartedAt = 0L
    private var connectedClients = 0
    private var editorWorker: ExecutorService? = null
    private val editorKeys = setOf("enter", "backspace", "space", "cursor_left", "cursor_right")

    override fun onServiceConnected() {
        super.onServiceConnected()
        editorWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "airmouse-editor").apply { isDaemon = true } }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        overlay = PointerOverlay(this, windowManager)
        updateScreenBounds()
        pointerX = screenBounds.exactCenterX()
        pointerY = screenBounds.exactCenterY()
        overlay.update(pointerX, pointerY, screenBounds)
        schedulePointerHide()
        TvStatusStore.update { it.copy(accessibilityEnabled = true, message = null) }
        ControlServer.start(applicationContext, this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        editorWorker?.shutdownNow()
        editorWorker = null
        mainHandler.removeCallbacks(hidePointerRunnable)
        ControlServer.stop()
        overlay.hide()
        TvStatusStore.update { it.copy(accessibilityEnabled = false, pairingUrl = null) }
        dragPath = null
        return super.onUnbind(intent)
    }

    override fun onAction(action: RemoteAction) {
        mainHandler.post {
            if (action is RemoteAction.Text || action is RemoteAction.SceneText ||
                (action is RemoteAction.Key && action.name in editorKeys)
            ) {
                // IME 生命周期在主线程捕获；远程文本查询/无障碍 API 可能等待数秒，
                // 交给独立串行队列，避免拖住光标绘制、IME 回调和后续输入。
                val session = RemoteInputBridge.captureSession()
                editorWorker?.execute {
                    runCatching { dispatch(action, session) }.onFailure { error ->
                        Log.w("AirMouseInput", "电视编辑命令执行失败", error)
                        val requestId = when (action) {
                            is RemoteAction.Text -> action.requestId
                            is RemoteAction.Key -> action.requestId
                            is RemoteAction.SceneText -> action.requestId
                            else -> null
                        }
                        if (requestId != null) ControlServer.notifyActionResult(requestId, false, editorUnavailableMessage)
                        else reportEditorUnavailable()
                    }
                }
            } else dispatch(action)
        }
    }

    override fun onClientCountChanged(count: Int) {
        mainHandler.post {
            // 某一台手机断开时也要结束它可能遗留的按下状态，即使还有其他手机在线。
            if (count < connectedClients) finishDrag()
            connectedClients = count
        }
    }

    override fun onPartyState(state: org.json.JSONObject) {
        mainHandler.post { overlay.updatePartyState(state) }
    }

    private fun dispatch(action: RemoteAction, editor: RemoteEditorSession? = null) {
        when (action) {
            is RemoteAction.Move -> movePointer(action.dx, action.dy)
            is RemoteAction.Click -> click(action.count)
            RemoteAction.DragStart -> startDrag()
            RemoteAction.DragEnd -> finishDrag()
            is RemoteAction.Scroll -> scroll(action.dx, action.dy)
            is RemoteAction.Key -> {
                val result = handleKey(action.name, editor)
                if (action.requestId != null) ControlServer.notifyActionResult(action.requestId, result,
                    if (result) "编辑操作已提交到电视。" else editorUnavailableMessage)
            }
            is RemoteAction.Text -> inputText(action, editor)
            is RemoteAction.EnableAirMouse -> Unit // ControlServer 在网络线程为请求客户端建立 TLS 通道。
            is RemoteAction.ListApps -> Unit // 应用列表由 ControlServer 按请求连接返回。
            is RemoteAction.LaunchApp -> launchApp(action)
            is RemoteAction.SceneText -> sendSceneText(action, editor)
            is RemoteAction.JoinParty,
            is RemoteAction.StartQuiz,
            is RemoteAction.Buzz,
            is RemoteAction.StartVote,
            is RemoteAction.Vote,
            is RemoteAction.EndParty -> Unit // 同乐命令由 ControlServer/PartyRoom 原子处理。
            is RemoteAction.CursorStyle -> overlay.updateCursorStyle(action.style)
        }
    }

    private fun launchApp(action: RemoteAction.LaunchApp) {
        val launchIntent = packageManager.getLaunchIntentForPackage(action.packageName)
        if (launchIntent == null) {
            ControlServer.notifyActionResult(action.requestId, false, "电视上找不到这个可启动的 App。")
            return
        }
        runCatching {
            // 无障碍服务不属于 Activity，上屏打开第三方 App 时必须加 NEW_TASK。
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }.onSuccess {
            ControlServer.notifyActionResult(action.requestId, true, "电视 App 已打开。")
        }.onFailure {
            ControlServer.notifyActionResult(action.requestId, false, "电视系统暂时不允许从 AirMouse 打开这个 App。")
        }
    }

    /**
     * 场景只在目标 App 已成为当前窗口、且它明确暴露了可编辑焦点时才输入片名。
     * 许多视频 App 打开后停在首页；此时宁可提示用户先进入搜索框，也不能把文字
     * 误发到仍在前台的其他页面。
     */
    private fun sendSceneText(action: RemoteAction.SceneText, editor: RemoteEditorSession?) {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != action.packageName) {
            root?.recycle()
            ControlServer.notifyActionResult(action.requestId, false, "电视 App 已打开，但当前页面还没就绪；请先进入它的搜索框再试。")
            return
        }
        val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val editable = focusedInput?.isEditable == true
        val committed = editable && editor?.commitText(action.value, TextInputMode.REPLACE) == true
        val filled = if (committed) true else if (editable) {
            focusedInput?.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.value)
                },
            ) == true
        } else false
        focusedInput?.recycle()
        root.recycle()
        ControlServer.notifyActionResult(
            action.requestId,
            filled,
            if (filled) "搜索内容已发送。" else "电视 App 已打开，但没有可输入的搜索框；请先进入搜索再试。",
        )
    }

    private fun movePointer(dx: Float, dy: Float) {
        updateScreenBounds()
        pointerX = (pointerX + dx).coerceIn(screenBounds.left.toFloat(), (screenBounds.right - 1).toFloat())
        pointerY = (pointerY + dy).coerceIn(screenBounds.top.toFloat(), (screenBounds.bottom - 1).toFloat())
        if (dragPath != null) dragPath?.lineTo(pointerX, pointerY)
        overlay.update(pointerX, pointerY, screenBounds)
        schedulePointerHide()
    }

    private fun click(count: Int) {
        revealPointer()
        val path = Path().apply {
            moveTo(pointerX, pointerY)
            lineTo(
                (pointerX + 1f).coerceAtMost((screenBounds.right - 1).toFloat()),
                (pointerY + 1f).coerceAtMost((screenBounds.bottom - 1).toFloat()),
            )
        }
        val strokes = (0 until count).map { index ->
            GestureDescription.StrokeDescription(path, index * 180L, 75L)
        }
        dispatchGesture(GestureDescription.Builder().apply { strokes.forEach(::addStroke) }.build(), null, null)
    }

    private fun startDrag() {
        revealPointer()
        dragPath = Path().apply { moveTo(pointerX, pointerY) }
        dragStartedAt = System.currentTimeMillis()
    }

    private fun finishDrag() {
        val path = dragPath ?: return
        schedulePointerHide()
        dragPath = null
        val duration = (System.currentTimeMillis() - dragStartedAt).coerceIn(120L, 10_000L)
        // Android 手势 API 在手势結束前不暴露逐帧指针流，因此拖动轨迹在抬起时一次提交。
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun scroll(dx: Float, dy: Float) {
        revealPointer()
        val startX = pointerX
        val startY = pointerY
        val distanceX = dx.coerceIn(-800f, 800f)
        val distanceY = dy.coerceIn(-800f, 800f)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(
                (startX + distanceX).coerceIn(screenBounds.left.toFloat(), (screenBounds.right - 1).toFloat()),
                (startY + distanceY).coerceIn(screenBounds.top.toFloat(), (screenBounds.bottom - 1).toFloat()),
            )
        }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 260)).build(),
            null,
            null,
        )
    }

    private fun handleKey(name: String, editor: RemoteEditorSession? = null): Boolean {
        // 文本键只通过捕获的输入会话或无障碍编辑器处理；会话已失效时不能改写新焦点。
        if (name in editorKeys) {
            val result = if (editor != null && !editor.isActive) false else when (name) {
                "backspace" -> editor?.backspace() == true || editFocusedText { it.backspace() }
                "space" -> editor?.commitText(" ", TextInputMode.INSERT) == true || editFocusedText { it.insert(" ") }
                "cursor_left", "cursor_right" -> {
                    val direction = if (name == "cursor_left") -1 else 1
                    editor?.moveCursor(direction) == true || editFocusedText { it.moveCursor(direction) }
                }
                else -> submitEditor(editor)
            }
            if (!result) reportEditorUnavailable()
            return result
        }
        when (name) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "up", "down", "left", "right" -> navigate(name)
            "ok" -> activateFocusedNode()
            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
            "mute" -> toggleMute()
            "play_pause" -> togglePlayback()
        }
        return true
    }

    private fun submitEditor(editor: RemoteEditorSession?): Boolean {
        if (editor != null) return editor.sendEnter()
        // 非 IME 路径只提交输入框声明的输入法动作，不能把“点击当前控件”当成搜索成功。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return withFocusedEditor { it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
    }

    private fun navigate(direction: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val globalAction = when (direction) {
                "up" -> GLOBAL_ACTION_DPAD_UP
                "down" -> GLOBAL_ACTION_DPAD_DOWN
                "left" -> GLOBAL_ACTION_DPAD_LEFT
                else -> GLOBAL_ACTION_DPAD_RIGHT
            }
            if (performGlobalAction(globalAction)) return
        }
        moveAccessibilityFocus(direction)
    }

    private fun moveAccessibilityFocus(direction: String) {
        val root = rootInActiveWindow ?: return report("当前画面没有可导航的无障碍控件。")
        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val currentBounds = Rect().also {
            if (current == null) it.set(screenBounds) else current.getBoundsInScreen(it)
        }
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        collectFocusable(root, candidates)
        val next = candidates.asSequence()
            .filter { (_, bounds) -> current == null || bounds != currentBounds }
            .filter { (_, bounds) -> isInDirection(currentBounds, bounds, direction) }
            .minByOrNull { (_, bounds) -> directionalDistance(currentBounds, bounds, direction) }
            ?.first
        val moved = next?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true ||
            next?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) == true
        candidates.forEach { (candidate, _) -> candidate.recycle() }
        current?.recycle()
        root.recycle()
        if (!moved) report("此画面不支持方向键节点导航，请尝试触摸板点击。")
    }

    private fun collectFocusable(node: AccessibilityNodeInfo, result: MutableList<Pair<AccessibilityNodeInfo, Rect>>) {
        if (node.isVisibleToUser && (node.isFocusable || node.isClickable)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) result += AccessibilityNodeInfo.obtain(node) to bounds
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            collectFocusable(child, result)
            child.recycle()
        }
    }

    private fun isInDirection(from: Rect, to: Rect, direction: String): Boolean = when (direction) {
        "up" -> to.centerY() < from.centerY() - 2
        "down" -> to.centerY() > from.centerY() + 2
        "left" -> to.centerX() < from.centerX() - 2
        else -> to.centerX() > from.centerX() + 2
    }

    private fun directionalDistance(from: Rect, to: Rect, direction: String): Float {
        val major = when (direction) {
            "up" -> from.centerY() - to.centerY()
            "down" -> to.centerY() - from.centerY()
            "left" -> from.centerX() - to.centerX()
            else -> to.centerX() - from.centerX()
        }.toFloat()
        val minor = if (direction == "up" || direction == "down") {
            abs(from.centerX() - to.centerX()).toFloat()
        } else abs(from.centerY() - to.centerY()).toFloat()
        return major + minor * 1.6f
    }

    private fun activateFocusedNode() {
        val root = rootInActiveWindow ?: return report("当前画面没有可点击的无障碍控件。")
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val activated = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        node?.recycle()
        root.recycle()
        if (!activated) click(1)
    }

    private fun inputText(action: RemoteAction.Text, editor: RemoteEditorSession?) {
        val startedAt = SystemClock.uptimeMillis()
        var route = "ime"
        val outcome = runCatching {
            EditorCommandTransaction.execute(action.submit, write = {
                if (editor != null && !editor.isActive) false
                // 恢复原来优先输入法的快路径；有活跃会话时不用先查询无障碍树。
                else editor?.commitText(action.value, action.mode) == true || run {
                    route = "accessibility"
                    when (action.mode) {
                        TextInputMode.REPLACE -> withFocusedEditor { node ->
                            writeEditorText(node, TextEditState(action.value, action.value.length, action.value.length))
                        }
                        TextInputMode.INSERT -> editFocusedText { it.insert(action.value) }
                    }
                }
            }, search = { submitEditor(editor) })
        }.getOrElse { error ->
            Log.w("AirMouseInput", "电视文字输入失败 request=${action.requestId}", error)
            EditorCommandResult(false, false)
        }
        val result = outcome.written
        // 只记录模式、字数和耗时，不把用户输入或目标输入框内容写入日志。
        Log.i("AirMouseInput", "text request=${action.requestId} route=$route mode=${action.mode} chars=${action.value.length} success=$result applyMs=${SystemClock.uptimeMillis() - startedAt}")
        val message = when {
            !result -> editorUnavailableMessage
            action.submit && !outcome.searched -> "文字输入已提交，但此输入框未接受搜索；请使用电视输入法的搜索键。"
            else -> "文字输入已提交到电视。"
        }
        if (action.requestId != null) ControlServer.notifyActionResult(action.requestId, result, message)
        else if (!result) report(message)
    }

    private val editorUnavailableMessage = "请先选中电视输入框；若仍无法编辑，当前 App 可能未开放文字或光标接口，可改用“AirMouse-手机键盘输入”。"

    private fun reportEditorUnavailable() = report(editorUnavailableMessage)

    private fun withFocusedEditor(action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        // 当前活动窗口可能是第三方输入法，不能只在 rootInActiveWindow 内找焦点。
        // 服务级 findFocus 跨窗口查询输入焦点，也省去一次无障碍根节点往返。
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            try {
                if (focused.isEditable && focused.isEnabled) return action(focused)
            } finally {
                focused.recycle()
            }
        }
        // 部分 TV 系统的跨窗口焦点查询不完整，再检查当前应用窗口；排除输入法和覆盖层。
        val candidates = windows
        try {
            for (window in candidates.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }
                .sortedByDescending { it.isFocused }) {
                val root = window.root ?: continue
                val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                try {
                    if (node?.isEditable == true && node.isEnabled) return action(node)
                } finally {
                    node?.recycle()
                    root.recycle()
                }
            }
            return false
        } finally {
            candidates.forEach { it.recycle() }
        }
    }

    private fun editFocusedText(transform: (TextEditState) -> TextEditState): Boolean = withFocusedEditor { node ->
        // findFocus 可以命中框架缓存；连续编辑时必须读取当前文字/选区，不能拿上一次的
        // 内容重写输入框。refresh 绕过节点缓存，可能等待 IPC，因此只在工作队列里调用。
        if (!node.refresh() || !node.isEditable || !node.isEnabled) return@withFocusedEditor false
        // 密码节点可能只暴露掩码，不能读出后整段重写；必须使用实际输入法连接编辑。
        if (node.isPassword) return@withFocusedEditor false
        val state = TextEditState.from(node.text?.toString().orEmpty(), node.textSelectionStart, node.textSelectionEnd)
            ?: return@withFocusedEditor false
        val edited = transform(state)
        when {
            edited == state -> true
            edited.text == state.text -> setEditorSelection(node, edited.start, edited.end)
            // 先确认此输入框允许定位选区，再更新文本并恢复光标到插入/删除处。
            !setEditorSelection(node, state.start, state.end) -> false
            else -> writeEditorText(node, edited)
        }
    }

    private fun writeEditorText(node: AccessibilityNodeInfo, state: TextEditState): Boolean {
        val result = EditorTextWriter.write(state,
            setText = { text -> node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
            ) },
            setSelection = { start, end -> setEditorSelection(node, start, end) },
        )
        if (result == EditorWriteResult.CURSOR_UNAVAILABLE) {
            // 内容已更新时不能返回“发送失败”，否则重试插入会重复文本。
            report("文字已更新，但此输入框无法定位光标；请启用并选中“AirMouse-手机键盘输入”。")
        }
        return result != EditorWriteResult.FAILED
    }

    private fun setEditorSelection(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean = node.performAction(
        AccessibilityNodeInfo.ACTION_SET_SELECTION,
        Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        },
    )

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun toggleMute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                AudioManager.FLAG_SHOW_UI,
            )
        } else {
            report("当前 Android 版本不支持静音快捷键。")
        }
    }

    private fun togglePlayback() {
        val now = android.os.SystemClock.uptimeMillis()
        // 经 AudioManager 投递标准媒体键，让当前活动的 TV 媒体会话决定是否响应。
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
    }

    private fun updateScreenBounds() {
        val size = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(size)
        screenBounds.set(0, 0, max(1, size.x), max(1, size.y))
    }

    /** 每次指针移动或触摸操作都重新显示箭头，并从最后一次操作开始计时。 */
    private fun revealPointer() {
        if (!::overlay.isInitialized) return
        overlay.showPointer()
        schedulePointerHide()
    }

    private fun schedulePointerHide() {
        mainHandler.removeCallbacks(hidePointerRunnable)
        mainHandler.postDelayed(hidePointerRunnable, pointerIdleTimeoutMs)
    }

    private fun report(message: String) {
        mainHandler.post { TvStatusStore.update { it.copy(message = message) } }
        ControlServer.notifyControllers(message)
    }
}

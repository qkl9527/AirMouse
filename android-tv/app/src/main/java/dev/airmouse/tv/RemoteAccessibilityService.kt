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
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.max

/** Android 不允许普通应用注入全局鼠标 HID；本服务用系统授权的触屏手势模拟鼠标操作。 */
class RemoteAccessibilityService : AccessibilityService(), ControlServer.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var overlay: PointerOverlay
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private var screenBounds = Rect()
    private var pointerX = 0f
    private var pointerY = 0f
    private var dragPath: Path? = null
    private var dragStartedAt = 0L
    private var connectedClients = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        overlay = PointerOverlay(this, windowManager)
        updateScreenBounds()
        pointerX = screenBounds.exactCenterX()
        pointerY = screenBounds.exactCenterY()
        overlay.update(pointerX, pointerY, screenBounds)
        TvStatusStore.update { it.copy(accessibilityEnabled = true, message = null) }
        ControlServer.start(applicationContext, this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ControlServer.stop()
        overlay.hide()
        TvStatusStore.update { it.copy(accessibilityEnabled = false, pairingUrl = null) }
        dragPath = null
        return super.onUnbind(intent)
    }

    override fun onAction(action: RemoteAction) {
        // 网络线程只做协议解析；Android View、窗口和手势调用统一切回主线程。
        mainHandler.post { dispatch(action) }
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

    private fun dispatch(action: RemoteAction) {
        when (action) {
            is RemoteAction.Move -> movePointer(action.dx, action.dy)
            is RemoteAction.Click -> click(action.count)
            RemoteAction.DragStart -> startDrag()
            RemoteAction.DragEnd -> finishDrag()
            is RemoteAction.Scroll -> scroll(action.dx, action.dy)
            is RemoteAction.Key -> handleKey(action.name)
            is RemoteAction.Text -> {
                if (!RemoteInputBridge.commitText(action.value)) setFocusedText(action.value)
            }
            is RemoteAction.EnableAirMouse -> Unit // ControlServer 在网络线程为请求客户端建立 TLS 通道。
            is RemoteAction.ListApps -> Unit // 应用列表由 ControlServer 按请求连接返回。
            is RemoteAction.LaunchApp -> launchApp(action)
            is RemoteAction.SceneText -> sendSceneText(action)
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
    private fun sendSceneText(action: RemoteAction.SceneText) {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != action.packageName) {
            root?.recycle()
            ControlServer.notifyActionResult(action.requestId, false, "电视 App 已打开，但当前页面还没就绪；请先进入它的搜索框再试。")
            return
        }
        val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val editable = focusedInput?.isEditable == true
        val committed = editable && RemoteInputBridge.commitText(action.value)
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
    }

    private fun click(count: Int) {
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
        dragPath = Path().apply { moveTo(pointerX, pointerY) }
        dragStartedAt = System.currentTimeMillis()
    }

    private fun finishDrag() {
        val path = dragPath ?: return
        dragPath = null
        val duration = (System.currentTimeMillis() - dragStartedAt).coerceIn(120L, 10_000L)
        // Android 手势 API 在手势結束前不暴露逐帧指针流，因此拖动轨迹在抬起时一次提交。
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun scroll(dx: Float, dy: Float) {
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

    private fun handleKey(name: String) {
        when (name) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "up", "down", "left", "right" -> navigate(name)
            "ok" -> activateFocusedNode()
            "enter" -> if (!RemoteInputBridge.sendEnter()) activateFocusedNode()
            "backspace" -> if (!RemoteInputBridge.backspace()) editFocusedText { text -> text.dropLast(1) }
            "space" -> if (!RemoteInputBridge.commitText(" ")) editFocusedText { text -> text + " " }
            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
            "mute" -> toggleMute()
            "play_pause" -> togglePlayback()
        }
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

    private fun setFocusedText(value: String) {
        val root = rootInActiveWindow ?: return report("电视没有可写入的输入框；请启用并选中 AirMouse 输入法。")
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val result = node?.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
        ) == true
        node?.recycle()
        root.recycle()
        if (!result) report("当前输入框不接受无障碍文字，请启用并选中 AirMouse 输入法。")
    }

    private fun editFocusedText(transform: (String) -> String) {
        val root = rootInActiveWindow ?: return report("电视没有可写入的输入框；请启用并选中 AirMouse 输入法。")
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val oldText = node?.text?.toString()
        val result = oldText != null && node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    transform(oldText),
                )
            },
        )
        node?.recycle()
        root.recycle()
        if (!result) report("当前输入框不支持键盘快捷输入，请启用并选中 AirMouse 输入法。")
    }

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

    private fun report(message: String) {
        TvStatusStore.update { it.copy(message = message) }
        ControlServer.notifyControllers(message)
    }
}

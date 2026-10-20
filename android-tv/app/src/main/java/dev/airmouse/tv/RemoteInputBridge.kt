package dev.airmouse.tv

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** 无障碍接收端到当前输入法连接的窄接口，不在网络服务中保留过期 InputConnection。 */
object RemoteInputBridge {
    @Volatile private var activeService: RemoteInputMethodService? = null

    internal fun attach(service: RemoteInputMethodService) {
        activeService = service
    }

    internal fun detach(service: RemoteInputMethodService) {
        if (activeService === service) activeService = null
    }

    fun commitText(value: String, mode: TextInputMode = TextInputMode.INSERT): Boolean =
        activeService?.commitRemoteText(value, mode) ?: false
    fun sendEnter(): Boolean = activeService?.sendRemoteEnter() ?: false
    fun backspace(): Boolean = activeService?.sendRemoteBackspace() ?: false
    fun moveCursor(direction: Int): Boolean = activeService?.moveRemoteCursor(direction) ?: false
    /** 在主线程捕获当前输入会话，再交给有序工作线程执行；不从后台读取 IME 生命周期字段。 */
    internal fun captureSession(): RemoteEditorSession? = activeService?.editorSession
}

/** 电视屏幕只显示简短提示；实际按键和文字由手机网页提供。 */
class RemoteInputMethodService : android.inputmethodservice.InputMethodService() {
    internal var editorSession: RemoteEditorSession? = null
        private set

    override fun onCreate() {
        super.onCreate()
        RemoteInputBridge.attach(this)
    }

    override fun onDestroy() {
        editorSession?.invalidate()
        editorSession = null
        RemoteInputBridge.detach(this)
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorSession?.invalidate()
        editorSession = currentInputConnection?.let { RemoteEditorSession(it, attribute?.actionId, attribute?.imeOptions) }
        RemoteInputBridge.attach(this)
    }

    override fun onFinishInput() {
        editorSession?.invalidate()
        editorSession = null
        super.onFinishInput()
    }

    override fun onCreateInputView(): android.view.View = android.widget.TextView(this).apply {
        text = getString(R.string.input_method_view)
        textSize = 18f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF20282B.toInt())
        setPadding(24, 18, 24, 18)
    }

    internal fun commitRemoteText(value: String, mode: TextInputMode): Boolean = editorSession?.commitText(value, mode) ?: false
    internal fun sendRemoteEnter(): Boolean = editorSession?.sendEnter() ?: false
    internal fun sendRemoteBackspace(): Boolean = editorSession?.backspace() ?: false
    internal fun moveRemoteCursor(direction: Int): Boolean = editorSession?.moveCursor(direction) ?: false
}

/** InputConnection 的远程方法允许跨线程调用；会话失效后拒绝操作旧输入框。 */
internal class RemoteEditorSession(
    private val connection: InputConnection,
    private val actionId: Int?,
    private val imeOptions: Int?,
) {
    @Volatile private var active = true
    val isActive: Boolean get() = active
    fun invalidate() { active = false }

    fun commitText(value: String, mode: TextInputMode): Boolean {
        if (!active) return false
        if (mode == TextInputMode.INSERT) return connection.commitText(value, 1)
        // 覆盖与插入必须有明确语义：先全选再提交，不能将覆盖悄悄降级为追加。
        connection.beginBatchEdit()
        return try {
            connection.performContextMenuAction(android.R.id.selectAll) && connection.commitText(value, 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    fun moveCursor(direction: Int): Boolean {
        if (!active) return false
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val text = extracted.text?.toString() ?: return false
        val state = TextEditState.from(text, extracted.selectionStart, extracted.selectionEnd) ?: return false
        val moved = state.moveCursor(direction)
        // 提取内容可能从完整文本的中间开始，setSelection 需要换算成全文偏移。
        // 左侧内容未被提取时交给无障碍路径处理，不把片段边界当作全文开头。
        if (direction < 0 && state.start == 0 && extracted.startOffset > 0) return false
        if (!active) return false
        return connection.setSelection(extracted.startOffset + moved.start, extracted.startOffset + moved.end)
    }

    fun sendEnter(): Boolean {
        if (!active) return false
        // 自定义 actionId 为 0 时使用 imeOptions 指定的搜索/完成动作，不能把 0 当成有效动作。
        val action = actionId?.takeIf { it != 0 } ?: ((imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION)
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            connection.performEditorAction(action)
        ) return true
        return sendKey(connection, KeyEvent.KEYCODE_ENTER)
    }

    fun backspace(): Boolean {
        if (!active) return false
        // TV 自定义输入框可能忽略 deleteSurroundingTextInCodePoints，远程代理仍返回
        // “已投递”，导致旧实现永远不走退格兜底。直接发送标准 DEL，让编辑器处理选区、
        // 光标与 Unicode 删除；也避免 getSelectedText 的同步查询拖住后续输入。
        return sendKey(connection, KeyEvent.KEYCODE_DEL)
    }

    private fun sendKey(connection: InputConnection, keyCode: Int): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        return connection.sendKeyEvent(down) && connection.sendKeyEvent(up)
    }
}

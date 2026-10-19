package dev.airmouse.tv

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
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

    fun commitText(value: String): Boolean = activeService?.commitRemoteText(value) ?: false
    fun sendEnter(): Boolean = activeService?.sendRemoteEnter() ?: false
    fun backspace(): Boolean = activeService?.sendRemoteBackspace() ?: false
}

/** 电视屏幕只显示简短提示；实际按键和文字由手机网页提供。 */
class RemoteInputMethodService : android.inputmethodservice.InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        RemoteInputBridge.attach(this)
    }

    override fun onDestroy() {
        RemoteInputBridge.detach(this)
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        RemoteInputBridge.attach(this)
    }

    override fun onCreateInputView(): android.view.View = android.widget.TextView(this).apply {
        text = getString(R.string.input_method_view)
        textSize = 18f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF20282B.toInt())
        setPadding(24, 18, 24, 18)
    }

    internal fun commitRemoteText(value: String): Boolean = currentInputConnection
        ?.commitText(value, 1)
        ?: false

    internal fun sendRemoteEnter(): Boolean {
        val connection = currentInputConnection ?: return false
        val info = currentInputEditorInfo
        if (info != null && info.actionId != EditorInfo.IME_ACTION_NONE &&
            connection.performEditorAction(info.actionId)
        ) return true
        return sendKey(connection, KeyEvent.KEYCODE_ENTER)
    }

    internal fun sendRemoteBackspace(): Boolean {
        val connection = currentInputConnection ?: return false
        if (connection.deleteSurroundingText(1, 0)) return true
        return sendKey(connection, KeyEvent.KEYCODE_DEL)
    }

    private fun sendKey(connection: InputConnection, keyCode: Int): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        return connection.sendKeyEvent(down) && connection.sendKeyEvent(up)
    }
}

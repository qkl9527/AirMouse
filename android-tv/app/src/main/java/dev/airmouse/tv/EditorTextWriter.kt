package dev.airmouse.tv

internal enum class EditorWriteResult { FAILED, WRITTEN, CURSOR_UNAVAILABLE }

/** 将文本提交与必要的光标恢复分开，避免默认覆盖输入多等待一次系统回调。 */
internal object EditorTextWriter {
    fun write(
        state: TextEditState,
        setText: (String) -> Boolean,
        setSelection: (Int, Int) -> Boolean,
    ): EditorWriteResult {
        if (!setText(state.text)) return EditorWriteResult.FAILED
        // ACTION_SET_TEXT 已保证光标位于末尾；额外 ACTION_SET_SELECTION 在某些
        // TV 系统页面会等待无障碍交互超时，让已显示的文字迟迟拿不到成功回执。
        if (state.start == state.text.length && state.end == state.text.length) return EditorWriteResult.WRITTEN
        return if (setSelection(state.start, state.end)) EditorWriteResult.WRITTEN else EditorWriteResult.CURSOR_UNAVAILABLE
    }
}

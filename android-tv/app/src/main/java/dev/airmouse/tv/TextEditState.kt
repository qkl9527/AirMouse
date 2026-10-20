package dev.airmouse.tv

/**
 * 输入框的一次文本/选区快照。Android 的选区索引是 UTF-16 偏移；移动和删除时
 * 按 Unicode code point 前进，避免把 emoji 的代理对拆成两个无效字符。
 * 不猜测未知光标的位置，防止编辑中间文字时误删末尾或覆盖整个输入框。
 */
internal data class TextEditState(val text: String, val start: Int, val end: Int) {
    fun insert(value: String): TextEditState {
        val cursor = start + value.length
        return TextEditState(text.replaceRange(start, end, value), cursor, cursor)
    }

    fun backspace(): TextEditState {
        val deleteFrom = if (start != end || start == 0) start else text.offsetByCodePoints(start, -1)
        return TextEditState(text.removeRange(deleteFrom, end), deleteFrom, deleteFrom)
    }

    fun moveCursor(direction: Int): TextEditState {
        val cursor = when {
            start != end -> if (direction < 0) start else end
            direction < 0 && start > 0 -> text.offsetByCodePoints(start, -1)
            direction > 0 && end < text.length -> text.offsetByCodePoints(end, 1)
            else -> start
        }
        return copy(start = cursor, end = cursor)
    }

    companion object {
        fun from(text: String, start: Int, end: Int): TextEditState? {
            fun validBoundary(index: Int): Boolean = index in 0..text.length &&
                !(index > 0 && index < text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())
            if (!validBoundary(start) || !validBoundary(end)) return null
            return TextEditState(text, minOf(start, end), maxOf(start, end))
        }
    }
}

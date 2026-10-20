package dev.airmouse.tv

internal data class EditorCommandResult(val written: Boolean, val searched: Boolean)

/** 输入与搜索在电视同一个有序任务内完成，不依赖手机收到回执后再补发回车。 */
internal object EditorCommandTransaction {
    fun execute(submit: Boolean, write: () -> Boolean, search: () -> Boolean): EditorCommandResult {
        if (!write()) return EditorCommandResult(false, false)
        return EditorCommandResult(true, submit && search())
    }
}

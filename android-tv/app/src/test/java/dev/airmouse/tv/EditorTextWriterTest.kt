package dev.airmouse.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorTextWriterTest {
    @Test fun replacingTextAcknowledgesWithoutWaitingForRedundantCursorAction() {
        val state = TextEditState("电影", 2, 2)
        val result = EditorTextWriter.write(state, setText = { true }, setSelection = { _, _ ->
            error("覆盖写入已将光标放到末尾，不应再等待设备的光标定位回调")
        })
        assertEquals(EditorWriteResult.WRITTEN, result)
    }

    @Test fun insertionInMiddleRestoresTheActualCursor() {
        val changes = mutableListOf<String>()
        val result = EditorTextWriter.write(TextEditState("我想看电影", 2, 2),
            setText = { changes += "text:$it"; true },
            setSelection = { start, end -> changes += "selection:$start:$end"; true },
        )
        assertEquals(listOf("text:我想看电影", "selection:2:2"), changes)
        assertEquals(EditorWriteResult.WRITTEN, result)
    }

    @Test fun failedWriteDoesNotAttemptCursorMovement() {
        assertEquals(EditorWriteResult.FAILED, EditorTextWriter.write(TextEditState("电影", 1, 1),
            setText = { false }, setSelection = { _, _ -> error("文字未写入，不应移动光标") },
        ))
    }

    @Test fun failedCursorRestoreDoesNotTurnCompletedWriteIntoFailedInput() {
        assertEquals(EditorWriteResult.CURSOR_UNAVAILABLE, EditorTextWriter.write(TextEditState("电影", 1, 1),
            setText = { true }, setSelection = { _, _ -> false },
        ))
    }
}

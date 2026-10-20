package dev.airmouse.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorCommandTransactionTest {
    @Test fun searchRunsAfterInputWithoutWaitingForPhoneAcknowledgement() {
        val calls = mutableListOf<String>()
        val result = EditorCommandTransaction.execute(true,
            write = { calls += "input"; true }, search = { calls += "search"; true },
        )
        assertEquals(listOf("input", "search"), calls)
        assertEquals(EditorCommandResult(true, true), result)
    }

    @Test fun failedInputNeverSearchesOldText() {
        assertEquals(EditorCommandResult(false, false), EditorCommandTransaction.execute(true,
            write = { false }, search = { error("写入失败不能提交旧内容") },
        ))
    }

    @Test fun ordinaryInputDoesNotSubmitSearch() {
        assertEquals(EditorCommandResult(true, false), EditorCommandTransaction.execute(false,
            write = { true }, search = { error("普通输入不应触发搜索") },
        ))
    }

    @Test fun failedSearchDoesNotRepeatSuccessfulInsertion() {
        var writes = 0
        val result = EditorCommandTransaction.execute(true,
            write = { writes++; true }, search = { false },
        )
        assertEquals(1, writes)
        assertEquals(EditorCommandResult(true, false), result)
    }
}

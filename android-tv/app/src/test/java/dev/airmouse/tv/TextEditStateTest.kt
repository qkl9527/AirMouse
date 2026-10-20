package dev.airmouse.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextEditStateTest {
    @Test fun insertAtTelevisionCursorKeepsSurroundingText() {
        val result = TextEditState.from("我想看电影", 3, 3)!!.insert("一部")
        assertEquals(TextEditState("我想看一部电影", 5, 5), result)
    }

    @Test fun insertionReplacesOnlyTheSelectedRange() {
        val result = TextEditState.from("今天看电影", 2, 3)!!.insert("去看")
        assertEquals(TextEditState("今天去看电影", 4, 4), result)
    }

    @Test fun backspaceDeletesBeforeCursorInsteadOfAtEnd() {
        val result = TextEditState.from("我想看电影", 2, 2)!!.backspace()
        assertEquals(TextEditState("我看电影", 1, 1), result)
    }

    @Test fun backspaceDeletesSelectedRangeIncludingReverseSelection() {
        assertEquals(TextEditState("我电影", 1, 1), TextEditState.from("我想看电影", 3, 1)!!.backspace())
    }

    @Test fun emojiIsNotSplitByDeletionOrCursorMovement() {
        val state = TextEditState.from("看😀电影", 3, 3)!!
        assertEquals(TextEditState("看电影", 1, 1), state.backspace())
        assertEquals(TextEditState("看😀电影", 1, 1), state.moveCursor(-1))
        assertEquals(state, TextEditState.from("看😀电影", 1, 1)!!.moveCursor(1))
    }

    @Test fun movementCollapsesSelectionInRequestedDirection() {
        val state = TextEditState.from("abcdef", 4, 1)!!
        assertEquals(TextEditState("abcdef", 1, 1), state.moveCursor(-1))
        assertEquals(TextEditState("abcdef", 4, 4), state.moveCursor(1))
    }

    @Test fun editingAtTextBoundariesIsHarmless() {
        val start = TextEditState.from("电影", 0, 0)!!
        val end = TextEditState.from("电影", 2, 2)!!
        assertEquals(start, start.backspace())
        assertEquals(start, start.moveCursor(-1))
        assertEquals(end, end.moveCursor(1))
        assertEquals(TextEditState("", 0, 0), TextEditState.from("", 0, 0)!!.backspace())
    }

    @Test fun unknownOrInvalidSelectionDoesNotGuessAnEditPosition() {
        assertNull(TextEditState.from("电视", -1, -1))
        assertNull(TextEditState.from("电视", 3, 3))
        assertNull(TextEditState.from("😀", 1, 1))
    }
}

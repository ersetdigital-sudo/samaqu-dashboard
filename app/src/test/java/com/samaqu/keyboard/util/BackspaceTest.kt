package com.samaqu.keyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eight mandatory Backspace scenarios, run against a fake editor that honours the same
 * InputConnection contract the fix relies on:
 *
 *  - `deleteSurroundingText` only ever touches text outside the selection (per the AOSP
 *    javadoc it "can't affect the contents of the selection"), lengths in UTF-16 code units;
 *  - `commitText` inserts at the cursor "removing text inside the selection if any";
 *  - `getSelectedText` may return null both for "no selection" and for "read failed".
 */
class BackspaceTest {

    private class FakeEditor(
        text: String,
        selectionStart: Int = text.length,
        selectionEnd: Int = text.length
    ) : Backspace.Target {

        val content = StringBuilder(text)
        var selStart = selectionStart
        var selEnd = selectionEnd

        /** Simulates getSelectedText() failing (returns null) when false. */
        var selectionReadable = true

        /** Simulates onUpdateSelection() having reported a non-empty selection. */
        var trackedSelection = selectionEnd > selectionStart

        /** Simulates getTextBeforeCursor() failing (returns null) when false. */
        var beforeReadable = true

        /** How often the editor was asked to delete outside the selection. */
        var deleteCalls = 0

        val result: String get() = content.toString()

        override fun selectedText(): CharSequence? {
            if (!selectionReadable) return null
            return content.substring(minOf(selStart, selEnd), maxOf(selStart, selEnd))
        }

        override fun hasTrackedSelection(): Boolean = trackedSelection

        override fun textBeforeCursor(length: Int): CharSequence? {
            if (!beforeReadable) return null
            return content.substring((selStart - length).coerceAtLeast(0), selStart)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            deleteCalls++
            // Per contract: text before the selection start / after the selection end only.
            val from = (selStart - beforeLength).coerceAtLeast(0)
            content.delete(from, selStart)
            val shift = selStart - from
            selStart -= shift
            selEnd -= shift
            val to = (selEnd + afterLength).coerceAtMost(content.length)
            content.delete(selEnd, to)
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int) {
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            content.replace(lo, hi, text.toString())
            // >0 is relative to (end of inserted text - 1), so 1 lands right after it.
            val cursor = if (newCursorPosition > 0) {
                lo + text.length + newCursorPosition - 1
            } else {
                lo - newCursorPosition
            }
            selStart = cursor.coerceIn(0, content.length)
            selEnd = selStart
            trackedSelection = false
        }
    }

    private fun assertNoLoneSurrogates(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate()) {
                assertTrue(
                    "lone high surrogate at $i in '$text'",
                    i + 1 < text.length && text[i + 1].isLowSurrogate()
                )
                i += 2
            } else {
                assertTrue("lone low surrogate at $i in '$text'", !c.isLowSurrogate())
                i++
            }
        }
    }

    // ------------------------------------------------- 1-3: selection cases

    @Test
    fun `select a word and press backspace - only that word is removed`() {
        val editor = FakeEditor("hallo dunia", selectionStart = 6, selectionEnd = 11)
        Backspace.delete(editor)
        assertEquals("hallo ", editor.result)
        assertEquals(6, editor.selStart)
        assertEquals(6, editor.selEnd)
    }

    @Test
    fun `select all on a long text and press backspace - everything is removed`() {
        val text = "Pesanannya sudah kami terima ya kak. Mohon kirim bukti transfer segera kami proses."
        val editor = FakeEditor(text, selectionStart = 0, selectionEnd = text.length)
        Backspace.delete(editor)
        assertEquals("", editor.result)
    }

    @Test
    fun `select part of the text and press backspace - only the selected part is removed`() {
        val editor = FakeEditor("sudah kami terima", selectionStart = 0, selectionEnd = 6)
        Backspace.delete(editor)
        assertEquals("kami terima", editor.result)
    }

    // ------------------------------------------------- 4-5: no selection

    @Test
    fun `backspace without a selection removes one character before the cursor`() {
        val editor = FakeEditor("hallo")
        Backspace.delete(editor)
        assertEquals("hall", editor.result)
        assertEquals(1, editor.deleteCalls)
    }

    @Test
    fun `backspace with the cursor at the start of the text changes nothing`() {
        val editor = FakeEditor("hallo", selectionStart = 0, selectionEnd = 0)
        Backspace.delete(editor)
        assertEquals("hallo", editor.result)
        assertEquals(0, editor.deleteCalls)
    }

    // ------------------------------------------------- 6: emoji

    @Test
    fun `deleting a selected emoji leaves no broken surrogate behind`() {
        val editor = FakeEditor("A👍B", selectionStart = 1, selectionEnd = 3)
        Backspace.delete(editor)
        assertEquals("AB", editor.result)
        assertNoLoneSurrogates(editor.result)
    }

    @Test
    fun `backspace after an emoji deletes the whole surrogate pair`() {
        val editor = FakeEditor("A👍", selectionStart = 3, selectionEnd = 3)
        Backspace.delete(editor)
        assertEquals("A", editor.result)
        assertNoLoneSurrogates(editor.result)
        assertEquals(1, editor.deleteCalls)
        assertEquals(2, Backspace.unitsToDelete("👍"))
    }

    // ------------------------------------------------- 7: rapid repeats

    @Test
    fun `rapid repeated backspace deletes exactly one step per press and never crashes`() {
        val editor = FakeEditor("hallo")
        repeat(3) { Backspace.delete(editor) }
        assertEquals("ha", editor.result)
        assertEquals(3, editor.deleteCalls)
    }

    @Test
    fun `select all then backspace repeatedly cannot delete anything twice`() {
        val text = "hallo"
        val editor = FakeEditor(text, selectionStart = 0, selectionEnd = text.length)
        repeat(7) { Backspace.delete(editor) }
        // First press clears the selection; every later press finds the cursor at the start
        // and must be a no-op - no crash, no extra deletion, no double work.
        assertEquals("", editor.result)
        assertEquals(0, editor.deleteCalls)
    }

    // ------------------------------------------------- 8: selection-capable target

    @Test
    fun `a target that supports selection keeps the cursor where the selection started`() {
        val editor = FakeEditor("abc def", selectionStart = 4, selectionEnd = 7)
        Backspace.delete(editor)
        assertEquals("abc ", editor.result)
        assertEquals(4, editor.selStart)
        assertEquals(4, editor.selEnd)
        assertEquals(0, editor.deleteCalls)
    }

    // ------------------------------------------------- condition C: read failures

    @Test
    fun `unreadable selection with a tracked range is still removed`() {
        val editor = FakeEditor("hallo", selectionStart = 0, selectionEnd = 5)
        editor.selectionReadable = false
        editor.trackedSelection = true
        Backspace.delete(editor)
        assertEquals("", editor.result)
    }

    @Test
    fun `unreadable selection without a tracked range never clears the editor`() {
        val text = "hallo dunia"
        val editor = FakeEditor(text, selectionStart = 0, selectionEnd = text.length)
        editor.selectionReadable = false
        editor.trackedSelection = false
        Backspace.delete(editor)
        // Cursor sits at the selection start (position 0), so rule B finds nothing before
        // it: the fallback must degrade to a no-op, never to wiping the field.
        assertEquals(text, editor.result)
    }

    @Test
    fun `unreadable text before the cursor deletes nothing rather than guessing`() {
        val editor = FakeEditor("hallo")
        editor.beforeReadable = false
        Backspace.delete(editor)
        assertEquals("hallo", editor.result)
        assertEquals(0, editor.deleteCalls)
    }

    // ------------------------------------------------- units

    @Test
    fun `unitsToDelete counts one visible character in UTF-16 units`() {
        assertEquals(0, Backspace.unitsToDelete(""))
        assertEquals(1, Backspace.unitsToDelete("a"))
        assertEquals(1, Backspace.unitsToDelete("x"))
        assertEquals(2, Backspace.unitsToDelete("👍"))
        assertEquals(1, Backspace.unitsToDelete("\uD83D"))   // lone high: already broken
    }
}

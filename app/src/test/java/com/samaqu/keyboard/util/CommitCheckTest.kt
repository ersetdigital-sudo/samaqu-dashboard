package com.samaqu.keyboard.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitCheckTest {

    private val invoice = "Berikut invoice pesanannya ya Ka Surya.\n\nTotal: Rp382.000\n\nKalau sudah transfer, kirim bukti transfernya di sini ya Ka."

    @Test
    fun `no input connection means nothing was sent`() {
        assertFalse(CommitCheck.inserted(returned = null, tailBeforeCursor = null, text = invoice))
    }

    @Test
    fun `a true answer is trusted`() {
        assertTrue(CommitCheck.inserted(returned = true, tailBeforeCursor = null, text = invoice))
    }

    @Test
    fun `a false answer with an editor that cannot be read is treated as sent`() {
        // Some chat editors answer false although the text went in; losing the whole form for
        // that would be worse than the rare case where nothing was inserted.
        assertTrue(CommitCheck.inserted(returned = false, tailBeforeCursor = null, text = invoice))
    }

    @Test
    fun `a false answer is overruled when the text is really in the editor`() {
        assertTrue(CommitCheck.inserted(returned = false, tailBeforeCursor = invoice, text = invoice))
    }

    @Test
    fun `line breaks and spaces changed by the editor do not matter`() {
        val normalised = invoice.replace("\n\n", "\n").replace("  ", " ") + " "
        assertTrue(CommitCheck.inserted(returned = false, tailBeforeCursor = normalised, text = invoice))
    }

    @Test
    fun `a false answer with other text before the cursor means it was not inserted`() {
        assertFalse(CommitCheck.inserted(returned = false, tailBeforeCursor = "halo kak ada stok?", text = invoice))
    }
}

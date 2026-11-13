package com.samaqu.keyboard.util

/**
 * Decides whether text handed to the chat's input field really went in. Pure Kotlin.
 *
 * `InputConnection.commitText` answers `false` in some chat editors although the text was
 * inserted. Trusting that answer left the invoice form un-reset after every send, so the answer
 * is cross-checked against what the editor shows before the cursor.
 */
object CommitCheck {

    /** How much of the end of the text is compared, whitespace aside. */
    private const val PROBE_LENGTH = 20

    /**
     * @param returned what `commitText` answered, or null when there was no input connection.
     * @param tailBeforeCursor the text before the cursor read back from the editor, or null when
     *   the editor cannot be read.
     */
    fun inserted(returned: Boolean?, tailBeforeCursor: String?, text: String): Boolean {
        if (returned == null) return false
        if (returned) return true
        // A false answer from an editor that cannot be read back: assume the text went in. Keeping
        // the whole form for that would repeat the bug; if nothing was inserted the CS sees an empty chat.
        if (tailBeforeCursor == null) return true
        val probe = text.filterNot { it.isWhitespace() }.takeLast(PROBE_LENGTH)
        return tailBeforeCursor.filterNot { it.isWhitespace() }.endsWith(probe)
    }
}

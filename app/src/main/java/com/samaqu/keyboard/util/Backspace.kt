package com.samaqu.keyboard.util

/**
 * What Backspace deletes, decided from what the editor reports - never from a local guess.
 *
 * The bug this replaces: the old handler called `deleteSurroundingText(1, 0)`, which per the
 * InputConnection contract "only operates on text before and after the selection" and therefore
 * **cannot delete the selection at all**. With Select All active it just removed one character
 * outside the selection (or nothing), leaving the selected text on screen.
 *
 * The rules, in order:
 *
 *  A. A readable selection exists -> `commitText("", 1)`, which the contract defines as text
 *     "inserted at the cursor position, removing text inside the selection if any". With an
 *     empty payload and `newCursorPosition = 1` the cursor lands exactly where the selection
 *     started. `deleteSurroundingText(0, 0)` is deliberately not used: it cannot touch the
 *     selection by definition.
 *  B. No selection -> exactly one whole character before the cursor. "Whole" is the point:
 *     the lengths of `deleteSurroundingText` are UTF-16 code units, so deleting `1` behind an
 *     emoji would leave half a surrogate pair. The pair is therefore deleted as `2`.
 *  C. The selection could not be read (`getSelectedText` returns null both for "no selection"
 *     and for "read failed") -> fall back to the range `onUpdateSelection` last reported. If
 *     that also says nothing is selected, only rule B applies. At no point is the editor
 *     cleared wholesale as a fallback, and nothing is deleted when the state is unknown.
 */
object Backspace {

    /**
     * The five operations this logic needs from an editor. The IME adapts `InputConnection`
     * to it; tests implement it over a plain string. Keeping the seam here is what makes the
     * decision itself verifiable on the JVM.
     */
    interface Target {
        /** Selected text, `""`/null when there is none, null also when the read failed. */
        fun selectedText(): CharSequence?

        /** Whether `onUpdateSelection` last reported a non-empty selection for this field. */
        fun hasTrackedSelection(): Boolean

        /** At most the last [length] chars before the cursor; null when unreadable. */
        fun textBeforeCursor(length: Int): CharSequence?

        /** Deletes [beforeLength] UTF-16 units before and [afterLength] units after the cursor, never the selection. */
        fun deleteSurroundingText(beforeLength: Int, afterLength: Int)

        /** Inserts [text] at the cursor, removing the selection first (per the contract). */
        fun commitText(text: CharSequence, newCursorPosition: Int)
    }

    /** Runs the A/B/C ladder above against [target]. */
    fun delete(target: Target) {
        val selected = target.selectedText()

        // A: a selection we can read, or C: one the framework told us about but the editor
        // would not return. commitText("") is safe in both cases: if the tracked range went
        // stale and there is no real selection, it inserts nothing and changes nothing.
        val readable = selected != null && selected.isNotEmpty()
        if (readable || (selected == null && target.hasTrackedSelection())) {
            target.commitText("", 1)
            return
        }

        // B: no selection - one whole character before the cursor, and nothing at all when
        // the cursor is at the start of the text (unitsToDelete -> 0).
        val before = target.textBeforeCursor(2) ?: return
        val units = unitsToDelete(before)
        if (units > 0) target.deleteSurroundingText(units, 0)
    }

    /**
     * UTF-16 units of [text] - the characters ending at the cursor - that make up the single
     * character Backspace should remove: `0` at the start of the text, `2` behind a character
     * on the supplementary planes (emoji), `1` otherwise.
     */
    fun unitsToDelete(text: CharSequence): Int = when {
        text.isEmpty() -> 0
        text.length >= 2 &&
            text[text.length - 1].isLowSurrogate() &&
            text[text.length - 2].isHighSurrogate() -> 2
        else -> 1
    }
}

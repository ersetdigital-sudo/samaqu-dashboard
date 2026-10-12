package com.samaqu.keyboard.util

/**
 * The keyboard's shift state machine: OFF, ONCE and LOCKED.
 *
 * Plain Kotlin with no Android dependency, for the same reason [CalculatorEngine] is: the
 * rules below are the part of shift that is easy to get subtly wrong - above all *when* a
 * single shift is spent - and this way they can be checked on the JVM instead of only by
 * tapping a phone.
 *
 * The cycle is OFF -> ONCE -> LOCKED -> OFF, which is what Android keyboards have used for
 * years: one tap shifts a single letter, a second tap locks capitals on, and a tap while
 * locked releases it.
 *
 * The load-bearing rule is [resolve]: only a *letter* spends ONCE. A space, a digit or a
 * symbol passes through untouched. Dropping the shift on the next key of any kind is the bug
 * this class exists to prevent - the user asks for one capital, presses space out of habit,
 * and the capital is silently gone.
 */
class ShiftState {

    enum class Level { OFF, ONCE, LOCKED }

    var level: Level = Level.OFF
        private set

    /** True while letters should be typed as capitals. */
    val isActive: Boolean
        get() = level != Level.OFF

    /** True while capitals hold after every letter, rather than for one. */
    val isLocked: Boolean
        get() = level == Level.LOCKED

    /** One tap on the shift key advances the cycle. */
    fun tap() {
        level = when (level) {
            Level.OFF -> Level.ONCE
            Level.ONCE -> Level.LOCKED
            Level.LOCKED -> Level.OFF
        }
    }

    /** Back to lowercase. Used whenever a fresh input field starts. */
    fun reset() {
        level = Level.OFF
    }

    /**
     * Applies the state to [code] and answers with the code that should actually be typed.
     *
     * Only a letter spends [Level.ONCE]; [Level.LOCKED] is never spent here and can only be
     * cleared by [tap]. Codes outside the letter ranges come back unchanged, so this is safe
     * to route every key press through.
     */
    fun resolve(code: Int): Int {
        val lower = code in 'a'.code..'z'.code
        val isLetter = lower || code in 'A'.code..'Z'.code

        val typed = if (lower && isActive) code - CASE_DELTA else code
        if (isLetter && level == Level.ONCE) level = Level.OFF
        return typed
    }

    private companion object {
        /** 'a' - 'A': the distance between a lowercase letter code and its capital. */
        const val CASE_DELTA = 32
    }
}

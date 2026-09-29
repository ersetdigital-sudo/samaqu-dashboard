package com.samaqu.keyboard.util

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Arithmetic for the keyboard's Quick Calculator.
 *
 * A small hand-written parser instead of `eval` or a scripting engine: the input comes from
 * the calculator's own buttons, so only `+ - * /`, decimals and a unary minus have to work -
 * and, more importantly, every failure mode has to come back as a value the panel can render
 * rather than as an exception thrown on the IME thread.
 */
object CalculatorEngine {

    /** Results beyond this are not readable on a keypad display, so the panel says so instead. */
    const val MAX_ABS = 1e15

    /** Digits kept after the decimal point when a result is not a whole number. */
    private const val DECIMAL_PLACES = 8

    /** Longest expression accepted, so a key held down cannot grow the string forever. */
    const val MAX_LENGTH = 64

    /**
     * Operator tokens. The first four are what the keypad prints (`-` is the real minus
     * sign U+2212); the ASCII forms are accepted too so a pasted or reused expression works.
     */
    val OPERATORS = setOf("+", "−", "×", "÷", "-", "*", "/")

    sealed class Result {
        /** A complete expression that evaluated cleanly. */
        data class Value(val value: Double) : Result()

        /** Empty, or ends on an operator - normal while typing, nothing to report. */
        object Incomplete : Result()

        object DivideByZero : Result()

        object Invalid : Result()

        /** Finite, but too large to be worth showing. */
        object TooLarge : Result()
    }

    /** Whether [token] is one of the operators the keypad can produce. */
    fun isOperator(token: String): Boolean = token in OPERATORS

    /**
     * Grows [expression] with a tapped key.
     *
     * Only the rules a calculator user actually notices are enforced: a second operator
     * replaces the first instead of building an unparsable `2+×3`, `.` starts a number so
     * `×.` becomes `×0.`, and a leading minus is kept because it means "negative".
     */
    fun append(expression: String, token: String): String {
        if (expression.length >= MAX_LENGTH) return expression

        val last = expression.lastOrNull()
        val endsOnOperator = last != null && isOperator(last.toString())

        if (token == ".") {
            if (expression.takeLastWhile { it.isDigit() || it == '.' }.contains('.')) return expression
            return if (expression.isEmpty() || endsOnOperator) expression + "0."
            else expression + "."
        }

        if (isOperator(token)) {
            val minus = token == "-" || token == "−"
            if (expression.isEmpty()) return if (minus) token else expression
            return if (endsOnOperator) expression.dropLast(1) + token else expression + token
        }

        return expression + token
    }

    fun evaluate(expression: String): Result {
        val trimmed = normalize(expression)
        if (trimmed.isEmpty()) return Result.Incomplete

        val value = try {
            Parser(trimmed).parse()
        } catch (e: IncompleteExpression) {
            return Result.Incomplete
        } catch (e: DivisionByZero) {
            return Result.DivideByZero
        } catch (e: InvalidExpression) {
            return Result.Invalid
        }

        return when {
            value.isNaN() || value.isInfinite() -> Result.Invalid
            abs(value) > MAX_ABS -> Result.TooLarge
            else -> Result.Value(value)
        }
    }

    /**
     * Renders a number the way both the display and the inserted chat text use it.
     *
     * Whole numbers never grow a `.0`, and the result is written in plain digits with `.` as
     * the separator: unlike `Rp 1.234`, it stays a number the user could type back in, and it
     * is what the chat message is meant to contain.
     */
    fun format(value: Double): String = when {
        !value.isFinite() -> ""
        value == 0.0 -> "0"
        value == floor(value) && abs(value) < MAX_ABS -> value.toLong().toString()
        else -> BigDecimal(value)
            .setScale(DECIMAL_PLACES, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    /**
     * Folds the glyphs the keypad prints onto the ASCII operators the parser reads, so the
     * parser itself only has to know about four characters.
     */
    private fun normalize(expression: String): String =
        expression.trim()
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')

    private class InvalidExpression : RuntimeException()
    private class IncompleteExpression : RuntimeException()
    private class DivisionByZero : RuntimeException()

    /**
     * Recursive descent over `expr := term (('+'|'-') term)*`, `term := unary (('*'|'/') unary)*`,
     * `unary := ('+'|'-')? number`.
     */
    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Double {
            val value = expr()
            skipSpaces()
            if (pos < src.length) throw InvalidExpression()
            return value
        }

        private fun expr(): Double {
            var value = term()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '+' -> { pos++; value += term() }
                    '-' -> { pos++; value -= term() }
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = unary()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '*' -> { pos++; value *= unary() }
                    '/' -> {
                        pos++
                        val divisor = unary()
                        if (divisor == 0.0) throw DivisionByZero()
                        value /= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun unary(): Double {
            skipSpaces()
            return when (peek()) {
                '-' -> { pos++; -unary() }
                '+' -> { pos++; unary() }
                else -> number()
            }
        }

        private fun number(): Double {
            skipSpaces()
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (pos == start) {
                // Running out of input means the user simply has not finished typing;
                // anything else in the middle of an expression is a real mistake.
                if (pos >= src.length) throw IncompleteExpression()
                throw InvalidExpression()
            }
            val text = src.substring(start, pos)
            if (text == "." || text.count { it == '.' } > 1) throw InvalidExpression()
            return text.toDoubleOrNull() ?: throw InvalidExpression()
        }

        private fun peek(): Char? = if (pos < src.length) src[pos] else null

        private fun skipSpaces() {
            while (pos < src.length && src[pos] == ' ') pos++
        }
    }
}

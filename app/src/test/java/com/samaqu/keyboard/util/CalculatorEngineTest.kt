package com.samaqu.keyboard.util

import com.samaqu.keyboard.util.CalculatorEngine.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorEngineTest {

    private fun value(expression: String): Double {
        val result = CalculatorEngine.evaluate(expression)
        assertTrue("expected a value for '$expression', got $result", result is Result.Value)
        return (result as Result.Value).value
    }

    // ------------------------------------------------------------- arithmetic

    @Test
    fun `multiplies the way a CS totals an order`() {
        assertEquals(375_000.0, value("125000×3"), 0.0001)
        assertEquals("375000", CalculatorEngine.format(value("125000×3")))
    }

    @Test
    fun `respects operator precedence and left associativity`() {
        assertEquals(14.0, value("2+3×4"), 0.0001)
        assertEquals(5.0, value("10-3-2"), 0.0001)
        assertEquals(2.5, value("10÷4"), 0.0001)
    }

    @Test
    fun `accepts both the printed glyphs and plain ascii operators`() {
        assertEquals(6.0, value("2×3"), 0.0001)
        assertEquals(6.0, value("2*3"), 0.0001)
        assertEquals(2.0, value("8÷4"), 0.0001)
        assertEquals(2.0, value("8/4"), 0.0001)
        // The minus key prints U+2212, not the ASCII hyphen.
        assertEquals(3.0, value("5−2"), 0.0001)
    }

    @Test
    fun `handles a unary minus`() {
        assertEquals(-3.0, value("-5+2"), 0.0001)
        // 5 - (-2): a minus operator followed by a negative number
        assertEquals(7.0, value("5- -2"), 0.0001)
    }

    // --------------------------------------------------------------- decimals

    @Test
    fun `keeps decimal arithmetic free of floating point noise`() {
        assertEquals("0.3", CalculatorEngine.format(value("0.1+0.2")))
        assertEquals("2.5", CalculatorEngine.format(value("5÷2")))
    }

    @Test
    fun `formats whole numbers without a trailing decimal`() {
        assertEquals("375000", CalculatorEngine.format(375_000.0))
        assertEquals("0", CalculatorEngine.format(0.0))
        assertEquals("-12", CalculatorEngine.format(-12.0))
        assertEquals("0.33333333", CalculatorEngine.format(1.0 / 3.0))
    }

    // ------------------------------------------------------------ failure modes

    @Test
    fun `an expression that is not finished yet is incomplete, not an error`() {
        assertEquals(Result.Incomplete, CalculatorEngine.evaluate(""))
        assertEquals(Result.Incomplete, CalculatorEngine.evaluate("   "))
        assertEquals(Result.Incomplete, CalculatorEngine.evaluate("12+"))
        assertEquals(Result.Incomplete, CalculatorEngine.evaluate("1250×"))
    }

    @Test
    fun `division by zero is reported instead of returning infinity`() {
        assertEquals(Result.DivideByZero, CalculatorEngine.evaluate("5÷0"))
        assertEquals(Result.DivideByZero, CalculatorEngine.evaluate("5/0.0"))
    }

    @Test
    fun `malformed input is invalid rather than a crash`() {
        assertEquals(Result.Invalid, CalculatorEngine.evaluate("1..2"))
        assertEquals(Result.Invalid, CalculatorEngine.evaluate("×"))
        assertEquals(Result.Invalid, CalculatorEngine.evaluate("2+×3"))
        assertEquals(Result.Invalid, CalculatorEngine.evaluate("."))
        assertEquals(Result.Invalid, CalculatorEngine.evaluate("1.2.3+4"))
    }

    @Test
    fun `numbers too large to display are refused`() {
        assertEquals(Result.TooLarge, CalculatorEngine.evaluate("9999999999999999"))
        assertEquals(Result.TooLarge, CalculatorEngine.evaluate("9000000000000000+9000000000000000"))
    }

    // ------------------------------------------------------- typing the keys

    @Test
    fun `digits and operators are appended in order`() {
        assertEquals("125000", CalculatorEngine.append("12500", "0"))
        assertEquals("125000×", CalculatorEngine.append("125000", "×"))
        assertEquals("125000×3", CalculatorEngine.append("125000×", "3"))
    }

    @Test
    fun `a second operator replaces the one before it`() {
        assertEquals("125000×", CalculatorEngine.append("125000+", "×"))
        assertEquals("125000÷", CalculatorEngine.append("125000÷", "÷"))
    }

    @Test
    fun `an operator cannot open an expression, except a minus`() {
        assertEquals("", CalculatorEngine.append("", "×"))
        assertEquals("", CalculatorEngine.append("", "+"))
        assertEquals("−", CalculatorEngine.append("", "−"))
        assertEquals("−5", CalculatorEngine.append("−", "5"))
    }

    @Test
    fun `a decimal point starts a number and never repeats`() {
        assertEquals("0.", CalculatorEngine.append("", "."))
        assertEquals("125000×0.", CalculatorEngine.append("125000×", "."))
        assertEquals("1.", CalculatorEngine.append("1", "."))
        assertEquals("1.5", CalculatorEngine.append("1.", "5"))
        assertEquals("1.5", CalculatorEngine.append("1.5", "."))
    }

    @Test
    fun `a tapped out expression stops growing`() {
        var expression = ""
        repeat(CalculatorEngine.MAX_LENGTH + 10) { expression = CalculatorEngine.append(expression, "9") }
        assertEquals(CalculatorEngine.MAX_LENGTH, expression.length)
    }

    @Test
    fun `checking ongkir totals through the keypad evaluates as expected`() {
        var expression = ""
        listOf("1", "2", "5", "0", "0", "0", "×", "3").forEach {
            expression = CalculatorEngine.append(expression, it)
        }
        assertEquals("125000×3", expression)
        assertEquals("375000", CalculatorEngine.format(value(expression)))
    }
}

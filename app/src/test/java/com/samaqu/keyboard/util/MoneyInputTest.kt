package com.samaqu.keyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyInputTest {

    @Test
    fun `digits get thousands dots as they are typed`() {
        assertEquals("9", MoneyInput.format("9"))
        assertEquals("9.000", MoneyInput.format("9000"))
        assertEquals("40.500", MoneyInput.format("40500"))
        assertEquals("1.250.000", MoneyInput.format("1250000"))
    }

    @Test
    fun `formatting is stable when applied again`() {
        assertEquals("9.000", MoneyInput.format("9.000"))
        assertEquals("1.250.000", MoneyInput.format(MoneyInput.format("1250000")))
    }

    @Test
    fun `anything that is not a digit is dropped`() {
        assertEquals("9.000", MoneyInput.format("Rp 9.000,-"))
        assertEquals("", MoneyInput.format("abc"))
        assertEquals("", MoneyInput.format(""))
    }

    @Test
    fun `leading zeros disappear but a single zero stays`() {
        assertEquals("9.000", MoneyInput.format("09000"))
        assertEquals("0", MoneyInput.format("0"))
        assertEquals("0", MoneyInput.format("000"))
    }

    @Test
    fun `an absurdly long number is cut instead of overflowing`() {
        assertEquals("123.456.789.012", MoneyInput.format("1234567890123456"))
    }

    @Test
    fun `parse reads the formatted text back as a number`() {
        assertEquals(9_000L, MoneyInput.parse("9.000"))
        assertEquals(1_250_000L, MoneyInput.parse("1.250.000"))
        assertEquals(0L, MoneyInput.parse("0"))
        assertEquals(null, MoneyInput.parse(""))
        assertEquals(null, MoneyInput.parse("abc"))
    }
}

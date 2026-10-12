package com.samaqu.keyboard.util

import com.samaqu.keyboard.util.ShiftState.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftStateTest {

    /** Types [text] through the state machine the way the IME's key handler does. */
    private fun type(shift: ShiftState, text: String): String =
        text.map { shift.resolve(it.code).toChar() }.joinToString("")

    // ---------------------------------------------------------------- the cycle

    @Test
    fun `off types lowercase`() {
        assertEquals("hallo", type(ShiftState(), "hallo"))
    }

    @Test
    fun `one tap capitalises the first letter only`() {
        val shift = ShiftState()
        shift.tap()
        assertEquals("Hallo", type(shift, "hallo"))
    }

    @Test
    fun `two taps lock capitals on`() {
        val shift = ShiftState()
        shift.tap()
        shift.tap()
        assertEquals(Level.LOCKED, shift.level)
        assertEquals("HALLO", type(shift, "hallo"))
    }

    @Test
    fun `a tap while locked releases it`() {
        val shift = ShiftState()
        shift.tap()
        shift.tap()
        shift.tap()
        assertEquals(Level.OFF, shift.level)
        assertEquals("hallo", type(shift, "hallo"))
    }

    // ------------------------------------------------------- locked behaviour

    @Test
    fun `locked survives a long run of letters`() {
        val shift = ShiftState()
        shift.tap()
        shift.tap()
        assertEquals("HALLO DUNIA SAMAQU", type(shift, "hallo dunia samaqu"))
        assertEquals(Level.LOCKED, shift.level)
    }

    @Test
    fun `locked is not released by letters, digits, symbols or spaces`() {
        val shift = ShiftState()
        shift.tap()
        shift.tap()
        assertEquals("A1 B!", type(shift, "a1 b!"))
        assertEquals(Level.LOCKED, shift.level)
    }

    // ----------------------------------------------- what must NOT spend a shift

    @Test
    fun `space does not spend a single shift`() {
        val shift = ShiftState()
        shift.tap()
        assertEquals(" ", type(shift, " "))
        assertEquals(Level.ONCE, shift.level)

        // The capital the user asked for is still waiting for the first letter.
        assertEquals("Hallo", type(shift, "hallo"))
    }

    @Test
    fun `digits and symbols do not spend a single shift`() {
        val shift = ShiftState()
        shift.tap()
        assertEquals("123!@#", type(shift, "123!@#"))
        assertEquals(Level.ONCE, shift.level)
    }

    @Test
    fun `a letter spends a single shift exactly once`() {
        val shift = ShiftState()
        shift.tap()
        assertEquals("H", type(shift, "h"))
        assertEquals(Level.OFF, shift.level)
        assertEquals("allo", type(shift, "allo"))
    }

    // ------------------------------------------------------------------ edges

    @Test
    fun `reset returns to lowercase`() {
        val shift = ShiftState()
        shift.tap()
        shift.tap()
        shift.reset()
        assertEquals(Level.OFF, shift.level)
        assertEquals("hallo", type(shift, "hallo"))
    }

    @Test
    fun `isActive covers once and locked, isLocked only locked`() {
        val shift = ShiftState()
        assertFalse(shift.isActive)
        assertFalse(shift.isLocked)

        shift.tap()
        assertTrue(shift.isActive)
        assertFalse("one tap must not read as Caps Lock", shift.isLocked)

        shift.tap()
        assertTrue(shift.isActive)
        assertTrue(shift.isLocked)
    }

    @Test
    fun `an already uppercase code is passed through, never shifted twice`() {
        // The layouts hand over lowercase codes, but a stray uppercase one must not have the
        // offset applied again - 'A' minus 32 is '!', which would be a visible corruption.
        val shift = ShiftState()
        shift.tap()
        assertEquals('A'.code, shift.resolve('A'.code))
    }

    @Test
    fun `non letter codes pass through untouched while shifted`() {
        val shift = ShiftState()
        shift.tap()
        listOf(' '.code, '1'.code, '.'.code, '\n'.code).forEach { code ->
            assertEquals(code, shift.resolve(code))
        }
    }
}

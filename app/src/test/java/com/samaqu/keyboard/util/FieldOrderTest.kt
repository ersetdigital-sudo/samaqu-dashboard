package com.samaqu.keyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldOrderTest {

    private val order = listOf(10, 20, 30)

    @Test
    fun `next gives the field after the current one`() {
        assertEquals(20, FieldOrder.next(10, order))
        assertEquals(30, FieldOrder.next(20, order))
    }

    @Test
    fun `the last field has no next`() {
        assertEquals(null, FieldOrder.next(30, order))
    }

    @Test
    fun `a field outside the order has no next`() {
        assertEquals(null, FieldOrder.next(99, order))
        assertEquals(null, FieldOrder.next(10, emptyList()))
    }
}

package com.samaqu.keyboard.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestOnlyTest {

    @Test
    fun `the only request is current`() {
        val requests = LatestOnly()

        assertTrue(requests.isCurrent(requests.start()))
    }

    @Test
    fun `starting a second request makes the first one stale`() {
        val requests = LatestOnly()
        val first = requests.start()
        val second = requests.start()

        assertFalse(requests.isCurrent(first))
        assertTrue(requests.isCurrent(second))
    }

    @Test
    fun `a request answered late stays stale even after more were started`() {
        val requests = LatestOnly()
        val tickets = (1..5).map { requests.start() }

        assertTrue(tickets.dropLast(1).none { requests.isCurrent(it) })
        assertTrue(requests.isCurrent(tickets.last()))
    }
}

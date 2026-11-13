package com.samaqu.keyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSettingsTest {

    private val default = "https://new.supabase.co"
    private val legacy = setOf("https://old.supabase.co")

    @Test
    fun `null saved value uses the default`() {
        assertEquals(default, SavedSettings.resolve(null, default, legacy))
    }

    @Test
    fun `blank saved value uses the default`() {
        assertEquals(default, SavedSettings.resolve("   ", default, legacy))
    }

    @Test
    fun `saved value equal to a legacy value uses the default`() {
        assertEquals(default, SavedSettings.resolve("https://old.supabase.co", default, legacy))
        assertEquals(default, SavedSettings.resolve(" https://old.supabase.co/ ", default, legacy))
    }

    @Test
    fun `saved value the owner typed on purpose is kept`() {
        assertEquals("https://mine.example.com", SavedSettings.resolve("https://mine.example.com", default, legacy))
    }
}

package com.samaqu.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the emoji panel while it is being redesigned: the emoji were regrouped
 * into categories, and this checks that regrouping kept every emoji, dropped
 * none, and never repeats one inside a category (a repeat would make the
 * GridLayoutManager / DiffUtil pair throw at runtime).
 */
class EmojiCatalogTest {

    /** The 64 emoji the keyboard shipped before the panel was regrouped. */
    private val original = listOf(
        "😊", "😍", "🙏", "👍", "✅", "❤️", "🔥", "💯", "🎉", "📦",
        "🚀", "💪", "👋", "😂", "🤝", "💰", "🛍️", "⭐", "📱", "💌",
        "✨", "🎁", "🚚", "💳", "📝", "🙌", "😘", "💕", "🤗", "👏",
        "😅", "😁", "🥰", "💖", "🌟", "⚡", "🔔", "📢", "💬", "📞",
        "🏷️", "💎", "🌈", "🎊", "🤩", "😎", "👌", "🙂", "😃", "🥳",
        "🫶", "🤑", "💸", "🎯", "✔️", "🆗", "📌", "⚠️", "🕐", "📅",
        "🆕", "🉐", "💹", "🏠"
    )

    @Test
    fun `every category has emoji`() {
        EmojiCatalog.CATEGORIES.forEach { category ->
            assertTrue("category ${category.labelRes} is empty", category.emojis.isNotEmpty())
        }
    }

    @Test
    fun `a category never repeats an emoji`() {
        EmojiCatalog.CATEGORIES.forEach { category ->
            assertEquals(
                "duplicate inside category ${category.labelRes}",
                category.emojis.size,
                category.emojis.toSet().size
            )
        }
    }

    @Test
    fun `regrouping kept the original emoji set`() {
        val current = EmojiCatalog.CATEGORIES.flatMap { it.emojis }
        assertEquals("emoji count changed", original.size, current.size)
        assertEquals("emoji set changed", original.toSet(), current.toSet())
    }
}

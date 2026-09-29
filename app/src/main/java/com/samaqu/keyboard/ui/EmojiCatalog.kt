package com.samaqu.keyboard.ui

import androidx.annotation.StringRes
import com.samaqu.keyboard.R

/**
 * One emoji tab: the chip label plus the emoji shown when that chip is active.
 */
data class EmojiCategory(
    @StringRes val labelRes: Int,
    val emojis: List<String>
)

/**
 * The emoji offered by the keyboard panel.
 *
 * Kept apart from [EmojiAdapter] (and free of Android classes other than the
 * label ids) so the grouping can be verified by a plain unit test.
 *
 * Ordered by how often a CS chat actually uses them, and every emoji the
 * keyboard shipped with is kept — just sorted into tabs instead of one long
 * undifferentiated run.
 */
object EmojiCatalog {

    val CATEGORIES: List<EmojiCategory> = listOf(
        EmojiCategory(
            R.string.emoji_cat_faces,
            listOf(
                "😊", "😍", "😂", "😅", "😁", "🥰", "😘",
                "🙂", "😃", "🤩", "😎", "🥳", "🤗"
            )
        ),
        EmojiCategory(
            R.string.emoji_cat_gestures,
            listOf(
                "🙏", "👍", "👋", "🤝", "💪", "🙌", "👏",
                "👌", "🫶", "❤️", "💕", "💖", "💌"
            )
        ),
        EmojiCategory(
            R.string.emoji_cat_business,
            listOf(
                "📦", "💰", "💳", "🛍️", "🚚", "🏷️", "💸", "🤑", "💹",
                "🆕", "🉐", "📢", "📅", "🕐", "📝", "🏠", "⚠️"
            )
        ),
        EmojiCategory(
            R.string.emoji_cat_symbols,
            listOf(
                "✅", "✔️", "🆗", "⭐", "🌟", "✨", "🔥", "💯", "🎉", "🎊", "🎁",
                "⚡", "🔔", "💬", "📞", "📱", "🚀", "🌈", "💎", "🎯", "📌"
            )
        )
    )
}

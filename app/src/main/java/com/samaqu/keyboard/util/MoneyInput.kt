package com.samaqu.keyboard.util

/** Pure helpers for money typed into a field: shown with thousands dots, read back as digits. */
object MoneyInput {

    /** More than this many digits would be a typo, and 19 would overflow a Long. */
    private const val MAX_DIGITS = 12

    /**
     * `9000` -> `9.000`. Everything that is not a digit is dropped first, so the result is
     * stable when applied to text that is already formatted; leading zeros disappear (a lone
     * zero stays) and the number is cut at [MAX_DIGITS] digits. No digits at all gives `""`.
     */
    fun format(text: String): String {
        val all = text.filter { it.isDigit() }
        if (all.isEmpty()) return ""
        val digits = all.trimStart('0').take(MAX_DIGITS).ifEmpty { "0" }
        return digits.reversed().chunked(3).joinToString(".").reversed()
    }

    /** The number in [text] (formatted or not), or null when it holds no digits. */
    fun parse(text: String): Long? = text.filter { it.isDigit() }.take(MAX_DIGITS).toLongOrNull()
}

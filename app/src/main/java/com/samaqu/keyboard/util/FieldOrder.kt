package com.samaqu.keyboard.util

/** Pure helper: which form field Enter should move on to. */
object FieldOrder {

    /** The entry after [current] in [order], or null when [current] is last or not in the list. */
    fun <T> next(current: T, order: List<T>): T? {
        val index = order.indexOf(current)
        return if (index < 0) null else order.getOrNull(index + 1)
    }
}

package com.samaqu.keyboard.util

import android.text.InputType

/** Pure helper (the constants it reads are compile-time values) for choosing the right deck. */
object FieldInput {

    /**
     * True for fields that only take digits (numbers, phone numbers): the keyboard then shows
     * its digits-only keypad instead of making the user hunt for "123".
     */
    fun wantsNumberDeck(inputType: Int): Boolean =
        when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> true
            else -> false
        }
}

package com.samaqu.keyboard.util

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldInputTest {

    @Test
    fun `number fields want the number deck`() {
        assertTrue(FieldInput.wantsNumberDeck(InputType.TYPE_CLASS_NUMBER))
    }

    @Test
    fun `phone fields want the number deck`() {
        assertTrue(FieldInput.wantsNumberDeck(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun `text fields keep the letter deck whatever their flags`() {
        assertFalse(FieldInput.wantsNumberDeck(InputType.TYPE_CLASS_TEXT))
        assertFalse(
            FieldInput.wantsNumberDeck(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            )
        )
        assertFalse(
            FieldInput.wantsNumberDeck(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        )
    }

    @Test
    fun `an unset input type keeps the letter deck`() {
        assertFalse(FieldInput.wantsNumberDeck(0))
    }

    @Test
    fun `number flags do not change the class`() {
        assertTrue(FieldInput.wantsNumberDeck(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL))
    }
}

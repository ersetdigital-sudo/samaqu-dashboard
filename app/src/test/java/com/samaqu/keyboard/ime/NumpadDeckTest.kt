package com.samaqu.keyboard.ime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the digits-only keypad shown for number and phone fields. Like the other decks it is
 * XML, so nothing in the compiler notices a missing digit or a row that runs off the screen.
 * All three key heights are checked: the active one is picked at runtime.
 */
class NumpadDeckTest {

    private val files = listOf("numpad.xml", "numpad_small.xml", "numpad_large.xml")

    private fun xml(name: String): String {
        val file = listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name"))
            .firstOrNull { it.isFile }
        assertTrue("deck file not found: $name", file != null)
        return file!!.readText()
    }

    private data class Key(val code: Int, val label: String, val widthPercent: Double, val repeatable: Boolean)

    private fun attr(tag: String, name: String) =
        Regex("$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1).orEmpty()

    private fun percent(v: String) = v.removeSuffix("%p").removeSuffix("%").trim().toDoubleOrNull() ?: 0.0

    private fun rows(name: String): List<List<Key>> {
        val text = xml(name)
        val tag = Regex("<Keyboard[^>]*>").find(text)!!.value
        val defaultWidth = percent(attr(tag, "android:keyWidth"))
        return Regex("<Row>(.*?)</Row>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { row ->
            Regex("<Key[^>]*/>").findAll(row.groupValues[1]).map { k ->
                Key(
                    code = attr(k.value, "android:codes").toInt(),
                    label = attr(k.value, "android:keyLabel"),
                    widthPercent = attr(k.value, "android:keyWidth").takeIf { it.isNotEmpty() }
                        ?.let { percent(it) } ?: defaultWidth,
                    repeatable = attr(k.value, "android:isRepeatable") == "true"
                )
            }.toList()
        }.toList()
    }

    @Test
    fun `the keypad has four full-width rows`() {
        files.forEach { name ->
            val rows = rows(name)
            assertEquals("$name rows", 4, rows.size)
            rows.forEachIndexed { i, row ->
                val width = row.sumOf { it.widthPercent }
                assertTrue("$name row ${i + 1} is $width% wide", width in 99.9..100.001)
            }
        }
    }

    @Test
    fun `every digit is there exactly once`() {
        files.forEach { name ->
            val codes = rows(name).flatten().map { it.code }
            ('0'..'9').forEach { digit ->
                assertEquals("$name digit $digit", 1, codes.count { it == digit.code })
            }
        }
    }

    @Test
    fun `delete repeats and enter is there`() {
        files.forEach { name ->
            val keys = rows(name).flatten()
            val delete = keys.firstOrNull { it.code == -5 }
            assertTrue("$name has no delete key", delete != null)
            assertTrue("$name delete must repeat while held", delete!!.repeatable)
            assertTrue("$name has no enter key", keys.any { it.code == -4 })
        }
    }

    @Test
    fun `no letter keys sneak in`() {
        files.forEach { name ->
            rows(name).flatten().forEach { key ->
                assertTrue("$name has a letter key ${key.label}", key.code !in 'a'.code..'z'.code)
            }
        }
    }
}

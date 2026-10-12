package com.samaqu.keyboard.ime

import com.samaqu.keyboard.util.ShiftState
import com.samaqu.keyboard.util.ShiftState.Level
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the decks that ship as XML (qwerty.xml and friends).
 *
 * The decks are data, not code, so nothing in the compiler notices a mistake in them: a row
 * that adds up to more than 100% pushes its right-hand column off the screen, and a key that
 * is simply absent only shows up when somebody goes looking for it on a phone. Both are
 * checked here instead, on the JVM, along with the rule that is easiest to break by hand -
 * the comma has to stay a comma whatever shift is doing.
 *
 * The files are parsed rather than pattern-matched as text, so the checks survive the decks
 * being reindented or reordered.
 *
 * All three key heights (Compact / Normal / Comfortable) are covered: the comma must not
 * depend on which size the user picked in Settings.
 */
class KeyboardDeckTest {

    private data class DeckKey(
        val codes: List<Int>,
        val label: String,
        val widthPercent: Double,
        val gapPercent: Double
    ) {
        /** The code the key sends on a plain tap. */
        val code: Int get() = codes.first()
    }

    private data class Deck(val name: String, val rows: List<List<DeckKey>>) {
        fun key(code: Int): DeckKey? = rows.flatten().firstOrNull { it.code == code }
        val bottomRow: List<DeckKey> get() = rows.last()
        fun rowWidth(row: List<DeckKey>): Double =
            row.sumOf { it.widthPercent } + row.sumOf { it.gapPercent }
    }

    private fun parse(name: String): Deck {
        val file = deckFile(name)
        assertTrue("deck file not found: ${file.absolutePath}", file.isFile)
        val xml = file.readText()

        // Default key width lives on the <Keyboard> element; individual keys may override it.
        val keyboardTag = Regex("<Keyboard[^>]*>").find(xml)?.value
        assertNotNull("$name has no <Keyboard> element", keyboardTag)
        val defaultWidth = percent(attr(keyboardTag!!, "android:keyWidth"))
        assertTrue("$name has no default key width", defaultWidth > 0.0)

        val rows = Regex("<Row>(.*?)</Row>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { row ->
                Regex("<Key[^>]*/>")
                    .findAll(row.groupValues[1])
                    .map { key ->
                        val tag = key.value
                        DeckKey(
                            codes = attr(tag, "android:codes")
                                .split(',')
                                .map { it.trim().toInt() },
                            label = attr(tag, "android:keyLabel"),
                            widthPercent = attr(tag, "android:keyWidth")
                                .takeIf { it.isNotEmpty() }
                                ?.let { percent(it) } ?: defaultWidth,
                            gapPercent = percent(attr(tag, "android:horizontalGap"))
                        )
                    }
                    .toList()
            }
            .toList()

        return Deck(name, rows)
    }

    private fun attr(tag: String?, name: String): String =
        tag?.let { Regex("$name=\"([^\"]*)\"").find(it)?.groupValues?.get(1) } ?: ""

    /** "45%p" and "45%" both mean 45 percent of the parent's width. */
    private fun percent(value: String): Double =
        value.removeSuffix("%p").removeSuffix("%").trim().toDoubleOrNull() ?: 0.0

    /**
     * The decks are read from the module source tree. Gradle runs unit tests with the module
     * directory as the working directory, but the second candidate keeps this working when the
     * suite is launched from the repository root instead.
     */
    private fun deckFile(name: String): File =
        listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name"))
            .firstOrNull { it.isFile }
            ?: File("src/main/res/xml/$name")

    private fun decks(): List<Deck> = DECKS.map { parse(it) }

    // ------------------------------------------------------------------ geometry

    @Test
    fun `every deck parses into its four rows`() {
        decks().forEach { deck ->
            assertEquals("${deck.name} row count", 4, deck.rows.size)
            deck.rows.forEachIndexed { index, row ->
                assertTrue("${deck.name} row ${index + 1} is empty", row.isNotEmpty())
            }
        }
    }

    @Test
    fun `no row is wider than the screen`() {
        decks().forEach { deck ->
            deck.rows.forEachIndexed { index, row ->
                // Over 100% is the one that actually breaks: Keyboard.resize() only ever
                // scales a row *down*, so an over-wide row would push its right-hand column
                // past the edge. Under 100% just leaves the deliberate inset below.
                assertTrue(
                    "${deck.name} row ${index + 1} is ${deck.rowWidth(row)}% wide",
                    deck.rowWidth(row) <= 100.001
                )
            }
        }
    }

    @Test
    fun `every row is either full width or the standard half key inset`() {
        decks().forEach { deck ->
            val widths = deck.rows.map { deck.rowWidth(it) }
            // QWERTY rows 1, 3 and 4 (now carrying the comma) fill the screen edge to edge;
            // row 2 is inset half a key at each end - its 5% gap before the `a` plus nine
            // letter keys - which is what makes the letter block line up the way it does.
            val expected = if (deck.name.startsWith("qwerty")) {
                listOf(100.0, 95.0, 100.0, 100.0)
            } else {
                listOf(100.0, 100.0, 100.0, 100.0)
            }
            widths.forEachIndexed { index, width ->
                assertEquals(
                    "${deck.name} row ${index + 1} width",
                    expected[index],
                    width,
                    0.001
                )
            }
        }
    }

    // -------------------------------------------------------------------- comma

    @Test
    fun `every deck has a comma key on the bottom row`() {
        decks().forEach { deck ->
            val comma = deck.key(COMMA)
            assertNotNull("${deck.name} has no comma key", comma)
            assertTrue(
                "${deck.name} comma is not on the bottom row",
                deck.bottomRow.any { it.code == COMMA }
            )
        }
    }

    @Test
    fun `the comma key types exactly one comma`() {
        decks().forEach { deck ->
            val comma = deck.key(COMMA)!!
            assertEquals("${deck.name} comma label", ",", comma.label)
            assertEquals("comma key code", ",", COMMA.toChar().toString())
            // A second code on the same key would make it type something else on long press,
            // which is not what a punctuation key is for.
            assertEquals("${deck.name} comma carries an extra code", 1, comma.codes.size)
        }
    }

    @Test
    fun `the comma sits to the left of the space bar`() {
        decks().forEach { deck ->
            val row = deck.bottomRow
            val comma = row.indexOfFirst { it.code == COMMA }
            val space = row.indexOfFirst { it.code == SPACE }
            assertTrue("${deck.name} bottom row has no space bar", space >= 0)
            assertEquals("${deck.name} comma is not immediately left of space", space - 1, comma)
        }
    }

    @Test
    fun `the comma is as wide as a letter key and no wider than the period`() {
        decks().forEach { deck ->
            val comma = deck.key(COMMA)!!
            val period = deck.key(PERIOD)!!
            assertTrue(
                "${deck.name} comma is too narrow to hit (${comma.widthPercent}%)",
                comma.widthPercent >= 10.0
            )
            assertEquals("${deck.name} comma width", period.widthPercent, comma.widthPercent, 0.001)
        }
    }

    @Test
    fun `adding the comma left the neighbouring keys alone`() {
        decks().forEach { deck ->
            val row = deck.bottomRow
            val space = row.first { it.code == SPACE }
            val period = row.first { it.code == PERIOD }
            val enter = row.first { it.code == ENTER }
            val emoji = row.first { it.code == EMOJI }

            // The space bar gives up the room the comma takes, and stays by far the widest
            // key on the deck: about three and a half letter keys.
            assertEquals("${deck.name} space width", 35.0, space.widthPercent, 0.001)
            assertTrue("${deck.name} space bar is too small", space.widthPercent >= 30.0)
            assertEquals("${deck.name} period width", 10.0, period.widthPercent, 0.001)
            assertEquals("${deck.name} enter width", 20.0, enter.widthPercent, 0.001)
            assertEquals("${deck.name} emoji width", 10.0, emoji.widthPercent, 0.001)
        }
    }

    @Test
    fun `the comma is not one of the keyboard's control codes`() {
        // onKey routes these to their own handlers; every other code is committed as the
        // character it names. A comma sharing one of them would stop being typable.
        val controls = listOf(
            SamaQuKeyboardView.CODE_SHIFT,
            SamaQuKeyboardView.CODE_DELETE,
            SamaQuKeyboardView.CODE_ENTER,
            SamaQuKeyboardView.CODE_SYMBOLS,
            SamaQuKeyboardView.CODE_QWERTY,
            SamaQuKeyboardView.CODE_EMOJI
        )
        assertFalse("comma clashes with a control code", COMMA in controls)
    }

    @Test
    fun `no other key on the deck produces a comma`() {
        decks().forEach { deck ->
            val commaKeys = deck.rows.flatten().filter { it.code == COMMA }
            assertEquals("${deck.name} comma key count", 1, commaKeys.size)
        }
    }

    // ------------------------------------------------------- shift and caps lock

    @Test
    fun `the comma is still a comma once, in caps lock, and on every repeat`() {
        val shift = ShiftState()

        assertEquals(",", shift.resolve(COMMA).toChar().toString())

        shift.tap()
        assertEquals(Level.ONCE, shift.level)
        assertEquals(",", shift.resolve(COMMA).toChar().toString())

        shift.tap()
        assertEquals(Level.LOCKED, shift.level)
        assertEquals(",", shift.resolve(COMMA).toChar().toString())
    }

    @Test
    fun `a comma does not spend a pending capital`() {
        val shift = ShiftState()
        shift.tap()

        // Comma first, capital second: the capital the user asked for is still waiting.
        val typed = listOf(COMMA, 'h'.code).map { shift.resolve(it).toChar() }.joinToString("")
        assertEquals(",H", typed)
        assertEquals(Level.OFF, shift.level)
    }

    @Test
    fun `a comma does not eat caps lock`() {
        val shift = ShiftState()
        shift.tap()
        shift.tap()
        assertEquals(Level.LOCKED, shift.level)

        assertEquals(",", shift.resolve(COMMA).toChar().toString())
        assertEquals(Level.LOCKED, shift.level)

        // Surrounding the comma with letters that caps lock does rewrite leaves the state
        // where it was, so the sentence keeps shouting until shift is tapped again.
        assertEquals("A,B", listOf('a'.code, COMMA, 'b'.code).joinToString("") {
            shift.resolve(it).toChar().toString()
        })
        assertEquals(Level.LOCKED, shift.level)
    }

    @Test
    fun `the comma can be typed over and over`() {
        val shift = ShiftState()
        shift.tap()
        shift.tap()
        val bursts = (1..20).joinToString("") { shift.resolve(COMMA).toChar().toString() }
        assertEquals(",,,,,,,,,,,,,,,,,,,,", bursts)
        assertEquals(Level.LOCKED, shift.level)
    }

    private companion object {
        const val COMMA = 44
        const val SPACE = 32
        const val PERIOD = 46
        const val ENTER = -4
        const val EMOJI = -200

        /** All three key heights, letters and symbols: the active one is picked at runtime. */
        val DECKS = listOf(
            "qwerty.xml",
            "qwerty_small.xml",
            "qwerty_large.xml",
            "symbols.xml",
            "symbols_small.xml",
            "symbols_large.xml"
        )
    }
}

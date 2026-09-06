// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDataTest {

    private val sample = LibraryData(
        stars = setOf("Apache/take 3.wav", "Riffs/idea.flac"),
        notes = mapOf(
            "Apache/take 3.wav" to "capo 2, chorus lifts a third",
            "Riffs/idea.flac" to "down a whole step\nsecond verse is the keeper",
        ),
    )

    @Test
    fun `round trips through JSON`() {
        assertEquals(sample, LibraryData.fromJson(sample.toJson()))
    }

    @Test
    fun `keeps newlines inside a note`() {
        val back = LibraryData.fromJson(sample.toJson())!!
        assertTrue(back.notes.getValue("Riffs/idea.flac").contains("\n"))
    }

    @Test
    fun `rejects JSON without the marker`() {
        assertNull(LibraryData.fromJson("""{"stars":["a/b.wav"],"notes":{}}"""))
    }

    @Test
    fun `rejects text that is not JSON`() {
        assertNull(LibraryData.fromJson("not json at all"))
        assertNull(LibraryData.fromJson(""))
        assertNull(LibraryData.fromJson("[1,2,3]"))
    }

    @Test
    fun `tolerates a file with the marker and nothing else`() {
        val data = LibraryData.fromJson("""{"titletrack_stars_notes":1}""")!!
        assertEquals(emptySet<String>(), data.stars)
        assertEquals(emptyMap<String, String>(), data.notes)
    }

    @Test
    fun `drops blank stars, blank note keys, and blank note text on the way in`() {
        val json = """
            {
              "titletrack_stars_notes": 1,
              "stars": ["Apache/take.wav", "  ", ""],
              "notes": { "Apache/take.wav": "  keep  ", "  ": "orphan", "Riffs/x.wav": "   " }
            }
        """.trimIndent()
        val data = LibraryData.fromJson(json)!!
        assertEquals(setOf("Apache/take.wav"), data.stars)
        assertEquals(mapOf("Apache/take.wav" to "keep"), data.notes)
    }
}

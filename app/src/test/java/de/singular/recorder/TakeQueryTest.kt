// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeQueryTest {

    private fun query(text: String) = TakeQuery.of(text)!!

    @Test
    fun `nothing typed is no query`() {
        assertNull(TakeQuery.of(""))
        assertNull(TakeQuery.of("   "))
    }

    @Test
    fun `a folder name finds the takes inside it`() {
        assertTrue(query("wilhelm").matches("Wilhelm/Refrain 20260617.m4a", null))
        assertFalse(query("wilhelm").matches("Bonaparte/Bonaparte.m4a", null))
    }

    @Test
    fun `every word has to match, in any order`() {
        val key = "Wilhelm/Refrain 20260617.m4a"
        assertTrue(query("refrain wilhelm").matches(key, null))
        assertFalse(query("wilhelm verse").matches(key, null))
    }

    @Test
    fun `the note counts as much as the name`() {
        val key = "2026-07-28 12-29-57.wav"
        assertTrue(query("riff in d").matches(key, "The riff in D, with the open string"))
        assertTrue(query("riff 12-29").matches(key, "The riff in D"))
        assertFalse(query("riff").matches(key, null))
    }

    @Test
    fun `case and accents are ignored on both sides`() {
        assertTrue(query("ubung").matches("Übung 3.wav", null))
        assertTrue(query("ÜBUNG").matches("ubung 3.wav", null))
        assertTrue(query("muistio").matches("Musiikkimuistio/Oma idea.flac", null))
    }
}

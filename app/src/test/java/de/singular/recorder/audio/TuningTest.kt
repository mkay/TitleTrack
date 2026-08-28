// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class TuningTest {

    @Test
    fun `the reference is where the scale is pinned`() {
        assertEquals(440.0, Tuning.hzOf(69), 1e-9)
        // A4 itself is above the range a guitar tuner covers, so the scale is checked an octave
        // down, where a string actually is.
        assertEquals(220.0, Tuning.hzOf(57), 1e-9)
        assertEquals("A3", Tuning.read(220.0)!!.note.toString())
        assertEquals(0.0, Tuning.read(220.0)!!.cents, 1e-9)
    }

    @Test
    fun `the reference itself is out of range, being above the highest string`() {
        assertNull(Tuning.read(Tuning.A4_HZ))
    }

    @Test
    fun `the six open strings of a guitar land on their own names`() {
        val strings = mapOf(
            "E2" to 82.4069, "A2" to 110.0, "D3" to 146.8324,
            "G3" to 195.9977, "B3" to 246.9417, "E4" to 329.6276,
        )
        strings.forEach { (name, hz) ->
            val reading = Tuning.read(hz)!!
            assertEquals(name, reading.note.toString())
            assertEquals("$name should be dead in tune", 0.0, reading.cents, 0.01)
        }
    }

    @Test
    fun `a bass's four strings are an octave below the guitar's, and named so`() {
        val strings = mapOf("E1" to 41.2034, "A1" to 55.0, "D2" to 73.4162, "G2" to 97.9989)
        strings.forEach { (name, hz) ->
            val reading = Tuning.read(hz)!!
            assertEquals(name, reading.note.toString())
            assertEquals(0.0, reading.cents, 0.01)
        }
    }

    @Test
    fun `an octave is twelve hundred cents, wherever it is taken`() {
        assertEquals(1200.0, Tuning.centsBetween(880.0, 440.0), 1e-9)
        assertEquals(-1200.0, Tuning.centsBetween(Tuning.hzOf(28), Tuning.hzOf(40)), 1e-9)
    }

    @Test
    fun `a flat string reads flat against the note it is nearest`() {
        val reading = Tuning.read(82.0)!!
        assertEquals("E2", reading.note.toString())
        assertTrue("expected flat, got ${reading.cents}", reading.cents < 0)
        assertEquals(-8.55, reading.cents, 0.05)
        assertFalse(reading.inTune)
    }

    @Test
    fun `the in-tune window is two cents either side`() {
        val target = Tuning.hzOf(45)
        assertTrue(Tuning.read(target)!!.inTune)
        assertTrue(Tuning.read(target * cents(1.9))!!.inTune)
        assertTrue(Tuning.read(target * cents(-1.9))!!.inTune)
        assertFalse(Tuning.read(target * cents(2.5))!!.inTune)
        assertFalse(Tuning.read(target * cents(-2.5))!!.inTune)
    }

    @Test
    fun `a pitch never lands more than half a semitone from its note`() {
        // Started just inside each bound: the bounds themselves are exactly the midpoint between
        // two notes, where rounding either way is equally right.
        var hz = Tuning.MIN_HZ * cents(1.0)
        while (hz < Tuning.MAX_HZ * cents(-1.0)) {
            val reading = Tuning.read(hz)!!
            assertTrue(
                "$hz sat ${reading.cents} cents from ${reading.note}",
                reading.cents > -50.0001 && reading.cents < 50.0001,
            )
            hz *= cents(3.0)
        }
    }

    @Test
    fun `anything outside the range is not a string being tuned`() {
        assertNull(Tuning.read(20.0))
        assertNull(Tuning.read(900.0))
        assertNull(Tuning.read(Tuning.MIN_HZ - 0.1))
        assertNull(Tuning.read(Tuning.MAX_HZ + 0.1))
    }

    @Test
    fun `the range covers a whole tone past both outermost strings`() {
        // A slack low E on a bass, and an over-tightened high E on a guitar, both still read.
        assertEquals("D1", Tuning.read(36.71)!!.note.toString())
        assertEquals("F♯4", Tuning.read(Tuning.hzOf(66))!!.note.toString())
    }

    /** The ratio that is [c] cents. */
    private fun cents(c: Double): Double = 2.0.pow(c / 1200.0)
}

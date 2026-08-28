// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow

class StrobeTest {

    private val blockFrames = 1024

    /** Run a signal through, returning the rings after each block. */
    private fun run(targetHz: Double, signal: ShortArray): List<Strobe.Rings> {
        val strobe = Strobe()
        strobe.retune(targetHz)
        val out = mutableListOf<Strobe.Rings>()
        var offset = 0
        while (offset + blockFrames <= signal.size) {
            strobe.push(signal.copyOfRange(offset, offset + blockFrames), blockFrames)
                ?.let { out.add(it) }
            offset += blockFrames
        }
        return out
    }

    /**
     * The settled part of a run.
     *
     * The rings themselves are right almost at once, but [Strobe.Rings.turnsPerSecond] is a
     * smoothed derivative of the angle and takes about 0.8 s to arrive — the detector's own
     * transient and the rate filter compound. Hence two-second signals and only the last third
     * judged.
     */
    private fun settled(rings: List<Strobe.Rings>) = rings.drop(rings.size * 2 / 3)

    private fun shortestWay(delta: Float): Float {
        var d = delta
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }

    /**
     * The claim the whole display rests on: at the target frequency, the rings do not move. Not
     * "move slowly" — a strobe that crept on a perfect note would be unreadable, because creeping
     * is the only thing it has to say.
     *
     * The mean is what that claim is about, and it is checked hard. A single block carries a little
     * ripple on top — see [ripple is bounded and far too small to see] for where it comes from and
     * why it does not matter — so the per-block figure is only bounded, not required to be zero.
     */
    @Test
    fun `a string exactly in tune holds both rings still`() {
        val rings = settled(run(110.0, Tones.note(110.0, 2.0, 0.6, 0.4)))
        val mean = rings.map { it.turnsPerSecond }.average()
        assertEquals("outer ring drifted", 0.0, mean, 0.005)

        val first = rings.first()
        val last = rings.last()
        assertTrue(
            "outer ring moved ${shortestWay(last.fundamental - first.fundamental)} rad",
            abs(shortestWay(last.fundamental - first.fundamental)) < 0.1f,
        )
        assertTrue(
            "inner ring moved ${shortestWay(last.octave - first.octave)} rad",
            abs(shortestWay(last.octave - first.octave)) < 0.1f,
        )
    }

    @Test
    fun `a string one hertz sharp turns the outer ring once a second`() {
        val rings = settled(run(110.0, Tones.note(111.0, 2.0, 0.6, 0.4)))
        assertEquals(1.0, rings.map { it.turnsPerSecond }.average(), 0.01)
    }

    @Test
    fun `flat turns it the other way`() {
        val rings = settled(run(110.0, Tones.note(109.0, 2.0, 0.6, 0.4)))
        assertEquals(-1.0, rings.map { it.turnsPerSecond }.average(), 0.01)
    }

    /**
     * A real input carries a negative-frequency image as well as the component being measured, and
     * the image lands at twice the reference — nearest the fundamental, and so least filtered, on
     * the lowest string there is. What survives the detector's filter is a wobble on the angle
     * rather than a drift of it: at bass E1, the worst case, under a degree and a half of ring.
     *
     * This is here so that the number is a measured bound rather than an assumption, and so that a
     * change to the filtering that made it worse would be noticed. It was three degrees at E1
     * before the detector's memory was tied to the period rather than fixed, and is two now —
     * around half a percent of a turn, which is nothing to an eye watching for rotation.
     */
    @Test
    fun `ripple is bounded and far too small to see`() {
        listOf(41.2034, 82.4069, 110.0, 329.6276).forEach { hz ->
            val rings = settled(run(hz, Tones.note(hz, 2.0, 0.6, 0.4)))
            val angles = rings.map { it.fundamental }
            val wobble = angles.maxOf { a -> abs(shortestWay(a - angles.first())) }
            assertTrue(
                "at $hz Hz the ring wobbled ${Math.toDegrees(wobble.toDouble())} degrees",
                wobble < 0.03f,
            )
        }
    }

    /**
     * The inner ring runs against the second harmonic, so it turns at exactly twice the rate. That
     * is what makes it the fine one: it is still visibly moving when the outer ring has stopped.
     */
    @Test
    fun `the inner ring turns at twice the rate of the outer`() {
        val rings = settled(run(110.0, Tones.note(110.5, 2.0, 0.6, 0.4)))
        val outer = totalTurns(rings.map { it.fundamental })
        val inner = totalTurns(rings.map { it.octave })
        assertEquals("outer turned $outer, inner $inner", 2.0, inner / outer, 0.06)
    }

    @Test
    fun `a tenth of a cent still moves the ring, given a moment`() {
        // What the needle cannot see: a tenth of a cent at A2 is 6 millihertz. Over a second the
        // ring turns barely at all — but it turns, steadily, and in a knowable direction.
        val sharp = 110.0 * 2.0.pow(0.1 / 1200.0)
        val rings = settled(run(110.0, Tones.note(sharp, 2.0, 0.6, 0.4)))
        val rate = rings.map { it.turnsPerSecond }.average()
        assertTrue("rate was $rate turns/s, expected about 0.0063", rate > 0.002 && rate < 0.012)
    }

    @Test
    fun `the ring position says which way the string is off, not only that it is`() {
        val sharp = run(110.0, Tones.note(110.6, 2.0, 0.6, 0.4))
        val flat = run(110.0, Tones.note(109.4, 2.0, 0.6, 0.4))
        assertTrue(settled(sharp).all { it.turnsPerSecond > 0 })
        assertTrue(settled(flat).all { it.turnsPerSecond < 0 })
    }

    @Test
    fun `strength follows how much of the note is actually there`() {
        val loud = settled(run(110.0, Tones.sine(110.0, 2.0, amplitude = 0.8))).last()
        val quiet = settled(run(110.0, Tones.sine(110.0, 2.0, amplitude = 0.05))).last()
        assertTrue("loud ${loud.strength} vs quiet ${quiet.strength}", loud.strength > quiet.strength)
        assertEquals(0.8f, loud.strength, 0.1f)
    }

    @Test
    fun `silence locks onto nothing`() {
        val rings = settled(run(110.0, Tones.silence(2.0)))
        rings.forEach { assertTrue("strength was ${it.strength}", it.strength < 0.01f) }
    }

    @Test
    fun `nothing is reported until a note has been chosen`() {
        val strobe = Strobe()
        assertNull(strobe.push(Tones.sine(110.0, 0.05), 1024))
        strobe.retune(110.0)
        assertNotNull(strobe.push(Tones.sine(110.0, 0.05), 1024))
    }

    /** Total signed revolutions across a sequence of angles, unwrapped. */
    private fun totalTurns(angles: List<Float>): Double {
        var total = 0.0
        for (i in 1 until angles.size) total += shortestWay(angles[i] - angles[i - 1]).toDouble()
        return total / (2 * PI)
    }
}

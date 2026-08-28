// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PitchTest {

    /** How far off [expected] a reading is, in cents — the only unit worth judging a tuner in. */
    private fun centsOff(result: Pitch.Result?, expected: Double): Double {
        assertNotNull("no pitch detected at all", result)
        return Tuning.centsBetween(result!!.hz, expected)
    }

    private fun detect(signal: ShortArray): Pitch.Result? {
        val pitch = Pitch()
        Tones.feed(pitch, signal)
        return pitch.detect()
    }

    @Test
    fun `every open string of a guitar is found within a cent and a half`() {
        val strings = listOf(82.4069, 110.0, 146.8324, 195.9977, 246.9417, 329.6276)
        strings.forEach { hz ->
            // Amplitudes falling away up the series, which is roughly what a plucked string does.
            val result = detect(Tones.note(hz, 0.5, 0.6, 0.3, 0.15, 0.08))
            val off = centsOff(result, hz)
            assertTrue("$hz Hz read ${result!!.hz} Hz, $off cents off", abs(off) < 1.5)
        }
    }

    @Test
    fun `every open string of a bass is found within a cent`() {
        val strings = listOf(41.2034, 55.0, 73.4162, 97.9989)
        strings.forEach { hz ->
            val result = detect(Tones.note(hz, 0.6, 0.6, 0.3, 0.15, 0.08))
            val off = centsOff(result, hz)
            assertTrue("$hz Hz read ${result!!.hz} Hz, $off cents off", abs(off) < 1.0)
        }
    }

    /**
     * The failure that would make the tuner feel broken, and the reason this file exists.
     *
     * A plucked low E has less energy at its fundamental than at the partials above it. Anything
     * that picks the loudest component answers 82 Hz to a bass's open E and sends you tuning the
     * string an octave wrong.
     */
    @Test
    fun `a low E whose fundamental is nearly gone is still a low E`() {
        val e1 = 41.2034
        val result = detect(Tones.note(e1, 0.6, 0.04, 0.7, 0.5, 0.35, 0.2))
        val off = centsOff(result, e1)
        assertTrue(
            "read ${result!!.hz} Hz — an octave error would land near ${e1 * 2}",
            abs(off) < 2.0,
        )
    }

    @Test
    fun `a fundamental removed entirely does not become its own second harmonic`() {
        val e2 = 82.4069
        val result = detect(Tones.note(e2, 0.5, 0.0, 0.6, 0.45, 0.3, 0.2))
        assertTrue("read ${result!!.hz} Hz", abs(centsOff(result, e2)) < 2.0)
    }

    @Test
    fun `a string pulled sharp reads sharp by the amount it was pulled`() {
        val target = 110.0
        listOf(-30.0, -7.0, 7.0, 30.0).forEach { cents ->
            val hz = target * Math.pow(2.0, cents / 1200.0)
            val result = detect(Tones.note(hz, 0.5, 0.6, 0.3, 0.15))
            val reading = Tuning.read(result!!.hz)!!
            assertEquals("A2", reading.note.toString())
            assertEquals("pulled $cents cents", cents, reading.cents, 1.0)
        }
    }

    /**
     * A low string heard faintly, with mains hum on top of it — a phone microphone hears little of
     * a 41 Hz string, and 50 Hz hum never decays. Asked this, the detector once answered 42.9 Hz:
     * not silence, not the right note, but a plausible-looking reading 70 cents out that a player
     * would have tuned to. Saying nothing is the only acceptable answer.
     */
    @Test
    fun `a low string buried under mains hum is not reported as the wrong note`() {
        val buried = Tones.mix(
            Tones.note(41.2034, 1.0, 0.004, 0.007, 0.006),
            Tones.hum(1.0),
            Tones.noise(1.0, amplitude = 0.004),
        )
        val result = detect(buried)
        if (result != null) {
            val off = Tuning.centsBetween(result.hz, 41.2034)
            assertTrue("answered ${result.hz} Hz, $off cents out", abs(off) < 5.0)
        }
    }

    @Test
    fun `silence has no pitch`() {
        assertNull(detect(Tones.silence(0.5)))
    }

    @Test
    fun `hiss has no pitch`() {
        assertNull(detect(Tones.noise(0.5)))
    }

    /**
     * An electric or semi-acoustic played unplugged, a foot from the phone. Quiet is not the same
     * as absent, and this is the level the tuner has to work at rather than the level a dreadnought
     * offers.
     */
    @Test
    fun `a quietly played instrument is still a note`() {
        val result = detect(Tones.note(146.8324, 0.5, 0.012, 0.006, 0.003))
        assertTrue("read ${result!!.hz} Hz", abs(centsOff(result, 146.8324)) < 2.0)
    }

    @Test
    fun `a very quiet room is silence, not a note`() {
        assertNull(detect(Tones.sine(110.0, 0.5, amplitude = 0.0005)))
    }

    @Test
    fun `nothing is reported before there is enough audio to be sure`() {
        val pitch = Pitch()
        Tones.feed(pitch, Tones.sine(110.0, 0.05))
        assertNull("answered from a fifth of the audio it needs", pitch.detect())

        Tones.feed(pitch, Tones.sine(110.0, 0.5))
        assertNotNull(pitch.detect())
    }

    @Test
    fun `a clean note is reported with high confidence and hiss is not`() {
        val clean = detect(Tones.note(146.8324, 0.5, 0.6, 0.3, 0.15))!!
        assertTrue("clarity was ${clean.clarity}", clean.clarity > 0.9f)
    }

    @Test
    fun `reset makes it wait for a fresh windowful`() {
        val pitch = Pitch()
        Tones.feed(pitch, Tones.sine(110.0, 0.5))
        assertNotNull(pitch.detect())

        pitch.reset()
        assertNull(pitch.detect())
    }

    /**
     * A twelfth-fret harmonic is an octave above the open string, which is above the range — and it
     * reads as the open string, because a signal repeating every period also repeats every two.
     * That is the useful answer rather than a lucky one: tuning off harmonics is a thing people do.
     *
     * It is worth knowing the limit behind this, though. A pitch far above the range can land on an
     * *odd* subharmonic, which is not a musical octave of anything: an isolated A5 reads as D4. The
     * tuner's range is a statement about what it is for, not a filter it applies to its input.
     */
    @Test
    fun `a harmonic an octave above the range reads as the string it came from`() {
        val e4 = 329.6276
        val result = detect(Tones.note(e4 * 2, 0.5, 0.6, 0.3, 0.15))
        assertTrue("read ${result!!.hz} Hz", abs(centsOff(result, e4)) < 2.0)
    }
}

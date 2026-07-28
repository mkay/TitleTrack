package de.singular.recorder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid the band counts on. Worth testing rather than listening to: an error here is a drummer
 * drifting over five minutes, which is hard to hear early and impossible to mistake later.
 */
class BeatsTest {

    private val beats = Beats(bpm = 120f, beatsPerBar = 4, sampleRate = 44_100)

    @Test
    fun `a beat at 120 bpm is half a second`() {
        assertEquals(22_050.0, beats.framesPerBeat, 0.001)
        assertEquals(5_512.5, beats.framesPerStep, 0.001)
    }

    @Test
    fun `every step is visited exactly once when walking in chunks`() {
        val chunk = 882 // ~20 ms, the player's own chunk
        val seen = ArrayList<Long>()
        var frame = 0L
        while (frame < 44_100L * 60) { // a minute
            seen += beats.stepsIn(frame, frame + chunk)
            frame += chunk
        }
        // 120 bpm for 60 s = 120 beats = 480 sixteenths, starting at step 0.
        assertEquals((0L until 480L).toList(), seen)
    }

    @Test
    fun `a nudge moves every step and never loses one`() {
        val nudged = beats.copy(offsetMs = 120)
        val chunk = 882
        val seen = ArrayList<Long>()
        var frame = 0L
        while (frame < 44_100L * 10) {
            seen += nudged.stepsIn(frame, frame + chunk)
            frame += chunk
        }
        assertEquals(seen.distinct(), seen)
        assertEquals(seen.sorted(), seen)
        // The first step now sits 120 ms in, and nothing is reported before it.
        assertEquals(0L, seen.first())
        assertEquals(44_100.0 * 120 / 1_000, nudged.frameOfStep(0), 0.001)
    }

    @Test
    fun `a negative nudge does not invent steps before the take`() {
        val early = beats.copy(offsetMs = -80)
        val steps = early.stepsIn(0, 882)
        // The rule is about frames, not indices: nothing may be triggered before the first sample.
        assertTrue(steps.all { early.frameOfStep(it) >= 0.0 })
    }

    @Test
    fun `a downbeat inside the take carries the grid backwards to the start`() {
        // Beat one pointed at 4 s in — as it is for an import that opens with somebody tuning up.
        val late = beats.copy(offsetMs = 4_000)
        val steps = late.stepsIn(0, 44_100)
        assertTrue("the first second is before beat one and must still be counted", steps.any())
        assertTrue("every step still lands inside the take", steps.all { late.frameOfStep(it) >= 0 })
        assertTrue("steps before the marker are negative", steps.first() < 0)
        // 4 s at 120 bpm is 8 beats, so the take's first sample is exactly a downbeat itself.
        assertEquals(0, late.stepInBar(steps.first()))
    }

    @Test
    fun `a step before the marker knows its place in the bar`() {
        // One step short of beat one is the last sixteenth of the bar before it, not the first.
        assertEquals(15, beats.stepInBar(-1))
        assertEquals(12, beats.stepInBar(-4))
        assertEquals(-1, beats.barOfStep(-1))
    }

    @Test
    fun `positions do not drift from accumulating a fractional beat`() {
        // 44100 / (110/60) is 24054.5454… frames per beat: the case where counting forward walks.
        val awkward = Beats(bpm = 110f, beatsPerBar = 4, sampleRate = 44_100)
        val step = 4L * 60 * 110 / 60 * Beats.STEPS_PER_BEAT // four minutes of sixteenths
        val exact = step * awkward.framesPerStep
        assertEquals(exact, awkward.frameOfStep(step), 0.0001)
    }

    @Test
    fun `bars and steps within them count from the downbeat`() {
        assertEquals(0, beats.stepInBar(0))
        assertEquals(15, beats.stepInBar(15))
        assertEquals(0, beats.stepInBar(16))
        assertEquals(1L, beats.barOfStep(16))
        assertEquals(2L, beats.barOfStep(35))
    }

    @Test
    fun `an empty or backwards window asks for nothing`() {
        assertTrue(beats.stepsIn(1_000, 1_000).isEmpty())
        assertTrue(beats.stepsIn(2_000, 1_000).isEmpty())
    }

    @Test
    fun `a pattern fills a bar of any length`() {
        val four = Patterns.BACKBEAT.hitsForBar(4)
        assertEquals(Patterns.BACKBEAT.hits, four)
        // A three keeps only what fits before the bar line.
        assertTrue(Patterns.BACKBEAT.hitsForBar(3).all { it.step < 12 })
        // A five carries on rather than leaving the last beat silent.
        val five = Patterns.BACKBEAT.hitsForBar(5)
        assertTrue(five.any { it.step >= 16 })
        assertTrue(five.all { it.step < 20 })
    }
}

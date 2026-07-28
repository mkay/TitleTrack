package de.singular.recorder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The kit renders in its own initialisers, which is worth a test on its own: the first version
 * declared the random source *below* the voices that use it, so constructing a [SynthKit] threw a
 * null pointer on the kick's first noisy sample. Nothing about that is audible in review, and it
 * costs a crash on the device to find.
 */
class SynthKitTest {

    private val kit = SynthKit(sampleRate = 44_100)

    private val voices
        get() = listOf(kit.kick, kit.snare, kit.hat, kit.hatOpen, kit.note(45))

    @Test
    fun `every voice renders something audible`() {
        for (voice in voices) {
            assertTrue("empty voice", voice.samples.isNotEmpty())
            assertTrue("silent voice", voice.samples.any { abs(it) > 0.05f })
        }
    }

    @Test
    fun `nothing leaves the rails`() {
        for (voice in voices) {
            assertTrue("clipped voice", voice.samples.all { it in -1f..1f })
        }
    }

    @Test
    fun `every voice decays rather than ending abruptly`() {
        // A one-shot cut off mid-swing clicks. The last hundredth should be near silence.
        for (voice in voices) {
            val tail = voice.samples.takeLast(voice.samples.size / 100)
            assertTrue("abrupt tail", tail.all { abs(it) < 0.1f })
        }
    }

    @Test
    fun `the same kit sounds the same twice`() {
        val other = SynthKit(sampleRate = 44_100)
        assertTrue(kit.snare.samples.contentEquals(other.snare.samples))
    }

    @Test
    fun `a bass note is the pitch it was asked for`() {
        // A4 = 440 Hz = midi 69: count zero crossings over the first tenth, before it decays far.
        val samples = kit.note(69).samples
        val window = samples.copyOfRange(441, 4_410)
        var crossings = 0
        for (i in 1 until window.size) {
            if (window[i - 1] < 0f && window[i] >= 0f) crossings++
        }
        // 440 Hz over 0.09 s ≈ 39.6 cycles.
        assertEquals(40f, crossings.toFloat(), 2f)
    }
}

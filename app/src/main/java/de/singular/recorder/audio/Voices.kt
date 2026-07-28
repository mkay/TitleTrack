package de.singular.recorder.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * One sound the band can make, as a buffer of mono samples in `-1f..1f`.
 *
 * Deliberately a *rendered buffer* rather than a live oscillator. Every voice here is a one-shot
 * shorter than a beat, so rendering each once and mixing copies of it is both simpler and cheaper
 * than running synthesis in the audio thread — the same trade `Metronome` makes for its click, for
 * the same reason: what happens under a feeder thread should be arithmetic, not decisions.
 *
 * It is an interface so that the patterns never learn what they are triggering. Swapping these
 * synthesised voices for recorded ones later is a second implementation and nothing else moving.
 */
interface Voice {
    /** The rendered one-shot. Treated as read-only by callers, who only ever mix from it. */
    val samples: FloatArray
}

/**
 * A synthesised kit and bass, in the register of a drum machine rather than a kit in a room.
 *
 * That is a choice rather than a limitation. A practice bed has to sit under a guitar without
 * competing with it, and the sounds that do that best are dry and short: what makes a sampled kit
 * worth having — the room, the cymbal wash, the shifting timbre from hit to hit — is exactly what
 * smears a click track into mush. These are built from the same parts as `Metronome.renderClick`:
 * a pitched body, an inharmonic or noisy component, and a fast exponential decay.
 *
 * Every voice is rendered once per instance and lives for as long as the player does.
 */
class SynthKit(private val sampleRate: Int = Wav.SAMPLE_RATE) {

    // Declared before the voices, and that is not a matter of taste: each voice below is rendered
    // in its own initialiser, and a property initialised further down the class is still null while
    // that runs. The kick asks for noise on its first sample.

    /** Deterministic, so a take sounds the same on every playback and every device. */
    private val random = Random(20260727)

    /** Carries the one-pole difference in [highs] across calls. */
    private var lastNoise = 0f

    /** A short pitch drop from ~110 Hz to ~48 Hz — the thump, with enough attack to place it. */
    val kick: Voice = rendered(140) { t, n ->
        val sweep = 48f + 62f * exp(-32f * t)
        val body = sin(2.0 * PI * sweep * t).toFloat()
        val click = if (t < 0.004f) 0.35f * noise() else 0f
        (body + click) * exp(-14f * t)
    }

    /** Noise over a 190 Hz body: the body places the pitch, the noise makes it a snare. */
    val snare: Voice = rendered(180) { t, _ ->
        val body = 0.5f * sin(2.0 * PI * 190f * t).toFloat()
        val rattle = 0.9f * noise()
        (body + rattle) * exp(-24f * t)
    }

    /** Filtered noise, very short. Two decays — the closed hat is the same voice, cut earlier. */
    val hat: Voice = rendered(60) { t, _ ->
        highs() * exp(-70f * t) * 0.5f
    }

    val hatOpen: Voice = rendered(220) { t, _ ->
        highs() * exp(-16f * t) * 0.45f
    }

    /**
     * A bass note at [midi], for phase two. A sine with a touch of its own second harmonic and a
     * slow decay: an electric bass played with the flesh of the thumb, near enough for a bed.
     *
     * Rendered per note on demand rather than held, because a chord track asks for a dozen
     * different pitches and none of them is hot enough a path to precompute.
     */
    fun note(midi: Int, durationMs: Int = 400): Voice {
        val freq = 440f * Math.pow(2.0, (midi - 69) / 12.0).toFloat()
        return rendered(durationMs) { t, _ ->
            val fundamental = sin(2.0 * PI * freq * t).toFloat()
            val octave = 0.22f * sin(2.0 * PI * freq * 2 * t).toFloat()
            // A fast attack ramp keeps the note from clicking; 4 ms is under a plucked string's.
            val attack = (t / 0.004f).coerceAtMost(1f)
            (fundamental + octave) * attack * exp(-3.2f * t) * 0.6f
        }
    }

    /** Noise with the low end taken out — a one-pole difference, which is all a hat needs. */
    private fun highs(): Float {
        val n = noise()
        val out = n - lastNoise
        lastNoise = n
        return out
    }

    private fun noise(): Float = random.nextFloat() * 2f - 1f

    /**
     * Render a one-shot, and take it to silence at the end.
     *
     * The fade is not a nicety. These envelopes are exponential, so they never actually reach zero,
     * and a buffer that stops while the kick is still at a seventh of full scale ends in a step —
     * which is a click, on every beat, at exactly the moment the ear is listening for the beat. A
     * few milliseconds of ramp costs nothing audible and removes it.
     */
    private fun rendered(durationMs: Int, sample: (t: Float, n: Int) -> Float): Voice {
        val n = sampleRate * durationMs / 1_000
        val fade = (sampleRate * FADE_MS / 1_000).coerceAtMost(n)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val raw = sample(i.toFloat() / sampleRate, i)
            val ramp = if (i >= n - fade) (n - i).toFloat() / fade else 1f
            out[i] = (raw * ramp).coerceIn(-1f, 1f)
        }
        return object : Voice {
            override val samples = out
        }
    }

    private companion object {
        const val FADE_MS = 6
    }
}

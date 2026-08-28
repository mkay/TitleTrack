// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Signals to point the tuner at, so its claims can be checked without a guitar in the room. */
object Tones {

    /**
     * A note [hz] hertz, [seconds] long, built from [partials] — amplitude of the fundamental
     * first, then the second harmonic, the third, and so on.
     *
     * Partial amplitudes are what makes these worth having: a plucked string is not a sine, and the
     * interesting failure of any pitch detector is what it does when the fundamental is not the
     * loudest thing present.
     */
    fun note(
        hz: Double,
        seconds: Double,
        vararg partials: Double,
        sampleRate: Int = Wav.SAMPLE_RATE,
        phase: Double = 0.0,
    ): ShortArray {
        val frames = (seconds * sampleRate).toInt()
        return ShortArray(frames) { n ->
            var sample = 0.0
            partials.forEachIndexed { index, amplitude ->
                val harmonic = index + 1
                sample += amplitude * sin(2 * PI * hz * harmonic * n / sampleRate + phase * harmonic)
            }
            (sample.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    /** A plain sine, which is the easy case. */
    fun sine(hz: Double, seconds: Double, amplitude: Double = 0.5): ShortArray =
        note(hz, seconds, amplitude)

    /** Hiss at a fixed seed, so "the tuner says nothing" is a repeatable claim. */
    fun noise(seconds: Double, amplitude: Double = 0.2, seed: Int = 4): ShortArray {
        val random = Random(seed)
        val frames = (seconds * Wav.SAMPLE_RATE).toInt()
        return ShortArray(frames) {
            ((random.nextDouble() * 2 - 1) * amplitude * 32767).toInt().toShort()
        }
    }

    /**
     * Mains hum, the thing a bass tuner is really up against: 50 Hz sits between G1 and G♯1, three
     * semitones above an open low E, and it never decays.
     */
    fun hum(seconds: Double, amplitude: Double = 0.01, hz: Double = 50.0): ShortArray =
        note(hz, seconds, amplitude)

    /** Everything the microphone hears at once, summed and clipped as the converter would. */
    fun mix(vararg signals: ShortArray): ShortArray {
        val n = signals.minOf { it.size }
        return ShortArray(n) { i ->
            signals.sumOf { it[i].toInt() }.coerceIn(-32768, 32767).toShort()
        }
    }

    fun silence(seconds: Double): ShortArray = ShortArray((seconds * Wav.SAMPLE_RATE).toInt())

    /** Feed a whole signal through in the block size the microphone actually delivers. */
    fun feed(pitch: Pitch, signal: ShortArray, blockFrames: Int = 1024) {
        var offset = 0
        while (offset < signal.size) {
            val count = minOf(blockFrames, signal.size - offset)
            pitch.push(signal.copyOfRange(offset, offset + count), count)
            offset += count
        }
    }
}

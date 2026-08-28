// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The strobe: how far the input's phase has slipped against a reference running at the target note.
 *
 * **This is a different measurement from [Pitch], not a different drawing of the same one.** A
 * strobe does not measure frequency at all. It compares the input against a reference oscillator
 * and shows the phase between them — and if the two are at the same frequency, that phase does not
 * change, however long you watch. A wheel lit by a lamp flashing at exactly its rotation rate
 * appears to stand still; a hair off, and it creeps.
 *
 * That is why a strobe out-resolves a needle. [Pitch] is capped by its analysis window: it has one
 * window's worth of evidence and no more. Here, being a hundredth of a cent sharp still turns the
 * ring all the way round eventually, so precision is limited only by how long you are willing to
 * watch. Nothing has to be made more accurate for that to happen — it falls out of the method.
 *
 * **Two rings.** One detector runs at the note's own frequency and one at twice it, against the
 * string's second harmonic. The second turns at exactly double the rate, which makes it a vernier:
 * the slow ring settles first, and then the fast one is still visibly creeping while the slow one
 * looks stopped. That is not a decorative multiplier — it is what the octave bands of a strobe do,
 * and the doubling is a consequence of the physics rather than a number chosen to look good.
 *
 * The reference oscillators run *continuously* across blocks, which is the whole trick: if their
 * phase restarted every block, the angle would report the input's absolute phase and drift with the
 * block boundary rather than against the reference. Everything here is JVM-only, so the claim that
 * an in-tune input stands still is a unit test.
 */
class Strobe(private val sampleRate: Int = Wav.SAMPLE_RATE) {

    /**
     * Where the two rings stand, in radians, and how fast they are turning.
     *
     * [turnsPerSecond] is the outer ring's rate, and is the frequency error in hertz: a string one
     * hertz sharp turns the outer ring once a second. It is positive sharp and negative flat, so
     * the direction of travel is which way to move the peg. The inner ring turns at twice this.
     */
    data class Rings(
        val fundamental: Float,
        val octave: Float,
        val turnsPerSecond: Float,
        /** How much signal the detectors are actually locked onto, 0f..1f. */
        val strength: Float,
    )

    private companion object {
        /** How long the reported rate is averaged over. Long enough to be still, short to follow. */
        const val RATE_TIME_CONSTANT_SECONDS = 0.15

        /** Below this there is no note to take a rate from, and the angle is only noise. */
        const val MIN_STRENGTH_FOR_RATE = 0.002f
    }

    private val fundamental = Detector(sampleRate)
    private val octave = Detector(sampleRate)

    private var targetHz = 0.0
    private var lastAngle = Double.NaN
    private var framesSinceAngle = 0
    private var rate = 0f

    /**
     * Point the reference oscillators at [hz], the note being tuned to.
     *
     * Re-pointing them resets their phase, so the rings jump. That is correct: they were measuring
     * against a different note, and there is no continuity between the two to preserve.
     */
    fun retune(hz: Double) {
        if (hz == targetHz) return
        targetHz = hz
        fundamental.tune(hz / sampleRate)
        octave.tune(2.0 * hz / sampleRate)
        lastAngle = Double.NaN
        framesSinceAngle = 0
        rate = 0f
    }

    /** Forget the accumulated phase — on retaking the microphone, or after a gap in the audio. */
    fun reset() {
        val hz = targetHz
        targetHz = 0.0
        if (hz > 0.0) retune(hz)
    }

    /**
     * Fold [count] frames of 16-bit PCM into both detectors and report where the rings now stand,
     * or null if no note has been set or the block carried nothing to lock onto.
     */
    fun push(block: ShortArray, count: Int): Rings? {
        if (targetHz <= 0.0 || count <= 0) return null

        fundamental.integrate(block, count)
        octave.integrate(block, count)

        val angle = fundamental.angle()
        val strength = fundamental.strength()

        framesSinceAngle += count
        if (!lastAngle.isNaN() && strength > MIN_STRENGTH_FOR_RATE) {
            // Shortest way round: the ring turns less than half a revolution per block for any
            // deviation the display can be showing, so the short arc is always the true one.
            var delta = angle - lastAngle
            while (delta > PI) delta -= 2 * PI
            while (delta < -PI) delta += 2 * PI
            val instant = delta / (2 * PI) * sampleRate / framesSinceAngle

            // Smoothed, because one block is 23 ms and what is left of the image ripple after the
            // detector's own filter still moves a single-block estimate by a tenth of a turn a
            // second. The angle is not affected — only this derivative of it — but the rate is what
            // carries the rings between frames, and jitter there is a ring that shimmers on a note
            // that is not moving.
            val blend = 1.0 - exp(-count.toDouble() / (RATE_TIME_CONSTANT_SECONDS * sampleRate))
            rate += (blend * (instant - rate)).toFloat()
        }
        lastAngle = angle
        framesSinceAngle = 0

        return Rings(
            fundamental = angle.toFloat(),
            octave = octave.angle().toFloat(),
            turnsPerSecond = rate,
            strength = strength,
        )
    }

    /**
     * One quadrature detector: the input multiplied by a running cosine and sine at the reference
     * frequency, summed over the block. The angle of the resulting vector is the phase between
     * input and reference, and it is the ring's position.
     */
    private class Detector(private val sampleRate: Int) {
        // The oscillator, carried between blocks as a unit vector that gets rotated one step per
        // sample. Rotating a vector is two multiplies where cos() and sin() per sample would be two
        // transcendental calls, at 44,100 samples a second, twice over.
        private var cosPhase = 1.0
        private var sinPhase = 0.0
        private var cosStep = 1.0
        private var sinStep = 0.0

        private var i = 0.0
        private var q = 0.0
        private var timeConstant = MIN_TIME_CONSTANT_SECONDS

        fun tune(cyclesPerSample: Double) {
            val step = 2 * PI * cyclesPerSample
            cosStep = cos(step)
            sinStep = sin(step)
            cosPhase = 1.0
            sinPhase = 0.0
            i = 0.0
            q = 0.0

            // The image this filter exists to bury sits at twice the reference, so a fixed time
            // constant buries it less the lower the note — and low is exactly where a bass player
            // is staring at the thing. Remembering a fixed number of the *note's own* periods
            // instead makes the residual wobble the same at E1 as at E4, and spends the extra
            // patience only where the note is slow anyway. The floor is there for the top of the
            // range, where the rule would otherwise ask for a memory too short to average a noisy
            // room over.
            val hz = cyclesPerSample * sampleRate
            timeConstant = if (hz > 0) max(MIN_TIME_CONSTANT_SECONDS, PERIODS_REMEMBERED / hz)
            else MIN_TIME_CONSTANT_SECONDS
        }

        fun integrate(block: ShortArray, count: Int) {
            var ci = 0.0
            var qi = 0.0
            for (n in 0 until count) {
                val x = block[n] / 32768.0
                ci += x * cosPhase
                qi -= x * sinPhase
                val nextCos = cosPhase * cosStep - sinPhase * sinStep
                sinPhase = cosPhase * sinStep + sinPhase * cosStep
                cosPhase = nextCos
            }

            // Blend this block's mean into a running vector rather than replacing it.
            //
            // A real input carries a negative-frequency image as well as the term being measured,
            // and the image lands at twice the reference. Over one 23 ms block that is only a few
            // cycles, so it does not average away — it leaves a ripple on the angle that a block-
            // at-a-time detector would show as a ring shivering on a perfectly steady note. A
            // one-pole filter buries it. Crucially it costs nothing that matters here: filtering a
            // rotating vector leaves it rotating at exactly the same rate, only lagged and shorter,
            // so "in tune means standing still" survives untouched.
            val blend = 1.0 - exp(-count.toDouble() / (timeConstant * sampleRate))
            i += blend * (ci / count - i)
            q += blend * (qi / count - q)

            // The rotation loses unit length to rounding over thousands of steps; left alone it
            // would slowly change the reference's amplitude and, worse, its frequency.
            val length = sqrt(cosPhase * cosPhase + sinPhase * sinPhase)
            if (length > 0.0) {
                cosPhase /= length
                sinPhase /= length
            }
        }

        fun angle(): Double = atan2(q, i)

        /** Vector length against the most it could be, which is half amplitude for a real input. */
        fun strength(): Float = (2.0 * sqrt(i * i + q * q)).coerceIn(0.0, 1.0).toFloat()

        private companion object {
            /**
             * The shortest the detector ever remembers, in seconds. Reached above about 300 Hz,
             * where [PERIODS_REMEMBERED] would ask for less than a noisy room can be averaged over.
             */
            const val MIN_TIME_CONSTANT_SECONDS = 0.04

            /**
             * Periods of the note held onto. Twelve is what brings the wobble under a degree and a
             * half everywhere; fewer showed at the bottom of the range, and many more would make a
             * bass sluggish to answer the peg for a steadiness no eye can see.
             */
            const val PERIODS_REMEMBERED = 12.0
        }
    }
}

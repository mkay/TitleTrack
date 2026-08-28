// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What note is being played, from the microphone, by autocorrelation.
 *
 * **Why not an FFT.** At 44.1 kHz a 4096-point transform has bins 10.8 Hz apart. Between B3 and its
 * neighbours that is several semitones wide, so the answer would come entirely from interpolating
 * between bins — and it would still be wrong as often as not, because a plucked low string has a
 * *weaker* fundamental than its second and third harmonics. An FFT would happily report the loudest
 * partial and call it the note.
 *
 * So this is YIN: the cumulative mean normalised difference function, in the time domain. Its
 * normalisation step exists precisely to stop the octave error above, and parabolic interpolation
 * around the winning lag recovers a fraction of a sample.
 *
 * **Two rates, for cost and for precision.** Searching every lag from 122 to 1260 samples over an
 * 8192-sample window is nine million operations per reading, several dozen times a second, which is
 * not a thing to do on a phone. So the coarse search runs on the signal decimated by four, where it
 * costs a sixteenth as much, and only the winning lag is then refined at the full rate over a
 * seventeen-lag span. The decimation is what makes it affordable; the refinement is what keeps it
 * accurate, and skipping the refinement would cost 13 cents of resolution at the top of the range.
 *
 * **Resolution, honestly.** One full-rate lag step is 3.2 cents at E2 and 13 cents at E4, and
 * parabolic interpolation reliably recovers to about a tenth of that — so roughly 0.3 cents low and
 * 1.3 cents high. That is enough for a needle whose scale is ±50 cents. It is *not* strobe
 * precision, and it is not meant to be: [Strobe] measures accumulating phase instead, and its
 * precision grows with how long you watch rather than being capped by a window.
 *
 * Nothing here touches Android, which is what lets the octave-error case be a unit test rather than
 * a thing discovered on a phone with a bass in hand.
 */
class Pitch(private val sampleRate: Int = Wav.SAMPLE_RATE) {

    /** A pitch and how sure of it we are. [clarity] is 0f..1f, 1f being perfectly periodic. */
    data class Result(val hz: Double, val clarity: Float)

    companion object {
        /**
         * Ratio the coarse search runs at. Four takes 44.1 kHz to 11.025 kHz, whose Nyquist is
         * still fifteen times the highest fundamental we look for.
         */
        const val DECIMATION = 4

        /** Frames analysed for a normal reading: 93 ms, which is 7.7 periods of guitar E2. */
        const val SHORT_WINDOW = 4096

        /**
         * Frames analysed once the pitch is low enough that [SHORT_WINDOW] would be marginal.
         *
         * Bass E1's period is 1070 frames, so 4096 holds only 3.8 of them — enough for the
         * difference function to find a dip, but not enough for it to be a confident one. 8192 is
         * 186 ms, which is sluggish for a needle and is why it is not simply used throughout.
         */
        const val LONG_WINDOW = 8192

        /** Below this, a reading is taken again with [LONG_WINDOW]. */
        const val LONG_WINDOW_BELOW_HZ = 70.0

        /**
         * YIN's absolute threshold. The first dip below this wins, rather than the deepest dip
         * anywhere — which is the other half of the octave-error defence, since the dip at twice
         * the true period is often the deeper one.
         */
        const val THRESHOLD = 0.15

        /** Below this, the reading is discarded: a room with no string in it is not a note. */
        const val CLARITY_FLOOR = 0.6f

        /** Below this RMS the input is silence, and silence has no pitch. About -54 dBFS. */
        const val SILENCE_RMS = 0.002f

        /** Anti-alias filter length for the decimation. Odd, so it has a whole-sample delay. */
        private const val FIR_TAPS = 33

        /** Full-rate lags either side of the coarse winner that the refinement looks at. */
        private const val REFINE_SPAN = DECIMATION * 2
    }

    private val coarseRate = sampleRate / DECIMATION

    // Lag bounds, from the frequency range the tuner covers. Long lag = low note.
    private val minCoarseLag = (coarseRate / Tuning.MAX_HZ).toInt().coerceAtLeast(2)
    private val maxCoarseLag = (coarseRate / Tuning.MIN_HZ).toInt() + 1
    private val minFineLag = (sampleRate / Tuning.MAX_HZ).toInt().coerceAtLeast(2)
    private val maxFineLag = (sampleRate / Tuning.MIN_HZ).toInt() + 1

    // Ring buffers, powers of two so the wrap is a mask. The fine one has to hold the longest
    // window plus the longest lag, since the difference function reaches that far past its frame.
    private val fine = FloatArray(ceilPowerOfTwo(LONG_WINDOW + maxFineLag + REFINE_SPAN + 2))
    private val fineMask = fine.size - 1
    private var finePos = 0
    private var fineFilled = 0L

    private val coarse = FloatArray(ceilPowerOfTwo(LONG_WINDOW / DECIMATION + maxCoarseLag + 2))
    private val coarseMask = coarse.size - 1
    private var coarsePos = 0

    /** Counts input frames towards the next decimated output. */
    private var decimationPhase = 0

    private val fir = lowPass(FIR_TAPS, cutoff = 0.45 / DECIMATION)

    // Scratch, allocated once: this runs dozens of times a second and has no business making
    // garbage. Sized for the worst case, which is the long window at the longest lag.
    private val coarseFrame = FloatArray(LONG_WINDOW / DECIMATION + maxCoarseLag + 1)
    private val fineFrame = FloatArray(LONG_WINDOW + maxFineLag + REFINE_SPAN + 2)
    private val diff = DoubleArray(maxCoarseLag + 1)
    private val normalised = DoubleArray(maxCoarseLag + 1)
    private val refineScratch = DoubleArray(2 * REFINE_SPAN + 3)

    /** Frames needed before [detect] can say anything at all. About 214 ms. */
    val warmUpFrames: Int get() = LONG_WINDOW + maxFineLag

    /**
     * Take [count] frames of 16-bit PCM, as the microphone hands them over.
     *
     * Each frame is stored at full rate and also fed through the anti-alias filter towards the
     * decimated buffer. The filter reads back out of the full-rate ring rather than keeping its own
     * history, which is why this must be called in order and never with the same audio twice.
     */
    fun push(block: ShortArray, count: Int) {
        for (i in 0 until count) {
            fine[finePos] = block[i] / 32768f
            finePos = (finePos + 1) and fineMask
            fineFilled++

            if (++decimationPhase == DECIMATION) {
                decimationPhase = 0
                coarse[coarsePos] = filtered()
                coarsePos = (coarsePos + 1) and coarseMask
            }
        }
    }

    /** Forget everything heard so far — on retaking the microphone, or after a gap. */
    fun reset() {
        fine.fill(0f)
        coarse.fill(0f)
        finePos = 0
        coarsePos = 0
        fineFilled = 0
        decimationPhase = 0
    }

    /**
     * The pitch of the audio pushed so far, or null when there is nothing worth reporting —
     * not enough audio yet, near silence, or a signal too aperiodic to call a note.
     *
     * The frame analysed ends one maximum lag back from the newest audio, so a reading describes
     * the last window that had a full lag's worth of room after it, up to 29 ms behind live.
     */
    fun detect(): Result? {
        if (fineFilled < warmUpFrames) return null

        var window = SHORT_WINDOW
        var candidate = coarseLag(window) ?: return null
        var hz = sampleRate.toDouble() / (candidate.lag * DECIMATION)

        // Low notes get a second look over the longer window. Only ever one retry: the long
        // window's own answer is taken as final, whatever it says.
        if (hz < LONG_WINDOW_BELOW_HZ && window != LONG_WINDOW) {
            window = LONG_WINDOW
            candidate = coarseLag(window) ?: return null
            hz = sampleRate.toDouble() / (candidate.lag * DECIMATION)
        }

        if (candidate.clarity < CLARITY_FLOOR) return null

        val refined = refine(candidate.lag * DECIMATION, window) ?: return null
        return Result(sampleRate.toDouble() / refined, candidate.clarity)
    }

    private class Candidate(val lag: Int, val clarity: Float)

    /**
     * YIN over the decimated signal: build the difference function, normalise it cumulatively,
     * then take the first dip below [THRESHOLD] rather than the deepest dip overall.
     */
    private fun coarseLag(window: Int): Candidate? {
        val frame = window / DECIMATION
        val span = frame + maxCoarseLag
        snapshot(coarse, coarseMask, coarsePos, span, coarseFrame)

        if (rms(coarseFrame, frame) < SILENCE_RMS) return null

        for (tau in 1..maxCoarseLag) {
            var sum = 0.0
            for (j in 0 until frame) {
                val d = (coarseFrame[j] - coarseFrame[j + tau]).toDouble()
                sum += d * d
            }
            diff[tau] = sum
        }

        // d'(tau): each difference against the running mean of every difference before it. A dip
        // is only interesting relative to how deep the function has been getting anyway.
        normalised[0] = 1.0
        var running = 0.0
        for (tau in 1..maxCoarseLag) {
            running += diff[tau]
            normalised[tau] = if (running <= 0.0) 1.0 else diff[tau] * tau / running
        }

        var tau = minCoarseLag
        var best = -1
        while (tau <= maxCoarseLag) {
            if (normalised[tau] < THRESHOLD) {
                // Walk to the bottom of this dip rather than stopping at its edge.
                while (tau + 1 <= maxCoarseLag && normalised[tau + 1] < normalised[tau]) tau++
                best = tau
                break
            }
            tau++
        }
        if (best < 0) {
            // Nothing crossed the threshold. The deepest dip is the best guess left, and its
            // clarity will usually be poor enough that the caller throws it away.
            best = minCoarseLag
            for (t in minCoarseLag..maxCoarseLag) if (normalised[t] < normalised[best]) best = t
        }

        val clarity = (1.0 - normalised[best]).coerceIn(0.0, 1.0).toFloat()
        return Candidate(best, clarity)
    }

    /**
     * Locate the true minimum at the full rate, near [centreLag], to a fraction of a sample.
     *
     * The raw difference function is used here rather than the normalised one: over seventeen lags
     * either side of a known winner there is no octave left to confuse, and the raw function is the
     * smoother of the two to fit a parabola to.
     */
    private fun refine(centreLag: Int, window: Int): Double? {
        val lo = max(minFineLag, centreLag - REFINE_SPAN) - 1
        val hi = min(maxFineLag, centreLag + REFINE_SPAN) + 1
        if (lo < 1 || hi <= lo + 1) return null

        val span = window + hi
        snapshot(fine, fineMask, finePos, span, fineFrame)

        var best = 0
        for (i in 0..hi - lo) {
            var sum = 0.0
            val tau = lo + i
            for (j in 0 until window) {
                val d = (fineFrame[j] - fineFrame[j + tau]).toDouble()
                sum += d * d
            }
            refineScratch[i] = sum
            if (sum < refineScratch[best]) best = i
        }

        // The winner sat at the edge of the span, so there is no parabola to fit. Rare — the coarse
        // search put the centre here — and the integer lag is still a usable answer.
        if (best == 0 || best == hi - lo) return (lo + best).toDouble()

        val before = refineScratch[best - 1]
        val at = refineScratch[best]
        val after = refineScratch[best + 1]
        val denominator = before - 2 * at + after
        if (denominator <= 0.0) return (lo + best).toDouble()
        val shift = 0.5 * (before - after) / denominator
        return lo + best + shift.coerceIn(-1.0, 1.0)
    }

    /** One decimated sample: the anti-alias filter applied over the newest [FIR_TAPS] frames. */
    private fun filtered(): Float {
        var sum = 0f
        var index = (finePos - 1) and fineMask
        for (tap in fir) {
            sum += tap * fine[index]
            index = (index - 1) and fineMask
        }
        return sum
    }

    /** The newest [count] samples of a ring, oldest first. */
    private fun snapshot(ring: FloatArray, mask: Int, writePos: Int, count: Int, into: FloatArray) {
        var index = (writePos - count) and mask
        for (i in 0 until count) {
            into[i] = ring[index]
            index = (index + 1) and mask
        }
    }

    private fun rms(x: FloatArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) sum += x[i].toDouble() * x[i]
        return sqrt(sum / count).toFloat()
    }

    /**
     * A Hamming-windowed sinc low pass, [taps] long, cutting at [cutoff] of the sample rate.
     *
     * Decimating without this would fold everything above 5.5 kHz back down into the band the
     * search runs in. A string has little energy up there, but "little" is not "none", and it would
     * arrive as broadband noise sitting directly on top of the thing being measured.
     */
    private fun lowPass(taps: Int, cutoff: Double): FloatArray {
        val centre = (taps - 1) / 2
        val h = DoubleArray(taps)
        var sum = 0.0
        for (i in 0 until taps) {
            val n = i - centre
            val sinc = if (n == 0) 2 * cutoff else sin(2 * PI * cutoff * n) / (PI * n)
            val window = 0.54 - 0.46 * cos(2 * PI * i / (taps - 1))
            h[i] = sinc * window
            sum += h[i]
        }
        // Unit gain at DC, so decimation cannot change the level the rest of this reasons about.
        return FloatArray(taps) { (h[it] / sum).toFloat() }
    }

    private fun ceilPowerOfTwo(n: Int): Int {
        var size = 1
        while (size < n) size = size shl 1
        return size
    }
}

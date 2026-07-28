package de.singular.recorder.audio

import kotlin.math.ceil

/**
 * The bar-and-beat grid a take is played against, and the arithmetic for moving between frames,
 * beats and the sixteenth-note steps a pattern is written in.
 *
 * **Where the grid comes from.** A take this app recorded was played to its own count-in, and
 * capture begins on the downbeat after the last click — so the first sample *is* beat one, and the
 * tempo is written into the file. That is the whole grid, known rather than estimated, which is
 * what makes a drummer possible at all without beat tracking.
 *
 * **Why [offsetMs] exists.** "Begins on the downbeat" is true to within a block. Capture discards
 * input blocks until the count-in coroutine reports itself finished (see `AudioRecorder.capture`),
 * which lands the first kept sample within ~23 ms of the beat, plus whatever latency the click
 * itself left the speaker with. Near enough to trim against and not near enough to play against: a
 * drummer a hair late is more distracting than no drummer. So the offset is a control, positive to
 * move the band later.
 *
 * Frame counts are [Double] on purpose. At 44.1 kHz and 110 bpm a beat is 24054.5454… frames, and
 * rounding that per beat walks a bar and a half away over five minutes. Positions are computed from
 * the step index every time rather than accumulated.
 */
data class Beats(
    val bpm: Float,
    val beatsPerBar: Int,
    /** Nudge, milliseconds; positive moves the band later. */
    val offsetMs: Int = 0,
    val sampleRate: Int = Wav.SAMPLE_RATE,
) {
    val framesPerBeat: Double = sampleRate * 60.0 / bpm.coerceIn(MIN_BPM, MAX_BPM)
    val framesPerStep: Double = framesPerBeat / STEPS_PER_BEAT
    val stepsPerBar: Int = beatsPerBar.coerceAtLeast(1) * STEPS_PER_BEAT
    private val offsetFrames: Double = offsetMs.toDouble() * sampleRate / 1_000

    /** Where step [step] falls, counting from the first downbeat. May be negative with a nudge. */
    fun frameOfStep(step: Long): Double = offsetFrames + step * framesPerStep

    /** [step]'s place in its bar, 0 until [stepsPerBar]. */
    fun stepInBar(step: Long): Int = Math.floorMod(step, stepsPerBar)

    /** The bar [step] belongs to, counting from 0. */
    fun barOfStep(step: Long): Long = Math.floorDiv(step, stepsPerBar.toLong())

    /**
     * Every step landing in `[fromFrame, toFrame)` — what the player asks for each chunk it fills.
     *
     * Half-open so that stepping chunk by chunk visits each step exactly once, and returns an empty
     * range rather than nonsense when a seek moves the playhead backwards.
     */
    fun stepsIn(fromFrame: Long, toFrame: Long): LongRange {
        if (toFrame <= fromFrame) return LongRange.EMPTY
        // Steps are the s where offset + s·framesPerStep lands in the window: ceil at the bottom,
        // one below ceil at the top. Clamped at zero — a negative nudge has no bar before bar one.
        val first = ceil((fromFrame - offsetFrames) / framesPerStep).toLong().coerceAtLeast(0)
        val last = ceil((toFrame - offsetFrames) / framesPerStep).toLong() - 1
        return if (last < first) LongRange.EMPTY else first..last
    }

    companion object {
        /** Sixteenths: fine enough for the patterns worth having, coarse enough to reason about. */
        const val STEPS_PER_BEAT = 4
    }
}

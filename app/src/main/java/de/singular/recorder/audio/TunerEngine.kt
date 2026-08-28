// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the tuner has to show, or as much of it as is currently known. */
data class TunerState(
    /** The note nearest what is being played, and how far from it. Null when nothing is heard. */
    val reading: Tuning.Reading? = null,
    /** Where the strobe's two rings stand, in radians, and how fast they are turning. */
    val rings: Strobe.Rings? = null,
    /** True while the microphone is held and being read. */
    val listening: Boolean = false,
    /**
     * The microphone could not be had — in practice because a take is being recorded.
     *
     * Some devices hand out exactly one input stream, so this is not a rare corner: it is what
     * happens if the Tuner tab is opened mid-take. Recording wins, and the screen says so rather
     * than showing a needle that will never move.
     */
    val micUnavailable: Boolean = false,
)

/**
 * Drives [Pitch] and [Strobe] from the microphone and publishes what they say.
 *
 * **The two measurements are not redundant.** [Pitch] answers "what note is this", which the strobe
 * cannot: a strobe only ever compares against a reference it has already been told to use. So the
 * pitch reading chooses the note, and the strobe is pointed at it and then measures the error far
 * more finely than the pitch reading ever could. Each does the half the other is bad at.
 *
 * **Microphone manners.** This holds the microphone only while [start] has been called and gives it
 * straight back on [stop], because holding it lights the system's microphone indicator and, on some
 * hardware, denies it to the recorder outright. The tuner never competes with a take: the caller is
 * expected not to start this during one, and if the stream is refused anyway that is reported
 * rather than retried.
 */
class TunerEngine(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext

    private val pitch = Pitch()
    private val strobe = Strobe()

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state.asStateFlow()

    private var job: Job? = null

    private companion object {
        /**
         * Frames per read. The same 23 ms the recorder uses, for the same reason — small enough
         * that the rings move continuously, large enough that the loop is not thrashing.
         */
        const val BLOCK_FRAMES = 1_024

        /** Headroom, so a scheduling hiccup drops no audio out of the middle of a note. */
        const val BUFFER_BLOCKS = 8

        /**
         * Readings this many blocks apart, so the pitch search runs about 20 times a second rather
         * than 43. Every block still reaches the strobe, whose rings are the thing being watched
         * closely; the note name does not need re-deciding every 23 ms, and the search is the
         * expensive half.
         */
        const val BLOCKS_PER_READING = 2

        /**
         * Readings kept for the median. Five at 21 a second is a quarter of a second of evidence.
         *
         * A median rather than an average, because the thing being defended against is not noise
         * on a good reading — it is the occasional *wrong* reading, an octave out or a room noise
         * caught mid-decay. An average lets one of those drag the number; a median ignores it
         * entirely unless it becomes the majority view.
         */
        const val SMOOTHING_READINGS = 5

        /**
         * Readings with nothing in them before the display goes quiet. Half a second, so that the
         * tail of a decaying note does not blink the note name out and back.
         */
        const val MISSES_BEFORE_SILENCE = 10
    }

    private val recentHz = ArrayDeque<Double>()
    private var misses = 0

    /** Take the microphone and start reading it. Does nothing if already listening. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) { listen() }
    }

    /** Give the microphone back and clear the display. */
    fun stop() {
        job?.cancel()
        job = null
        pitch.reset()
        strobe.reset()
        _state.value = TunerState()
    }

    /**
     * The median of the last few readings, so that the number on screen can be read.
     *
     * Raw readings arrive twenty times a second and each is its own best guess; shown directly they
     * are a blur, and a single bad one is indistinguishable from the string having moved.
     */
    private fun smoothed(result: Pitch.Result?): Tuning.Reading? {
        if (result == null) {
            if (++misses >= MISSES_BEFORE_SILENCE) recentHz.clear()
        } else {
            misses = 0
            recentHz.addLast(result.hz)
            while (recentHz.size > SMOOTHING_READINGS) recentHz.removeFirst()
        }
        if (recentHz.isEmpty()) return null
        return Tuning.read(recentHz.sorted()[recentHz.size / 2])
    }

    @SuppressLint("MissingPermission") // the caller carries @RequiresPermission
    private suspend fun listen() {
        val input = MicInput.open(appContext, BLOCK_FRAMES, BUFFER_BLOCKS) ?: run {
            _state.value = TunerState(micUnavailable = true)
            return
        }

        pitch.reset()
        strobe.reset()
        recentHz.clear()
        misses = 0
        _state.value = TunerState(listening = true)

        try {
            input.startRecording()
            val block = ShortArray(BLOCK_FRAMES)
            var blocksSinceReading = 0

            while (currentCoroutineContext().isActive) {
                // No gain: the tuner measures frequency, and frequency does not care how loud the
                // string is. Applying the recorder's gain here would only move quiet notes closer
                // to clipping, and a clipped note is a note with new harmonics on it.
                val read = input.read(block, gain = 1f)
                if (read <= 0) break

                pitch.push(block, read)

                // Every block, so the rings move as smoothly as the audio arrives.
                val rings = strobe.push(block, read)

                if (++blocksSinceReading >= BLOCKS_PER_READING) {
                    blocksSinceReading = 0
                    val reading = smoothed(pitch.detect())
                    // Pointing the strobe at a note it is already on is a no-op, so this only
                    // resets its phase when the note being played actually changes.
                    reading?.let { strobe.retune(it.note.hz) }
                    _state.value = TunerState(reading = reading, rings = rings, listening = true)
                } else if (rings != null) {
                    _state.value = _state.value.copy(rings = rings)
                }
            }
        } catch (e: IllegalStateException) {
            // The device refused the stream, or something took it. Reported rather than retried:
            // the tuner is a screen you are looking at, and a silent dead needle is worse than a
            // line saying the microphone is busy.
            _state.value = TunerState(micUnavailable = true)
        } finally {
            input.close()
        }
    }
}

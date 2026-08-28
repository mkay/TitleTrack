// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil

/** Where a take is in its life. */
enum class RecordPhase {
    /** Nothing captured; the record button is waiting. */
    IDLE,

    /** Clicks are sounding; the microphone is open but nothing is being kept yet. */
    COUNT_IN,

    /** Capturing. */
    RECORDING,

    /** Held, with the take intact and the microphone still open — Save or Continue from here. */
    PAUSED,
}

/** Everything the recording screen draws, updated once per captured block (~23 ms). */
data class RecorderState(
    val phase: RecordPhase = RecordPhase.IDLE,
    /** Length of what is on disk, in milliseconds — count-in excluded. */
    val elapsedMs: Long = 0,
    /** Peak of the last block, 0f..1f linear. */
    val level: Float = 0f,
    /** Clicks still to sound before capture begins; 0 outside [RecordPhase.COUNT_IN]. */
    val countInBeatsLeft: Int = 0,
    /**
     * Milliseconds until capture begins, counted on the beat grid the clicks are on — so the
     * screen can run the visual metronome through the count-in and hand it over on the downbeat.
     * 0 outside [RecordPhase.COUNT_IN].
     */
    val countInRemainingMs: Long = 0,
    /** Set when the microphone could not be opened at all. */
    val error: String? = null,
    /**
     * Whether the downbeat the count-in is walking towards is known yet.
     *
     * False for the first fraction of a count-in while the output latency is still being measured,
     * because until it is there is no honest answer: the downbeat moves by however far behind the
     * output turns out to be. On a speaker that is ~100 ms and nobody sees it. Over Bluetooth the
     * answer took 834 ms and moved the downbeat by 875 — nearly two beats at 120 bpm — so the dots
     * would have walked off confidently and then lurched backwards. They wait instead.
     *
     * It is also the truthful thing to draw: over Bluetooth the first click is not *heard* until
     * one output latency in, which is the same moment this becomes true.
     */
    val countInSettled: Boolean = true,
    /**
     * Set when the clicks went out over a wireless route and the latency could not be measured, so
     * no compensation was applied and this take is off its own grid by however far behind the
     * earbuds are. Rare — but silent otherwise, and a take that does not line up with nothing said
     * about it is the worst version of this bug.
     */
    val clickTimingUnknown: Boolean = false,
) {
    val hasTake: Boolean get() = elapsedMs > 0
}

/**
 * The capture engine: microphone → raw PCM in the cache → a WAV the caller streams out on save.
 *
 * **Why raw PCM and not a WAV straight away.** A take is scratch until it is kept: restart wipes
 * it, discard drops it, and only Save decides where in the user's folder it lands and what it is
 * called. Writing to a cache file means restart is a truncate rather than a delete-and-recreate in
 * someone's music folder, an abandoned take never litters it, and the header — whose sizes are only
 * knowable at the end — is written once, in a single forward pass over the payload ([writeWavTo]).
 *
 * **Why the microphone stays open while paused.** Pause discards incoming blocks rather than
 * stopping the recorder, so Continue resumes on the next block instead of paying [AudioRecord]'s
 * cold start again. Playing a wrong chord and hitting Restart is meant to be instant.
 */
class AudioRecorder(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val metronome = Metronome()

    /** The scratch take. Cache, because an unsaved take is not worth surviving a low-disk sweep. */
    private val takeFile = File(appContext.cacheDir, "take.pcm")

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var job: Job? = null
    private var monitorJob: Job? = null

    // Commands from the UI thread into the capture loop. Volatile rather than locked: each is a
    // single write the loop reads once per block, and a command landing one block late is
    // imperceptible (23 ms).
    @Volatile private var paused = false
    @Volatile private var restartRequested = false

    /** Frames on disk. Written by the capture loop only; read for the save. */
    @Volatile private var framesWritten = 0L

    /**
     * Digital gain for everything read from the microphone, in decibels. Applied to monitoring as
     * well as to takes, so the meter and the level test show what would actually land on disk.
     */
    @Volatile private var gainDb = 0

    fun setInputGain(db: Int) {
        gainDb = db
    }

    private companion object {
        /**
         * Frames per read. 1024 at 44.1 kHz is ~23 ms — fast enough that the level meter and the
         * visual metronome look continuous, large enough that the loop is not thrashing.
         */
        const val BLOCK_FRAMES = 1_024

        /** Buffer several blocks, so a scheduling hiccup cannot drop audio mid-phrase. */
        const val BUFFER_BLOCKS = 8
    }

    /**
     * Listen without recording: input level only, nothing written anywhere.
     *
     * This is how you find out where to stand before committing to a take — a guitar three feet
     * from the phone and one on the other side of the room look very different on the meter, and
     * discovering that after playing the part is discovering it too late.
     *
     * Idle only, and stopped the moment a take starts: some devices hand out exactly one input
     * stream, so holding this open would be holding the microphone away from the recording.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startMonitoring() {
        if (_state.value.phase != RecordPhase.IDLE) return
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.Default) { monitor() }
    }

    /** Give the microphone back. Called whenever the record screen stops being looked at. */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        if (_state.value.phase == RecordPhase.IDLE) {
            _state.value = _state.value.copy(level = 0f)
        }
    }

    @SuppressLint("MissingPermission") // the caller carries @RequiresPermission
    private suspend fun monitor() {
        val input = openRecorder() ?: return
        try {
            input.startRecording()
            val block = ShortArray(BLOCK_FRAMES)
            while (currentCoroutineContext().isActive) {
                val read = input.read(block, gain())
                if (read <= 0) break
                // Level only. The samples are looked at and dropped: nothing here reaches a file,
                // which is the whole difference between this and a take.
                if (_state.value.phase != RecordPhase.IDLE) break
                _state.value = _state.value.copy(level = peakOf(block, read))
            }
        } catch (e: IllegalStateException) {
            // The device refused the stream, or it was taken by something else. Not worth
            // reporting: the meter simply stays where it was, and Record still tries for itself.
        } finally {
            input.close()
        }
    }

    /**
     * Begin a take, replacing any in progress.
     *
     * With [countInBars] > 0 the clicks sound first and capture starts on the downbeat after them;
     * the microphone is opened before the first click, so the take begins with a warm input path
     * rather than [AudioRecord]'s cold-start silence.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(bpm: Float, beatsPerBar: Int, countInBars: Int, audioMetronome: Boolean = false) {
        scope.launch {
            // Free the input before the take asks for it.
            monitorJob?.cancelAndJoin()
            monitorJob = null
            job?.cancelAndJoin()
            paused = false
            restartRequested = false
            framesWritten = 0L
            _state.value = RecorderState(phase = RecordPhase.COUNT_IN)
            job = scope.launch(Dispatchers.Default) {
                capture(bpm, beatsPerBar, countInBars, audioMetronome)
            }
        }
    }

    /** Hold the take. The microphone stays open; [resume] picks up on the next block. */
    fun pause() {
        if (_state.value.phase != RecordPhase.RECORDING) return
        paused = true
        _state.value = _state.value.copy(phase = RecordPhase.PAUSED)
    }

    /** Carry on after [pause]. */
    fun resume() {
        if (_state.value.phase != RecordPhase.PAUSED) return
        paused = false
        _state.value = _state.value.copy(phase = RecordPhase.RECORDING)
    }

    /**
     * Throw the take away and start it again from the top, count-in included — the wrong-chord
     * button. Handled inside the capture loop so the microphone is never closed and reopened.
     */
    fun restart() {
        if (_state.value.phase == RecordPhase.IDLE) return
        paused = false
        restartRequested = true
    }

    /** Stop capturing and drop the take. */
    fun discard() {
        scope.launch {
            job?.cancelAndJoin()
            job = null
            framesWritten = 0L
            runCatching { takeFile.delete() }
            _state.value = RecorderState()
        }
    }

    /** Milliseconds of audio held, for naming a file before it is written. */
    val takeMs: Long get() = framesToMs(framesWritten)

    /**
     * Stream the held take out as a WAV. Runs off the main thread; the caller supplies the sink
     * (a `content://` document's output stream), so nothing here needs to know about storage.
     *
     * Safe to call while paused — the loop is not appending, and the length is read once up front.
     */
    fun writeWavTo(out: OutputStream, bpm: Float?, title: String?, gainDb: Int = 0) {
        val bytes = framesWritten * Wav.BYTES_PER_FRAME
        out.write(Wav.header(dataBytes = bytes, bpm = bpm, title = title, gainDb = gainDb))
        RandomAccessFile(takeFile, "r").use { source ->
            val buf = ByteArray(64 * 1_024)
            var left = bytes
            while (left > 0) {
                val want = minOf(left, buf.size.toLong()).toInt()
                val read = source.read(buf, 0, want)
                if (read <= 0) break
                out.write(buf, 0, read)
                left -= read
            }
        }
        out.flush()
    }

    /** The capture loop. Ends on cancellation, or on a microphone that will not open. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun capture(
        bpm: Float,
        beatsPerBar: Int,
        countInBars: Int,
        audioMetronome: Boolean,
    ) {
        val input = openRecorder() ?: run {
            _state.value = RecorderState(error = "The microphone could not be opened.")
            return
        }
        val file = RandomAccessFile(takeFile, "rw")
        file.setLength(0)

        var countIn: Job? = null
        try {
            input.startRecording()
            val block = ShortArray(BLOCK_FRAMES)
            val bytes = ByteArray(BLOCK_FRAMES * Wav.BYTES_PER_FRAME)

            var countingIn = countInBars > 0
            var wasPaused = false
            var countInEndsAt = 0L
            val beatMs = (60_000f / bpm.coerceIn(MIN_BPM, MAX_BPM)).toLong().coerceAtLeast(1)
            // The clicks themselves, without the metronome's silent lead-in: the lead is latency
            // cover, not a beat, and the dots on screen must not count it as one.
            val countInMusicalMs = (beatsPerBar * countInBars).coerceAtLeast(1) * beatMs

            // With the audible metronome on, one stream plays the count-in *and* the take: handed
            // the count-in's own lead-in, its beat 0 is the first click of the count-in and the
            // take's downbeat falls on the click after the last of them. Two tracks left a gap at
            // exactly that join — see [Metronome.startTakeClicks].
            fun beginTakeClicks() {
                if (audioMetronome) {
                    metronome.startTakeClicks(
                        bpm = bpm,
                        beatsPerBar = beatsPerBar,
                        delayMs = if (countInBars > 0) Metronome.LEAD_MS else 0,
                    )
                }
            }

            // Coming back from a pause, the clicks rejoin the take's grid rather than staying gone.
            // The grid is frames written, not wall clock — dropped blocks are not in the take — so
            // the next beat is worked out from the length so far, and the accent lands where the bar
            // says rather than on whichever beat happens to be next.
            fun resumeTakeClicks() {
                if (!audioMetronome) return
                val framesPerBeat = Wav.SAMPLE_RATE * 60.0 / bpm.coerceIn(MIN_BPM, MAX_BPM)
                val next = ceil(framesWritten / framesPerBeat).toLong()
                val untilFrames = (next * framesPerBeat - framesWritten).toLong()
                metronome.startTakeClicks(
                    bpm = bpm,
                    beatsPerBar = beatsPerBar,
                    delayMs = framesToMs(untilFrames),
                    fromBeat = next,
                )
            }

            /**
             * Wait out the count-in, then let the take begin.
             *
             * **The wait is the clicks' own length plus the output latency**, and that correction is
             * the difference between a take the band can play under and one it cannot. What a player
             * comes in on is the click they *heard*; the device presents it a whole output latency
             * after we queued it — 222 ms measured on this phone's speaker, 41% of a beat at 110 bpm.
             * Starting capture on the nominal end put the take's first sample that far ahead of the
             * beat the player was following, so their downbeat landed a third of a beat into bar one
             * and every grid built on "sample zero is beat one" was wrong by the same amount.
             *
             * Holding the take back instead keeps that promise literally true, which is what the
             * whole accompaniment rests on. The countdown on screen is moved by the same amount as
             * soon as the figure is known, so the dots still land with the clicks.
             */
            fun beginCountIn() {
                countingIn = true
                val nominal = metronome.countInMs(bpm, beatsPerBar, countInBars)
                // Zero means "not known yet", not "now" — the screen holds the dots still until
                // this says where the downbeat actually is. See [RecorderState.countInSettled].
                countInEndsAt = 0L
                val startedAt = SystemClock.elapsedRealtime()
                countIn = scope.launch(Dispatchers.Default) {
                    if (audioMetronome) {
                        // The clicks come from the through-take stream when that is running, so this
                        // is only the wait: sounding the count-in here too would double every click.
                        val late = metronome.awaitTakeClickLatency()
                        countInEndsAt = startedAt + nominal + late
                        val waited = SystemClock.elapsedRealtime() - startedAt
                        delay((nominal + late - waited).coerceAtLeast(1))
                    } else {
                        // The static count-in measures its own latency and waits it out; this only
                        // has to move the countdown to match.
                        val late = metronome.countIn(bpm, beatsPerBar, countInBars) {
                            countInEndsAt = startedAt + nominal + it
                        }
                        // A latency of zero never fires the callback — settle it here so a count-in
                        // that measured nothing still ends, rather than waiting on a downbeat that
                        // is never announced.
                        if (countInEndsAt == 0L) countInEndsAt = startedAt + nominal + late
                    }
                    countingIn = false
                }
            }

            /**
             * No count-in, but a click to play along with: hold the take for one output latency all
             * the same, so bar one starts where the first click was heard rather than where it was
             * queued. Without this the grid is off by the same 222 ms as above, for the same reason.
             *
             * Brief — a fifth of a second — and it borrows the count-in phase to do the waiting, so
             * "Counting in…" flashes up. That is the honest description of what is happening.
             */
            fun beginLatencyHold() {
                countingIn = true
                countInEndsAt = 0L
                val startedAt = SystemClock.elapsedRealtime()
                countIn = scope.launch(Dispatchers.Default) {
                    val late = metronome.awaitTakeClickLatency()
                    countInEndsAt = startedAt + Metronome.LEAD_MS + late
                    val waited = SystemClock.elapsedRealtime() - startedAt
                    delay((Metronome.LEAD_MS + late - waited).coerceAtLeast(1))
                    countingIn = false
                }
            }

            // Clicks first: the wait below measures the track they are streaming from.
            beginTakeClicks()
            when {
                countingIn -> beginCountIn()
                audioMetronome -> beginLatencyHold()
                else -> _state.value = _state.value.copy(phase = RecordPhase.RECORDING)
            }

            while (true) {
                coroutineContext.ensureActive()
                // Gain is read per block, so changing it takes effect within 23 ms — and the take
                // on disk, the meter and the waveform are all the same, already-boosted samples.
                val read = input.read(block, gain())
                if (read <= 0) continue

                if (restartRequested) {
                    restartRequested = false
                    countIn?.cancel()
                    metronome.stopAll()
                    file.setLength(0)
                    file.seek(0)
                    framesWritten = 0L
                    countingIn = countInBars > 0
                    if (countingIn) {
                        _state.value = _state.value.copy(
                            phase = RecordPhase.COUNT_IN, elapsedMs = 0, level = 0f,
                        )
                        beginTakeClicks()
                        beginCountIn()
                    } else {
                        _state.value = _state.value.copy(
                            phase = RecordPhase.RECORDING, elapsedMs = 0, level = 0f,
                            countInBeatsLeft = 0,
                            countInRemainingMs = 0,
                        )
                        beginTakeClicks()
                        if (audioMetronome) beginLatencyHold()
                    }
                    continue
                }

                val peak = peakOf(block, read)

                if (countingIn) {
                    // Counted from the *musical* part of the wait, never the whole of it. What is
                    // left over is the lead-in and the output latency the take is being held back
                    // by, and neither is a beat: counting them showed a bar of four as "5" for the
                    // first fraction of a second, and — since the latency is added to the total as
                    // soon as it is measured, part-way through — flickered back to 5 again after
                    // reaching 4. A one-bar count-in counts 4, 3, 2, 1, whatever the wait is doing.
                    // Zero is "the downbeat is not known yet", not "it is now": show the count-in
                    // whole and still until the latency measurement lands. Falling through to the
                    // arithmetic below would read it as a downbeat long past and count zero beats
                    // left — the count-in emptied before a single click had been heard.
                    val settled = countInEndsAt != 0L
                    val musical = if (settled) {
                        (countInEndsAt - SystemClock.elapsedRealtime())
                            .coerceIn(0, countInMusicalMs)
                    } else {
                        countInMusicalMs
                    }
                    val left = (musical + beatMs - 1) / beatMs
                    _state.value = _state.value.copy(
                        phase = RecordPhase.COUNT_IN,
                        level = peak,
                        countInBeatsLeft = left.toInt().coerceAtLeast(0),
                        countInRemainingMs = musical,
                        countInSettled = settled,
                    )
                    continue // the clicks are not part of the take
                }

                if (paused) {
                    // Read and drop: the microphone stays warm so Continue is immediate. The clicks
                    // go with it — a pause is silence that never reaches the take, so a metronome
                    // running through one would come back describing a grid the take does not have.
                    metronome.stopTakeClicks()
                    wasPaused = true
                    _state.value = _state.value.copy(phase = RecordPhase.PAUSED, level = peak)
                    continue
                }
                if (wasPaused) {
                    wasPaused = false
                    resumeTakeClicks()
                }

                for (i in 0 until read) {
                    val s = block[i].toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                file.write(bytes, 0, read * Wav.BYTES_PER_FRAME)
                framesWritten += read

                _state.value = _state.value.copy(
                    phase = RecordPhase.RECORDING,
                    elapsedMs = framesToMs(framesWritten),
                    level = peak,
                    countInBeatsLeft = 0,
                    countInRemainingMs = 0,
                    countInSettled = true,
                    // A measurement that never arrived on a wireless route is the one case where
                    // this take's grid cannot be trusted — see [Metronome.lastOutputWasWireless].
                    clickTimingUnknown = !metronome.latencyMeasured &&
                        metronome.lastOutputWasWireless == true,
                )
            }
        } finally {
            countIn?.cancel()
            metronome.stopAll()
            runCatching { file.close() }
            input.close()
        }
    }

    /**
     * Open the microphone for *recording an instrument*, which wants a different source than
     * CrystalBall's listener does. The choosing of that source, and the float-first read path that
     * makes the gain free, live in [MicInput] — the tuner needs exactly the same treatment, and one
     * of the two copies would have drifted.
     */
    @SuppressLint("MissingPermission") // guarded by @RequiresPermission up the call chain
    private fun openRecorder(): MicInput? =
        MicInput.open(appContext, BLOCK_FRAMES, BUFFER_BLOCKS)

    private fun gain(): Float = Gain.dbToLinear(gainDb.toFloat())

    private fun peakOf(block: ShortArray, count: Int): Float {
        var peak = 0
        for (i in 0 until count) {
            val v = abs(block[i].toInt())
            if (v > peak) peak = v
        }
        return peak / 32_768f
    }

    private fun framesToMs(frames: Long): Long = frames * 1_000 / Wav.SAMPLE_RATE
}

package de.singular.recorder.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
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

        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
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
        val record = input.record
        try {
            record.startRecording()
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
            runCatching { record.stop() }
            runCatching { record.release() }
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
        val record = input.record
        val file = RandomAccessFile(takeFile, "rw")
        file.setLength(0)

        var countIn: Job? = null
        try {
            record.startRecording()
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

            fun beginCountIn() {
                countingIn = true
                countInEndsAt = SystemClock.elapsedRealtime() +
                    metronome.countInMs(bpm, beatsPerBar, countInBars)
                countIn = scope.launch(Dispatchers.Default) {
                    // The clicks come from the through-take stream when that is running, so this is
                    // only the wait: sounding the count-in here as well would double every click.
                    if (audioMetronome) delay(metronome.countInMs(bpm, beatsPerBar, countInBars))
                    else metronome.countIn(bpm, beatsPerBar, countInBars)
                    countingIn = false
                }
            }

            if (countingIn) beginCountIn() else _state.value =
                _state.value.copy(phase = RecordPhase.RECORDING)
            beginTakeClicks()

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
                        beginCountIn()
                    } else {
                        _state.value = _state.value.copy(
                            phase = RecordPhase.RECORDING, elapsedMs = 0, level = 0f,
                            countInBeatsLeft = 0,
                            countInRemainingMs = 0,
                        )
                    }
                    beginTakeClicks()
                    continue
                }

                val peak = peakOf(block, read)

                if (countingIn) {
                    val untilDownbeat = countInEndsAt - SystemClock.elapsedRealtime()
                    val left = (untilDownbeat + beatMs - 1) / beatMs
                    _state.value = _state.value.copy(
                        phase = RecordPhase.COUNT_IN,
                        level = peak,
                        countInBeatsLeft = left.toInt().coerceAtLeast(0),
                        countInRemainingMs = untilDownbeat.coerceIn(0, countInMusicalMs),
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
                )
            }
        } finally {
            countIn?.cancel()
            metronome.stopAll()
            runCatching { file.close() }
            // stop() throws if the recorder never initialised; release() is what must always run.
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * Open the microphone for *recording an instrument*, which wants a different source than
     * CrystalBall's listener does.
     *
     * `UNPROCESSED` first: automatic gain control riding a decaying chord, and noise suppression
     * deciding a sustained note is background hum, are audible on a recording in a way they are not
     * on a chromagram that normalises every frame anyway. Where the device does not advertise it,
     * `VOICE_RECOGNITION` is the usual second best — it skips the AGC and the voice-call
     * processing on most hardware — and `MIC` is the floor.
     */
    @SuppressLint("MissingPermission") // guarded by @RequiresPermission up the call chain
    private fun openRecorder(): Input? {
        // Float first, 16-bit as the floor. What the file holds is 16-bit either way; the point of
        // asking for float is where the gain lands — see [Input.read].
        for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, ENCODING)) {
            val minBuffer = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, CHANNEL, encoding)
            if (minBuffer <= 0) continue
            val bufferBytes = maxOf(minBuffer, BLOCK_FRAMES * Wav.BYTES_PER_FRAME * BUFFER_BLOCKS * 2)

            for (source in preferredSources()) {
                val record = runCatching {
                    AudioRecord(source, Wav.SAMPLE_RATE, CHANNEL, encoding, bufferBytes)
                }.getOrNull() ?: continue
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return Input(record, float = encoding == AudioFormat.ENCODING_PCM_FLOAT)
                }
                record.release()
            }
        }
        return null
    }

    private fun gain(): Float = Gain.dbToLinear(gainDb.toFloat())

    /**
     * The microphone, read a block at a time as 16-bit PCM with [gain] already applied.
     *
     * Android has no microphone preamp gain to turn up — nothing in `AudioRecord` or
     * `AudioManager` exposes one — so a quiet instrument can only be lifted by multiplying samples.
     * Doing that here, while the device is still handing back **float**, is what makes it free: the
     * boost happens before the reduction to the 16 bits that reach the file, so it costs no
     * resolution. Multiplying an already-quantised 16-bit sample would spend bits that cannot come
     * back. Where a device refuses float the 16-bit path does the same arithmetic and simply pays
     * that price, which is still better than the take being unusable.
     */
    private class Input(val record: AudioRecord, private val float: Boolean) {

        private val floats = if (float) FloatArray(BLOCK_FRAMES) else FloatArray(0)

        fun read(into: ShortArray, gain: Float): Int {
            if (!float) {
                val read = record.read(into, 0, into.size)
                if (read > 0 && gain != 1f) {
                    for (i in 0 until read) into[i] = Gain.applySample(into[i], gain, softClip = false)
                }
                return read
            }
            val read = record.read(floats, 0, minOf(floats.size, into.size), AudioRecord.READ_BLOCKING)
            for (i in 0 until read) {
                // Clamped rather than soft-clipped: a gain that clips is a setting to correct, and
                // bending the peaks would hide it. The meter is already red up there.
                into[i] = ((floats[i] * gain).coerceIn(-1f, 1f) * 32_767f).toInt().toShort()
            }
            return read
        }
    }

    private fun preferredSources(): List<Int> {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessed = audio
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.toBoolean() == true
        return buildList {
            if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
    }

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

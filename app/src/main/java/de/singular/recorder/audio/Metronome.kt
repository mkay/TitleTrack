// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * The clicks: a count-in, and optionally a beat carrying on through the take.
 *
 * [countIn] plays bars × beatsPerBar clicks at a given tempo and then finishes, so the caller can
 * start recording on the downbeat that follows. [startTakeClicks] keeps clicking after it, for the
 * audible metronome — which is off by default and belongs to headphones, since a click on a phone
 * speaker lands in the take. An accented (higher) click marks beat 1 of each bar either way.
 *
 * The whole count-in is rendered to one mono PCM buffer and played by a single [AudioTrack] in
 * static mode — inter-click timing is therefore sample-accurate, not subject to scheduler jitter.
 * The through-take clicks cannot be laid out in advance and are streamed instead; see there.
 *
 * Adapted from RubberRing's `audio/Metronome.kt`. The one change that matters here: RubberRing
 * returns [LEAD_MS] *early*, to absorb its player's startup latency and land the audio on the
 * downbeat. A recorder must not do that — the microphone is already open and running through the
 * count-in, so returning early would only fold the tail of the last click into the take. This one
 * returns on the beat.
 */
class Metronome(private val sampleRate: Int = Wav.SAMPLE_RATE) {

    // Two "tock" timbres, rendered once: a higher accent and a lower plain click, both dry and
    // woody like a mechanical metronome.
    private val accentClick = renderClick(freq = 1_100f, durationMs = 30)
    private val plainClick = renderClick(freq = 800f, durationMs = 30)

    @Volatile private var track: AudioTrack? = null

    @Volatile private var clicking = false
    private var clickTrack: AudioTrack? = null
    private var clickFeeder: Thread? = null
    private var clickStartNanos = 0L

    companion object {
        /**
         * Silent lead-in before the first click, covering AudioTrack cold-start latency. Public
         * because a caller running the clicks through a take has to line its own grid up with it.
         */
        const val LEAD_MS = 80L

        /** How long to wait for the device to report a timestamp before giving up on it. */
        private const val LATENCY_BUDGET_MS = 300L
        private const val LATENCY_POLL_MS = 10L

        /** No device's output is a quarter of a second behind; past this, the reading is wrong. */
        private const val MAX_LATENCY_MS = 400L
    }

    /**
     * Sound the count-in and suspend until the downbeat that follows the last click — *as heard*,
     * not as queued. Returns normally only if it ran to completion; cancelling stops the clicks.
     *
     * [onLatency] is called with the output latency once it is known, a few tens of milliseconds in,
     * because the caller's own countdown was started on the nominal length and has to be moved by
     * the same amount. Returns that latency as well, for callers that only need it at the end.
     */
    suspend fun countIn(
        bpm: Float,
        beatsPerBar: Int,
        bars: Int,
        onLatency: (Long) -> Unit = {},
    ): Long {
        val beats = (beatsPerBar * bars).coerceAtLeast(1)
        val safeBpm = bpm.coerceIn(MIN_BPM, MAX_BPM)
        val intervalFrames = (sampleRate * 60.0 / safeBpm).toInt().coerceAtLeast(1)
        // A short silence before the first click so the audio path is warmed up by the time it
        // sounds — otherwise cold-start latency intermittently swallows the first click's attack.
        val leadFrames = (sampleRate * LEAD_MS / 1_000).toInt()
        val buffer = ShortArray(leadFrames + intervalFrames * beats)
        for (b in 0 until beats) {
            val click = if (b % beatsPerBar == 0) accentClick else plainClick
            val at = leadFrames + b * intervalFrames
            System.arraycopy(click, 0, buffer, at, minOf(click.size, buffer.size - at))
        }

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(buffer.size * 2)
            .build()
        track = t
        try {
            t.write(buffer, 0, buffer.size)
            val startNanos = System.nanoTime()
            t.play()
            // The buffer's length is lead + one full beat per click, so it ends exactly on the
            // downbeat after the last one — in the track's own time. On the wall clock it ends a
            // whole output latency later, and it is the wall clock the recorder starts on.
            val nominalMs = (buffer.size.toLong() * 1_000 / sampleRate).coerceAtLeast(1)
            val late = awaitLatency(t, startNanos, budgetMs = minOf(LATENCY_BUDGET_MS, nominalMs / 3))
            if (late > 0) onLatency(late)
            val waited = (System.nanoTime() - startNanos) / 1_000_000
            delay((nominalMs + late - waited).coerceAtLeast(1))
            return late
        } finally {
            stop()
        }
    }

    /**
     * How far behind the wall clock the through-take clicks actually are, once the audio device has
     * told us — zero if it never does, which leaves the old behaviour rather than a guess.
     *
     * This is the whole of the fix for a take recorded to the click landing off its own grid. What a
     * player follows is what they *hear*, and that is one output latency behind the frames we queued:
     * measured at 222 ms on the Fairphone's speaker, 41% of a beat at 110 bpm, which is why the drums
     * sounded unrelated to the take. The recorder holds the take's first sample back by this much so
     * that the beat the player came in on is the beat the file starts with — see [AudioRecorder].
     */
    suspend fun awaitTakeClickLatency(budgetMs: Long = LATENCY_BUDGET_MS): Long {
        val t = clickTrack ?: return 0
        return awaitLatency(t, clickStartNanos, budgetMs)
    }

    /**
     * Poll [AudioTrack.getTimestamp] until it will say when a frame was presented, then compare that
     * with when the frame was due. Gives up at [budgetMs] and answers zero.
     *
     * The timestamp is the only sanctioned way to ask: `AudioManager`'s output latency is a hidden
     * API, and counting the frames still queued misses the device's own pipeline, which is most of it.
     */
    private suspend fun awaitLatency(t: AudioTrack, startNanos: Long, budgetMs: Long): Long {
        val deadline = System.nanoTime() + budgetMs.coerceAtLeast(0) * 1_000_000
        while (true) {
            latencyMsOf(t, startNanos)?.let { return it }
            if (System.nanoTime() >= deadline) return 0
            delay(LATENCY_POLL_MS)
        }
    }

    /** One reading, or null while the device has nothing to report yet. */
    private fun latencyMsOf(t: AudioTrack, startNanos: Long): Long? {
        val ts = AudioTimestamp()
        if (!runCatching { t.getTimestamp(ts) }.getOrDefault(false)) return null
        if (ts.framePosition <= 0) return null
        val dueNanos = startNanos + ts.framePosition * 1_000_000_000L / sampleRate
        // Clamped rather than trusted: a bogus reading must not hold a take back by a bar.
        return ((ts.nanoTime - dueNanos) / 1_000_000).coerceIn(0, MAX_LATENCY_MS)
    }

    /** How long [countIn] will take, for the countdown the screen shows while it runs. */
    fun countInMs(bpm: Float, beatsPerBar: Int, bars: Int): Long {
        val beats = (beatsPerBar * bars).coerceAtLeast(1)
        val safeBpm = bpm.coerceIn(MIN_BPM, MAX_BPM)
        return LEAD_MS + (beats * 60_000.0 / safeBpm).toLong()
    }

    /**
     * Click on every beat until stopped, with beat one landing [delayMs] from now — for the audible
     * metronome that carries on through a take. Idempotent; a second call is ignored.
     *
     * **Streamed rather than rendered.** [countIn] can lay its whole job out in one buffer because it
     * knows how many clicks it will play; a take does not end until someone says so, so this is a
     * feeder thread writing ~20 ms at a time. The blocking write is what paces it — no scheduler
     * timing is trusted with a beat.
     *
     * **Beat positions are computed, never accumulated.** At 44.1 kHz and 110 bpm a beat is
     * 24054.5454… frames, and adding a rounded interval per beat walks more than a bar away over five
     * minutes. Each beat's frame comes from its index, so the grid cannot drift from itself.
     *
     * **When this runs, it plays the count-in too**, and [countIn] is not used at all — the caller
     * passes [LEAD_MS] as the delay and lets beat 0 be the first click of the count-in. That is not
     * tidiness: the two were separate tracks at first, and the seam was audible. Each `AudioTrack`
     * starts on its own latency, so a static count-in ending and a streamed take beginning put a gap
     * of tens of milliseconds between the last click of the one and the first of the other — right at
     * the downbeat, which is the single moment a metronome is judged on. One stream cannot do that:
     * whatever the start costs, it costs every click equally and the grid stays even.
     *
     * What the player *hears* is still behind the take's own grid by the device's output latency, and
     * that remains — the take begins on the wall clock while the clicks begin when the audio device
     * gets round to them. Worth knowing before treating a take recorded to these clicks as
     * sample-accurate against the grid; not something this can fix.
     */
    fun startTakeClicks(bpm: Float, beatsPerBar: Int, delayMs: Long, fromBeat: Long = 0) {
        if (clicking) return
        val safeBpm = bpm.coerceIn(MIN_BPM, MAX_BPM)
        val framesPerBeat = sampleRate * 60.0 / safeBpm
        val bar = beatsPerBar.coerceAtLeast(1)
        val firstFrame = (delayMs.coerceAtLeast(0) * sampleRate / 1_000)
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // The buffer *is* latency: the feeder fills it before its blocking write starts pacing
            // it, so everything queued ahead is time between a click being written and being heard.
            // One minimum buffer, not two, and the low-latency path where the device offers it —
            // together they cut what has to be compensated for below, and steady 20 ms chunks keep
            // up with the smaller buffer easily.
            .setBufferSizeInBytes(minBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        clickTrack = t
        clicking = true
        clickStartNanos = System.nanoTime()
        t.play()
        clickFeeder = Thread(
            { feedClicks(t, framesPerBeat, bar, firstFrame, fromBeat.coerceAtLeast(0)) },
            "Metronome-feeder",
        ).apply { start() }
    }

    /** Stop the through-take clicks, leaving any count-in alone (idempotent). */
    fun stopTakeClicks() {
        if (clickTrack == null) return
        clicking = false
        clickFeeder?.let { runCatching { it.join(200) } }
        clickFeeder = null
        clickTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        clickTrack = null
    }

    /** Silence, whatever was sounding — the end of a take, or the start of another one. */
    fun stopAll() {
        stop()
        stopTakeClicks()
    }

    /**
     * Write silence with a click mixed in wherever a beat falls.
     *
     * Each chunk asks which beats *overlap* it rather than tracking a click across the boundary: a
     * click is 30 ms and a chunk 20, so one always straddles, and looking the overlap up from the
     * beat index keeps the feeder stateless. Two clicks cannot collide — the shortest beat this
     * allows, at 240 bpm, is 250 ms.
     */
    private fun feedClicks(
        t: AudioTrack,
        framesPerBeat: Double,
        beatsPerBar: Int,
        firstFrame: Long,
        fromBeat: Long,
    ) {
        val chunkFrames = (sampleRate / 50).coerceAtLeast(256) // ~20 ms
        val chunk = ShortArray(chunkFrames)
        val longest = maxOf(accentClick.size, plainClick.size)
        var frame = 0L
        while (clicking) {
            chunk.fill(0)
            val until = frame + chunkFrames
            val firstBeat = floor((frame - longest - firstFrame) / framesPerBeat).toLong()
            val lastBeat = floor((until - firstFrame) / framesPerBeat).toLong()
            var b = firstBeat.coerceAtLeast(0)
            while (b <= lastBeat) {
                val at = firstFrame + Math.round(b * framesPerBeat)
                val click =
                    if ((fromBeat + b) % beatsPerBar == 0L) accentClick else plainClick
                val from = maxOf(at, frame)
                val to = minOf(at + click.size, until)
                for (i in from until to) {
                    chunk[(i - frame).toInt()] = click[(i - at).toInt()]
                }
                b++
            }
            // Blocking, and deliberately the only thing pacing this loop.
            if (t.write(chunk, 0, chunkFrames) < 0) break
            frame = until
        }
    }

    /** Stop and release any in-flight count-in (idempotent). */
    fun stop() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
    }

    /**
     * One click: a dry, woody "tock" like a mechanical metronome. A fundamental plus an
     * inharmonic partial (~2.76×) under a fast exponential decay gives the block-y character;
     * rendered to 16-bit mono PCM.
     */
    private fun renderClick(freq: Float, durationMs: Int): ShortArray {
        val n = sampleRate * durationMs / 1_000
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / sampleRate
            val env = exp(-9f * t * (1_000f / durationMs)) // fast decay → short, dry
            val tone = sin(2.0 * PI * freq * t).toFloat() +
                0.6f * sin(2.0 * PI * freq * 2.76 * t).toFloat()
            out[i] = (tone * env * 0.5f * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}

/** Tempo range offered anywhere in the app — count-in, visual metronome, saved metadata. */
const val MIN_BPM = 40f
const val MAX_BPM = 240f

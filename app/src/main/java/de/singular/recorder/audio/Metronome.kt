package de.singular.recorder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
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

    companion object {
        /**
         * Silent lead-in before the first click, covering AudioTrack cold-start latency. Public
         * because a caller running the clicks through a take has to line its own grid up with it.
         */
        const val LEAD_MS = 80L
    }

    /**
     * Sound the count-in and suspend until the downbeat that follows the last click. Returns
     * normally only if it ran to completion; cancelling the coroutine stops the clicks.
     */
    suspend fun countIn(bpm: Float, beatsPerBar: Int, bars: Int) {
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
            t.play()
            // Suspend for the whole buffer: its length is lead + one full beat per click, so it
            // ends exactly on the downbeat after the last one. Cancellation → finally.
            delay((buffer.size.toLong() * 1_000 / sampleRate).coerceAtLeast(1))
        } finally {
            stop()
        }
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
     * feeder thread writing ~20 ms at a time, in the manner of [BandPlayer]. The blocking write is
     * what paces it — no scheduler timing is trusted with a beat.
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
     * gets round to them. It is the same order of error as the block quantisation [Beats] describes,
     * and the reason the band has a nudge. Worth knowing before treating a take recorded to these
     * clicks as sample-accurate against the grid; not something this can fix.
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
            .setBufferSizeInBytes(minBytes * 2)
            .build()
        clickTrack = t
        clicking = true
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

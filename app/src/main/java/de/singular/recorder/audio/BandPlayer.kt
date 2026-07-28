package de.singular.recorder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A take, played with a band under it.
 *
 * Only used while the band is on: ordinary playback stays with `MediaPlayer`, which handles every
 * format the device knows and needs no decode step in front of it. This exists because two sounds
 * that must agree about where beat one is cannot come from two independent players — a transport
 * and an `AudioTrack` started at the same moment drift apart, and by the end of a five-minute take
 * the drummer is playing a different song. Here there is one stream, one clock, and the drums are
 * mixed into the take's own samples chunk by chunk, so they cannot drift by construction.
 *
 * **Why the take comes from a file.** [AudioDecoder]'s standing promise is that a take is never
 * held whole — five minutes is 26 MB of samples — and this keeps it: the decoded PCM is a cache
 * file, read a chunk at a time through a [RandomAccessFile], which also gives seek for nothing.
 *
 * **Why the drums are not rendered in advance.** They are cheap: a handful of one-shots mixed at
 * offsets arithmetic can find. Synthesising per chunk means a pattern change, a level, or a nudge
 * of the grid is audible on the next chunk (~20 ms) instead of after a re-render, which is what
 * makes the controls feel like a mixing desk rather than a build step.
 *
 * The feeder is modelled on RubberRing's `LoopPlayer`: a thread writing ~20 ms at a time, all live
 * controls `@Volatile` and picked up on the next chunk, seeks handled as a pending request rather
 * than by touching the track from another thread. Every public method is safe from the main thread.
 */
class BandPlayer(
    pcmFile: File,
    private val channels: Int,
    val sampleRate: Int,
    private val kit: SynthKit,
    /** Called on the feeder thread when the take runs out and looping is off. */
    private val onFinished: () -> Unit,
) {
    private val pcm = RandomAccessFile(pcmFile, "r")
    private val bytesPerFrame = channels * 2

    val frameCount: Long = pcmFile.length() / bytesPerFrame

    @Volatile var positionFrame: Long = 0
        private set

    @Volatile var beats: Beats = Beats(bpm = 100f, beatsPerBar = 4, sampleRate = sampleRate)
    @Volatile var pattern: DrumPattern = Patterns.default
    @Volatile var takeLevel: Float = 1f
    @Volatile var drumsLevel: Float = DEFAULT_DRUMS_LEVEL
    @Volatile var looping: Boolean = false

    @Volatile private var running = false
    @Volatile private var pendingSeek: Long = -1
    private var track: AudioTrack? = null
    private var feeder: Thread? = null

    /** Voices still sounding, carried across chunk boundaries — a cymbal outlives its 20 ms. */
    private val active = ArrayList<Ringing>(16)

    private class Ringing(val samples: FloatArray, var at: Int, val gain: Float)

    val isPlaying: Boolean get() = running

    fun play(fromFrame: Long = positionFrame) {
        if (running) return
        positionFrame = fromFrame.coerceIn(0, frameCount)
        val mask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(mask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBytes * 2)
            .build()
        track = t
        running = true
        t.play()
        feeder = Thread({ feed(t) }, "BandPlayer-feeder").apply { start() }
    }

    /**
     * Stop, and give the audio device back.
     *
     * Keyed on there being a track rather than on [running], because the feeder clears that flag
     * itself when the take runs out — and a pause that returned early there would leave the
     * `AudioTrack` held open by a player nobody is listening to.
     */
    fun pause() {
        if (track == null) return
        running = false
        feeder?.let { runCatching { it.join(200) } }
        feeder = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }

    /** Move the playhead. Takes effect on the next chunk if playing. */
    fun seekTo(frame: Long) {
        val f = frame.coerceIn(0, frameCount)
        positionFrame = f
        pendingSeek = f
    }

    fun release() {
        pause()
        runCatching { pcm.close() }
    }

    private fun feed(t: AudioTrack) {
        val chunkFrames = (sampleRate / 50).coerceAtLeast(256) // ~20 ms
        val raw = ByteArray(chunkFrames * bytesPerFrame)
        val mix = FloatArray(chunkFrames * channels)
        val out = ShortArray(chunkFrames * channels)
        var frame = positionFrame
        runCatching { pcm.seek(frame * bytesPerFrame) }

        while (running) {
            val seek = pendingSeek
            if (seek >= 0) {
                pendingSeek = -1
                frame = seek
                runCatching { pcm.seek(frame * bytesPerFrame) }
                // A hat left ringing across a jump belongs to a bar that is no longer playing.
                synchronized(active) { active.clear() }
            }

            val framesLeft = frameCount - frame
            if (framesLeft <= 0) {
                if (looping) {
                    seekTo(0)
                    continue
                }
                running = false
                onFinished()
                break
            }

            val frames = min(chunkFrames.toLong(), framesLeft).toInt()
            val wanted = frames * bytesPerFrame
            val got = runCatching { pcm.read(raw, 0, wanted) }.getOrDefault(-1)
            if (got <= 0) {
                running = false
                onFinished()
                break
            }
            val gotFrames = got / bytesPerFrame

            takeInto(mix, raw, gotFrames)
            drumsInto(mix, frame, gotFrames)
            for (i in 0 until gotFrames * channels) {
                // A hard ceiling rather than a limiter: the levels are the user's, the take is
                // already at whatever level it was normalised to, and a sum that reaches the top
                // is a fader to pull down rather than something to disguise.
                out[i] = (mix[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }

            val written = t.write(out, 0, gotFrames * channels, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break
            frame += gotFrames
            positionFrame = frame
        }
    }

    /** The take's own samples, scaled by its fader. */
    private fun takeInto(mix: FloatArray, raw: ByteArray, frames: Int) {
        val gain = takeLevel
        for (i in 0 until frames * channels) {
            val lo = raw[i * 2].toInt() and 0xFF
            val hi = raw[i * 2 + 1].toInt()
            mix[i] = ((hi shl 8) or lo).toShort() / 32768f * gain
        }
    }

    /**
     * Trigger whatever the grid asks for inside this chunk, then mix everything still ringing.
     *
     * Positions come from [Beats.frameOfStep] every time rather than being counted forward: a beat
     * at 44.1 kHz is rarely a whole number of frames, and accumulating the remainder walks the
     * drummer off the take over the length of a song.
     */
    private fun drumsInto(mix: FloatArray, fromFrame: Long, frames: Int) {
        val level = drumsLevel
        val grid = beats
        val bar = pattern.hitsForBar(grid.beatsPerBar)
        if (level > 0f) {
            for (step in grid.stepsIn(fromFrame, fromFrame + frames)) {
                val inBar = grid.stepInBar(step)
                for (hit in bar) {
                    if (hit.step != inBar) continue
                    val swung = swing(grid, step)
                    val at = (grid.frameOfStep(step) + swung - fromFrame).roundToInt()
                    val voice = when (hit.drum) {
                        Drum.KICK -> kit.kick
                        Drum.SNARE -> kit.snare
                        Drum.HAT -> kit.hat
                        Drum.HAT_OPEN -> kit.hatOpen
                    }
                    synchronized(active) {
                        active += Ringing(voice.samples, -at, hit.velocity * level)
                    }
                }
            }
        }
        synchronized(active) {
            if (active.isEmpty()) return
            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val ringing = iterator.next()
                for (f in 0 until frames) {
                    val index = ringing.at + f
                    if (index < 0) continue
                    if (index >= ringing.samples.size) break
                    val sample = ringing.samples[index] * ringing.gain
                    // Mono voices, spread across whatever the take has.
                    for (c in 0 until channels) mix[f * channels + c] += sample
                }
                ringing.at += frames
                if (ringing.at >= ringing.samples.size) iterator.remove()
            }
        }
    }

    /** How late an off-beat sixteenth sits, in frames. Straight patterns answer zero. */
    private fun swing(grid: Beats, step: Long): Double {
        if (pattern.swing <= 0f) return 0.0
        val offBeatEighth = Math.floorMod(step, Beats.STEPS_PER_BEAT.toLong()) == 2L
        return if (offBeatEighth) pattern.swing * grid.framesPerStep else 0.0
    }

    companion object {
        /** Under the take by default: this is a bed to play over, not a track to play along to. */
        const val DEFAULT_DRUMS_LEVEL = 0.7f
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * The microphone, opened the way an instrument wants it and read a block at a time as 16-bit PCM.
 *
 * **Why this is shared rather than copied.** [AudioRecorder] and [TunerEngine] both need the same
 * unusual thing from Android's microphone: the signal with as little done to it as possible.
 * Automatic gain control riding a decaying chord ruins a recording, and it ruins a pitch reading
 * for the same reason — the level is being changed underneath the thing measuring it. Noise
 * suppression deciding a sustained low note is background hum is worse for the tuner than for the
 * recorder, since that note is the entire signal. Getting that source preference right once, in one
 * place, is the point; two copies of it would drift, and the tuner's copy would drift silently.
 *
 * `UNPROCESSED` first where the device advertises it, `VOICE_RECOGNITION` next — it skips the AGC
 * and the voice-call processing on most hardware — and `MIC` as the floor.
 */
class MicInput private constructor(
    private val record: AudioRecord,
    private val float: Boolean,
    blockFrames: Int,
) {

    private val floats = if (float) FloatArray(blockFrames) else FloatArray(0)

    fun startRecording() = record.startRecording()

    /** Give the microphone back. Safe to call whether or not recording ever started. */
    fun close() {
        // stop() throws if the recorder never initialised; release() is what must always run.
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    /**
     * One block into [into], with [gain] already applied. Returns frames read, or 0 or less on a
     * refused or exhausted stream.
     *
     * Android has no microphone preamp gain to turn up — nothing in `AudioRecord` or `AudioManager`
     * exposes one — so a quiet instrument can only be lifted by multiplying samples. Doing that
     * here, while the device is still handing back **float**, is what makes it free: the boost
     * happens before the reduction to the 16 bits that reach the file, so it costs no resolution.
     * Multiplying an already-quantised 16-bit sample would spend bits that cannot come back. Where
     * a device refuses float the 16-bit path does the same arithmetic and simply pays that price,
     * which is still better than the take being unusable.
     */
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

    companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**
         * Open the microphone, or null if the device would not give it up.
         *
         * [bufferBlocks] of headroom are asked for beyond [blockFrames], so a scheduling hiccup
         * cannot drop audio mid-phrase.
         */
        @SuppressLint("MissingPermission") // guarded by @RequiresPermission up the call chain
        fun open(context: Context, blockFrames: Int, bufferBlocks: Int): MicInput? {
            // Float first, 16-bit as the floor. What a file holds is 16-bit either way; the point
            // of asking for float is where the gain lands — see [read].
            for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, ENCODING)) {
                val minBuffer = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, CHANNEL, encoding)
                if (minBuffer <= 0) continue
                val bufferBytes =
                    maxOf(minBuffer, blockFrames * Wav.BYTES_PER_FRAME * bufferBlocks * 2)

                for (source in preferredSources(context)) {
                    val record = runCatching {
                        AudioRecord(source, Wav.SAMPLE_RATE, CHANNEL, encoding, bufferBytes)
                    }.getOrNull() ?: continue
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        return MicInput(
                            record,
                            float = encoding == AudioFormat.ENCODING_PCM_FLOAT,
                            blockFrames = blockFrames,
                        )
                    }
                    record.release()
                }
            }
            return null
        }

        private fun preferredSources(context: Context): List<Int> {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val unprocessed = audio
                ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                ?.toBoolean() == true
            return buildList {
                if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                add(MediaRecorder.AudioSource.MIC)
            }
        }
    }
}

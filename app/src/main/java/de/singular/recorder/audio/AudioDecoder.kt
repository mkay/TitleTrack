package de.singular.recorder.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes anything the device can play, far enough to draw it.
 *
 * A folder of recordings collects more than this app puts in it — takes from the stock recorder,
 * an idea mailed over as an mp3, a bounce from a laptop — and a waveform is exactly as useful for
 * those. So rather than only understanding the WAV we write, the platform's own decoders do the
 * work: whatever [android.media.MediaPlayer] would play, this can draw.
 *
 * The PCM is never kept. It arrives a buffer at a time, is folded into [PeakBuckets], and is
 * dropped — a five-minute take is 26 MB of samples and about 420 floats worth keeping. Decoding
 * stops the moment the last column is filled, so a long file that is mostly tail costs no more
 * than the part that gets drawn.
 */
object AudioDecoder {

    /**
     * How long [uri] runs, in milliseconds, or 0 if it has no audio track or will not open.
     *
     * Only the container is parsed — no codec is started — so this is a header read rather than a
     * decode, and cheap enough to do for every file in a listing.
     */
    fun durationMs(context: Context, uri: Uri): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                .mapNotNull { it.long(MediaFormat.KEY_DURATION) }
                .maxOrNull()
                ?.div(1_000) ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Peaks in `0f..1f`, or null if [uri] has no audio track this device can decode.
     *
     * Runs the codec synchronously, so call it off the main thread. [stillWanted] is polled
     * between buffers: a user who backs out of the player should not leave a decoder running.
     */
    fun peaks(
        context: Context,
        uri: Uri,
        buckets: Int = Waveform.BUCKETS,
        stillWanted: () -> Boolean = { true },
    ): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            runCatching { extractor.setDataSource(context, uri, null) }.getOrElse { return null }

            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            val input = extractor.getTrackFormat(track)
            val mime = input.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = input.integer(MediaFormat.KEY_SAMPLE_RATE) ?: return null
            val durationUs = input.long(MediaFormat.KEY_DURATION) ?: return null
            if (sampleRate <= 0 || durationUs <= 0) return null

            // Frames from the container's own duration. It is a claim rather than a count, but it
            // is the only length available before decoding, and it is right to within a frame.
            val totalFrames = durationUs / 1_000_000.0 * sampleRate
            if (totalFrames < 1) return null

            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(input, null, null, 0)
            codec.start()

            return drain(extractor, codec, input, totalFrames.toLong(), buckets, stillWanted)
        } catch (e: Exception) {
            // A malformed or half-copied file throws from anywhere in here. It is not worth
            // distinguishing: none of it is drawable, and the player says so either way.
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** The decode loop: feed the codec from the extractor, fold what comes out into buckets. */
    private fun drain(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        totalFrames: Long,
        buckets: Int,
        stillWanted: () -> Boolean,
    ): FloatArray? {
        val peaks = PeakBuckets(buckets, totalFrames)
        val info = MediaCodec.BufferInfo()

        // Until the codec announces its output format these are the best guess available, and for
        // most decoders they are also the answer.
        var channels = inputFormat.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        var bytes = ByteArray(0)
        var floats = FloatArray(0)
        var fedEverything = false
        var done = false

        while (!done && !peaks.full && stillWanted()) {
            if (!fedEverything) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)
                    val read = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(
                            index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        fedEverything = true
                    } else {
                        codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = codec.outputFormat
                    channels = out.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                    encoding = out.integer(MediaFormat.KEY_PCM_ENCODING) ?: encoding
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit // nothing ready yet; go round again

                else -> if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val f = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            if (floats.size < f.remaining()) floats = FloatArray(f.remaining())
                            val n = f.remaining()
                            f.get(floats, 0, n)
                            peaks.addPcmFloat(floats, n, channels)
                        } else {
                            if (bytes.size < info.size) bytes = ByteArray(info.size)
                            buffer.get(bytes, 0, info.size)
                            peaks.addPcm16(bytes, info.size, channels)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }

        return if (stillWanted()) peaks.finish() else null
    }

    private fun MediaFormat.integer(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.long(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    /** Long enough not to spin the loop, short enough to notice [peaks]' cancellation promptly. */
    private const val TIMEOUT_US = 10_000L
}

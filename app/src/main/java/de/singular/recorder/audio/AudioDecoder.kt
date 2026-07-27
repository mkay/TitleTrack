package de.singular.recorder.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Where a decode sends its PCM.
 *
 * The samples arrive a buffer at a time and in whichever encoding the codec chose, which is why
 * this is a sink rather than a return value: nothing here ever holds a whole file. Drawing folds
 * the buffers into peaks and forgets them; normalising writes them out again.
 */
interface PcmSink {

    /** Called once, before the first buffer, with what the container says is coming. */
    fun onStart(sampleRate: Int, channels: Int, totalFrames: Long) {}

    /**
     * The rate and channel count the codec actually settled on, once it announces them. Worth
     * listening to: HE-AAC decoders hand back twice the sample rate the container declares.
     */
    fun onOutputFormat(sampleRate: Int, channels: Int) {}

    /** [count] bytes of interleaved little-endian 16-bit PCM. */
    fun onPcm16(pcm: ByteArray, count: Int, channels: Int)

    /** [count] floats of interleaved PCM in `-1f..1f`, as some decoders hand back. */
    fun onPcmFloat(pcm: FloatArray, count: Int, channels: Int)

    /** False once the sink has all it needs — the decode stops there rather than running on. */
    val wantsMore: Boolean get() = true
}

/**
 * Decodes anything the device can play.
 *
 * A folder of recordings collects more than this app puts in it — takes from the stock recorder,
 * an idea mailed over as an mp3, a bounce from a laptop — and a waveform, or a level lift, is
 * exactly as useful for those. So rather than only understanding the WAV we write, the platform's
 * own decoders do the work: whatever [android.media.MediaPlayer] would play, this can read.
 *
 * The PCM is never kept here. It arrives a buffer at a time, goes to a [PcmSink], and is dropped —
 * a five-minute take is 26 MB of samples. Decoding stops the moment the sink has had enough, so a
 * waveform that is already drawn costs nothing more.
 *
 * One format is refused outright — see [MIME_ALAC].
 */
object AudioDecoder {

    /**
     * Apple Lossless, which this app will not decode.
     *
     * Not a policy about the format: it is that asking whether *this* device can decode it is not
     * safe to ask. ALAC is not one of Android's required formats and AOSP ships no decoder for it,
     * so where it exists at all it is a vendor extra — on Qualcomm devices `c2.qti.alac.sw.decoder`.
     * That decoder is registered, claims `audio/alac`, and then rejects the codec-specific data that
     * `MediaExtractor` hands it (`checkAlacCSDParameters: Found an invalid ALAC CSD param`). The
     * DSP offload path reads the same cookie as `bit_depth:0` and fails every write while
     * [android.media.MediaPlayer] reports itself playing — silence that looks like playback.
     *
     * Worse than the failure is how it fails: the codec tears down its input buffers while
     * `MediaExtractor.readSampleData` is still copying a packet into one, and the app dies with a
     * SIGSEGV inside `memcpy` — the waveform read as readily as anything else. A crash cannot be
     * caught, so the only defence is not to start the codec.
     *
     * The cost is a device whose ALAC decoder works being refused along with the rest. Taking that
     * on knowingly: ALAC arrives here only by way of an Apple export, and the answer for such a file
     * is to convert it once — to FLAC, which is lossless, no larger, and a format Android requires
     * every device to decode.
     */
    const val MIME_ALAC = "audio/alac"

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
        var peaks: PeakBuckets? = null
        val sink = object : PcmSink {
            override fun onStart(sampleRate: Int, channels: Int, totalFrames: Long) {
                peaks = PeakBuckets(buckets, totalFrames)
            }

            override fun onPcm16(pcm: ByteArray, count: Int, channels: Int) {
                peaks?.addPcm16(pcm, count, channels)
            }

            override fun onPcmFloat(pcm: FloatArray, count: Int, channels: Int) {
                peaks?.addPcmFloat(pcm, count, channels)
            }

            // Once every column is filled there is nothing left for the rest of the file to say.
            override val wantsMore: Boolean get() = peaks?.full != true
        }
        if (!decode(context, uri, sink, stillWanted)) return null
        return if (stillWanted()) peaks?.finish() else null
    }

    /**
     * Whether this device can really decode [uri], established by decoding the first buffer of it.
     *
     * Not the same question as "is there a decoder for this type", and the codec list cannot answer
     * it: a device may register a decoder that fails on the first packet it is given, as this one
     * does for [MIME_ALAC]. Running the thing is what settles it.
     *
     * Stops at the first buffer of PCM, so it costs a codec start rather than a decode. Call it off
     * the main thread with the rest of this class.
     */
    fun canDecode(context: Context, uri: Uri): Boolean {
        var got = false
        val sink = object : PcmSink {
            override fun onPcm16(pcm: ByteArray, count: Int, channels: Int) {
                got = got || count > 0
            }

            override fun onPcmFloat(pcm: FloatArray, count: Int, channels: Int) {
                got = got || count > 0
            }

            override val wantsMore: Boolean get() = !got
        }
        // Both halves matter: decode reports the codec throwing, `got` reports it running without
        // ever producing a sample.
        return decode(context, uri, sink) && got
    }

    /**
     * Decode [uri] into [sink]. Returns false if it has no audio track this device can decode.
     *
     * Runs the codec synchronously, so call it off the main thread. [stillWanted] is polled between
     * buffers: a user who backs out of the player should not leave a decoder running.
     */
    fun decode(
        context: Context,
        uri: Uri,
        sink: PcmSink,
        stillWanted: () -> Boolean = { true },
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            runCatching { extractor.setDataSource(context, uri, null) }.getOrElse { return false }

            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return false

            val input = extractor.getTrackFormat(track)
            val mime = input.getString(MediaFormat.KEY_MIME) ?: return false
            // Before any codec is created, because for this one, creating it is the danger.
            if (mime == MIME_ALAC) return false
            val sampleRate = input.integer(MediaFormat.KEY_SAMPLE_RATE) ?: return false
            val durationUs = input.long(MediaFormat.KEY_DURATION) ?: return false
            if (sampleRate <= 0 || durationUs <= 0) return false

            // Frames from the container's own duration. It is a claim rather than a count, but it
            // is the only length available before decoding, and it is right to within a frame.
            val totalFrames = durationUs / 1_000_000.0 * sampleRate
            if (totalFrames < 1) return false

            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(input, null, null, 0)
            codec.start()

            sink.onStart(
                sampleRate,
                input.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: 1,
                totalFrames.toLong(),
            )
            drain(extractor, codec, input, sink, stillWanted)
            return true
        } catch (e: Exception) {
            // A malformed or half-copied file throws from anywhere in here. It is not worth
            // distinguishing: none of it is usable, and the caller says so either way.
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** The decode loop: feed the codec from the extractor, pass what comes out to the sink. */
    private fun drain(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        sink: PcmSink,
        stillWanted: () -> Boolean,
    ) {
        val info = MediaCodec.BufferInfo()

        // Until the codec announces its output format these are the best guess available, and for
        // most decoders they are also the answer.
        var channels = inputFormat.integer(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        var bytes = ByteArray(0)
        var floats = FloatArray(0)
        var fedEverything = false
        var done = false

        while (!done && sink.wantsMore && stillWanted()) {
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
                    val rate = out.integer(MediaFormat.KEY_SAMPLE_RATE)
                        ?: inputFormat.integer(MediaFormat.KEY_SAMPLE_RATE) ?: 0
                    sink.onOutputFormat(rate, channels)
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
                            sink.onPcmFloat(floats, n, channels)
                        } else {
                            if (bytes.size < info.size) bytes = ByteArray(info.size)
                            buffer.get(bytes, 0, info.size)
                            sink.onPcm16(bytes, info.size, channels)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
    }

    private fun MediaFormat.integer(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.long(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    /** Long enough not to spin the loop, short enough to notice [peaks]' cancellation promptly. */
    private const val TIMEOUT_US = 10_000L
}

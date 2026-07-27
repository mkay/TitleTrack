package de.singular.recorder.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.OutputStream

/**
 * Reading and writing the native FLAC container.
 *
 * The counterpart to [Wav], and here for the same reason: an edit has to write a file, and for a
 * take that did not arrive as WAV, writing WAV nearly triples it for no gain in quality. FLAC is
 * lossless too — the samples come back bit for bit — at roughly the size of the ALAC or m4a it came
 * from, and Android requires every device to decode it, which is more than can be said for the
 * format that started all this.
 *
 * **Why a container by hand.** `MediaCodec` encodes the frames but will not wrap them:
 * `MediaMuxer` has no native `.flac` output, only FLAC inside MP4, which is not what a folder of
 * recordings wants. So the stream is laid out here — magic, STREAMINFO, a Vorbis comment, frames —
 * exactly as [Wav] lays out RIFF.
 *
 * **Why the frames are buffered.** STREAMINFO carries the total sample count, and that is not known
 * until the last frame is encoded, while a SAF output stream cannot be seeked back to patch it.
 * So frames go to a cache file, and the header is written in front of them at the end. A take is
 * minutes long, so this costs a copy of something that was already on disk.
 *
 * The tempo travels as it does in a WAV: `bpm=` in a comment, plus a `BPM` field of its own, so a
 * desktop tool shows the same thing this app reads back.
 */
/**
 * What an edit writes its result as. Both are lossless; the difference is size.
 *
 * [WAV] is what the app records, and the format its own takes stay in through an edit: a WAV is
 * scaled or cut without a codec going anywhere near it. [FLAC] is the same audio at roughly half
 * the bytes, which is what a take that arrived compressed wants back — a normalised copy of a FLAC
 * written as WAV is nearly three times the file for nothing.
 */
enum class AudioFormat(val mime: String, val extension: String, val label: String) {
    FLAC(Flac.MIME, "flac", "FLAC"),
    WAV(RIFF_WAVE_MIME, "wav", "WAV"),
}

/** Providers are inconsistent about WAV, but this is the one to ask for. */
const val RIFF_WAVE_MIME = "audio/x-wav"

object Flac {

    const val MIME = "audio/flac"

    /** FLAC's own name for itself, at the head of every stream. */
    private const val MAGIC = "fLaC"

    /** STREAMINFO is fixed at 34 bytes, and always the first metadata block. */
    private const val STREAMINFO_BYTES = 34

    private const val BLOCK_STREAMINFO = 0
    private const val BLOCK_VORBIS_COMMENT = 4

    /** Middling: the encoder's own default, and the last levels buy little on 16-bit mono. */
    private const val COMPRESSION_LEVEL = 5

    private const val BPM_KEY = "bpm="
    private const val GAIN_KEY = "gain="

    /**
     * A sink that takes little-endian 16-bit PCM and leaves a complete `.flac` on [out].
     *
     * An [OutputStream] on purpose: the callers already write PCM to one — scaled by a gain, cut to
     * a trim — and this way choosing FLAC over WAV swaps the stream and changes nothing else.
     *
     * Nothing reaches [out] until [close], which is when the header can finally be written. Closing
     * does not close [out]: the caller opened it and `use` will close it.
     */
    class Writer(
        private val out: OutputStream,
        private val sampleRate: Int,
        private val channels: Int,
        cacheDir: File,
        private val bpm: Float? = null,
        private val title: String? = null,
        private val gainDb: Int = 0,
    ) : OutputStream() {

        private val frames = File(cacheDir, "flac-frames.bin")
        private val framesOut = frames.outputStream().buffered()
        private val codec = MediaCodec.createEncoderByType(MIME)
        private val info = MediaCodec.BufferInfo()

        /** STREAMINFO as the encoder reports it, before the sample count is patched in. */
        private var streamInfo: ByteArray? = null
        private var totalFrames = 0L
        private var closed = false

        /** A partial frame across a write boundary — PCM only means anything in whole frames. */
        private var pending = ByteArray(0)
        private var pendingSize = 0

        init {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, COMPRESSION_LEVEL)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "write after close" }
            var at = off
            var left = len
            // The encoder is fed whole frames; an odd byte waits here for the rest of its sample.
            if (pendingSize > 0) {
                val want = minOf(BYTES_PER_SAMPLE - pendingSize, left)
                System.arraycopy(b, at, pending, pendingSize, want)
                pendingSize += want
                at += want
                left -= want
                if (pendingSize == BYTES_PER_SAMPLE) {
                    feed(pending, 0, BYTES_PER_SAMPLE)
                    pendingSize = 0
                }
            }
            val whole = left - left % BYTES_PER_SAMPLE
            if (whole > 0) feed(b, at, whole)
            val rest = left - whole
            if (rest > 0) {
                if (pending.size < BYTES_PER_SAMPLE) pending = ByteArray(BYTES_PER_SAMPLE)
                System.arraycopy(b, at + whole, pending, 0, rest)
                pendingSize = rest
            }
        }

        /**
         * Finish the stream: flush the encoder, then write magic, header and frames to [out].
         *
         * Failure still tears the codec down and deletes the cache file — a half-encoded take is of
         * no use to anyone, and the caller deletes the document it was going into.
         */
        override fun close() {
            if (closed) return
            closed = true
            try {
                signalEndOfStream()
                drain(untilEndOfStream = true)
                framesOut.flush()
                framesOut.close()
                val header = streamInfo
                    ?: throw IllegalStateException("The FLAC encoder never described its stream.")
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.write(metadataBlock(BLOCK_STREAMINFO, withTotalSamples(header), last = false))
                out.write(metadataBlock(BLOCK_VORBIS_COMMENT, vorbisComment(), last = true))
                frames.inputStream().use { it.copyTo(out) }
            } finally {
                runCatching { framesOut.close() }
                runCatching { codec.stop() }
                runCatching { codec.release() }
                frames.delete()
            }
        }

        /** Hand [len] bytes to the encoder, draining whatever it has ready as we go. */
        private fun feed(b: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) {
                    drain(untilEndOfStream = false)
                    continue
                }
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                val n = minOf(buffer.remaining(), left)
                buffer.put(b, at, n)
                codec.queueInputBuffer(index, 0, n, presentationTimeUs(), 0)
                totalFrames += n / (BYTES_PER_SAMPLE * channels)
                at += n
                left -= n
                drain(untilEndOfStream = false)
            }
        }

        private fun signalEndOfStream() {
            while (true) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(
                        index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    return
                }
                drain(untilEndOfStream = false)
            }
        }

        /** Move whatever the encoder has produced into the frames file. */
        private fun drain(untilEndOfStream: Boolean) {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        streamInfo = streamInfoFrom(codec.outputFormat) ?: streamInfo

                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEndOfStream) return

                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            // The codec-config buffer is the header, not audio: it describes the
                            // stream and is written in front of the frames, never among them.
                            if (config) streamInfo = streamInfoOf(bytes) else framesOut.write(bytes)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        /**
         * Presentation timestamps the encoder will accept.
         *
         * FLAC does not carry them and nothing downstream reads them, but a codec fed a stream of
         * zeroes is entitled to complain, so they follow the samples actually queued.
         */
        private fun presentationTimeUs(): Long =
            if (sampleRate <= 0) 0 else totalFrames * 1_000_000L / sampleRate

        /** STREAMINFO with the sample count filled in — see the note on buffering, above. */
        private fun withTotalSamples(header: ByteArray): ByteArray {
            val patched = header.copyOf()
            // Bytes 10..17 are one 64-bit field: rate (20), channels (3), depth (5), samples (36).
            var packed = 0L
            for (i in 0 until 8) packed = (packed shl 8) or (patched[10 + i].toLong() and 0xFF)
            packed = (packed and TOTAL_SAMPLES_MASK.inv()) or (totalFrames and TOTAL_SAMPLES_MASK)
            for (i in 0 until 8) patched[10 + i] = ((packed ushr ((7 - i) * 8)) and 0xFF).toByte()
            return patched
        }

        private fun vorbisComment(): ByteArray {
            val fields = buildList {
                if (!title.isNullOrBlank()) add("TITLE=$title")
                if (bpm != null) add("BPM=${trimZeros(bpm)}")
                val comment = buildString {
                    if (bpm != null) append(BPM_KEY + trimZeros(bpm))
                    if (gainDb != 0) {
                        if (isNotEmpty()) append(' ')
                        append(GAIN_KEY)
                        append(if (gainDb > 0) "+" else "")
                        append(gainDb)
                    }
                }
                if (comment.isNotEmpty()) add("COMMENT=$comment")
                add("ENCODER=TitleTrack")
            }
            val vendor = "TitleTrack".toByteArray(Charsets.UTF_8)
            val encoded = fields.map { it.toByteArray(Charsets.UTF_8) }
            val out = ByteArrayBuilder(vendor.size + encoded.sumOf { it.size + 4 } + 8)
            // A Vorbis comment is little-endian, alone among FLAC's structures.
            out.u32le(vendor.size.toLong())
            out.raw(vendor)
            out.u32le(encoded.size.toLong())
            for (field in encoded) {
                out.u32le(field.size.toLong())
                out.raw(field)
            }
            return out.toByteArray()
        }
    }

    /** What a FLAC's header says about the file behind it — the shape [Wav.Info] has. */
    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
        val bpm: Float?,
    ) {
        val durationMs: Long
            get() = if (sampleRate <= 0) 0 else totalSamples * 1_000 / sampleRate
    }

    /**
     * Parse [bytes] — the head of a FLAC file — into an [Info], or null if it is not a FLAC.
     *
     * Reads only the metadata blocks, so a few KB is ample unless a file carries cover art, in
     * which case the comment may sit past the end of what was read and the tempo is simply not
     * found. That is the same answer as a file that never had one.
     */
    fun readInfo(bytes: ByteArray): Info? {
        if (bytes.size < 4 + 4 + STREAMINFO_BYTES) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != MAGIC) return null

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var samples = 0L
        var bpm: Float? = null

        var p = 4
        while (p + 4 <= bytes.size) {
            val last = bytes[p].toInt() and 0x80 != 0
            val type = bytes[p].toInt() and 0x7F
            val size = ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                ((bytes[p + 2].toInt() and 0xFF) shl 8) or
                (bytes[p + 3].toInt() and 0xFF)
            val body = p + 4
            if (size < 0 || body + size > bytes.size) break // truncated read, not a broken file
            when (type) {
                BLOCK_STREAMINFO -> if (size >= STREAMINFO_BYTES) {
                    var packed = 0L
                    for (i in 0 until 8) {
                        packed = (packed shl 8) or (bytes[body + 10 + i].toLong() and 0xFF)
                    }
                    sampleRate = (packed ushr 44).toInt() and 0xFFFFF
                    channels = ((packed ushr 41).toInt() and 0x7) + 1
                    bits = ((packed ushr 36).toInt() and 0x1F) + 1
                    samples = packed and TOTAL_SAMPLES_MASK
                }

                BLOCK_VORBIS_COMMENT -> bpm = findBpm(bytes, body, body + size)
            }
            if (last) break
            p = body + size
        }
        if (sampleRate <= 0 || channels <= 0) return null
        return Info(sampleRate, channels, bits, samples, bpm)
    }

    /** `BPM`, or the `bpm=` this app also writes into `COMMENT`, whichever turns up first. */
    private fun findBpm(bytes: ByteArray, from: Int, end: Int): Float? {
        var p = from
        if (p + 4 > end) return null
        val vendor = u32le(bytes, p)
        p += 4 + vendor.toInt()
        if (p + 4 > end) return null
        val count = u32le(bytes, p).toInt()
        p += 4
        var fromComment: Float? = null
        for (i in 0 until count) {
            if (p + 4 > end) break
            val size = u32le(bytes, p).toInt()
            p += 4
            if (size < 0 || p + size > end) break
            val field = String(bytes, p, size, Charsets.UTF_8)
            p += size
            val key = field.substringBefore('=').uppercase()
            val value = field.substringAfter('=', "")
            if (key == "BPM") value.trim().toFloatOrNull()?.let { return it }
            if (key == "COMMENT") {
                fromComment = value.substringAfter(BPM_KEY, "")
                    .takeWhile { it.isDigit() || it == '.' }
                    .toFloatOrNull() ?: fromComment
            }
        }
        return fromComment
    }

    /**
     * The 34 STREAMINFO bytes out of whatever the encoder called codec config.
     *
     * Encoders differ on how much of the stream's head they hand back: some the bare block, some
     * the whole thing from the magic onwards. Both are recognised rather than one being assumed.
     */
    private fun streamInfoOf(csd: ByteArray): ByteArray? = when {
        csd.size >= 4 + 4 + STREAMINFO_BYTES &&
            String(csd, 0, 4, Charsets.US_ASCII) == MAGIC ->
            csd.copyOfRange(8, 8 + STREAMINFO_BYTES)

        csd.size >= STREAMINFO_BYTES -> csd.copyOfRange(0, STREAMINFO_BYTES)

        else -> null
    }

    private fun streamInfoFrom(format: MediaFormat): ByteArray? {
        val csd = runCatching { format.getByteBuffer("csd-0") }.getOrNull() ?: return null
        val bytes = ByteArray(csd.remaining())
        csd.duplicate().get(bytes)
        return streamInfoOf(bytes)
    }

    /** A metadata block header — last-block flag, type, 24-bit length — and its body. */
    private fun metadataBlock(type: Int, body: ByteArray, last: Boolean): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = (type or if (last) 0x80 else 0).toByte()
        out[1] = ((body.size ushr 16) and 0xFF).toByte()
        out[2] = ((body.size ushr 8) and 0xFF).toByte()
        out[3] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }

    /** "96" rather than "96.0" — as [Wav] writes it, so the two files read alike. */
    private fun trimZeros(bpm: Float): String =
        if (bpm == bpm.toInt().toFloat()) bpm.toInt().toString() else bpm.toString()

    private fun u32le(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)

    /** 36 bits of sample count, the odd size FLAC actually specifies. */
    private const val TOTAL_SAMPLES_MASK = 0xF_FFFF_FFFFL

    private const val BYTES_PER_SAMPLE = 2

    /** As [AudioDecoder]'s: long enough not to spin, short enough to keep the encoder fed. */
    private const val TIMEOUT_US = 10_000L

    private class ByteArrayBuilder(capacity: Int) {
        private var buf = ByteArray(capacity.coerceAtLeast(16))
        private var size = 0

        fun u32le(v: Long) {
            ensure(4)
            buf[size++] = (v and 0xFF).toByte()
            buf[size++] = ((v ushr 8) and 0xFF).toByte()
            buf[size++] = ((v ushr 16) and 0xFF).toByte()
            buf[size++] = ((v ushr 24) and 0xFF).toByte()
        }

        fun raw(b: ByteArray) {
            ensure(b.size)
            System.arraycopy(b, 0, buf, size, b.size)
            size += b.size
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensure(more: Int) {
            if (size + more <= buf.size) return
            buf = buf.copyOf(maxOf(buf.size * 2, size + more))
        }
    }
}

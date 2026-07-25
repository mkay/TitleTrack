package de.singular.recorder.audio

/**
 * Reading and writing the RIFF/WAVE container.
 *
 * Takes are captured as raw PCM and only wrapped in a header on save, so this never has to seek
 * backwards to patch a size field: by the time a header is written, the payload length is known.
 *
 * The tempo a take was played to is written *into* the file, as a `LIST/INFO` comment. Tempo is
 * what the future drum and bass tracks have to lock to, and a sidecar index would lose it the
 * first time a file is moved or copied on a desktop — which is exactly what a folder full of
 * recordings is for. Unknown chunks are skipped by every player worth the name, so a file with
 * this in it is still an ordinary WAV.
 */
object Wav {

    /** What we record at: CD-rate mono, 16-bit. See [de.singular.recorder.audio.AudioRecorder]. */
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_FRAME = CHANNELS * BITS_PER_SAMPLE / 8

    /** Key used inside the INFO comment, e.g. `bpm=96`. */
    private const val BPM_KEY = "bpm="

    /**
     * The complete header preceding [dataBytes] bytes of PCM: `RIFF … fmt … [LIST] … data`.
     *
     * [bpm] and [title], when given, go into a `LIST/INFO` chunk between `fmt ` and `data`.
     */
    fun header(
        dataBytes: Long,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE,
        bpm: Float? = null,
        title: String? = null,
    ): ByteArray {
        val info = infoChunk(bpm, title)
        val out = ByteArrayBuilder(44 + info.size)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        out.ascii("RIFF")
        // Everything after this field: "WAVE" + fmt chunk (24) + info + data chunk header (8).
        out.u32(4L + 24L + info.size + 8L + dataBytes)
        out.ascii("WAVE")

        out.ascii("fmt ")
        out.u32(16)
        out.u16(1) // PCM, uncompressed
        out.u16(channels)
        out.u32(sampleRate.toLong())
        out.u32(byteRate.toLong())
        out.u16(blockAlign)
        out.u16(bitsPerSample)

        out.raw(info)

        out.ascii("data")
        out.u32(dataBytes)
        return out.toByteArray()
    }

    /** What a header says about the file behind it. */
    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataBytes: Long,
        val bpm: Float?,
    ) {
        val durationMs: Long
            get() {
                val bytesPerSecond = sampleRate.toLong() * channels * bitsPerSample / 8
                return if (bytesPerSecond <= 0) 0 else dataBytes * 1_000 / bytesPerSecond
            }
    }

    /**
     * Parse [bytes] — the head of a WAV file; a couple of KB is ample — into an [Info], or null if
     * it is not a WAVE at all.
     *
     * [fileBytes], when known, repairs the length of a file whose `data` size field is wrong: some
     * recorders (and any interrupted write) leave it at 0 or 0xFFFFFFFF, and a duration of zero is
     * more misleading in a list of takes than a length inferred from the file itself.
     */
    fun readInfo(bytes: ByteArray, fileBytes: Long = -1): Info? {
        if (bytes.size < 12) return null
        if (ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return null

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataBytes = -1L
        var dataStart = -1L
        var bpm: Float? = null

        var p = 12
        while (p + 8 <= bytes.size) {
            val id = ascii(bytes, p, 4)
            val size = u32(bytes, p + 4)
            val body = p + 8
            when (id) {
                "fmt " -> if (body + 16 <= bytes.size) {
                    channels = u16(bytes, body + 2)
                    sampleRate = u32(bytes, body + 4).toInt()
                    bits = u16(bytes, body + 14)
                }
                "LIST" -> if (body + 4 <= bytes.size && ascii(bytes, body, 4) == "INFO") {
                    bpm = findBpm(bytes, body + 4, minOf(bytes.size.toLong(), body + size).toInt())
                }
                "data" -> {
                    dataBytes = size
                    dataStart = body.toLong()
                }
            }
            if (id == "data") break // the payload; nothing we read follows it
            // Chunks are word-aligned: an odd body carries a pad byte the size does not count.
            p = body + size.toInt() + (size.toInt() and 1)
            if (size < 0 || p <= body) return null // corrupt size — stop rather than spin
        }

        if (sampleRate <= 0 || channels <= 0 || bits <= 0) return null
        // 0xFFFFFFFF (streamed/unfinished) or a size overrunning the file: trust the file instead.
        val trueData = if (fileBytes > 0 && dataStart >= 0 &&
            (dataBytes <= 0 || dataBytes > fileBytes - dataStart)
        ) fileBytes - dataStart else dataBytes
        return Info(sampleRate, channels, bits, trueData.coerceAtLeast(0), bpm)
    }

    /** The `LIST/INFO` chunk carrying tempo and title, or empty when there is nothing to say. */
    private fun infoChunk(bpm: Float?, title: String?): ByteArray {
        if (bpm == null && title.isNullOrBlank()) return ByteArray(0)
        val body = ByteArrayBuilder(64)
        body.ascii("INFO")
        if (!title.isNullOrBlank()) body.infoField("INAM", title)
        if (bpm != null) body.infoField("ICMT", BPM_KEY + trimZeros(bpm))
        body.infoField("ISFT", "SparkPlug")
        val bodyBytes = body.toByteArray()

        val out = ByteArrayBuilder(bodyBytes.size + 8)
        out.ascii("LIST")
        out.u32(bodyBytes.size.toLong())
        out.raw(bodyBytes)
        return out.toByteArray()
    }

    /** Walk the INFO fields between [from] and [end] looking for our `bpm=` comment. */
    private fun findBpm(bytes: ByteArray, from: Int, end: Int): Float? {
        var p = from
        while (p + 8 <= end) {
            val id = ascii(bytes, p, 4)
            val size = u32(bytes, p + 4).toInt()
            val body = p + 8
            if (size < 0 || body + size > end) return null
            if (id == "ICMT") {
                val text = String(bytes, body, size, Charsets.US_ASCII).trimEnd('\u0000')
                val value = text.substringAfter(BPM_KEY, "").takeWhile { it.isDigit() || it == '.' }
                value.toFloatOrNull()?.let { return it }
            }
            p = body + size + (size and 1)
        }
        return null
    }

    /** "96" rather than "96.0", but "96.5" kept — tempo is usually whole and reads better so. */
    private fun trimZeros(bpm: Float): String =
        if (bpm == bpm.toInt().toFloat()) bpm.toInt().toString() else bpm.toString()

    private fun ascii(b: ByteArray, at: Int, len: Int): String =
        if (at + len > b.size) "" else String(b, at, len, Charsets.US_ASCII)

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)

    /** A little-endian byte sink; RIFF is little-endian throughout. */
    private class ByteArrayBuilder(capacity: Int) {
        private var buf = ByteArray(capacity.coerceAtLeast(16))
        private var size = 0

        fun ascii(s: String) = raw(s.toByteArray(Charsets.US_ASCII))

        fun u16(v: Int) {
            ensure(2)
            buf[size++] = (v and 0xFF).toByte()
            buf[size++] = ((v ushr 8) and 0xFF).toByte()
        }

        fun u32(v: Long) {
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

        /** One INFO field: a four-letter id, a length, NUL-terminated text, padded to even. */
        fun infoField(id: String, text: String) {
            val body = (text + '\u0000').toByteArray(Charsets.US_ASCII)
            ascii(id)
            u32(body.size.toLong())
            raw(body)
            if (body.size and 1 == 1) raw(ByteArray(1))
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensure(more: Int) {
            if (size + more <= buf.size) return
            buf = buf.copyOf(maxOf(buf.size * 2, size + more))
        }
    }
}

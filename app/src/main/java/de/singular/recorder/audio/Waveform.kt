package de.singular.recorder.audio

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Reduces audio to the handful of numbers a waveform is actually drawn from.
 *
 * One peak per bucket, not an average: an average of a quiet passage and a loud one is a
 * medium-loud passage, which is a lie about what is in the file. A peak envelope is what every
 * editor draws and what a player recognises as "the shape of the take".
 *
 * WAV is read here directly, because it is already PCM and a codec would be a waste; everything
 * else goes through [AudioDecoder] and arrives at the same [PeakBuckets].
 */
object Waveform {

    /** Enough columns to look like a waveform on a phone, few enough to draw every frame. */
    const val BUCKETS = 420

    /**
     * Peaks in `0f..1f`, [buckets] of them, or null if this is not 16-bit PCM WAV.
     *
     * [fileBytes], the size of the file on disk, repairs a `data` size field the writer never
     * patched — 0 or 0xFFFFFFFF, which an interrupted write leaves behind. Without a length there
     * is nothing to divide into buckets, so a take that would still play would draw as nothing.
     */
    fun readWav(input: InputStream, fileBytes: Long, buckets: Int = BUCKETS): FloatArray? {
        val head = ByteArray(12)
        if (!input.readFully(head)) return null
        if (ascii(head, 0) != "RIFF" || ascii(head, 8) != "WAVE") return null

        var channels = 0
        var bits = 0
        var declared = -1L
        var consumed = 12L

        // Walk the chunk list to the payload, picking up `fmt ` on the way past.
        val chunk = ByteArray(8)
        while (input.readFully(chunk)) {
            consumed += 8
            val id = ascii(chunk, 0)
            val size = u32(chunk, 4)
            if (id == "data") {
                declared = size
                break
            }
            val body = ByteArray(size.toInt().coerceIn(0, MAX_CHUNK))
            if (body.size.toLong() != size) return null // absurd or corrupt chunk size
            if (!input.readFully(body)) return null
            consumed += body.size
            if (id == "fmt " && body.size >= 16) {
                channels = u16(body, 2)
                bits = u16(body, 14)
            }
            if (size and 1L == 1L) {
                input.read() // chunks are word-aligned; skip the pad byte
                consumed++
            }
        }

        if (declared < 0 || channels <= 0 || bits != 16) return null

        val available = fileBytes - consumed
        val dataBytes = if (declared in 1..available) declared else available
        val frames = dataBytes / (channels * 2)
        if (frames <= 0) return null

        val peaks = PeakBuckets(buckets, frames)
        val buf = ByteArray(BLOCK)
        while (!peaks.full) {
            val n = input.read(buf)
            if (n <= 0) break
            peaks.addPcm16(buf, n, channels)
        }
        return peaks.finish()
    }

    /** [InputStream.read] is free to return short reads; a header parser cannot live with that. */
    private fun InputStream.readFully(into: ByteArray): Boolean {
        var got = 0
        while (got < into.size) {
            val n = read(into, got, into.size - got)
            if (n <= 0) return false
            got += n
        }
        return true
    }

    private fun ascii(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.US_ASCII)

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)

    private const val BLOCK = 64 * 1024

    /** Bigger than any real `fmt `/`LIST` chunk: past this the file is corrupt, not just odd. */
    private const val MAX_CHUNK = 1 shl 20
}

/**
 * Collects PCM into [buckets] peak values, one column of the drawing each.
 *
 * Fed in whatever blocks the source produces — 64 kB off a file, or a codec's output buffer — and
 * finished once. [totalFrames] only has to be close: it sets how wide a bucket is, and being a
 * fraction of a percent out moves the right-hand edge by less than a pixel.
 */
class PeakBuckets(private val buckets: Int, totalFrames: Long) {

    private val peaks = FloatArray(buckets)

    // Frames per bucket as a float: at 44.1 kHz a ten-second take is 1050.0 of them, and rounding
    // that down walks the last fraction of a second off the right-hand edge.
    private val perBucket = totalFrames.toDouble() / buckets

    private var frameIndex = 0L
    private var peak = 0f
    private var bucket = 0
    private var seen = 0L

    /** True once every column has been filled — the caller can stop decoding. */
    val full: Boolean get() = bucket >= buckets

    /** [count] bytes of interleaved little-endian 16-bit PCM. */
    fun addPcm16(pcm: ByteArray, count: Int, channels: Int) {
        val stride = channels * 2
        var i = 0
        while (i + stride <= count && !full) {
            var frame = 0f
            for (c in 0 until channels) {
                val at = i + c * 2
                val s = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort()
                frame = max(frame, abs(s.toInt()) / 32768f)
            }
            advance(frame)
            i += stride
        }
    }

    /** [count] floats of interleaved PCM in `-1f..1f`, as a codec may hand back. */
    fun addPcmFloat(pcm: FloatArray, count: Int, channels: Int) {
        var i = 0
        while (i + channels <= count && !full) {
            var frame = 0f
            for (c in 0 until channels) frame = max(frame, abs(pcm[i + c]))
            advance(frame)
            i += channels
        }
    }

    /**
     * Mono is what Spark Plug records; anything else collapses to its loudest channel, which keeps
     * a stereo import from drawing at half height.
     */
    private fun advance(frame: Float) {
        peak = max(peak, frame)
        frameIndex++
        seen++
        // Close the bucket the moment this frame crosses its right edge.
        if (frameIndex >= (bucket + 1) * perBucket) {
            peaks[bucket] = peak
            peak = 0f
            bucket++
        }
    }

    /** The envelope, or null if nothing was ever fed in. */
    fun finish(): FloatArray? {
        if (seen == 0L) return null
        // A source that ran out early still owns the bucket it stopped in.
        if (bucket < buckets) peaks[bucket] = max(peaks[bucket], peak)
        return peaks
    }
}

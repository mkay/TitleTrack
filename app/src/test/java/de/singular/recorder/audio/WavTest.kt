package de.singular.recorder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WavTest {

    /** `RIFF` + `fmt ` + `data`, with no `LIST/INFO` between them. */
    private val HEADER_BYTES_NO_INFO = 44

    private fun file(dataBytes: Long, bpm: Float?, title: String? = null): ByteArray {
        val header = Wav.header(dataBytes = dataBytes, bpm = bpm, title = title)
        return header + ByteArray(dataBytes.toInt())
    }

    @Test
    fun `header round-trips format, length and tempo`() {
        val seconds = 3
        val dataBytes = (Wav.SAMPLE_RATE * Wav.BYTES_PER_FRAME * seconds).toLong()
        val bytes = file(dataBytes, bpm = 96f, title = "Riff idea")

        val info = Wav.readInfo(bytes, fileBytes = bytes.size.toLong())!!
        assertEquals(Wav.SAMPLE_RATE, info.sampleRate)
        assertEquals(Wav.CHANNELS, info.channels)
        assertEquals(Wav.BITS_PER_SAMPLE, info.bitsPerSample)
        assertEquals(dataBytes, info.dataBytes)
        assertEquals(3_000L, info.durationMs)
        assertEquals(96f, info.bpm!!, 0.01f)
    }

    @Test
    fun `a take with no tempo reports none`() {
        val bytes = file(dataBytes = 1_000, bpm = null)
        assertNull(Wav.readInfo(bytes, fileBytes = bytes.size.toLong())!!.bpm)
    }

    @Test
    fun `fractional tempo survives`() {
        val bytes = file(dataBytes = 1_000, bpm = 96.5f)
        assertEquals(96.5f, Wav.readInfo(bytes, fileBytes = bytes.size.toLong())!!.bpm!!, 0.01f)
    }

    /** Every chunk must start on an even offset, or strict readers refuse the file. */
    @Test
    fun `chunks stay word-aligned whatever the title's length`() {
        for (length in 1..8) {
            val title = "x".repeat(length)
            val header = Wav.header(dataBytes = 0, bpm = 100f, title = title)
            val at = String(header, Charsets.US_ASCII).indexOf("data")
            assertEquals("title length $length", 0, at % 2)
        }
    }

    /**
     * An interrupted write leaves a `data` size that overruns the file. The list of takes should
     * still show a duration, taken from what is actually there.
     */
    @Test
    fun `a truncated file falls back to its real length`() {
        val full = file(dataBytes = (Wav.SAMPLE_RATE * Wav.BYTES_PER_FRAME * 4).toLong(), bpm = null)
        val cut = full.copyOf(full.size / 2)
        val info = Wav.readInfo(cut, fileBytes = cut.size.toLong())!!
        val bytesPerSecond = Wav.SAMPLE_RATE.toLong() * Wav.BYTES_PER_FRAME
        val payload = cut.size - HEADER_BYTES_NO_INFO
        assertEquals(payload * 1_000L / bytesPerSecond, info.durationMs)
        assertEquals(payload.toLong(), info.dataBytes)
    }
}

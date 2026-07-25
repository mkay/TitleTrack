package de.singular.recorder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GainTest {

    /** Little-endian 16-bit PCM, as a WAV file holds it. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samples(buf: ByteArray): List<Int> =
        (buf.indices step 2).map {
            ((buf[it + 1].toInt() shl 8) or (buf[it].toInt() and 0xFF)).toShort().toInt()
        }

    @Test
    fun `peak mode lifts the loudest sample to full scale`() {
        val gain = Gain.linearFor(NormalizeMode.PEAK, peak = 0.25f, rms = 0.05f)
        assertEquals(4f, gain, 1e-4f)
        assertFalse(Gain.needsSoftClip(gain, 0.25f))
    }

    @Test
    fun `a take already at full scale is left alone`() {
        assertEquals(1f, Gain.linearFor(NormalizeMode.PEAK, peak = 1f, rms = 0.3f), 1e-6f)
    }

    @Test
    fun `silence does not blow up`() {
        assertEquals(1f, Gain.linearFor(NormalizeMode.PEAK, peak = 0f, rms = 0f), 1e-6f)
        assertEquals(1f, Gain.linearFor(NormalizeMode.LOUDNESS, peak = 0f, rms = 0f), 1e-6f)
    }

    @Test
    fun `loudness aims at the target rms and is capped`() {
        // -34 dBFS rms wants +20 dB to reach -14, which is past the ceiling.
        val quiet = Gain.linearFor(NormalizeMode.LOUDNESS, peak = 0.1f, rms = 0.02f)
        assertEquals(Gain.MAX_LOUDNESS_BOOST_DB, Gain.linearToDb(quiet), 1e-3f)

        // -20 dBFS rms is a +6 dB job, comfortably inside it.
        val near = Gain.linearFor(NormalizeMode.LOUDNESS, peak = 0.6f, rms = 0.1f)
        assertEquals(6f, Gain.linearToDb(near), 0.1f)
        assertTrue(Gain.needsSoftClip(near, 0.6f))
    }

    @Test
    fun `applying gain scales every sample in place`() {
        val buf = pcm(1000, -1000, 0, 500)
        Gain.applyPcm16(buf, 0, buf.size, gain = 2f, softClip = false)
        assertEquals(listOf(2000, -2000, 0, 1000), samples(buf))
    }

    @Test
    fun `a scaled block honours the offset and leaves the rest untouched`() {
        val buf = pcm(1000, 1000, 1000)
        Gain.applyPcm16(buf, 2, 2, gain = 3f, softClip = false)
        assertEquals(listOf(1000, 3000, 1000), samples(buf))
    }

    @Test
    fun `hard scaling clamps rather than wrapping`() {
        val buf = pcm(30000, -30000)
        Gain.applyPcm16(buf, 0, buf.size, gain = 4f, softClip = false)
        assertEquals(listOf(32767, -32768), samples(buf))
    }

    @Test
    fun `soft clipping stays inside full scale and keeps its sign`() {
        val buf = pcm(20000, -20000, 100)
        Gain.applyPcm16(buf, 0, buf.size, gain = 2f, softClip = true)
        val out = samples(buf)
        // 20000 doubled is well past the rail; bent, it lands just under it — and symmetrically.
        assertTrue(out[0] in 30000..32767)
        assertEquals(-out[0], out[1])
        // Below the knee the gain is still exactly the gain.
        assertEquals(200, out[2])
    }

    @Test
    fun `the meter reports peak and rms as fractions of full scale`() {
        val meter = Gain.Meter()
        meter.add(pcm(16384, -16384, 16384, -16384), 0, 8)
        assertEquals(0.5f, meter.peak, 1e-4f)
        assertEquals(0.5f, meter.rms, 1e-4f)
    }

    @Test
    fun `the meter ignores a trailing odd byte`() {
        val meter = Gain.Meter()
        val buf = pcm(8192, 0) + byteArrayOf(0x7F)
        meter.add(buf, 0, buf.size)
        assertTrue(abs(meter.peak - 0.25f) < 1e-4f)
    }
}

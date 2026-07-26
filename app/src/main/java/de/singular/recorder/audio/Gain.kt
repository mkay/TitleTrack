package de.singular.recorder.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * How much to lift a take, and what "loud enough" means.
 *
 * [PEAK] is the familiar normalize — scale until the loudest sample sits at full scale. It cannot
 * distort, and it cannot help a take that already touches the top once. [LOUDNESS] aims at the
 * *average* level instead, which is what the ear calls quiet, and so has to push some peaks past
 * full scale — where the soft clipper in [Gain.applyPcm16] catches them.
 */
enum class NormalizeMode { PEAK, LOUDNESS }

/**
 * Level maths for normalising a take: measure it, decide on a gain, apply it to 16-bit PCM.
 *
 * Nothing here knows about files. Title Track plays takes straight off storage through the system
 * player, so unlike a mixer's fader this gain has to be written into the audio itself — see
 * [de.singular.recorder.storage.RecordingStore.normalize] for where that happens.
 */
object Gain {

    /** Average level [NormalizeMode.LOUDNESS] aims for, in dBFS RMS. */
    const val TARGET_RMS_DB = -14f

    /**
     * Ceiling on a [NormalizeMode.LOUDNESS] boost, in dB. This one buys loudness with soft
     * clipping, so past a point the artefacts and the raised hiss outrun the benefit.
     */
    const val MAX_LOUDNESS_BOOST_DB = 18f

    /**
     * Ceiling on a [NormalizeMode.PEAK] boost, in dB. Peak mode cannot distort however far it goes,
     * so this is generous — it only stops a near-silent take (a muted pickup, a covered mic) from
     * being amplified into pure noise.
     */
    const val MAX_PEAK_BOOST_DB = 30f

    /** Below this much boost there is nothing worth rewriting a file for. */
    const val MIN_USEFUL_BOOST_DB = 0.5f

    /** Level above which [applyPcm16]'s soft clipper starts bending, when clipping is enabled. */
    private const val SOFT_KNEE = 0.8f

    private const val FULL_SCALE = 32768f

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    fun linearToDb(linear: Float): Float =
        if (linear <= 0f) Float.NEGATIVE_INFINITY else 20f * log10(linear)

    /**
     * The linear gain [mode] calls for on a take whose [peak] and [rms] are as measured (both
     * 0f..1f). Only ever boosts: a take that is already loud enough is left alone rather than
     * turned down, because turning it down would throw away resolution for nothing. Silence yields
     * 1f rather than a division blow-up.
     */
    fun linearFor(mode: NormalizeMode, peak: Float, rms: Float): Float {
        val (raw, maxBoostDb) = when (mode) {
            NormalizeMode.PEAK ->
                (if (peak <= 0f) 1f else 1f / peak) to MAX_PEAK_BOOST_DB

            NormalizeMode.LOUDNESS ->
                (if (rms <= 0f) 1f else dbToLinear(TARGET_RMS_DB - linearToDb(rms))) to
                    MAX_LOUDNESS_BOOST_DB
        }
        return raw.coerceIn(1f, dbToLinear(maxBoostDb))
    }

    /**
     * Whether applying [gain] to a take peaking at [peak] would overshoot full scale — i.e. whether
     * the soft clipper is needed. False for [NormalizeMode.PEAK] by construction, which keeps peak
     * mode an exact, undistorted scale.
     */
    fun needsSoftClip(gain: Float, peak: Float): Boolean = gain * peak > 1f

    /**
     * Scale [count] bytes of little-endian 16-bit PCM in [buf], from [offset], by [gain], in place.
     *
     * With [softClip] off, samples are simply scaled (and clamped, against rounding at the very top
     * of the scale). With it on, anything past [SOFT_KNEE] is bent through a tanh knee that
     * saturates smoothly towards full scale, so a loudness boost warms its transients over rather
     * than shattering them into hard-clipped edges.
     */
    fun applyPcm16(buf: ByteArray, offset: Int, count: Int, gain: Float, softClip: Boolean) {
        var i = offset
        val end = offset + count - 1
        while (i < end) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            val scaled = applySample(s, gain, softClip).toInt()
            buf[i] = (scaled and 0xFF).toByte()
            buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /** One sample, scaled the same way [applyPcm16] scales a block of them. */
    fun applySample(sample: Short, gain: Float, softClip: Boolean): Short {
        val x = (sample / FULL_SCALE) * gain
        val y = if (softClip) softClip(x) else x
        return (y * FULL_SCALE).toInt().coerceIn(-32768, 32767).toShort()
    }

    /** Linear below the knee, tanh above it — |output| stays below 1f for any input. */
    private fun softClip(x: Float): Float {
        val mag = abs(x)
        if (mag <= SOFT_KNEE) return x
        val over = (mag - SOFT_KNEE) / (1f - SOFT_KNEE)
        val bent = SOFT_KNEE + (1f - SOFT_KNEE) * tanh(over)
        return if (x < 0f) -bent else bent
    }

    /**
     * Running peak and RMS of a take, fed in whatever blocks the file arrives in.
     *
     * Streaming rather than measuring an array, because a take is a file on storage and a long one
     * has no business being held in memory whole just to find out how loud it is.
     */
    class Meter {
        private var maxSample = 0
        private var sumSquares = 0.0
        private var samples = 0L

        /** [count] bytes of little-endian 16-bit PCM from [offset]; a trailing odd byte is ignored. */
        fun add(buf: ByteArray, offset: Int, count: Int) {
            var i = offset
            val end = offset + count - 1
            while (i < end) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                val mag = abs(s)
                if (mag > maxSample) maxSample = mag
                sumSquares += s.toDouble() * s.toDouble()
                samples++
                i += 2
            }
        }

        /** Loudest sample seen, as a fraction of full scale. */
        val peak: Float get() = maxSample / FULL_SCALE

        /** Average level, as a fraction of full scale. */
        val rms: Float
            get() = if (samples == 0L) 0f else (sqrt(sumSquares / samples) / FULL_SCALE).toFloat()
    }
}

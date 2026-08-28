// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Notes, their frequencies, and how far a measured pitch sits from the nearest one.
 *
 * **There is no instrument setting here, and that is the point.** A tuner that snapped to the six
 * open strings of a guitar would need to know whether it was looking at a guitar or a bass, because
 * bass E1 and guitar E2 are both called E and an octave apart — and getting that wrong silently is
 * exactly the failure a tuner must not have. Snapping to the nearest *semitone* over the whole
 * range instead covers both instruments with one rule, names the octave it found, and never has to
 * be told what is plugged in.
 *
 * The range runs a whole tone below bass E1 to a whole tone above guitar E4, so a string being
 * brought up from slack still reads instead of going dead just when you need it.
 */
object Tuning {

    /** The reference the whole scale hangs off. Fixed: standard tuning is what this tuner is for. */
    const val A4_HZ = 440.0

    /** MIDI 69 is A4 by definition; every other note is derived from it. */
    private const val A4_MIDI = 69

    /** The ratio of one equal-tempered semitone. */
    private val SEMITONE = 2.0.pow(1.0 / 12.0)

    /** D1, a whole tone below a bass's low E — a string being brought up from slack still reads. */
    const val MIN_MIDI = 26

    /** F♯4, a whole tone above a guitar's high E. */
    const val MAX_MIDI = 66

    /** Half a semitone below [MIN_MIDI], which is where that note stops being the nearest one. */
    val MIN_HZ = hzOf(MIN_MIDI) * SEMITONE.pow(-0.5)

    /** Half a semitone above [MAX_MIDI], likewise. */
    val MAX_HZ = hzOf(MAX_MIDI) * SEMITONE.pow(0.5)

    /**
     * How close counts as in tune, in cents.
     *
     * Two cents is under half of what an ear can hear as a beat against a reference on these
     * strings, and it is reachable on the needle. The strobe resolves far finer than this — see
     * [Strobe] — so on that display the rings coming to a stop is the finer instrument, and this
     * threshold only drives the note name and its ring.
     */
    const val IN_TUNE_CENTS = 2.0

    private val NAMES =
        arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    /** One note of the chromatic scale, named the way it is spoken: letter plus octave. */
    data class Note(val midi: Int) {
        /** Letter and accidental, without the octave — the big glyph on both displays. */
        val name: String get() = NAMES[midi.mod(12)]

        /** Scientific pitch notation, so A4 is 4 and a bass's low E is 1. */
        val octave: Int get() = midi / 12 - 1

        /** What this note is in tune at. */
        val hz: Double get() = hzOf(midi)

        override fun toString(): String = "$name$octave"
    }

    /** A measured pitch, placed against the nearest note. */
    data class Reading(
        val hz: Double,
        val note: Note,
        /** Distance from [note], negative flat and positive sharp. Within ±50 by construction. */
        val cents: Double,
    ) {
        val inTune: Boolean get() = cents >= -IN_TUNE_CENTS && cents <= IN_TUNE_CENTS
    }

    /** The frequency of a MIDI note number, in equal temperament against [A4_HZ]. */
    fun hzOf(midi: Int): Double = A4_HZ * 2.0.pow((midi - A4_MIDI) / 12.0)

    /** Where a frequency falls on the MIDI scale, fractionally — 69.5 is halfway above A4. */
    fun midiOf(hz: Double): Double = A4_MIDI + 12.0 * log2(hz / A4_HZ)

    /**
     * The distance from [hz] to [targetHz] in cents: 1200 to the octave, so a doubling is exactly
     * 1200 and the figure stays linear where the frequencies do not.
     */
    fun centsBetween(hz: Double, targetHz: Double): Double = 1200.0 * log2(hz / targetHz)

    /**
     * Place [hz] against the nearest note, or null if that note falls outside
     * [MIN_MIDI]..[MAX_MIDI].
     *
     * Out of range is reported rather than clamped: a reading of 900 Hz is not a sharp F♯4, it is
     * something that is not a string being tuned, and the display should say nothing at all.
     */
    fun read(hz: Double): Reading? {
        if (hz <= 0.0) return null
        val midi = midiOf(hz).roundToInt()
        if (midi < MIN_MIDI || midi > MAX_MIDI) return null
        val note = Note(midi)
        return Reading(hz, note, centsBetween(hz, note.hz))
    }

    private fun log2(x: Double): Double = ln(x) / LN_2

    private val LN_2 = ln(2.0)
}

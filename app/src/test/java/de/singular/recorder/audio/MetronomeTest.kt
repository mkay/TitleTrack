// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The count-in's latency budget, which is the one number standing between a Bluetooth take and
 * being half a beat off its own grid.
 *
 * Measured on this project's phone with AirPods over A2DP: the device took **711 ms** to report a
 * timestamp for the static count-in track, and **591-893 ms** for the streamed one. The budget must
 * clear that with room, and the fraction it takes of the count-in is the whole of what decides it.
 *
 * This is a regression test in the literal sense. The budget was `nominalMs / 3` — 693 ms for a
 * one-bar count-in at 120 bpm — which loses to a 711 ms answer by eighteen milliseconds. Close
 * enough to pass a hurried test and fail in use, and the failure is silent: no compensation, and a
 * take that simply does not line up.
 */
class MetronomeTest {

    private val metronome = Metronome()

    /** One bar of four at [bpm], as [Metronome.countInMs] computes it. */
    private fun oneBarAt(bpm: Float): Long = metronome.countInMs(bpm, beatsPerBar = 4, bars = 1)

    @Test
    fun `a one-bar count-in leaves room for the slowest measurement seen`() {
        // 120 bpm is the tightest common case: a bar is 2 s, and everything slower has more room.
        val budget = metronome.latencyBudgetWithin(oneBarAt(120f))
        assertTrue(
            "a one-bar count-in at 120 bpm budgets ${budget}ms, which does not clear the 711ms " +
                "a Bluetooth device actually took",
            budget >= 900,
        )
    }

    @Test
    fun `the old one-third budget would not have cleared it`() {
        // Guards the fraction itself: /3 is what shipped, and what silently broke Bluetooth.
        assertTrue(oneBarAt(120f) / 3 < 711)
    }

    @Test
    fun `a fast count-in still budgets what it can rather than overrunning itself`() {
        // The budget is spent inside the count-in, so it can never exceed it — at 240 bpm the whole
        // bar is 1 s and the take must not be held past its own downbeat waiting for an answer.
        val nominal = oneBarAt(240f)
        assertTrue(metronome.latencyBudgetWithin(nominal) < nominal)
    }

    @Test
    fun `a long count-in is capped by the budget rather than growing with the bar`() {
        // Four bars at 40 bpm is 24 s; four fifths of that would be a preposterous thing to wait.
        val budget = metronome.latencyBudgetWithin(metronome.countInMs(40f, beatsPerBar = 4, bars = 4))
        assertTrue("budgeted ${budget}ms", budget <= 2_000)
    }
}

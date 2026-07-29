// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * A silent metronome: one dot per beat in the bar, the current one lit.
 *
 * Silent because the microphone is open — an audible click on a phone speaker ends up inside the
 * take. That leaves the eye to carry the tempo, and a row of dots does it better than the swinging
 * arm this replaced: the arm asked to be watched, and anything you have to watch while playing is
 * a thing you are not playing. Dots sit under the waveform and read from the edge of vision.
 *
 * Position in the bar is the point, not just the pulse. The lit dot walks the bar, so a glance
 * says *where* you are and not merely that a beat went past — which is what you need to come back
 * in after counting yourself out of a phrase. Beat one is larger and takes the accent colour.
 *
 * Driven from [beats] continuously rather than from beat events, so the decay is exact at any
 * tempo and cannot drift from the audio actually on disk.
 */
@Composable
fun BeatDots(
    beats: Float,
    beatsPerBar: Int,
    modifier: Modifier = Modifier,
    running: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    val offbeat = MaterialTheme.colorScheme.record
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val bar = beatsPerBar.coerceAtLeast(1)

    // 0 at the beat, rising towards 1 just before the next.
    val phase = beats - floor(beats)
    val current = Math.floorMod(floor(beats).toInt(), bar)
    // A short decay, snappy enough not to blur into the next beat at 200 bpm.
    val flash = if (running) (1f - phase * 4f).coerceIn(0f, 1f) else 0f

    Box(modifier.fillMaxWidth().height(DotArea)) {
        Canvas(Modifier.fillMaxWidth().height(DotArea)) {
            val spacing = size.width / bar
            val base = size.height * 0.34f
            for (i in 0 until bar) {
                val lit = if (i == current) flash else 0f
                val downbeat = i == 0
                // Off-beats stay visible when unlit so the bar reads as a bar, not as one dot.
                val radius = base * (if (downbeat) 1.15f else 1f) * (0.70f + 0.30f * lit)
                drawCircle(
                    color = lerp(dim, if (downbeat) accent else offbeat, lit),
                    radius = radius,
                    center = Offset(spacing * (i + 0.5f), size.height / 2f),
                )
            }
        }
    }
}

/** The count-in, as the number of clicks still to come — the one thing worth reading in that half-second. */
@Composable
fun CountInDots(beatsLeft: Int, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    Box(modifier.height(24.dp)) {
        Canvas(Modifier.height(24.dp).size(width = (beatsLeft.coerceAtLeast(1) * 22).dp, height = 24.dp)) {
            val r = 6f
            for (i in 0 until beatsLeft) {
                drawCircle(
                    if (i == 0) accent else dim,
                    radius = r,
                    center = Offset(r + i * 22f, size.height / 2f),
                )
            }
        }
    }
}

/** Tall enough for a big dot and the space it needs not to look crowded. */
private val DotArea = 44.dp

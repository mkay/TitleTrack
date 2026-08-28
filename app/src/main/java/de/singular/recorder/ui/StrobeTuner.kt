// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

import de.singular.recorder.audio.Strobe
import de.singular.recorder.audio.Tuning
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Two rings of blocks that stand still when the string is in tune, and creep when it is not.
 *
 * **Nothing here decides how fast to turn.** The angles come from [Strobe], which measures the phase
 * between the string and a reference running at the note it should be — so a ring that has stopped
 * has stopped because the two frequencies are the same, not because a threshold was crossed and
 * something was told to stop. That is the whole reason a strobe can be trusted below the precision
 * of anything with a needle: there is no measurement of "how far off" in the loop at all.
 *
 * **Why the inner ring turns twice as fast.** It runs against the string's second harmonic, so its
 * phase advances at double the rate by physics rather than by multiplication. It is the vernier: the
 * outer ring settles first and looks stopped, and the inner one is still visibly creeping. When both
 * stand still there is nothing left to correct that this display can see.
 *
 * The rings are advanced on the frame clock between readings, using the rate [Strobe] measured.
 * Audio arrives about forty times a second and the screen redraws sixty to a hundred and twenty
 * times; stepping the angle only when a reading lands would show that difference as a stutter, on
 * the one display whose entire content is smooth motion.
 *
 * **Two things hold it still that the measurement alone would not.** With no note being played the
 * detectors lock onto whatever the room offers, and their phase wanders; left to run, the rings spin
 * wildly at a display that has nothing to say, which is both wrong and hypnotic. So they move only
 * while a note is actually being heard. And the rate is capped: fifty cents out at E2 is two and a
 * half turns a second, which is not a strobe any more but a blur. Past about twenty-five cents the
 * cap holds the rings at a fast, readable creep — a magnitude the needle reports properly anyway,
 * this display's job being the last few cents where the needle has run out of resolution.
 */
@Composable
fun StrobeTuner(
    rings: Strobe.Rings?,
    reading: Tuning.Reading?,
    modifier: Modifier = Modifier,
) {
    // Carried on the frame clock, then re-anchored whenever a fresh angle arrives.
    var outer by remember { mutableFloatStateOf(0f) }
    var inner by remember { mutableFloatStateOf(0f) }

    // Deliberately not conditioned on there being a confident *note*. Naming a note takes a full
    // window and a clarity of 0.8, and a plucked string stops meeting that about half a second after
    // the attack — while the string is still ringing, and while the strobe can still see its target
    // perfectly well. Tying the rings to the note name switched them off exactly when they were
    // wanted. The strobe has its own measure of whether it has signal, and that is what to use.
    val moving = rings != null && rings.strength > MIN_STRENGTH

    // One loop for the life of the display, reading whatever the latest measurement is. Restarting
    // it per reading would tear down and rebuild a coroutine forty times a second.
    val latest by rememberUpdatedState(if (moving) rings else null)
    LaunchedEffect(Unit) {
        var last = 0L
        var anchored: Strobe.Rings? = null
        while (true) {
            withFrameNanos { now ->
                val r = latest
                when {
                    r == null -> Unit

                    // A fresh measurement: take its angles outright. Corrections are tiny and
                    // forty times a second, so this reads as the ring simply being where the
                    // audio says it is rather than as a jump.
                    r !== anchored -> {
                        outer = r.fundamental
                        inner = r.octave
                        anchored = r
                    }

                    // Between measurements, carry the rings at the rate that was measured.
                    last != 0L -> {
                        val seconds = (now - last) / 1_000_000_000f
                        val rate = r.turnsPerSecond.coerceIn(-MAX_TURNS_PER_SECOND, MAX_TURNS_PER_SECOND)
                        outer += rate * TAU * seconds
                        inner += rate * 2f * TAU * seconds
                    }
                }
                last = now
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val dim = scheme.onSurfaceVariant
    val inTune = reading?.inTune == true

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val colour = if (moving) accent else dim.copy(alpha = 0.5f)
            drawRing(
                angle = outer,
                blocks = OUTER_BLOCKS,
                duty = OUTER_DUTY,
                outerFraction = 0.94f,
                innerFraction = 0.78f,
                colour = colour,
            )
            // A deeper shade rather than a fainter one. The inner ring is the fast one and the
            // harder to follow, so thinning it with alpha took contrast from where contrast is most
            // needed; darkening keeps it solid while still telling the two rings apart.
            drawRing(
                angle = inner,
                blocks = INNER_BLOCKS,
                duty = INNER_DUTY,
                outerFraction = 0.73f,
                innerFraction = 0.60f,
                colour = lerp(colour, Color.Black, 0.32f),
            )
        }

        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = reading?.note?.name ?: "—",
                fontSize = 88.sp,
                fontWeight = FontWeight.Light,
                color = if (inTune) accent else scheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One ring of blocks, rotated to [angle].
 *
 * Blocks rather than a continuous band because a smooth ring has no features to follow: rotation is
 * only visible if there is something whose movement the eye can lock onto, which is what a real
 * strobe's shutter pattern is for.
 */
private fun DrawScope.drawRing(
    angle: Float,
    blocks: Int,
    duty: Float,
    outerFraction: Float,
    innerFraction: Float,
    colour: Color,
) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f
    val ro = radius * outerFraction
    val ri = radius * innerFraction
    val step = TAU / blocks
    val width = step * duty

    for (i in 0 until blocks) {
        val start = angle + i * step
        val path = Path().apply {
            moveTo(centre.x + ro * cos(start), centre.y + ro * sin(start))
            lineTo(centre.x + ro * cos(start + width), centre.y + ro * sin(start + width))
            lineTo(centre.x + ri * cos(start + width), centre.y + ri * sin(start + width))
            lineTo(centre.x + ri * cos(start), centre.y + ri * sin(start))
            close()
        }
        drawPath(path, colour)
    }
}

private const val TAU = 2f * PI.toFloat()


/**
 * The two rings are counted and proportioned differently, and by eye rather than by rule.
 *
 * The outer one is the coarse ring, the one glanced at from a distance, and it reads best as a few
 * broad blocks. The inner one is the vernier — finer, more of it, and turning twice as fast — and
 * it reads best left narrow: thickening it as well only made the fast ring the busy one.
 */
private const val OUTER_BLOCKS = 14
private const val OUTER_DUTY = 0.68f
private const val INNER_BLOCKS = 16
private const val INNER_DUTY = 0.5f

/**
 * Below this the rings hold still: there is no note left, only the room.
 *
 * Low, because of what it measures — the level of the *fundamental alone* at the microphone, which
 * on a quiet instrument, or any low string through a phone, is a small number even while the note is
 * perfectly audible. Set an order of magnitude higher it demanded that a string be hit hard before
 * anything moved, and it went on demanding it as the note rang out.
 *
 * What stops the rings spinning at an empty room is not this but the target: the detectors only run
 * once a note has been named, and against that note. Silence has none to offer.
 */
private const val MIN_STRENGTH = 0.0001f

/**
 * The fastest the rings are allowed to turn. Beyond this a ring stops reading as rotation and starts
 * reading as flicker, and the magnitude it would have been conveying is the needle's job.
 */
private const val MAX_TURNS_PER_SECOND = 1.5f


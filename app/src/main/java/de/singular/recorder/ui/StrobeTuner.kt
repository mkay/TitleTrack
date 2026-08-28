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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
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

                    // A fresh measurement, slow enough to draw honestly: take its angles
                    // outright. Corrections are tiny and forty times a second, so this reads as
                    // the ring being where the audio says it is rather than as a jump.
                    r !== anchored && abs(r.turnsPerSecond) <= limitFor(last, now) -> {
                        outer = r.fundamental
                        inner = r.octave
                        anchored = r
                    }

                    // Otherwise carry the rings at the measured rate, held to what the screen can
                    // actually show. Both are scaled by the same factor, so the inner ring stays
                    // exactly twice the outer: that relationship is the vernier, and a clamp that
                    // broke it would leave two rings whose difference meant nothing.
                    last != 0L -> {
                        val seconds = (now - last) / 1_000_000_000f
                        val limit = limitFor(last, now)
                        val rate = r.turnsPerSecond.coerceIn(-limit, limit)
                        outer += rate * TAU * seconds
                        inner += rate * 2f * TAU * seconds
                        anchored = r
                    }
                }
                last = now
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val dim = scheme.onSurfaceVariant
    // The same holding and fading as the needle, so the two faces end a note the same way — see
    // [NeedleTuner]. The rings have their own resting state and stay drawn; it is the note over
    // them that would otherwise blink out.
    var held by remember { mutableStateOf<Tuning.Reading?>(null) }
    if (reading != null) held = reading
    val shown = reading ?: held
    val presence by animateFloatAsState(
        targetValue = if (reading != null) 1f else 0f,
        animationSpec = tween(durationMillis = if (reading != null) 180 else 650),
        label = "presence",
    )
    val inTune = shown?.inTune == true

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
                text = shown?.note?.name.orEmpty(),
                fontSize = 88.sp,
                fontWeight = FontWeight.Light,
                color = (if (inTune) accent else scheme.onSurface).copy(alpha = presence),
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
 * The fastest the rings may turn and still be read, in turns per second, given a frame [from]..[to].
 *
 * A ring of blocks is a sampled thing, and past a certain speed it aliases: the blocks advance far
 * enough between frames that the eye pairs each one with its neighbour and the ring appears to slow,
 * stop, or run backwards. A strobe whose direction cannot be trusted is worse than no strobe, since
 * direction is the half of the reading that tells you which way to turn the peg.
 *
 * So the limit is derived rather than picked: no ring may advance more than [READABLE_BLOCK_STEP] of
 * one block per frame. **The binding ring is the inner one** — it has more blocks and turns at twice
 * the rate, so it aliases at well under half the speed the outer one could manage, and a single
 * fixed cap chosen for the outer ring would have left the inner one reversing.
 *
 * Derived per frame, from the frame actually delivered, so a 120 Hz screen is allowed twice what a
 * 60 Hz one is instead of both being held to a guess.
 */
private fun limitFor(from: Long, to: Long): Float {
    val seconds = (to - from) / 1_000_000_000f
    if (from == 0L || seconds <= 0f) return Float.MAX_VALUE
    val outer = READABLE_BLOCK_STEP / (OUTER_BLOCKS * seconds)
    val inner = READABLE_BLOCK_STEP / (INNER_BLOCKS * 2f * seconds)
    return min(outer, inner)
}

/**
 * How far a ring may turn between frames, as a fraction of one block.
 *
 * A third is comfortably under the half where direction becomes ambiguous, and leaves the motion
 * reading as motion rather than as a sequence of positions.
 */
private const val READABLE_BLOCK_STEP = 1f / 3f


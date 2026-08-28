// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import de.singular.recorder.audio.Tuning
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A moving-coil meter: a needle over an arc marked in cents, the note it is measuring in the middle.
 *
 * **The needle is damped, and that is not decoration.** Readings arrive twenty times a second and
 * each is its own best guess; a needle driven straight from them twitches, and a twitching needle
 * cannot be read at the exact moment you most need it — while a peg is turning. A real meter's
 * pointer has mass and a spring, and settles rather than jumps. This is the same arrangement as a
 * critically damped spring: fast, and with no overshoot, because a needle that swung past the
 * centre and came back would say "sharp" for an instant on its way to saying "in tune".
 *
 * The scale is ±50 cents because that is the whole of what a note *is* — half a semitone either way
 * and the nearest note has changed, and [Tuning] would be naming a different one. So the needle
 * never has anywhere off-scale to go, and the ends of the arc mean something exact rather than
 * being where the drawing stopped.
 */
@Composable
fun NeedleTuner(
    reading: Tuning.Reading?,
    modifier: Modifier = Modifier,
) {
    val cents = (reading?.cents ?: 0.0).coerceIn(-HALF_SCALE, HALF_SCALE).toFloat()

    // Critically damped: no bounce, because overshoot on a tuner is a lie in the wrong direction.
    val swing by animateFloatAsState(
        targetValue = cents,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "needle",
    )

    val scheme = MaterialTheme.colorScheme
    val marks = scheme.onSurfaceVariant
    val ink = scheme.onSurface
    val accent = scheme.primary
    val inTune = reading?.inTune == true
    val live = reading != null

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawFace(marks = marks, accent = accent, inTuneZone = inTune, live = live)
            if (live) drawNeedle(swing, ink)
            drawArrows(swing, live, accent, marks)
        }

        // The note, and how far off it is, in the well below the arc where the needle cannot
        // reach. Text rather than drawn glyphs: this is the one part a font does better.
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = reading?.note?.name ?: "\u2014",
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = if (inTune) accent else ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (reading == null) "" else "%+.1f".format(reading.cents),
                style = MaterialTheme.typography.titleMedium,
                color = if (inTune) accent else scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The ground both faces are drawn on: fills the space it is given, and centres the instrument in it
 * at a fixed shape.
 *
 * Two layers rather than one, because they want different things. The panel wants to be as large as
 * the screen allows, so that the tuner reads as an instrument rather than a card floating in a lot
 * of nothing. The meter on it does not: a needle sweep or a pair of rings has a size at which it is
 * legible, and past that it only gets emptier in the middle. So the ground stretches and the
 * instrument does not.
 */
@Composable
fun TunerFacePanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(
        modifier
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.waveformPanel),
        contentAlignment = Alignment.Center,
    ) {
        // Sized from whichever constraint binds, so a short screen shrinks the instrument rather
        // than letting it run off the panel.
        Box(
            Modifier
                .width(min(maxWidth, maxHeight * TunerFaceAspect))
                .aspectRatio(TunerFaceAspect),
            content = content,
        )
    }
}

/** The dial: the arc, the ticks, and the band that marks in tune. */
private fun DrawScope.drawFace(
    marks: Color,
    accent: Color,
    inTuneZone: Boolean,
    live: Boolean,
) {
    val pivot = pivot()
    val radius = radius()

    // The arc the needle sweeps. Drawn as a thin line rather than the reference's block segments:
    // segmented, it reads as a scale of discrete steps, and this measurement is continuous.
    val sweep = Path()
    var first = true
    var c = -HALF_SCALE
    while (c <= HALF_SCALE + 0.001) {
        val p = pointAt(pivot, radius, c.toFloat())
        if (first) sweep.moveTo(p.x, p.y) else sweep.lineTo(p.x, p.y)
        first = false
        c += 1.0
    }
    drawPath(sweep, marks.copy(alpha = 0.35f), style = Stroke(width = 1.5.dp.toPx()))

    // The in-tune band, drawn on the face rather than only signalled by colour: it gives the
    // needle somewhere to *arrive*, which a single centre line does not.
    val band = Path()
    first = true
    c = -Tuning.IN_TUNE_CENTS
    while (c <= Tuning.IN_TUNE_CENTS + 0.001) {
        val p = pointAt(pivot, radius, c.toFloat())
        if (first) band.moveTo(p.x, p.y) else band.lineTo(p.x, p.y)
        first = false
        c += 0.5
    }
    drawPath(
        band,
        if (inTuneZone) accent else marks.copy(alpha = 0.5f),
        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
    )

    for (tick in -5..5) {
        val at = tick * 10f
        val major = tick == 0
        val long = major || tick % 5 == 0
        val inner = radius - (if (long) TICK_LONG else TICK_SHORT).toPx()
        val a = pointAt(pivot, radius, at)
        val b = pointAt(pivot, inner, at)
        drawLine(
            color = if (major) marks else marks.copy(alpha = if (live) 0.7f else 0.45f),
            start = a,
            end = b,
            strokeWidth = (if (major) 3f else 1.5f).dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** The pointer, pivoting from below the face as a meter's does. */
private fun DrawScope.drawNeedle(cents: Float, ink: Color) {
    val pivot = pivot()
    val tip = pointAt(pivot, radius() + NEEDLE_OVERSHOOT.toPx(), cents)
    val tail = pointAt(pivot, -NEEDLE_TAIL.toPx(), cents)
    drawLine(
        color = ink,
        start = tail,
        end = tip,
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(ink, radius = 4.dp.toPx(), center = pivot)
}

/**
 * Which way to turn the peg, lit by how far off the note is.
 *
 * **These point the way to go, not the way things are.** The obvious arrangement — light the left
 * arrow when the note is flat, because that is the side the needle is on — makes the arrows a second
 * copy of the needle, and a redundant one, since the needle has already said which side by pointing
 * there. Pointing at the correction instead adds something: sharp lights the *left* arrow, meaning
 * come down. The needle says where you are, the arrows say where to go, and neither repeats the
 * other.
 */
private fun DrawScope.drawArrows(cents: Float, live: Boolean, accent: Color, marks: Color) {
    val lit = (abs(cents) / 12f).coerceIn(0f, 1f)
    val y = size.height * 0.80f
    val inset = size.width * 0.11f
    val w = size.width * 0.045f
    val h = size.height * 0.055f

    fun triangle(x: Float, pointingLeft: Boolean, on: Boolean) {
        val dir = if (pointingLeft) -1f else 1f
        val path = Path().apply {
            moveTo(x + dir * w, y)
            lineTo(x - dir * w * 0.5f, y - h / 2)
            lineTo(x - dir * w * 0.5f, y + h / 2)
            close()
        }
        drawPath(
            path,
            if (on) accent.copy(alpha = 0.35f + 0.65f * lit) else marks.copy(alpha = 0.18f),
        )
    }

    triangle(inset, pointingLeft = true, on = live && cents > Tuning.IN_TUNE_CENTS)
    triangle(size.width - inset, pointingLeft = false, on = live && cents < -Tuning.IN_TUNE_CENTS)
}

/** Where the needle is hinged: below the face, so the sweep across the arc is gentle. */
private fun DrawScope.pivot() = Offset(size.width / 2f, size.height * 0.94f)

private fun DrawScope.radius() = size.height * 0.78f

/** A point [cents] along the arc, at [radius] from [pivot]. Straight up is dead in tune. */
private fun pointAt(pivot: Offset, radius: Float, cents: Float): Offset {
    val angle = (cents / HALF_SCALE.toFloat()) * HALF_SWEEP_RADIANS - PI.toFloat() / 2f
    return Offset(
        pivot.x + radius * cos(angle),
        pivot.y + radius * sin(angle),
    )
}

/**
 * The shape of both faces.
 *
 * Shared, and shared deliberately: the two are swapped by a control the user is holding, and a face
 * that changed height would move that control out from under their thumb. It is a compromise — the
 * needle's arc would rather be wider and the strobe's rings would rather be square — and a steady
 * layout is worth more than either preference.
 */
const val TunerFaceAspect = 1.2f

/** Half the scale, in cents: past this the nearest note has changed and the reading is another. */
private const val HALF_SCALE = 50.0

/** Half the arc the needle sweeps. 38° either way is wide enough to read a cent or two of movement. */
private const val HALF_SWEEP_RADIANS = 38f * PI.toFloat() / 180f

private val TICK_LONG = 14.dp
private val TICK_SHORT = 8.dp
private val NEEDLE_OVERSHOOT = 6.dp
private val NEEDLE_TAIL = 14.dp

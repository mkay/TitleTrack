package de.singular.recorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A silent metronome: the classic wedge with a swinging arm, plus a flash on each beat.
 *
 * Silent because the microphone is open — an audible click on a phone speaker ends up inside the
 * take. That makes the swing carry the tempo on its own, so the arm is driven from [beats]
 * continuously rather than being animated on beat events: the eye reads a moving arm long before
 * it registers a flash, and a flash alone gives no warning of *when* the next beat lands.
 *
 * The arm crosses centre on the beat and is at full deflection between beats — one side per beat,
 * like a real one at half its rate. Beat 1 of the bar flashes in the accent colour and larger.
 */
@Composable
fun VisualMetronome(
    beats: Float,
    beatsPerBar: Int,
    modifier: Modifier = Modifier,
    running: Boolean = true,
) {
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    val armColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val accent = MaterialTheme.colorScheme.primary

    // 0 at the beat, rising to 1 halfway between: the arm is at centre on the beat.
    val phase = beats - floor(beats)
    val beatIndex = floor(beats).toInt()
    val onDownbeat = beatsPerBar > 0 && Math.floorMod(beatIndex, beatsPerBar) == 0
    // Which side to swing to alternates every beat, so a full swing spans two.
    val direction = if (Math.floorMod(beatIndex, 2) == 0) 1f else -1f
    val swing = sin(PI * phase).toFloat() * direction
    // A short decay after each beat, snappy enough not to blur into the next at 200 bpm.
    val flash = if (running) (1f - phase * 6f).coerceIn(0f, 1f) else 0f

    Box(modifier.size(width = 108.dp, height = 132.dp)) {
        Canvas(Modifier.size(width = 108.dp, height = 132.dp)) {
            val w = size.width
            val h = size.height
            val pivot = Offset(w / 2f, h * 0.94f)

            // The body: a wedge, drawn as an outline so it never competes with the arm.
            val body = Path().apply {
                moveTo(w * 0.30f, h * 0.06f)
                lineTo(w * 0.70f, h * 0.06f)
                lineTo(w * 0.92f, h * 0.97f)
                lineTo(w * 0.08f, h * 0.97f)
                close()
            }
            drawPath(body, outline, style = Stroke(width = 2.5f))

            // The arm, pivoting at the base.
            val maxAngle = 26.0 * PI / 180.0
            val angle = maxAngle * swing
            val length = h * 0.80f
            val tip = Offset(
                x = pivot.x + (sin(angle) * length).toFloat(),
                y = pivot.y - (cos(angle) * length).toFloat(),
            )
            val lit = lerp(armColor, if (onDownbeat) accent else RecordRed, flash)
            drawLine(lit, start = pivot, end = tip, strokeWidth = 4f)

            // The weight, and the beat flash at the tip.
            val weightAt = Offset(
                x = pivot.x + (sin(angle) * length * 0.55f).toFloat(),
                y = pivot.y - (cos(angle) * length * 0.55f).toFloat(),
            )
            drawCircle(lit, radius = w * 0.075f, center = weightAt)
            val tipRadius = w * (if (onDownbeat) 0.10f else 0.07f) * (0.55f + 0.45f * flash)
            drawCircle(lit, radius = tipRadius, center = tip)
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

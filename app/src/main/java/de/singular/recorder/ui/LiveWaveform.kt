package de.singular.recorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.log10
import kotlin.math.max

/**
 * The last few seconds of input, scrolling right to left as they are captured.
 *
 * A rolling window rather than the whole take: at 43 blocks a second a four-minute take is ten
 * thousand columns, and squeezing those into a phone's width gives a solid block that says nothing
 * about what you just played. Eight seconds is about a phrase, which is the span you are actually
 * listening back over in your head while playing. The whole take gets drawn properly in the player,
 * once there is a file to read it from.
 *
 * Scrolling is driven from the frame clock rather than from captured blocks, so it moves at a
 * steady 20 columns a second whatever the capture rate does — a waveform that stutters reads as a
 * recording that is stuttering. Each column keeps the loudest level seen while it was being
 * filled, for the same reason the player draws peaks: an average would smooth away exactly the
 * transient you are looking for.
 */
@Composable
fun LiveWaveform(
    level: Float,
    running: Boolean,
    hasTake: Boolean,
    modifier: Modifier = Modifier,
    monitoring: Boolean = false,
    columns: Int = COLUMNS,
) {
    val history = remember(columns) { FloatArray(columns) }
    // The array is mutated in place, so Compose cannot see it change. This is what it watches.
    var version by remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(level)

    // A discarded or restarted take should not leave its shape behind for the next one to start
    // on top of. Ending one keeps it — that is the take you just played, worth a last look.
    LaunchedEffect(hasTake) {
        if (!hasTake) {
            history.fill(0f)
            version++
        }
    }

    // A take starts on a clean canvas, not on the tail of what the room sounded like beforehand.
    LaunchedEffect(running) {
        if (running) {
            history.fill(0f)
            version++
        }
    }

    LaunchedEffect(running, monitoring, columns) {
        if (!running && !monitoring) return@LaunchedEffect
        var peak = 0f
        var columnStartedAt = 0L
        while (true) {
            withFrameMillis { now ->
                peak = max(peak, latest)
                if (columnStartedAt == 0L) columnStartedAt = now
                if (now - columnStartedAt >= COLUMN_MS) {
                    System.arraycopy(history, 1, history, 0, history.size - 1)
                    history[history.size - 1] = peak
                    peak = 0f
                    columnStartedAt = now
                    version++
                }
            }
        }
    }

    // Muted while only listening: the same shape, plainly not being kept. The difference has to be
    // legible at a glance, because "am I recording?" is the one question this screen must never
    // leave ambiguous.
    val fill = if (running) {
        MaterialTheme.colorScheme.primary
    } else if (monitoring) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val clipped = MaterialTheme.colorScheme.record
    val panel = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)
    val zeroLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)

    Canvas(
        modifier
            .clip(ControlShape)
            .background(panel),
    ) {
        version // read, so a new column redraws this
        val mid = size.height / 2

        // The zero line runs the full width whether or not anything has been recorded yet: it is
        // what says "your take appears here" to an otherwise blank screen, and once a take is
        // running it is the axis the bars are read against.
        drawLine(
            color = zeroLine,
            start = Offset(0f, mid),
            end = Offset(size.width, mid),
            strokeWidth = 1.dp.toPx(),
        )

        val step = size.width / history.size
        val barWidth = max(1f, step * 0.6f)
        for (i in history.indices) {
            val bar = amplitudeToHeight(history[i])
            if (bar <= 0f) continue // silence is the zero line, already drawn
            val x = i * step + (step - barWidth) / 2
            val half = max(1f, bar * mid * 0.92f)
            drawRect(
                color = if (history[i] > CLIP) clipped else fill,
                topLeft = Offset(x, mid - half),
                size = Size(barWidth, half * 2),
            )
        }
    }
}

/**
 * Linear amplitude to bar height over a 60 dB window — the same scaling as [LevelMeter].
 *
 * A guitar picked at a sensible level sits around −20 dBFS, which drawn linearly is a tenth of the
 * height and looks like a fault rather than a take.
 */
private fun amplitudeToHeight(level: Float): Float {
    if (level <= 0f) return 0f
    val db = 20f * log10(max(level, 1e-6f))
    return ((db + 60f) / 60f).coerceIn(0f, 1f)
}

/** Loud enough to be worth warning about, matching the level meter's own threshold. */
private const val CLIP = 0.95f

/** 50 ms a column: 20 a second, so [COLUMNS] of them hold eight seconds. */
private const val COLUMN_MS = 50L
private const val COLUMNS = 160

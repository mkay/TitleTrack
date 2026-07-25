package de.singular.recorder.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

/** `1:23` — or `12:03.4` while recording, where the tenths show that it is still running. */
fun formatDuration(ms: Long, tenths: Boolean = false): String {
    val totalSeconds = ms / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (tenths) String.format(Locale.US, "%d:%02d.%d", minutes, seconds, (ms % 1_000) / 100)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000f)
    bytes >= 1_000 -> "${bytes / 1_000} kB"
    else -> "$bytes B"
}

fun formatDate(epochMs: Long): String =
    if (epochMs <= 0) "" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        .format(Date(epochMs))

/**
 * Where the beat is, as a continuous count since the take began — 2.5 means halfway between the
 * third and fourth beat.
 *
 * The recorder reports its elapsed time once per captured block (~23 ms), which is accurate but
 * steps; interpolating from the wall clock between reports is what makes the pendulum swing rather
 * than tick along in stages. It re-anchors on every report, so the beat cannot drift away from the
 * audio actually on disk — the clock only ever fills the gap between two truths.
 */
@Composable
fun rememberBeatPosition(elapsedMs: Long, running: Boolean, bpm: Int): Float {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        while (running) withFrameMillis { frame = it }
    }
    val anchorMs = remember(elapsedMs) { elapsedMs }
    val anchorAt = remember(elapsedMs) { SystemClock.elapsedRealtime() }
    val ms = if (running) {
        frame // read, so a new frame recomposes this
        anchorMs + (SystemClock.elapsedRealtime() - anchorAt)
    } else {
        elapsedMs
    }
    return ms / 60_000f * bpm.coerceAtLeast(1)
}

/**
 * Input level, as a horizontal bar.
 *
 * Scaled in decibels over a 60 dB floor rather than linearly: a guitar picked at a sensible level
 * sits around −20 dBFS, which on a linear meter is a tenth of the bar and looks like a problem.
 */
@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier, active: Boolean = true) {
    // Smoothed, because the raw block peak at ~43 updates a second reads as flicker rather than
    // as level. Short enough that a transient still visibly moves the bar.
    val shown by animateFloatAsState(
        targetValue = amplitudeToBar(level),
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "level",
    )

    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fill = if (active) MaterialTheme.colorScheme.primary else track

    Box(modifier.fillMaxWidth().height(8.dp).clip(ControlShape)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRect(track, size = size)
            val w = size.width * shown.coerceIn(0f, 1f)
            // The top of the range is where clipping lives; colour it as the warning it is.
            drawRect(if (shown > 0.95f) RecordRed else fill, size = Size(w, size.height))
        }
    }
}

/** Linear amplitude (0..1) to bar fraction, over a 60 dB window. */
private fun amplitudeToBar(level: Float): Float {
    if (level <= 0f) return 0f
    val db = 20f * log10(max(level, 1e-6f))
    return ((db + 60f) / 60f).coerceIn(0f, 1f)
}

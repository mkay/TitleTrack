package de.singular.recorder.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** `1:23` — or `12:03.4` while recording, where the tenths show that it is still running. */
fun formatDuration(ms: Long, tenths: Boolean = false): String {
    val totalSeconds = ms / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (tenths) String.format(Locale.US, "%d:%02d.%d", minutes, seconds, (ms % 1_000) / 100)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/** `0:02.40` — hundredths, for the trim edges, where a tenth is coarser than the nudge step. */
fun formatPrecise(ms: Long): String = String.format(
    Locale.US,
    "%d:%02d.%02d",
    ms / 60_000,
    ms / 1_000 % 60,
    ms % 1_000 / 10,
)

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000f)
    bytes >= 1_000 -> "${bytes / 1_000} kB"
    else -> "$bytes B"
}

/**
 * `WAV`, `M4A` — a file's format, from its name.
 *
 * Worth saying out loud on every take: the folder is the user's own, so it holds imports as well as
 * recordings, and what a file *is* decides what can be done to it (normalising an import writes a
 * new WAV rather than touching the original). Empty for a name with no extension to read.
 */
fun formatKind(name: String): String =
    name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase() ?: ""

fun formatDate(epochMs: Long): String =
    if (epochMs <= 0) "" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        .format(Date(epochMs))

/** One text field and two buttons: what naming a folder, a take or a rename all come down to. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = ControlShape,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One number, typed — for a value that a slider can only get near.
 *
 * A fader is right for finding a level by ear and wrong for saying 110: the last few pixels of a
 * track are worth several bpm, and a figure you already know should not have to be hunted for. So
 * every value beside a slider opens this, in the way the record screen's tempo has always worked.
 *
 * Nonsense commits nothing: a field that will not parse leaves the value where it was rather than
 * snapping it to a bound, which is what makes it safe to clear the field while typing.
 */
@Composable
fun NumberDialog(
    title: String,
    initial: String,
    unit: String,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
    /** What to put it back to, where there is such a thing, and what to call that. */
    defaultLabel: String? = null,
    onDefault: (() -> Unit)? = null,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun commit() {
        text.trim().replace(',', '.').toFloatOrNull()?.let(onConfirm)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = ControlShape,
                suffix = { Text(unit) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { commit() }) { Text("Set") } },
        // The way back to the default lives here rather than on the control itself. A double tap on
        // the slider was tried and is not reliable enough to keep: Material's slider owns that
        // gesture, and an affordance that works sometimes is worse than one that is simply written
        // down where the value is already being edited.
        dismissButton = {
            Row {
                if (onDefault != null && defaultLabel != null) {
                    TextButton(onClick = {
                        onDefault()
                        onDismiss()
                    }) { Text(defaultLabel) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

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
 * Linear amplitude (0f..1f) to a fraction of the height available, over a 60 dB window.
 *
 * Every level in this app is drawn through here — the meter, the live waveform, the player's
 * waveform — because they are all pictures of the same thing and must agree. A take peaking at
 * −15 dBFS is a *well recorded* acoustic guitar; drawn linearly it fills a sixth of the height and
 * reads as a failure, which is exactly the wrong thing to tell someone who just played it.
 *
 * 60 dB is the window a phone microphone in a room actually occupies: below that is the noise
 * floor, and giving it height only makes silence look like signal.
 */
fun amplitudeToHeight(level: Float): Float {
    if (level <= 0f) return 0f
    val db = 20f * log10(max(level, 1e-6f))
    return ((db + 60f) / 60f).coerceIn(0f, 1f)
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
        targetValue = amplitudeToHeight(level),
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "level",
    )

    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fill = if (active) MaterialTheme.colorScheme.primary else track
    val clipped = MaterialTheme.colorScheme.record

    Box(modifier.fillMaxWidth().height(8.dp).clip(ControlShape)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRect(track, size = size)
            val w = size.width * shown.coerceIn(0f, 1f)
            // The top of the range is where clipping lives; colour it as the warning it is.
            drawRect(if (shown > 0.95f) clipped else fill, size = Size(w, size.height))
        }
    }
}

/**
 * Oblique bands across a filled control, in the manner of hazard tape.
 *
 * One repeating linear gradient with its stops doubled up, so each pair is a hard edge rather than a
 * fade. The axis runs down-right, which puts the bands themselves perpendicular to it, rising to the
 * right. Because the axis is the diagonal of a [band]-sided square, its length is twice [band] and
 * each half of the gradient is one band wide measured across.
 *
 * Drawn behind the label and over the container, and it carries the container's own clip, so this
 * belongs on something that fills the control rather than on the control itself. [color] lies over the
 * fill, so it is a tint and carries its own alpha — see `recordBand` in Theme.kt.
 */
private fun Modifier.obliqueBands(color: Color, band: Dp = ButtonBand) = drawBehind {
    val d = band.toPx() * sqrt(2f)
    drawRect(
        Brush.linearGradient(
            0.0f to Color.Transparent,
            0.5f to Color.Transparent,
            0.5f to color,
            1.0f to color,
            start = Offset.Zero,
            end = Offset(d, d),
            tileMode = TileMode.Repeated,
        ),
    )
}

/** Band width, across. Wide enough to read as banding at a glance rather than as a hatch. */
private val ButtonBand = 11.dp

/** A thumb-height, full-width action — the shape of every button hit without looking. */
@Composable
fun BigButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: androidx.compose.ui.graphics.Color? = null,
    onContainer: androidx.compose.ui.graphics.Color? = null,
    outlined: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    band: androidx.compose.ui.graphics.Color? = null,
) {
    // Only the surface-built button below has something spanning it to draw the bands on. Material's
    // Button gives its content an inset row, which would leave the bands short of the edges — so this
    // fails rather than half-drawing them.
    require(band == null || onLongClick != null) {
        "band needs the holdable button; Material's own insets its content"
    }
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    val shape = ControlShape
    val sized = modifier.height(64.dp).let { if (modifier == Modifier) it.fillMaxWidth() else it }
    if (onLongClick != null) {
        // Material's buttons take one gesture only, so a button with two is built from a surface.
        // Same shape, same height, same colours — it is the same button, holdable.
        val enabledContainer = container ?: MaterialTheme.colorScheme.primary
        Surface(
            modifier = sized.combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
            shape = shape,
            color = if (enabled) {
                enabledContainer
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            },
            contentColor = if (enabled) {
                onContainer ?: MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        ) {
            Row(
                Modifier.fillMaxSize().let { if (band != null && enabled) it.obliqueBands(band) else it },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        }
    } else if (outlined) {
        OutlinedButton(onClick, sized, enabled = enabled, shape = shape) { content() }
    } else {
        Button(
            onClick, sized, enabled = enabled, shape = shape,
            colors = if (container != null) {
                ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = onContainer ?: MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) { content() }
    }
}

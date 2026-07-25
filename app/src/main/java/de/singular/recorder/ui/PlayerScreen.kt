package de.singular.recorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.singular.recorder.OpenTake
import de.singular.recorder.PlaybackState
import de.singular.recorder.audio.NormalizeMode
import de.singular.recorder.storage.Take
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * One take, opened: its waveform, a playhead, and somewhere to put a thumb.
 *
 * The waveform is the reason this screen exists. A seek bar tells you where you are in a take; the
 * shape tells you where the *playing* is — where the count-in ends, where the chord you fluffed
 * sits, where it trails off — which is what you are looking for when you re-open a take at all.
 */
@Composable
fun PlayerScreen(
    open: OpenTake,
    playback: PlaybackState,
    busy: Boolean,
    onPlayPause: (Take, Long) -> Unit,
    onSeek: (Long) -> Unit,
    onRename: (String) -> Unit,
    onNormalize: (NormalizeMode, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val take = open.take
    // "Loaded" and "playing" are not the same thing: a stopped take keeps its place, here and in
    // the mini player, so that Play picks up where it left off rather than starting over.
    val loaded = playback.uri == take.uri
    val playing = loaded && playback.playing

    // Where the playhead sits before this take has been loaded at all — the "press play here" mark.
    var scrubMs by remember(take.uri) { mutableLongStateOf(0L) }
    val positionMs = if (loaded) playback.positionMs else scrubMs
    val durationMs = max(
        1L,
        if (loaded && playback.durationMs > 0) playback.durationMs else take.durationMs,
    )

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            take.name.substringBeforeLast('.'),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                formatKind(take.name).takeIf { it.isNotEmpty() }?.let {
                    append(it)
                    append(" · ")
                }
                append(formatDuration(take.durationMs))
                take.bpm?.let {
                    append(" · ")
                    append(if (it == it.toInt().toFloat()) "${it.toInt()}" else "$it")
                    append(" bpm")
                }
                append(" · ")
                append(formatSize(take.sizeBytes))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        // The waveform takes whatever is left, as on the record screen: this is the screen's
        // subject, and a 180dp strip in the middle of an empty half-page read as a placeholder.
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                open.loadingWaveform -> CircularProgressIndicator()
                open.peaks == null -> Text(
                    "No waveform for this file — nothing on this device could decode it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )

                else -> WaveformView(
                    peaks = open.peaks,
                    progress = positionMs.toFloat() / durationMs,
                    onScrub = { fraction ->
                        val ms = (fraction * durationMs).roundToLong().coerceIn(0, durationMs)
                        if (loaded) onSeek(ms) else scrubMs = ms
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        // The edits sit in the screen rather than behind an overflow: they are two, they are what
        // else there is to do here, and a menu would hide them behind a guess.
        PlayerTools(
            take = take,
            busy = busy,
            onRename = onRename,
            onNormalize = onNormalize,
        )

        Spacer(Modifier.height(12.dp))
        Button(
            // -1 resumes wherever the take was left; a mark set before loading is passed as-is.
            onClick = { onPlayPause(take, if (loaded) -1L else scrubMs) },
            Modifier.fillMaxWidth().height(64.dp),
            shape = ControlShape,
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(if (playing) "Stop" else "Play", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Share, in the app bar: something you do *with* the take, next to the way back out. */
@Composable
fun PlayerShareAction(onShare: () -> Unit) {
    IconButton(onClick = onShare) {
        Icon(Icons.Default.Share, contentDescription = "Share this take")
    }
}

/**
 * What can be done *to* the open take, rather than with it — the two edits, side by side above the
 * transport, where they can be seen rather than remembered.
 */
@Composable
private fun PlayerTools(
    take: Take,
    busy: Boolean,
    onRename: (String) -> Unit,
    onNormalize: (NormalizeMode, Boolean) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    // Null until a mode is picked, then holds it while the second question is answered.
    var normalizing by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<NormalizeMode?>(null) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { renaming = true },
            Modifier.weight(1f).height(48.dp),
            enabled = !busy,
            shape = ControlShape,
        ) {
            Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Rename")
        }
        OutlinedButton(
            onClick = { normalizing = true },
            Modifier.weight(1f).height(48.dp),
            enabled = !busy,
            shape = ControlShape,
        ) {
            // Rewriting a take takes a moment on a long one, and the spinner sits in the button
            // that started it rather than somewhere else on the screen.
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.GraphicEq, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Normalise")
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "Rename",
            initial = take.name.substringBeforeLast('.'),
            confirm = "Rename",
            onConfirm = {
                renaming = false
                onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }

    // Two questions, one at a time: how loud, then what to do with the result. Four buttons in one
    // dialog would be a grid to read; two pairs are two glances.
    if (normalizing) {
        ChoiceDialog(
            title = "Normalise",
            body = { Text("Lift a quiet take to a usable level.", style = DialogBody) },
            options = listOf(
                "Peak — loudest moment hits the top" to {
                    mode = NormalizeMode.PEAK
                    normalizing = false
                },
                "Loudness — louder overall, peaks rounded" to {
                    mode = NormalizeMode.LOUDNESS
                    normalizing = false
                },
            ),
            onDismiss = { normalizing = false },
        )
    }

    mode?.let { chosen ->
        // An imported m4a or mp3 has to be decoded to be lifted, and re-encoding it would cost a
        // second generation of lossy audio — so those only ever come out as a new WAV, and the
        // dialog offers what is actually possible rather than a button that would fail.
        val isWav = take.name.endsWith(".wav", ignoreCase = true)
        ChoiceDialog(
            title = "Normalise",
            body = {
                Text(
                    if (isWav) {
                        buildAnnotatedString {
                            append("Overwriting rewrites the recording itself: there is ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("no undo") }
                            append(". A copy leaves this take alone.")
                        }
                    } else {
                        buildAnnotatedString {
                            append("This one isn't a WAV, so it is decoded and saved as a new ")
                            append("WAV file — bigger, and lossless. The original is left alone.")
                        }
                    },
                    style = DialogBody,
                )
            },
            options = buildList {
                if (isWav) {
                    add(
                        "Overwrite this take" to {
                            mode = null
                            onNormalize(chosen, false)
                        },
                    )
                }
                add(
                    (if (isWav) "Save a normalised copy" else "Save a normalised WAV") to {
                        mode = null
                        onNormalize(chosen, true)
                    },
                )
            },
            onDismiss = { mode = null },
        )
    }
}

/**
 * A dialog whose answers are its buttons — a line of explanation, then one full-width choice per
 * line, and Cancel where a confirm button would be.
 */
@Composable
private fun ChoiceDialog(
    title: String,
    body: @Composable () -> Unit,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                body()
                options.forEach { (label, onClick) ->
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onClick,
                        Modifier.fillMaxWidth(),
                        shape = ControlShape,
                    ) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val DialogBody
    @Composable get() = MaterialTheme.typography.bodyMedium

/**
 * The peak envelope, mirrored about the centre line, with everything up to [progress] filled in.
 *
 * Touch anywhere seeks there, and a drag scrubs — the whole width is the control, because on a
 * waveform the thing you want to reach is a feature you can see, not a fraction you can calculate.
 */
@Composable
private fun WaveformView(
    peaks: FloatArray,
    progress: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val unplayed = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    // The same panel and zero line the record screen draws on, so a take looks the same played
    // back as it did being made.
    val panel = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)
    val zeroLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)

    Canvas(
        modifier
            .clip(ControlShape)
            .background(panel)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) onScrub(offset.x / size.width)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    if (size.width > 0) onScrub(change.position.x / size.width)
                }
            },
    ) {
        val n = peaks.size
        if (n == 0) return@Canvas
        val step = size.width / n
        val barWidth = max(1f, step * 0.62f)
        val mid = size.height / 2
        val edge = progress.coerceIn(0f, 1f) * size.width

        drawLine(
            color = zeroLine,
            start = Offset(0f, mid),
            end = Offset(size.width, mid),
            strokeWidth = 1.dp.toPx(),
        )

        for (i in 0 until n) {
            val x = i * step + (step - barWidth) / 2
            // A floor of one pixel: silence is part of the take, and a gap in the drawing reads as
            // a gap in the file rather than as a quiet bar.
            val half = max(1f, peaks[i] * mid * 0.95f)
            drawRect(
                color = if (x + barWidth / 2 <= edge) played else unplayed,
                topLeft = Offset(x, mid - half),
                size = Size(barWidth, half * 2),
            )
        }

        if (progress > 0f) {
            drawRect(
                color = played.copy(alpha = 0.9f),
                topLeft = Offset(edge - 1f, 0f),
                size = Size(2f, size.height),
            )
        }
    }
}

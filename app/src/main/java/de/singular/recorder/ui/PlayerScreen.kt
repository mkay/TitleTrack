package de.singular.recorder.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.singular.recorder.OpenTake
import de.singular.recorder.PlaybackState
import de.singular.recorder.R
import de.singular.recorder.audio.NormalizeMode
import de.singular.recorder.storage.Take
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
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
    onTrim: (Float, Float, Boolean) -> Unit,
    onRestart: (Take) -> Unit,
    looping: Boolean,
    onToggleLoop: () -> Unit,
    beatsPerBar: Int = 4,
    modifier: Modifier = Modifier,
) {
    val take = open.take
    // "Loaded" and "playing" are not the same thing: a stopped take keeps its place, here and in
    // the mini player, so that Play picks up where it left off rather than starting over.
    val loaded = playback.uri == take.uri
    val playing = loaded && playback.playing

    // Where the playhead sits before this take has been loaded at all — the "press play here" mark.
    var scrubMs by remember(take.uri) { mutableLongStateOf(0L) }
    // The trim selection, as fractions of the take. Reset whenever the take changes underneath.
    var trimming by remember(take.uri) { mutableStateOf(false) }
    var startFrac by remember(take.uri) { mutableFloatStateOf(0f) }
    var endFrac by remember(take.uri) { mutableFloatStateOf(1f) }
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
                    selection = if (trimming) startFrac..endFrac else null,
                    // One beat as a fraction of the take, for the grid and the magnet. The take
                    // carries the tempo it was played to; an import usually carries nothing.
                    beatFrac = take.bpm
                        ?.takeIf { it > 0f }
                        ?.let { (60_000f / it) / durationMs }
                        ?.takeIf { it > 0.001f }
                        ?: 0f,
                    beatsPerBar = beatsPerBar,
                    onHandleDrag = { edge, frac ->
                        val minGap = (MIN_TRIM_MS.toFloat() / durationMs).coerceAtMost(0.5f)
                        if (edge == TrimEdge.START) {
                            startFrac = frac.coerceIn(0f, endFrac - minGap)
                        } else {
                            endFrac = frac.coerceIn(startFrac + minGap, 1f)
                        }
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
        if (trimming) {
            TrimTools(
                take = take,
                busy = busy,
                startMs = (startFrac * durationMs).roundToLong(),
                endMs = (endFrac * durationMs).roundToLong(),
                durationMs = durationMs,
                onNudge = { edge, deltaMs ->
                    val delta = deltaMs.toFloat() / durationMs
                    val minGap = (MIN_TRIM_MS.toFloat() / durationMs).coerceAtMost(0.5f)
                    if (edge == TrimEdge.START) {
                        startFrac = (startFrac + delta).coerceIn(0f, endFrac - minGap)
                    } else {
                        endFrac = (endFrac + delta).coerceIn(startFrac + minGap, 1f)
                    }
                },
                onCancel = { trimming = false },
                onTrim = { asCopy ->
                    trimming = false
                    onTrim(startFrac, endFrac, asCopy)
                },
            )
        } else {
            // The edits sit in the screen rather than behind an overflow: they are what else there
            // is to do here, and a menu would hide them behind a guess.
            PlayerTools(
                take = take,
                busy = busy,
                onRename = onRename,
                onNormalize = onNormalize,
                onTrim = {
                    startFrac = 0f
                    endFrac = 1f
                    trimming = true
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        // Every tap acts at once, and a second tap inside the double-tap window means "from the
        // top". Compose's own double-click would hold the *first* tap back to see whether a second
        // one is coming, which is a fifth of a second of nothing happening on the one button that
        // must never feel slow.
        var lastTapAt by remember { mutableLongStateOf(0L) }
        BigButton(
            text = if (playing) "Stop" else "Play",
            // In loop mode the lemniscate takes the transport's place rather than sitting next to
            // it: the label already says what a press will do, so the icon is free to say what
            // kind of playback this is.
            icon = when {
                looping -> ImageVector.vectorResource(R.drawable.ic_all_inclusive)
                playing -> Icons.Default.Pause
                else -> Icons.Default.PlayArrow
            },
            onClick = {
                val now = SystemClock.uptimeMillis()
                val second = now - lastTapAt < DOUBLE_TAP_MS
                lastTapAt = now
                // -1 resumes wherever the take was left; a mark set before loading is passed as-is.
                if (second) onRestart(take) else onPlayPause(take, if (loaded) -1L else scrubMs)
            },
            // Holding the transport is where a mode belongs: repeat is something playback *is*,
            // not a fourth button competing with the three edits above it.
            onLongClick = onToggleLoop,
        )
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
    onTrim: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    // Null until a mode is picked, then holds it while the second question is answered.
    var normalizing by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<NormalizeMode?>(null) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { renaming = true },
            Modifier.weight(1f).height(48.dp),
            enabled = !busy,
            shape = ControlShape,
            contentPadding = ToolPadding,
        ) {
            Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Rename", style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(
            onClick = onTrim,
            Modifier.weight(1f).height(48.dp),
            enabled = !busy,
            shape = ControlShape,
            contentPadding = ToolPadding,
        ) {
            Icon(Icons.Default.ContentCut, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Trim", style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(
            onClick = { normalizing = true },
            Modifier.weight(1f).height(48.dp),
            enabled = !busy,
            shape = ControlShape,
            contentPadding = ToolPadding,
        ) {
            // Rewriting a take takes a moment on a long one, and the spinner sits in the button
            // that started it rather than somewhere else on the screen.
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.GraphicEq, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Level", style = MaterialTheme.typography.labelLarge)
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
 * The peak envelope, mirrored about the centre line, with everything up to [progress] filled in —
 * zoomable, and with the trim handles when there is a selection.
 *
 * A viewport of [zoom] and offset maps fractions of the take to pixels, after RubberRing's loop
 * waveform: `x = (frac - offset) * width * zoom`. Zoom is what makes trimming an edit rather than
 * a gesture at a smudge — at 1x a thumb covers a tenth of a second of a take, and the note attack
 * you want to cut on is inside that.
 *
 * All the gestures live in one detector, because they compete for the same finger:
 *  - **Tap** anywhere seeks there.
 *  - **Hold a trim handle still, then drag** moves that edge. The hold is what stops a reach for a
 *    handle from seeking, and the grab offset keeps the line under the finger rather than jumping.
 *  - **Drag** pans when zoomed in, and scrubs at 1x when there is no selection to protect.
 *  - **Two fingers** pinch to zoom and pan.
 *  - **Drag the strip along the bottom** (only when zoomed) to scroll fast.
 */
@Composable
private fun WaveformView(
    peaks: FloatArray,
    progress: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
    selection: ClosedFloatingPointRange<Float>? = null,
    beatFrac: Float = 0f,
    beatsPerBar: Int = 4,
    onHandleDrag: (TrimEdge, Float) -> Unit = { _, _ -> },
) {
    // Neutral, matching the record screen: the played part is the waveform's own ink, the unplayed
    // part the same ink dimmed. Position is carried by the step in weight rather than by a change
    // of colour, which is the reading a waveform wants — see [WaveformInk].
    val played = MaterialTheme.colorScheme.onSurface.copy(alpha = WaveformInk)
    val unplayed = MaterialTheme.colorScheme.onSurface.copy(alpha = WaveformMuted)
    // The same panel and zero line the record screen draws on, so a take looks the same played
    // back as it did being made.
    val panel = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)
    val zeroLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val handleColor = MaterialTheme.colorScheme.onSurface
    val tabFill = MaterialTheme.colorScheme.primary
    val gripColor = MaterialTheme.colorScheme.onPrimary
    val beatLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val barLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val outside = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val scrollTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val scrollThumb = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val haptic = LocalHapticFeedback.current

    // The viewport. Kept here rather than hoisted: it is how this take is being *looked at*, and it
    // starts again from the whole file whenever a different one is opened.
    var zoom by remember(peaks) { mutableFloatStateOf(1f) }
    var offset by remember(peaks) { mutableFloatStateOf(0f) }
    // Read inside the gesture without re-arming the detector on every pixel of a drag.
    val currentSelection by rememberUpdatedState(selection)

    Canvas(
        modifier
            .clip(ControlShape)
            .background(panel)
            // Keep the system's edge swipes off the waveform: grabbing a handle near the screen
            // edge should trim the take, not navigate back out of the app.
            .systemGestureExclusion()
            .pointerInput(peaks) {
                val slop = viewConfiguration.touchSlop
                val handleThreshold = HandleTouch.toPx()
                val scrollbarTouch = ScrollbarTouch.toPx()

                fun clampOffset(o: Float) = o.coerceIn(0f, (1f - 1f / zoom).coerceAtLeast(0f))

                fun xToFrac(x: Float): Float {
                    val w = size.width.toFloat()
                    if (w <= 0f) return 0f
                    return (offset + (x / w) / zoom).coerceIn(0f, 1f)
                }

                fun applyZoom(factor: Float, focalX: Float) {
                    val w = size.width.toFloat()
                    if (w <= 0f) return
                    val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                    val focalFrac = offset + (focalX / w) / zoom
                    zoom = newZoom
                    offset = clampOffset(focalFrac - (focalX / w) / newZoom)
                }

                fun applyPan(dxPixels: Float) {
                    val w = size.width.toFloat()
                    if (w <= 0f) return
                    offset = clampOffset(offset - dxPixels / (w * zoom))
                }

                /** Centre the visible window on the touched point — jump-to-position scrolling. */
                fun scrollTo(x: Float) {
                    val w = size.width.toFloat()
                    if (w <= 0f) return
                    offset = clampOffset((x / w) - (1f / zoom) / 2f)
                }

                fun handleNear(x: Float): TrimEdge? {
                    val range = currentSelection ?: return null
                    val w = size.width.toFloat()
                    val startX = (range.start - offset) * w * zoom
                    val endX = (range.endInclusive - offset) * w * zoom
                    val dStart = abs(x - startX)
                    val dEnd = abs(x - endX)
                    return when {
                        dStart <= handleThreshold && dStart <= dEnd -> TrimEdge.START
                        dEnd <= handleThreshold -> TrimEdge.END
                        else -> null
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    var mode = GestureMode.NONE
                    var edge: TrimEdge? = null
                    var moved = false
                    var multiTouch = false
                    var grabDx = 0f

                    val inScrollbar = zoom > 1f && down.position.y >= size.height - scrollbarTouch
                    if (inScrollbar) {
                        mode = GestureMode.SCROLLBAR // the strip needs no hold; it is only a view
                        scrollTo(downX)
                    } else {
                        val near = handleNear(downX)
                        if (near != null && awaitStillHold(down.id, downX, slop)) {
                            mode = GestureMode.HANDLE
                            edge = near
                            val range = currentSelection
                            if (range != null) {
                                val at = if (near == TrimEdge.START) range.start else range.endInclusive
                                grabDx = downX - (at - offset) * size.width * zoom
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }

                    var x = downX
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break

                        if (pressed >= 2) {
                            multiTouch = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoomChange != 1f) applyZoom(zoomChange, centroid.x)
                            if (panChange.x != 0f) applyPan(panChange.x)
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val change = event.changes.first { it.pressed }
                        if (abs(change.position.x - downX) > slop) moved = true
                        x = change.position.x
                        when (mode) {
                            GestureMode.HANDLE -> {
                                val frac = ((x - grabDx) / size.width / zoom + offset)
                                    .coerceIn(0f, 1f)
                                edge?.let { onHandleDrag(it, snapToBeat(frac, beatFrac)) }
                                change.consume()
                            }

                            GestureMode.SCROLLBAR -> {
                                scrollTo(x)
                                change.consume()
                            }

                            GestureMode.PAN -> {
                                applyPan(change.positionChange().x)
                                change.consume()
                            }

                            GestureMode.SCRUB -> {
                                onScrub(xToFrac(x))
                                change.consume()
                            }

                            GestureMode.NONE -> if (moved) {
                                // What a drag means depends on what there is to protect: panning
                                // when zoomed, scrubbing at 1x, and nothing at all at 1x while
                                // trimming — seeking out from under an edit is never what a stray
                                // drag meant.
                                mode = when {
                                    zoom > 1f -> GestureMode.PAN
                                    currentSelection == null -> GestureMode.SCRUB
                                    else -> GestureMode.NONE
                                }
                                if (mode == GestureMode.PAN) applyPan(change.positionChange().x)
                                if (mode == GestureMode.SCRUB) onScrub(xToFrac(x))
                                if (mode != GestureMode.NONE) change.consume()
                            }
                        }
                    }

                    // A touch that never became anything else is a tap: seek there.
                    if (!moved && !multiTouch && mode == GestureMode.NONE) onScrub(xToFrac(downX))
                }
            },
    ) {
        val n = peaks.size
        if (n == 0) return@Canvas
        val w = size.width
        val mid = size.height / 2

        fun fracToX(f: Float) = (f - offset) * w * zoom

        drawLine(
            color = zeroLine,
            start = Offset(0f, mid),
            end = Offset(w, mid),
            strokeWidth = 1.dp.toPx(),
        )

        // One column per pixel, each taking the loudest bucket it covers. At 1x that is several
        // buckets folded together; zoomed in it is one bucket stretched over several columns, which
        // is where the extra resolution read from the file earns itself.
        val edge = fracToX(progress.coerceIn(0f, 1f))
        val columns = max(1, w.toInt())
        for (px in 0 until columns) {
            val fL = offset + (px.toFloat() / w) / zoom
            val fR = offset + ((px + 1).toFloat() / w) / zoom
            if (fL > 1f) break
            val bL = (fL * n).toInt().coerceIn(0, n - 1)
            val bR = (fR * n).toInt().coerceIn(bL + 1, n)
            var peak = 0f
            for (b in bL until bR) peak = max(peak, peaks[b])
            // Decibels, the same 60 dB window the record screen and the meter use: drawn linearly
            // a take peaking at −15 dBFS — a good acoustic level — fills a sixth of the height and
            // looks like a failed recording. A floor of one pixel keeps silence on the zero line,
            // so a gap reads as a gap in the file rather than as a quiet bar.
            val half = max(1f, amplitudeToHeight(peak) * mid * 0.95f)
            drawRect(
                color = if (px <= edge) played else unplayed,
                topLeft = Offset(px.toFloat(), mid - half),
                size = Size(1f, half * 2),
            )
        }

        if (progress > 0f && edge >= 0f && edge <= w) {
            drawRect(
                color = played.copy(alpha = 0.9f),
                topLeft = Offset(edge - 1f, 0f),
                size = Size(2f, size.height),
            )
        }

        if (selection != null) {
            // The beat grid, faintly, and only while trimming. It is derived from one tempo and the
            // first sample, so it is exactly right at the start of the take and progressively more
            // of a guess after that — a take played to a silent click drifts. Hence faint, and
            // hence a magnet rather than a ruler: what it is for is finding the downbeat you are
            // near.
            if (beatFrac > 0f) {
                val first = (offset / beatFrac).toInt().coerceAtLeast(0)
                var beat = first
                while (beat * beatFrac <= 1f && beat - first < MAX_GRID_LINES) {
                    val x = fracToX(beat * beatFrac)
                    if (x > w) break
                    if (x >= 0f) {
                        val bar = beatsPerBar > 0 && beat % beatsPerBar == 0
                        drawLine(
                            color = if (bar) barLine else beatLine,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = if (bar) 1.5.dp.toPx() else 1.dp.toPx(),
                        )
                    }
                    beat++
                }
            }

            // What is being thrown away, greyed over: the lit part is what survives.
            val startX = fracToX(selection.start).coerceIn(0f, w)
            val endX = fracToX(selection.endInclusive).coerceIn(0f, w)
            if (startX > 0f) drawRect(outside, size = Size(startX, size.height))
            if (endX < w) {
                drawRect(outside, topLeft = Offset(endX, 0f), size = Size(w - endX, size.height))
            }
            fracToX(selection.start).let {
                if (it in 0f..w) {
                    drawTrimHandle(it, size.height, true, handleColor, tabFill, gripColor)
                }
            }
            fracToX(selection.endInclusive).let {
                if (it in 0f..w) {
                    drawTrimHandle(it, size.height, false, handleColor, tabFill, gripColor)
                }
            }
        }

        // The scroll strip, only once there is somewhere to scroll to: the thumb is the part of the
        // take on screen, and dragging it covers the whole file in one movement.
        if (zoom > 1f) {
            val stripH = ScrollbarHeight.toPx()
            val y = size.height - stripH
            val radius = CornerRadius(stripH / 2f, stripH / 2f)
            drawRoundRect(
                scrollTrack,
                topLeft = Offset(0f, y),
                size = Size(w, stripH),
                cornerRadius = radius,
            )
            val thumbW = ((1f / zoom) * w).coerceAtLeast(stripH)
            val thumbX = (offset * w).coerceIn(0f, w - thumbW)
            drawRoundRect(
                scrollThumb,
                topLeft = Offset(thumbX, y),
                size = Size(thumbW, stripH),
                cornerRadius = radius,
            )
        }
    }
}

/** What a single finger is currently doing to the waveform. */
private enum class GestureMode { NONE, HANDLE, PAN, SCRUB, SCROLLBAR }

/**
 * Wait for [id] to stay within [slop] of [startX] for as long as a long press takes. True if it
 * held — the caller should arm its grab — false if the finger lifted, moved, or was joined.
 *
 * Hand-rolled rather than `detectDragGesturesAfterLongPress`, because the hold has to be one branch
 * of a detector that also pans, zooms and scrubs; two detectors would race for the same finger.
 */
private suspend fun AwaitPointerEventScope.awaitStillHold(
    id: PointerId,
    startX: Float,
    slop: Float,
): Boolean {
    val gaveUp = withTimeoutOrNull(LONG_PRESS_MS) {
        var done = false
        while (!done) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == id }
            // Lifted, joined by a second finger, or moved: whatever this gesture is, it is not a
            // deliberate grab of a handle.
            done = change == null ||
                !change.pressed ||
                event.changes.count { it.pressed } >= 2 ||
                abs(change.position.x - startX) > slop
        }
        true
    }
    // Timing out is the success case here: the finger stayed put for the whole hold.
    return gaveUp == null
}

/** How long a handle must be held before it can be dragged. */
private const val LONG_PRESS_MS = 350L

/** As far in as the viewport goes: a couple of seconds across the screen on a long take. */
private const val MAX_ZOOM = 40f

/** The scroll strip along the bottom, and the reach for it. */
private val ScrollbarHeight = 10.dp
private val ScrollbarTouch = 32.dp

/** Where a handle can be grabbed from — generous, because it is grabbed with a thumb. */
private val HandleTouch = 40.dp

/** A grid line every beat is only worth drawing while they are further apart than a finger. */
private const val MAX_GRID_LINES = 400

/**
 * Pull [frac] onto the nearest beat if it is already close to one, and leave it exactly where it is
 * otherwise.
 *
 * A magnet, not a quantiser: an eighth of a beat either side sticks, and dragging any further comes
 * straight out of it. A take played to a visual click is not on the grid to the millisecond, so the
 * grid must never be the thing that decides where the cut goes.
 */
private fun snapToBeat(frac: Float, beatFrac: Float): Float {
    if (beatFrac <= 0f) return frac
    val nearest = (frac / beatFrac).roundToInt() * beatFrac
    return if (abs(frac - nearest) <= beatFrac / 8f) nearest.coerceIn(0f, 1f) else frac
}

/**
 * A marker line with a grip tab beside it, after RubberRing's loop markers: the tab is rounded only
 * on the corners away from the line, so it reads as attached to the marker rather than floating
 * near it, and it is big enough to find without looking.
 */
private fun DrawScope.drawTrimHandle(
    x: Float,
    height: Float,
    towardRight: Boolean,
    line: androidx.compose.ui.graphics.Color,
    tabFill: androidx.compose.ui.graphics.Color,
    grip: androidx.compose.ui.graphics.Color,
) {
    drawLine(line, start = Offset(x, 0f), end = Offset(x, height), strokeWidth = 2.dp.toPx())

    val tabW = 22.dp.toPx()
    val tabH = 56.dp.toPx()
    val r = CornerRadius(5.dp.toPx(), 5.dp.toPx())
    val flat = CornerRadius.Zero
    val left = if (towardRight) x else x - tabW
    val top = height / 2f - tabH / 2f
    drawPath(
        Path().apply {
            addRoundRect(
                RoundRect(
                    left = left, top = top, right = left + tabW, bottom = top + tabH,
                    topLeftCornerRadius = if (towardRight) flat else r,
                    bottomLeftCornerRadius = if (towardRight) flat else r,
                    topRightCornerRadius = if (towardRight) r else flat,
                    bottomRightCornerRadius = if (towardRight) r else flat,
                ),
            )
        },
        tabFill,
    )

    // Two ridges in the middle of the tab, so it reads as something to take hold of.
    val cx = left + tabW / 2f
    val gripHalf = tabH * 0.2f
    val gap = 3.dp.toPx()
    for (dx in floatArrayOf(-gap, gap)) {
        drawLine(
            grip,
            start = Offset(cx + dx, height / 2f - gripHalf),
            end = Offset(cx + dx, height / 2f + gripHalf),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/**
 * How soon a second tap on Play counts as "start from the beginning" rather than as a new press.
 * The platform's own double-tap window, which is what a thumb expects.
 */
private const val DOUBLE_TAP_MS = 300L

/** Which end of the selection a gesture or a nudge is moving. */
enum class TrimEdge { START, END }

/** Shortest take worth keeping — below this a trim is a slip of the thumb, not an edit. */
const val MIN_TRIM_MS = 200L

/** How far a nudge moves an edge. Small enough to land on an attack, big enough to be one tap. */
private const val NUDGE_MS = 10L

private val ToolPadding = PaddingValues(horizontal = 6.dp)

/**
 * The trim bar: where the two edges are, buttons to move them by a hair, and the way out.
 *
 * Dragging gets an edge to roughly the right place; these get it exactly there. On a phone, a
 * finger covers about a tenth of a second of a take, so "nearly right" is where a drag always
 * ends — and repeating a 10 ms step is easier than trying to be precise with a thumb.
 */
@Composable
private fun TrimTools(
    take: Take,
    busy: Boolean,
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    onNudge: (TrimEdge, Long) -> Unit,
    onCancel: () -> Unit,
    onTrim: (Boolean) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdgeNudge("Start", startMs, Modifier.weight(1f)) { onNudge(TrimEdge.START, it) }
            EdgeNudge("End", endMs, Modifier.weight(1f)) { onNudge(TrimEdge.END, it) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                Modifier.weight(1f).height(48.dp),
                shape = ControlShape,
                contentPadding = ToolPadding,
            ) { Text("Cancel", style = MaterialTheme.typography.labelLarge) }
            Button(
                onClick = { asking = true },
                Modifier.weight(1f).height(48.dp),
                enabled = !busy && endMs - startMs >= MIN_TRIM_MS,
                shape = ControlShape,
                contentPadding = ToolPadding,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        "Keep ${formatDuration(endMs - startMs)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }

    if (asking) {
        // The same question normalising asks, for the same reason — except a trim throws audio
        // away outright, so the copy is the option worth having.
        val isWav = take.name.endsWith(".wav", ignoreCase = true)
        val cut = formatDuration(durationMs - (endMs - startMs))
        ChoiceDialog(
            title = "Trim",
            body = {
                Text(
                    if (isWav) {
                        buildAnnotatedString {
                            append("$cut is removed. Overwriting this take has ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("no undo") }
                            append("; a copy leaves it alone.")
                        }
                    } else {
                        buildAnnotatedString {
                            append("$cut is removed. This one isn't a WAV, so the trim is decoded ")
                            append("and saved as a new WAV file. The original is left alone.")
                        }
                    },
                    style = DialogBody,
                )
            },
            options = buildList {
                if (isWav) {
                    add(
                        "Overwrite this take" to {
                            asking = false
                            onTrim(false)
                        },
                    )
                }
                add(
                    (if (isWav) "Save a trimmed copy" else "Save a trimmed WAV") to {
                        asking = false
                        onTrim(true)
                    },
                )
            },
            onDismiss = { asking = false },
        )
    }
}

/** One edge of the selection: what time it sits at, and a hair either way. */
@Composable
private fun EdgeNudge(
    label: String,
    atMs: Long,
    modifier: Modifier = Modifier,
    onNudge: (Long) -> Unit,
) {
    Row(
        modifier
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f))
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                formatPrecise(atMs),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = { onNudge(-NUDGE_MS) }, Modifier.size(40.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "$label earlier", Modifier.size(18.dp))
        }
        IconButton(onClick = { onNudge(NUDGE_MS) }, Modifier.size(40.dp)) {
            Icon(Icons.Default.Add, contentDescription = "$label later", Modifier.size(18.dp))
        }
    }
}

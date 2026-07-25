package de.singular.recorder.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.singular.recorder.OpenTake
import de.singular.recorder.PlaybackState
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
    onPlayPause: (Take, Long) -> Unit,
    onSeek: (Long) -> Unit,
    onShare: (Take) -> Unit,
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

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp),
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
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))
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
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { onShare(take) },
            Modifier.fillMaxWidth().height(52.dp),
            shape = ControlShape,
        ) {
            Icon(Icons.Default.Share, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Share")
        }
    }
}

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

    Canvas(
        modifier
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

package de.singular.recorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.singular.recorder.PlaybackState

/**
 * The take being listened to, wherever you happen to be.
 *
 * It sits above the tabs rather than inside the library, because playback outlives the screen it
 * was started from: without this, wandering off to the Record tab left a take playing with nothing
 * anywhere to stop it.
 *
 * It shows while a take is playing, and goes on showing one that was stopped part-way — that take
 * is still the one in hand, and the bar is how it gets started again from where it stopped. What it
 * does not do is outlive a take that ran out on its own: reaching the end leaves it loaded at 0:00,
 * which used to leave the bar sitting there reporting silence about something already finished with.
 *
 * So stop and close stay two different things, as they were: stop silences the take and keeps it,
 * close puts it away.
 */
@OptIn(ExperimentalMaterial3Api::class) // Slider's thumb slot
@Composable
fun MiniPlayer(
    playback: PlaybackState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val take = playback.take?.takeIf { playback.playing || !playback.finished } ?: return
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The name is the way back into the full player, the same as it is in the list.
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onOpen)
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        take.name.substringBeforeLast('.'),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    Text(
                        buildString {
                            formatKind(take.name).takeIf { it.isNotEmpty() }?.let {
                                append(it)
                                append(" · ")
                            }
                            append(formatDuration(playback.positionMs))
                            append(" / ")
                            append(formatDuration(playback.durationMs))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playback.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (playback.playing) "Stop" else "Play",
                        tint = MaterialTheme.colorScheme.brandFill,
                    )
                }
                // Stop only silences the take; this is how it stops taking up the screen. Dimmed,
                // because it is the least likely of the two to be wanted and was reading at the
                // same weight as the transport beside it — a cross at full strength on a dark bar
                // pulls harder than a filled triangle does.
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close the player",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = DismissTint),
                    )
                }
            }
            Slider(
                value = playback.positionMs.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..playback.durationMs.toFloat().coerceAtLeast(1f),
                // The track alone, no handle: at this height a thumb is bigger than the bar it
                // rides on, and the seek target is the whole width either way.
                thumb = {},
                // Drawn rather than themed. Material's own track is as tall as a control and splits
                // into two lozenges with a gap, which at the foot of the screen reads as a second
                // toolbar; and it paints both halves in the accent, so the part not yet played is
                // as loud as the part that has. This is a hairline, and only the played part is
                // coloured — the rest is the same faint ink the waveform panel uses.
                track = { state ->
                    val span = state.valueRange.endInclusive - state.valueRange.start
                    val played = if (span > 0f) {
                        ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(TimelineHeight)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(played)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                },
                // Taller than the line it draws, so the seek target stays a thumb rather than
                // shrinking to the hairline.
                modifier = Modifier.fillMaxWidth().height(TimelineTouch),
            )
        }
    }
}

/** A line under the take, not a bar beside it — see the track slot above. */
private val TimelineHeight = 3.dp

/** What the finger gets, regardless of what the eye gets. */
private val TimelineTouch = 20.dp

/** Present, but plainly the quieter of the two buttons. */
private const val DismissTint = 0.45f

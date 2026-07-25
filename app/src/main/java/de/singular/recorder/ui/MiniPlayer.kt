package de.singular.recorder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.singular.recorder.PlaybackState

/**
 * The take being listened to, wherever you happen to be.
 *
 * It sits above the tabs rather than inside the library, because playback outlives the screen it
 * was started from: without this, wandering off to the Record tab left a take playing with nothing
 * anywhere to stop it. Stopping does not dismiss the bar — it keeps the take and the position, so
 * the button is a real toggle rather than a one-way exit; closing it is the separate, deliberate
 * act of putting the take away.
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
    val take = playback.take ?: return
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp)) {
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
                        "${formatDuration(playback.positionMs)} / " +
                            formatDuration(playback.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playback.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (playback.playing) "Stop" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                // Stop only silences the take; this is how it stops taking up the screen.
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close the player")
                }
            }
            Slider(
                value = playback.positionMs.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..playback.durationMs.toFloat().coerceAtLeast(1f),
                // The track alone, no handle: at this height a thumb is bigger than the bar it
                // rides on, and the seek target is the whole width either way.
                thumb = {},
                colors = SliderDefaults.colors(),
                modifier = Modifier.fillMaxWidth().height(16.dp),
            )
        }
    }
}

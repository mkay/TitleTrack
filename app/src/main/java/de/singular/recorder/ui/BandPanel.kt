package de.singular.recorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.singular.recorder.BandState
import de.singular.recorder.audio.MAX_BPM
import de.singular.recorder.audio.MIN_BPM
import de.singular.recorder.audio.Patterns
import kotlin.math.roundToInt

/**
 * The band's controls: what it plays, how loud, and where beat one is.
 *
 * Sits where the tools row does, in place of it, the way trim's controls do — the screen has one
 * place for "what you are doing to this take", and two panels competing for the bottom of it would
 * leave neither room to be read. Playback carries on underneath, which is the point: every control
 * here changes what you are already listening to.
 *
 * The faders are the reason the accompaniment is mixed live rather than rendered. Moving one while
 * the drums play is how you find the level a bed should sit at — a slider that took a rebuild to
 * hear would be a slider nobody moved twice.
 */
@Composable
fun BandPanel(
    state: BandState,
    onToggle: () -> Unit,
    onPattern: (String) -> Unit,
    onTakeLevel: (Float) -> Unit,
    onDrumsLevel: (Float) -> Unit,
    onOffset: (Int) -> Unit,
    onBpm: (Float) -> Unit,
    onBeatsPerBar: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Band", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Switch(checked = state.on, onCheckedChange = { onToggle() })
            if (state.preparing) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDone, shape = ControlShape) { Text("Done") }
        }

        // The tempo the drummer is counting. A take carries the tempo it was played to, and that
        // is the usual answer; where it does not, this says so rather than presenting a borrowed
        // number as the take's own.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.bpm.roundToInt()} bpm",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.tempoFromTake) "from the take" else "not in the take — set it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            Stepper(label = "${state.beatsPerBar}/4", onLess = {
                onBeatsPerBar((state.beatsPerBar - 1).coerceAtLeast(2))
            }, onMore = { onBeatsPerBar((state.beatsPerBar + 1).coerceAtMost(12)) })
        }
        Slider(
            value = state.bpm,
            onValueChange = onBpm,
            valueRange = MIN_BPM..MAX_BPM,
            colors = quietTrack(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (pattern in Patterns.all) {
                FilterChip(
                    selected = pattern.id == state.arrangement.patternId,
                    onClick = { onPattern(pattern.id) },
                    label = { Text(pattern.label, style = MaterialTheme.typography.labelMedium) },
                    shape = ControlShape,
                )
            }
        }

        Fader("Take", state.arrangement.takeLevel, onTakeLevel)
        Fader("Drums", state.arrangement.drumsLevel, onDrumsLevel)

        // Where beat one actually is. Capture starts within a block of the downbeat and the click
        // left the speaker with a latency of its own, so "on the beat" is close but not exact —
        // and a drummer a hair out is worse company than none.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Nudge",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(56.dp),
            )
            Slider(
                value = state.arrangement.offsetMs.toFloat(),
                onValueChange = { onOffset(it.roundToInt()) },
                valueRange = -250f..250f,
                colors = quietTrack(),
                modifier = Modifier.weight(1f),
            )
            Text(
                "${if (state.arrangement.offsetMs > 0) "+" else ""}${state.arrangement.offsetMs} ms",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(64.dp),
            )
        }
    }
}

@Composable
private fun Fader(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1.5f,
            colors = quietTrack(),
            modifier = Modifier.weight(1f),
        )
        Text(
            "${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}

@Composable
private fun Stepper(label: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onLess,
            shape = ControlShape,
            contentPadding = ToolPadding,
            modifier = Modifier.height(36.dp),
        ) { Text("−") }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp),
        )
        OutlinedButton(
            onClick = onMore,
            shape = ControlShape,
            contentPadding = ToolPadding,
            modifier = Modifier.height(36.dp),
        ) { Text("+") }
    }
}

/** The default paints both halves of the track in the accent, leaving no position to read. */
@Composable
private fun quietTrack() = SliderDefaults.colors(
    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
)

/**
 * The band's controls, bundled.
 *
 * Seven lambdas is too many to thread through a screen's parameter list one at a time — the player
 * already takes eleven — and they always travel together, so they travel as one thing.
 */
data class BandControls(
    val toggle: () -> Unit,
    val pattern: (String) -> Unit,
    val takeLevel: (Float) -> Unit,
    val drumsLevel: (Float) -> Unit,
    val offset: (Int) -> Unit,
    val bpm: (Float) -> Unit,
    val beatsPerBar: (Int) -> Unit,
)

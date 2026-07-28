package de.singular.recorder.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import de.singular.recorder.audio.BandPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onDownbeat: (Int) -> Unit,
    /** Where the playhead stands, so beat one can be taken from it rather than aimed at. */
    playheadMs: Long,
    onPlaceDownbeat: (Boolean) -> Unit,
    /** The take's own tempo, if it carries one — what tells a fact from a fallback. */
    takeKnowsTempo: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which value is being typed rather than dragged to, if any.
    var editing by remember { mutableStateOf(Editing.NONE) }

    when (editing) {
        Editing.NONE -> Unit
        Editing.BPM -> NumberDialog(
            title = "Tempo",
            initial = state.bpm.roundToInt().toString(),
            unit = "bpm",
            onConfirm = onBpm,
            onDismiss = { editing = Editing.NONE },
            defaultLabel = if (takeKnowsTempo) "From take" else null,
            onDefault = if (takeKnowsTempo) ({ onBpm(0f) }) else null,
        )

        Editing.NUDGE -> NumberDialog(
            title = "Nudge",
            initial = state.arrangement.offsetMs.toString(),
            unit = "ms",
            onConfirm = { onOffset(it.roundToInt()) },
            onDismiss = { editing = Editing.NONE },
            defaultLabel = "None",
            onDefault = { onOffset(0) },
        )

        Editing.TAKE -> NumberDialog(
            title = "Take level",
            initial = (state.arrangement.takeLevel * 100).roundToInt().toString(),
            unit = "%",
            onConfirm = { onTakeLevel((it / 100f).coerceIn(0f, 1.5f)) },
            onDismiss = { editing = Editing.NONE },
            defaultLabel = "Default",
            onDefault = { onTakeLevel(1f) },
        )

        Editing.DRUMS -> NumberDialog(
            title = "Drums level",
            initial = (state.arrangement.drumsLevel * 100).roundToInt().toString(),
            unit = "%",
            onConfirm = { onDrumsLevel((it / 100f).coerceIn(0f, 1.5f)) },
            onDismiss = { editing = Editing.NONE },
            defaultLabel = "Default",
            onDefault = { onDrumsLevel(BandPlayer.DEFAULT_DRUMS_LEVEL) },
        )

        Editing.DOWNBEAT -> NumberDialog(
            title = "Beat one",
            initial = state.arrangement.downbeatMs.toString(),
            unit = "ms",
            onConfirm = { onDownbeat(it.roundToInt()) },
            onDismiss = { editing = Editing.NONE },
        )
    }

    // Scrollable, because this panel is the tallest thing in the app and the waveform above it now
    // keeps a floor of its own: on a short screen something has to give, and it should be the row of
    // faders rather than the take they are being set against.
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
                modifier = Modifier
                    .clip(ControlShape)
                    .clickable { editing = Editing.BPM }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
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

        Fader("Take", state.arrangement.takeLevel, { editing = Editing.TAKE }, onTakeLevel)
        Fader("Drums", state.arrangement.drumsLevel, { editing = Editing.DRUMS }, onDrumsLevel)

        // Where bar one starts, for takes that do not say. A take this app recorded begins on the
        // downbeat, so the answer is zero and this row is a statement rather than a question; an
        // import knows nothing about its own bars, and a grid laid from sample zero on a file that
        // opens with somebody finding their pick is wrong by however long that took.
        //
        // Pointing at it beats typing it: the waveform is right there, the attacks are visible, and
        // the tap lands on the nearest one. Beat one, not bar one of the music — the grid runs
        // backwards from wherever it is put, so the clearest downbeat in the take will do.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Beat one",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(72.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    state.arrangement.downbeatMs > 0 -> "set"
                    takeKnowsTempo -> "the start — recorded here"
                    else -> "assumed at the start"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            // Two ways in, because they suit different takes. Pointing is quick when the downbeat is
            // visible as an attack; the playhead is what you use when it is only audible, having
            // scrubbed or listened your way to it — and it inherits the zoom the waveform already
            // has, so it is as precise as the take deserves.
            TextButton(onClick = { onPlaceDownbeat(true) }) { Text("Tap…") }
            TextButton(onClick = { onDownbeat(playheadMs.toInt()) }) { Text("Playhead") }
        }

        // Once it is somewhere, it is moved in tens rather than re-aimed: the last few milliseconds
        // are heard rather than seen, and they are found with the drums playing.
        if (state.arrangement.downbeatMs > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(80.dp))
                Stepper(
                    label = formatPrecise(state.arrangement.downbeatMs.toLong()),
                    onLabelClick = { editing = Editing.DOWNBEAT },
                    onLess = { onDownbeat(state.arrangement.downbeatMs - DOWNBEAT_STEP_MS) },
                    onMore = { onDownbeat(state.arrangement.downbeatMs + DOWNBEAT_STEP_MS) },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onDownbeat(0) }) { Text("Clear") }
            }
        }

        // Where beat one actually is, to within a hair. Capture starts within a block of the
        // downbeat and the click left the speaker with a latency of its own, so "on the beat" is
        // close but not exact — and a drummer a hair out is worse company than none.
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
            EditableValue(
                text = "${if (state.arrangement.offsetMs > 0) "+" else ""}" +
                    "${state.arrangement.offsetMs} ms",
                width = 64.dp,
            ) { editing = Editing.NUDGE }
        }
    }
}

@Composable
private fun Fader(
    label: String,
    value: Float,
    onEdit: () -> Unit,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1.5f,
            colors = quietTrack(),
            modifier = Modifier.weight(1f),
        )
        EditableValue(text = "${(value * 100).roundToInt()}%", width = 64.dp, onClick = onEdit)
    }
}

/** A value that can be typed as well as dragged to — accent-coloured, as tappable values are. */
@Composable
private fun EditableValue(text: String, width: Dp, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.End,
        modifier = Modifier
            .width(width)
            .clip(ControlShape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/** A tenth of a beat at 60 bpm, and about as fine as a difference anyone hears in a bed. */
private const val DOWNBEAT_STEP_MS = 10

@Composable
private fun Stepper(
    label: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    onLabelClick: (() -> Unit)? = null,
) {
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
            color = if (onLabelClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .width(72.dp)
                .clip(ControlShape)
                .let { if (onLabelClick != null) it.clickable(onClick = onLabelClick) else it }
                .padding(vertical = 6.dp),
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
/** Which of the panel's values is open for typing, if any. */
private enum class Editing { NONE, BPM, NUDGE, TAKE, DRUMS, DOWNBEAT }

data class BandControls(
    val toggle: () -> Unit,
    val pattern: (String) -> Unit,
    val takeLevel: (Float) -> Unit,
    val drumsLevel: (Float) -> Unit,
    val offset: (Int) -> Unit,
    val bpm: (Float) -> Unit,
    val beatsPerBar: (Int) -> Unit,
    val downbeat: (Int) -> Unit,
)

package de.singular.recorder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.singular.recorder.RecorderViewModel
import de.singular.recorder.Settings
import de.singular.recorder.audio.MAX_BPM
import de.singular.recorder.audio.MIN_BPM
import de.singular.recorder.audio.RecordPhase
import de.singular.recorder.audio.RecorderState

/**
 * The take in progress.
 *
 * Everything on it is sized for a player holding an instrument: the two buttons that matter while
 * recording — Done and Restart — are the full width of the screen and thumb-height, because
 * Restart is only useful if it can be hit without looking, mid-phrase, after a wrong chord.
 *
 * A take ends when Done is pressed; there is no resuming it. Splicing a second attempt onto the
 * end of the first only ever produced takes with a seam in them — Restart is what that impulse
 * actually wants.
 */
@Composable
fun RecordScreen(
    state: RecorderState,
    settings: Settings,
    folderLabel: String?,
    onChooseFolder: () -> Unit,
    onRecord: () -> Unit,
    onFinish: () -> Unit,
    onRestart: () -> Unit,
    onDiscard: () -> Unit,
    onSave: (String) -> Unit,
    onSetBpm: (Int) -> Unit,
    onSetCountInBars: (Int) -> Unit,
    onSetVisualMetronome: (Boolean) -> Unit,
    defaultName: () -> String,
    modifier: Modifier = Modifier,
) {
    var saving by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The clock at the top, the waveform filling everything between it and the buttons, the
        // beat under the thumb. Each is where it is because of how it gets looked at: the time is
        // glanced at, the waveform is watched, and the beat is taken in without looking at all.
        when (state.phase) {
            RecordPhase.IDLE -> if (state.error != null) {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.record,
                    textAlign = TextAlign.Center,
                )
            }

            RecordPhase.COUNT_IN -> Text(
                "Counting in…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            else -> {
                val running = state.phase == RecordPhase.RECORDING
                Text(
                    formatDuration(state.elapsedMs, tenths = true),
                    style = MaterialTheme.typography.displayMedium,
                    color = if (running) MaterialTheme.colorScheme.record
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (running) "Recording" else "Take ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Present from the first launch, empty but for its zero line. A blank half-screen
            // says nothing; a marked-out one says the take will appear here.
            LiveWaveform(
                level = state.level,
                running = state.phase == RecordPhase.RECORDING,
                hasTake = state.hasTake,
                monitoring = settings.listenBeforeRecording &&
                    state.phase == RecordPhase.IDLE,
                modifier = Modifier.fillMaxSize(),
            )
            // Nothing is being captured during the count-in, so the count borrows the space.
            if (state.phase == RecordPhase.COUNT_IN) {
                Text(
                    state.countInBeatsLeft.coerceAtLeast(1).toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        when (state.phase) {
            RecordPhase.IDLE -> {
                TakeSettings(settings, onSetBpm, onSetCountInBars, onSetVisualMetronome)
                Spacer(Modifier.height(16.dp))
                BigButton(
                    text = "Record",
                    icon = Icons.Default.Mic,
                    onClick = onRecord,
                    container = MaterialTheme.colorScheme.record,
                    enabled = folderLabel != null,
                )
                if (folderLabel == null) {
                    TextButton(onClick = onChooseFolder) {
                        Text("Choose a folder to record into…")
                    }
                }
            }

            RecordPhase.COUNT_IN -> {
                BigButton("Cancel", icon = null, onClick = onDiscard, outlined = true)
            }

            RecordPhase.RECORDING -> {
                // The two that have to be hittable without looking.
                BigButton("Done", Icons.Default.Stop, onFinish)
                Spacer(Modifier.height(12.dp))
                BigButton("Restart", Icons.Default.Replay, onRestart, outlined = true)
            }

            RecordPhase.PAUSED -> {
                BigButton(
                    "Save", Icons.Default.Save,
                    onClick = {
                        // Unless the user asked to be asked, Save means saved: the generated name
                        // is the one the dialog would have offered anyway.
                        val generated = defaultName()
                        if (settings.promptForFilename) {
                            name = generated
                            saving = true
                        } else {
                            onSave(generated)
                        }
                    },
                    enabled = state.hasTake,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton(
                        "Restart", Icons.Default.Replay, onRestart,
                        modifier = Modifier.weight(1f), outlined = true,
                    )
                    BigButton(
                        "Discard", null, onDiscard,
                        modifier = Modifier.weight(1f), outlined = true,
                    )
                }
            }
        }

        if (settings.visualMetronome && state.phase != RecordPhase.IDLE) {
            Spacer(Modifier.height(14.dp))
            BeatDots(
                beats = rememberBeatPosition(
                    elapsedMs = state.elapsedMs,
                    running = state.phase == RecordPhase.RECORDING,
                    bpm = settings.bpm,
                ),
                beatsPerBar = settings.beatsPerBar,
                running = state.phase == RecordPhase.RECORDING,
            )
        }
    }

    if (saving) {
        SaveDialog(
            name = name,
            folderLabel = folderLabel.orEmpty(),
            durationMs = state.elapsedMs,
            onName = { name = it },
            onConfirm = {
                saving = false
                onSave(name)
            },
            onDismiss = { saving = false },
        )
    }
}

/**
 * Tempo, count-in and the metronome as one row of three — set before a take, hidden during one.
 *
 * These were three stacked rows of controls, which is a lot of screen for settings that are
 * usually already right. Here each is a value you can read at a glance and tap to change: the
 * common case is checking them, not changing them.
 */
@Composable
private fun TakeSettings(
    settings: Settings,
    onSetBpm: (Int) -> Unit,
    onSetCountInBars: (Int) -> Unit,
    onSetVisualMetronome: (Boolean) -> Unit,
) {
    var tempoOpen by rememberSaveable { mutableStateOf(false) }
    var countInOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingCell(
            label = "Tempo",
            value = "${settings.bpm} bpm",
            onClick = { tempoOpen = true },
            modifier = Modifier.weight(1f),
        )
        CellDivider()
        Box(Modifier.weight(1f)) {
            SettingCell(
                label = "Count-in",
                value = countInLabel(settings.countInBars),
                onClick = { countInOpen = true },
            )
            DropdownMenu(expanded = countInOpen, onDismissRequest = { countInOpen = false }) {
                for (bars in 0..2) {
                    DropdownMenuItem(
                        text = { Text(countInLabel(bars)) },
                        onClick = {
                            countInOpen = false
                            onSetCountInBars(bars)
                        },
                    )
                }
            }
        }
        CellDivider()
        // A boolean does not need a menu to choose between its two values.
        SettingCell(
            label = "Metronome",
            value = if (settings.visualMetronome) "On" else "Off",
            onClick = { onSetVisualMetronome(!settings.visualMetronome) },
            dimValue = !settings.visualMetronome,
            modifier = Modifier.weight(1f),
        )
    }

    if (tempoOpen) {
        TempoDialog(
            bpm = settings.bpm,
            onSetBpm = onSetBpm,
            onDismiss = { tempoOpen = false },
        )
    }
}

private fun countInLabel(bars: Int): String = when (bars) {
    0 -> "Off"
    1 -> "1 bar"
    else -> "$bars bars"
}

/** One third of the settings row: what it is, and what it currently says. */
@Composable
private fun SettingCell(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimValue: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = if (dimValue) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun CellDivider() {
    VerticalDivider(
        Modifier.height(28.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * Tempo three ways: the slider to get near, the arrows to land on it, and the number itself to
 * type when you already know what you want — "138" is quicker to say than to hunt for.
 *
 * Changes apply as they are made rather than on an OK, so the count-in and the beat dots are
 * already at the new tempo when the dialog closes.
 */
@Composable
private fun TempoDialog(bpm: Int, onSetBpm: (Int) -> Unit, onDismiss: () -> Unit) {
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    // An empty or nonsensical field commits nothing: the tempo stays where it was rather than
    // snapping to 40 because a digit was rubbed out on the way to typing another.
    fun commit() {
        typed.toIntOrNull()?.let(onSetBpm)
        typing = false
    }

    LaunchedEffect(typing) { if (typing) focus.requestFocus() }

    AlertDialog(
        onDismissRequest = {
            if (typing) commit()
            onDismiss()
        },
        title = { Text("Tempo") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            if (typing) commit()
                            onSetBpm(bpm - 1)
                        },
                        shape = ControlShape,
                    ) { Text("−") }
                    if (typing) {
                        OutlinedTextField(
                            value = typed,
                            // Digits only, and no more of them than a tempo can have: the field
                            // cannot be got into a state the dialog then has to argue with.
                            onValueChange = { typed = it.filter(Char::isDigit).take(3) },
                            singleLine = true,
                            shape = ControlShape,
                            textStyle = MaterialTheme.typography.headlineSmall
                                .copy(textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { commit() }),
                            modifier = Modifier.width(128.dp).focusRequester(focus),
                        )
                    } else {
                        Text(
                            "$bpm bpm",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .width(128.dp)
                                .clip(ControlShape)
                                .clickable {
                                    typed = bpm.toString()
                                    typing = true
                                }
                                .padding(vertical = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (typing) commit()
                            onSetBpm(bpm + 1)
                        },
                        shape = ControlShape,
                    ) { Text("+") }
                }
                Slider(
                    value = bpm.toFloat(),
                    onValueChange = {
                        typing = false
                        onSetBpm(it.toInt())
                    },
                    valueRange = MIN_BPM..MAX_BPM,
                    // The default draws both halves of the track in the accent colour, which
                    // leaves nothing to read the position against.
                    colors = SliderDefaults.colors(
                        inactiveTrackColor =
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (typing) commit()
                    onDismiss()
                },
            ) { Text("Done") }
        },
    )
}

@Composable
private fun SaveDialog(
    name: String,
    folderLabel: String,
    durationMs: Long,
    onName: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save take") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text("Name") },
                    singleLine = true,
                    shape = ControlShape,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${formatDuration(durationMs)} · into $folderLabel",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A thumb-height, full-width action. */
@Composable
private fun BigButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: androidx.compose.ui.graphics.Color? = null,
    outlined: Boolean = false,
    enabled: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    val shape = ControlShape
    val sized = modifier.height(64.dp).let { if (modifier == Modifier) it.fillMaxWidth() else it }
    if (outlined) {
        OutlinedButton(onClick, sized, enabled = enabled, shape = shape) { content() }
    } else {
        Button(
            onClick, sized, enabled = enabled, shape = shape,
            colors = if (container != null) {
                ButtonDefaults.buttonColors(containerColor = container)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) { content() }
    }
}

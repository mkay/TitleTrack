package de.singular.recorder.ui

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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.singular.recorder.RecorderViewModel
import de.singular.recorder.Settings
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
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (state.phase) {
                    RecordPhase.COUNT_IN -> {
                        Text(
                            state.countInBeatsLeft.coerceAtLeast(1).toString(),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Counting in…", style = MaterialTheme.typography.bodyMedium)
                    }

                    else -> {
                        val running = state.phase == RecordPhase.RECORDING
                        Text(
                            formatDuration(state.elapsedMs, tenths = true),
                            style = MaterialTheme.typography.displayMedium,
                            color = if (running) RecordRed else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (state.phase) {
                                RecordPhase.RECORDING -> "Recording"
                                RecordPhase.PAUSED -> "Take ready"
                                else -> if (state.error != null) state.error else "Ready"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        if (settings.visualMetronome && state.phase != RecordPhase.IDLE) {
                            Spacer(Modifier.height(20.dp))
                            val beats = rememberBeatPosition(
                                elapsedMs = state.elapsedMs,
                                running = running,
                                bpm = settings.bpm,
                            )
                            VisualMetronome(
                                beats = beats,
                                beatsPerBar = settings.beatsPerBar,
                                running = running,
                            )
                        }
                    }
                }
            }
        }

        LevelMeter(state.level, active = state.phase != RecordPhase.IDLE)
        Spacer(Modifier.height(20.dp))

        when (state.phase) {
            RecordPhase.IDLE -> {
                TakeSettings(settings, onSetBpm, onSetCountInBars, onSetVisualMetronome)
                Spacer(Modifier.height(16.dp))
                BigButton(
                    text = "Record",
                    icon = Icons.Default.Mic,
                    onClick = onRecord,
                    container = RecordRed,
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

/** Tempo, count-in and the silent metronome — set before a take, hidden during one. */
@Composable
private fun TakeSettings(
    settings: Settings,
    onSetBpm: (Int) -> Unit,
    onSetCountInBars: (Int) -> Unit,
    onSetVisualMetronome: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tempo", style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onSetBpm(settings.bpm - 1) },
                    shape = ControlShape,
                ) { Text("−") }
                Text(
                    "${settings.bpm} bpm",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(88.dp),
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    onClick = { onSetBpm(settings.bpm + 1) },
                    shape = ControlShape,
                ) { Text("+") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Count-in", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (bars in 0..2) {
                    val label = when (bars) {
                        0 -> "Off"
                        1 -> "1 bar"
                        else -> "$bars bars"
                    }
                    if (settings.countInBars == bars) {
                        Button(onClick = {}, shape = ControlShape) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { onSetCountInBars(bars) },
                            shape = ControlShape,
                        ) { Text(label) }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Visual metronome", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Silent — an audible click would end up in the take.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Switch(checked = settings.visualMetronome, onCheckedChange = onSetVisualMetronome)
        }
    }
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

package de.singular.recorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.singular.recorder.R
import de.singular.recorder.Settings
import de.singular.recorder.ThemeMode

@Composable
fun SettingsScreen(
    settings: Settings,
    folderLabel: String?,
    onChooseFolder: () -> Unit,
    onSetBeatsPerBar: (Int) -> Unit,
    onSetListenBeforeRecording: (Boolean) -> Unit,
    onSetPromptForFilename: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Section("Recordings folder")
        Text(
            folderLabel ?: "Not chosen yet",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Takes land here as 44.1 kHz mono WAV, tempo included.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onChooseFolder, shape = ControlShape) {
            Text(if (folderLabel == null) "Choose folder…" else "Change folder…")
        }

        Spacer(Modifier.height(24.dp))
        Section("Microphone")
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Listen before recording", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Check levels and catch clipping before you play, not after. Keeps the " +
                        "microphone open, so its indicator stays lit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Switch(
                checked = settings.listenBeforeRecording,
                onCheckedChange = onSetListenBeforeRecording,
            )
        }

        Spacer(Modifier.height(24.dp))
        Section("Saving")
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Prompt for a filename", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Off: takes are saved at once, named by date and time. Rename them later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Switch(
                checked = settings.promptForFilename,
                onCheckedChange = onSetPromptForFilename,
            )
        }

        Spacer(Modifier.height(24.dp))
        Section("Time signature")
        Text(
            "Beats to the bar, for the count-in and the metronome.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (beats in listOf(3, 4, 6)) {
                if (settings.beatsPerBar == beats) {
                    Button(onClick = {}, shape = ControlShape) { Text("$beats/4") }
                } else {
                    OutlinedButton(
                        onClick = { onSetBeatsPerBar(beats) },
                        shape = ControlShape,
                    ) { Text("$beats/4") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Section("Display")
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_brightness_alert),
                contentDescription = null,
                Modifier.padding(end = 16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("Keep the screen on", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Your hands are on the instrument. Costs battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Switch(checked = settings.keepScreenOn, onCheckedChange = onSetKeepScreenOn)
        }

        Spacer(Modifier.height(12.dp))
        Text("Theme", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in ThemeMode.entries) {
                val label = mode.name.lowercase().replaceFirstChar { it.uppercase() }
                if (settings.themeMode == mode) {
                    Button(onClick = {}, shape = ControlShape) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { onSetThemeMode(mode) },
                        shape = ControlShape,
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

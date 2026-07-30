// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.singular.recorder.R
import de.singular.recorder.Settings
import de.singular.recorder.ThemeMode

/**
 * The two halves of the settings, and the page that is not a setting at all.
 *
 * The split is by *when you come here*, not by what the settings technically are. **Recording** is
 * everything that shapes the next take — the things a musician changes between sets, or while
 * working out how to record a room. **System** is the app's own set-up: where takes go, how the
 * library sorts, what the screen does. One is visited often and the other is visited twice.
 *
 * Saving is under Recording rather than System, which is the one placement worth arguing: naming a
 * take happens at the end of a take, with the instrument still in hand, and it is part of that loop
 * rather than part of the app's set-up.
 *
 * **About** is the odd one, and is here because the tab row is the only always-visible strip on this
 * screen. It was a row at the bottom of System, which is where a footer item belongs right up until
 * the settings acquire tabs — then it is a destination buried inside one of two halves, with nothing
 * about "System" that says the app's version and its bug tracker are in there. A tab is one tap from
 * either half and says its own name whichever half is showing.
 *
 * The honest cost is that two of these tabs set things and the third does not, so the row is no
 * longer three of a kind. The alternative was pinning the About row under the tabs, which puts a
 * destination *above* the settings and inverts where a footer sits; the bottom of the screen was not
 * available, being already the mini player and the app's own tabs. Between a slightly mixed tab row
 * and a slightly upside-down page, the tab row is the one that stays legible as it grows.
 */
enum class SettingsTab(val title: String) {
    RECORDING("Recording"),
    SYSTEM("System"),
    ABOUT("About"),
}

@Composable
fun SettingsScreen(
    settings: Settings,
    folderLabel: String?,
    onChooseFolder: () -> Unit,
    onSetBeatsPerBar: (Int) -> Unit,
    onSetAudioMetronome: (Boolean) -> Unit,
    onSetListenBeforeRecording: (Boolean) -> Unit,
    onSetPromptForFilename: (Boolean) -> Unit,
    onSetStarredFirst: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local, unlike the library's tab: back from here leaves the settings altogether, so there is
    // no back chain a level up that needs to know which half is showing.
    var tab by rememberSaveable { mutableStateOf(SettingsTab.RECORDING) }

    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
            SettingsTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = { Text(entry.title) },
                )
            }
        }
        // Each tab scrolls on its own, rather than the row of settings being poured into one shared
        // scroller. Two reasons: About brings its own layout and its own scroll — it is a page, not
        // a column of rows — and a shared scroll state carried its offset across a tab switch, so
        // arriving at System already halfway down it.
        when (tab) {
            SettingsTab.RECORDING -> SettingsPage {
                RecordingSettings(
                    settings = settings,
                    onSetBeatsPerBar = onSetBeatsPerBar,
                    onSetAudioMetronome = onSetAudioMetronome,
                    onSetListenBeforeRecording = onSetListenBeforeRecording,
                    onSetPromptForFilename = onSetPromptForFilename,
                )
            }

            SettingsTab.SYSTEM -> SettingsPage {
                SystemSettings(
                    settings = settings,
                    folderLabel = folderLabel,
                    onChooseFolder = onChooseFolder,
                    onSetStarredFirst = onSetStarredFirst,
                    onSetKeepScreenOn = onSetKeepScreenOn,
                    onSetThemeMode = onSetThemeMode,
                )
            }

            SettingsTab.ABOUT -> AboutScreen()
        }
    }
}

/** The ground the two settings halves stand on: the whole tab, scrolling, at the page's margin. */
@Composable
private fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        content = content,
    )
}

@Composable
private fun RecordingSettings(
    settings: Settings,
    onSetBeatsPerBar: (Int) -> Unit,
    onSetAudioMetronome: (Boolean) -> Unit,
    onSetListenBeforeRecording: (Boolean) -> Unit,
    onSetPromptForFilename: (Boolean) -> Unit,
) {
    Section("Microphone")
    SwitchRow(
        title = "Listen before recording",
        detail = "Check levels and catch clipping before you play, not after. Keeps the " +
            "microphone open, so its indicator stays lit.",
        checked = settings.listenBeforeRecording,
        onCheckedChange = onSetListenBeforeRecording,
    )

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
                Button(
                    onClick = {},
                    shape = ControlShape,
                    colors = brandButtonColors(),
                ) { Text("$beats/4") }
            } else {
                OutlinedButton(
                    onClick = { onSetBeatsPerBar(beats) },
                    shape = ControlShape,
                ) { Text("$beats/4") }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    Section("Metronome")
    SwitchRow(
        title = "Click while recording",
        detail = "For headphones. On a speaker the microphone hears the click too, and it lands " +
            "in the take for good. The count-in clicks either way, and the beat on screen is " +
            "always silent.",
        checked = settings.audioMetronome,
        onCheckedChange = onSetAudioMetronome,
    )

    Spacer(Modifier.height(24.dp))
    Section("Saving")
    SwitchRow(
        title = "Prompt for a filename",
        detail = "Off: takes are saved at once, named by date and time. Rename them later.",
        checked = settings.promptForFilename,
        onCheckedChange = onSetPromptForFilename,
    )
}

@Composable
private fun SystemSettings(
    settings: Settings,
    folderLabel: String?,
    onChooseFolder: () -> Unit,
    onSetStarredFirst: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
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
    Section("Library")
    SwitchRow(
        title = "Starred takes first",
        detail = "Off: every folder stays in date order. The Starred tab still gathers them.",
        checked = settings.starredFirst,
        onCheckedChange = onSetStarredFirst,
    )

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
        Switch(
            checked = settings.keepScreenOn,
            onCheckedChange = onSetKeepScreenOn,
            colors = brandSwitchColors(),
        )
    }

    Spacer(Modifier.height(12.dp))
    Text("Theme", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in ThemeMode.entries) {
            val label = mode.name.lowercase().replaceFirstChar { it.uppercase() }
            if (settings.themeMode == mode) {
                Button(
                    onClick = {},
                    shape = ControlShape,
                    colors = brandButtonColors(),
                ) { Text(label) }
            } else {
                OutlinedButton(
                    onClick = { onSetThemeMode(mode) },
                    shape = ControlShape,
                ) { Text(label) }
            }
        }
    }
}

/** A setting that is on or off, with the sentence that says what off means. */
@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = brandSwitchColors())
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

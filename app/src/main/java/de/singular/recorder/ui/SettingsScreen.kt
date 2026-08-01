// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import de.singular.recorder.R
import java.util.Locale
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
enum class SettingsTab(@StringRes val title: Int) {
    RECORDING(R.string.settings_tab_recording),
    SYSTEM(R.string.settings_tab_system),
    ABOUT(R.string.settings_tab_about),
}

@Composable
fun SettingsScreen(
    settings: Settings,
    tab: SettingsTab,
    onTabChange: (SettingsTab) -> Unit,
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
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
            SettingsTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { onTabChange(entry) },
                    text = { Text(stringResource(entry.title)) },
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
    Section(R.string.settings_section_microphone)
    SwitchRow(
        title = R.string.setting_listen_title,
        detail = R.string.setting_listen_detail,
        checked = settings.listenBeforeRecording,
        onCheckedChange = onSetListenBeforeRecording,
    )

    Spacer(Modifier.height(24.dp))
    Section(R.string.settings_section_time_signature)
    Text(
        stringResource(R.string.settings_time_signature_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (beats in listOf(3, 4, 6)) {
            val label = stringResource(R.string.time_signature_over_four, beats)
            if (settings.beatsPerBar == beats) {
                Button(
                    onClick = {},
                    shape = ControlShape,
                    colors = brandButtonColors(),
                ) { Text(label) }
            } else {
                OutlinedButton(
                    onClick = { onSetBeatsPerBar(beats) },
                    shape = ControlShape,
                ) { Text(label) }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    Section(R.string.settings_section_metronome)
    SwitchRow(
        title = R.string.setting_click_title,
        detail = R.string.setting_click_detail,
        checked = settings.audioMetronome,
        onCheckedChange = onSetAudioMetronome,
    )

    Spacer(Modifier.height(24.dp))
    Section(R.string.settings_section_saving)
    SwitchRow(
        title = R.string.setting_prompt_name_title,
        detail = R.string.setting_prompt_name_detail,
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
    Section(R.string.settings_section_folder)
    Text(
        folderLabel ?: stringResource(R.string.settings_folder_none),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(R.string.settings_folder_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onChooseFolder, shape = ControlShape) {
        Text(
            stringResource(
                if (folderLabel == null) R.string.action_choose_folder
                else R.string.action_change_folder,
            ),
        )
    }

    Spacer(Modifier.height(24.dp))
    Section(R.string.settings_section_library)
    SwitchRow(
        title = R.string.setting_starred_first_title,
        detail = R.string.setting_starred_first_detail,
        checked = settings.starredFirst,
        onCheckedChange = onSetStarredFirst,
    )

    Spacer(Modifier.height(24.dp))
    Section(R.string.settings_section_display)
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
            Text(
                stringResource(R.string.setting_keep_screen_on_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.setting_keep_screen_on_detail),
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
    Text(stringResource(R.string.setting_theme), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in ThemeMode.entries) {
            val label = stringResource(mode.label)
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

    Spacer(Modifier.height(24.dp))
    LanguageSetting()
}

/** What each [ThemeMode] is called on screen. The enum's own name is an identifier, not a label. */
private val ThemeMode.label: Int
    @StringRes get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

/** A setting that is on or off, with the sentence that says what off means. */
@Composable
private fun SwitchRow(
    @StringRes title: Int,
    @StringRes detail: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = brandSwitchColors())
    }
}

@Composable
private fun Section(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

/**
 * One offered language: its BCP-47 [tag], or null for "follow the device".
 *
 * [label] is deliberately the language's name *in that language* — Deutsch, not German. Someone who
 * has landed in a language they can't read needs to find their way out, and the only word on the
 * screen they are sure to recognise is their own language's name for itself.
 */
private data class LanguageChoice(val tag: String?, val label: String)

/**
 * The languages on offer: the device default, then everything listed in `supported_locales`,
 * sorted by how each names itself.
 */
@Composable
private fun rememberLanguageChoices(): List<LanguageChoice> {
    val systemLabel = stringResource(R.string.language_system)
    val tags = stringArrayResource(R.array.supported_locales)
    return remember(systemLabel, tags) {
        val offered = tags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            // Ask the locale to name itself, then fix the case: several languages write their own
            // name lowercase mid-sentence (français, español) but expect a capital when it stands
            // alone as a label.
            val own = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
            LanguageChoice(tag, own)
        }.sortedBy { it.label }
        listOf(LanguageChoice(null, systemLabel)) + offered
    }
}

/** The current app language as a BCP-47 tag, or null when it is following the device. */
private fun currentLanguageTag(): String? =
    AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotEmpty() }
        ?.substringBefore(',')

/**
 * The language row and its picker.
 *
 * Applying a choice goes through [AppCompatDelegate], not a preference of our own: on Android 13+
 * that writes through to the system's per-app language, so this picker and the one in Android's own
 * Settings agree with each other instead of quietly disagreeing. Selecting recreates the activity,
 * which is what re-reads the resources — so there is nothing to do afterwards.
 */
@Composable
private fun LanguageSetting() {
    val choices = rememberLanguageChoices()
    // Read once per composition rather than held in state: the activity is recreated on change, so
    // this is re-read with the new value on the way back up.
    val currentTag = currentLanguageTag()
    val current = choices.firstOrNull { it.tag == currentTag } ?: choices.first()
    var picking by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .clickable { picking = true }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            Modifier.padding(end = 16.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.setting_language), style = MaterialTheme.typography.bodyLarge)
            Text(
                current.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.setting_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(ControlShape)
                                .clickable {
                                    picking = false
                                    AppCompatDelegate.setApplicationLocales(
                                        if (choice.tag == null) {
                                            LocaleListCompat.getEmptyLocaleList()
                                        } else {
                                            LocaleListCompat.forLanguageTags(choice.tag)
                                        },
                                    )
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = choice.tag == current.tag,
                                onClick = null, // the whole row is the target
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.brandFill,
                                ),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

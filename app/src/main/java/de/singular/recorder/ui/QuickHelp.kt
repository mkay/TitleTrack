// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.singular.recorder.R

/**
 * The things a screen cannot say for itself.
 *
 * Everything here is a **hold or a second tap** — gestures that have no affordance and so cannot be
 * discovered by looking, only by being told. Anything with a label on it is deliberately absent: a
 * help page that lists what the buttons say is a help page nobody finishes reading, and the two
 * would then have to be kept in agreement forever.
 *
 * Pinned to the bottom of the drawer because it is wanted mid-take, with the instrument still in
 * hand. About is not, and lives in Settings — see [SettingsScreen].
 */
@Composable
fun QuickHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.help_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HelpItem(R.string.help_level_title, R.string.help_level_body)
                HelpItem(R.string.help_click_title, R.string.help_click_body)
                HelpItem(R.string.help_replay_title, R.string.help_replay_body)
                HelpItem(R.string.help_loop_title, R.string.help_loop_body)
                HelpItem(R.string.help_trim_title, R.string.help_trim_body)
                HelpItem(R.string.help_select_title, R.string.help_select_body)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_got_it)) } },
    )
}

@Composable
private fun HelpItem(@StringRes title: Int, @StringRes body: Int) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

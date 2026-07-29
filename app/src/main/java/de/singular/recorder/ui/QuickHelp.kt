// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

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
import androidx.compose.ui.unit.dp

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
        title = { Text("Quick help") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HelpItem(
                    "Set your level",
                    "Hold Record and play the loudest thing you are going to play. It offers a " +
                        "gain that leaves the take room to be louder than the rehearsal.",
                )
                HelpItem(
                    "Hear the click through the take",
                    "Long-press Metronome on the record row. For headphones only — on a speaker " +
                        "the microphone hears it too, and it lands in the take for good.",
                )
                HelpItem(
                    "Play a take again",
                    "Double-tap Play to start it from the beginning; a single tap picks up where " +
                        "you left it.",
                )
                HelpItem(
                    "Loop a take",
                    "Hold Play. The whole take repeats until you stop it — the lemniscate on the " +
                        "button says it is on.",
                )
                HelpItem(
                    "Pick out several takes",
                    "Long-press a take in the library. The bar becomes the selection's, for " +
                        "moving or deleting the lot.",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

@Composable
private fun HelpItem(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

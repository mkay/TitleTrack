package de.singular.recorder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.singular.recorder.MovePicker
import de.singular.recorder.storage.Folder

/**
 * Where should this go? — the folder tree, walked down to somewhere to put a take.
 *
 * A dialog rather than a screen, and it browses its own copy of the tree: the list underneath is
 * still showing the folder the take is coming out of, which is where the user wants to be left when
 * the move is done. Only sub-folders are listed, because the takes in them are not what is being
 * chosen, and any folder can be landed in — including the one on screen, which is what "Move here"
 * means at every level.
 */
@Composable
fun MoveDialog(
    picker: MovePicker,
    onOpenFolder: (Folder) -> Unit,
    onUp: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (picker.uris.size == 1) "Move to…" else "Move ${picker.uris.size} to…")
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    picker.destination.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (picker.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                // Capped rather than free: a folder of twenty sub-folders would otherwise push the
                // buttons off the bottom of the screen, and the buttons are the point of the dialog.
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    if (picker.canGoUp) {
                        item {
                            PickerRow(
                                icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(20.dp)) },
                                label = "Up one level",
                                onClick = onUp,
                            )
                        }
                    }
                    items(picker.folders, key = { it.uri.toString() }) { folder ->
                        PickerRow(
                            icon = {
                                Icon(
                                    Icons.Default.Folder,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            label = folder.name,
                            onClick = { onOpenFolder(folder) },
                        )
                    }
                    if (!picker.loading && picker.folders.isEmpty()) {
                        item {
                            Text(
                                "No sub-folders here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Move here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

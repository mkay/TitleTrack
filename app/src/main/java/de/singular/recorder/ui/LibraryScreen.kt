package de.singular.recorder.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.singular.recorder.LibraryState
import de.singular.recorder.PlaybackState
import de.singular.recorder.storage.Folder
import de.singular.recorder.storage.Take

/**
 * The recordings folder, browsed in place.
 *
 * A flat list of takes stops being usable at about twenty, which one afternoon produces — so the
 * folder the user granted is browsed as the tree it already is, sub-folders and all, rather than
 * being flattened into a library with tags. Whatever they organise here is the same thing they will
 * see from a desktop, which is the point of recording into their storage rather than ours.
 */
@Composable
fun LibraryScreen(
    state: LibraryState,
    playback: PlaybackState,
    onOpenFolder: (Folder) -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onCreateFolder: (String) -> Unit,
    onPlay: (Take) -> Unit,
    onOpen: (Take) -> Unit,
    onRename: (Uri, String) -> Unit,
    onDelete: (Uri) -> Unit,
    onShare: (Take) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) } // uri being renamed
    var renamingName by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingName by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Breadcrumb(state.path, onBreadcrumb, Modifier.weight(1f))
            IconButton(onClick = { newFolder = true }, enabled = state.current != null) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "New sub-folder")
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()

        val listing = state.listing
        when {
            state.current == null -> Hint("Choose a folder to keep your recordings in.", Modifier.weight(1f))
            listing?.error != null -> Hint(listing.error, Modifier.weight(1f))
            listing != null && listing.folders.isEmpty() && listing.takes.isEmpty() ->
                Hint("Nothing here yet. Recorded takes will appear in this folder.", Modifier.weight(1f))

            listing != null -> LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(listing.folders, key = { _, it -> it.uri.toString() }) { i, folder ->
                    if (i > 0) RowDivider()
                    FolderRow(
                        folder = folder,
                        onOpen = { onOpenFolder(folder) },
                        onRename = {
                            renaming = folder.uri.toString()
                            renamingName = folder.name
                        },
                        onDelete = {
                            deleting = folder.uri.toString()
                            deletingName = folder.name
                        },
                    )
                }
                itemsIndexed(listing.takes, key = { _, it -> it.uri.toString() }) { i, take ->
                    if (i > 0 || listing.folders.isNotEmpty()) RowDivider()
                    TakeRow(
                        take = take,
                        playing = playback.uri == take.uri && playback.playing,
                        onPlay = { onPlay(take) },
                        onOpen = { onOpen(take) },
                        onRename = {
                            renaming = take.uri.toString()
                            renamingName = take.name.substringBeforeLast('.')
                        },
                        onDelete = {
                            deleting = take.uri.toString()
                            deletingName = take.name
                        },
                        onShare = { onShare(take) },
                    )
                }
            }

            else -> Spacer(Modifier.weight(1f))
        }
    }

    if (newFolder) {
        NameDialog(
            title = "New sub-folder",
            initial = "",
            confirm = "Create",
            onConfirm = {
                newFolder = false
                onCreateFolder(it)
            },
            onDismiss = { newFolder = false },
        )
    }

    renaming?.let { uri ->
        NameDialog(
            title = "Rename",
            initial = renamingName,
            confirm = "Rename",
            onConfirm = {
                renaming = null
                onRename(Uri.parse(uri), it)
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { uri ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete?") },
            text = { Text("“$deletingName” will be removed from your storage. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(Uri.parse(uri))
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Breadcrumb(path: List<Folder>, onJump: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        path.forEachIndexed { index, folder ->
            if (index > 0) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    Modifier.size(16.dp),
                )
            }
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (index == path.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clickable(enabled = index != path.lastIndex) { onJump(index) }
                    .padding(vertical = 12.dp, horizontal = 2.dp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: Folder,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            // The same 48dp footprint the play button has, so folder names and take names start
            // at the same place however the list is mixed.
            Modifier.size(LeadingSlot).padding(12.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RowMenu(onRename = onRename, onDelete = onDelete, onShare = null)
    }
}

/**
 * One take: the triangle plays it where it stands, the name opens it in the player. A stray tap on
 * the row lands on the name, which is the one of the two that does not make a noise.
 */
@Composable
private fun TakeRow(
    take: Take,
    playing: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlay) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Stop" else "Play",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                take.name.substringBeforeLast('.'),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                buildString {
                    append(formatDuration(take.durationMs))
                    take.bpm?.let {
                        append(" · ")
                        append(if (it == it.toInt().toFloat()) "${it.toInt()}" else "$it")
                        append(" bpm")
                    }
                    append(" · ")
                    append(formatSize(take.sizeBytes))
                    val date = formatDate(take.modifiedAt)
                    if (date.isNotEmpty()) {
                        append(" · ")
                        append(date)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
        RowMenu(onRename = onRename, onDelete = onDelete, onShare = onShare)
    }
}

/**
 * Rows are as tall as the 48dp touch targets inside them and no taller: the icon buttons set the
 * height, so the padding only has to keep them off each other rather than reserve room of its own.
 */
private val RowPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val LeadingSlot = 48.dp

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun RowMenu(onRename: () -> Unit, onDelete: () -> Unit, onShare: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            if (onShare != null) {
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = {
                        open = false
                        onShare()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = ControlShape,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

package de.singular.recorder.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.singular.recorder.LibraryState
import de.singular.recorder.R
import de.singular.recorder.PlaybackState
import de.singular.recorder.StarredTake
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
    /** Opens the destination picker for one take, or for a whole batch of them. */
    onMove: (List<Uri>) -> Unit,
    selection: Set<String>,
    onToggleSelect: (String) -> Unit,
    starredKeys: Set<String>,
    starKeyOf: (Uri) -> String?,
    onToggleStar: (Take) -> Unit,
    /** Which takes have something written against them. Keys, not text — the row only shows that
     *  there is one. */
    notedKeys: Set<String>,
    starredTakes: List<StarredTake>?,
    onLoadStarred: () -> Unit,
    // Hoisted, because back has to be able to leave the starred tab, and the whole back chain
    // lives with the screen state a level up. Handling it here would leave which handler wins to
    // the order the two compositions happen to register in.
    tab: LibraryTab,
    onTabChange: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selecting = selection.isNotEmpty()

    // Resolved on arrival rather than held: takes can be renamed, moved or deleted between one
    // look and the next, and a stale list of favourites is worse than a moment's wait.
    LaunchedEffect(tab, starredKeys) { if (tab == LibraryTab.STARRED) onLoadStarred() }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) } // uri being renamed
    var renamingName by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingName by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        // Hidden while picking: the contextual bar has taken the top of the screen, and switching
        // tabs mid-batch would abandon a selection that only means anything in the folder it was
        // made in.
        if (!selecting) {
            TabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
                LibraryTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { onTabChange(entry) },
                        text = { Text(entry.title) },
                    )
                }
            }
        }

        if (tab == LibraryTab.STARRED) {
            StarredList(
                takes = starredTakes,
                playback = playback,
                onPlay = onPlay,
                onOpen = onOpen,
                onToggleStar = onToggleStar,
                onRename = { take ->
                    renaming = take.uri.toString()
                    renamingName = take.name.substringBeforeLast('.')
                },
                onDelete = { take ->
                    deleting = take.uri.toString()
                    deletingName = take.name
                },
                notedKeys = notedKeys,
                onShare = onShare,
                onMove = { take -> onMove(listOf(take.uri)) },
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

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
                        selected = folder.uri.toString() in selection,
                        selecting = selecting,
                        onOpen = { onOpenFolder(folder) },
                        onToggleSelect = { onToggleSelect(folder.uri.toString()) },
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
                        selected = take.uri.toString() in selection,
                        selecting = selecting,
                        starred = starKeyOf(take.uri) in starredKeys,
                        hasNote = starKeyOf(take.uri) in notedKeys,
                        onToggleStar = { onToggleStar(take) },
                        onPlay = { onPlay(take) },
                        onOpen = { onOpen(take) },
                        onToggleSelect = { onToggleSelect(take.uri.toString()) },
                        onRename = {
                            renaming = take.uri.toString()
                            renamingName = take.name.substringBeforeLast('.')
                        },
                        onDelete = {
                            deleting = take.uri.toString()
                            deletingName = take.name
                        },
                        onShare = { onShare(take) },
                        onMove = { onMove(listOf(take.uri)) },
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
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectionTint(selected)
            .rowClicks(selecting, onOpen, onToggleSelect)
            .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectionTick(selected)
        } else {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                // The same 48dp footprint the play button has, so folder names and take names start
                // at the same place however the list is mixed.
                Modifier.size(LeadingSlot).padding(12.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!selecting) RowMenu(onRename = onRename, onDelete = onDelete, onShare = null)
    }
}

/**
 * One take: the triangle plays it where it stands, the name opens it in the player. A stray tap on
 * the row lands on the name, which is the one of the two that does not make a noise.
 */
@Composable
internal fun TakeRow(
    take: Take,
    playing: Boolean,
    selected: Boolean,
    selecting: Boolean,
    starred: Boolean,
    /** Whether anything is written against this take — the glyph only says *that*, never what. */
    hasNote: Boolean = false,
    onToggleStar: () -> Unit,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    // Where the take lives, for lists that gather takes from more than one folder. Null in the
    // library itself, where every row is in the folder named at the top of the screen already.
    folder: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectionTint(selected)
            .rowClicks(selecting, onOpen, onToggleSelect)
            .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tick takes the play button's slot rather than the menu's, which is not where the
        // pattern usually puts it: play is the one control here that would make a noise if it were
        // still live while picking, so it is the one that has to go.
        if (selecting) {
            SelectionTick(selected)
        } else {
            IconButton(onClick = onPlay) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Stop" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                take.name.substringBeforeLast('.'),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                buildString {
                    formatKind(take.name).takeIf { it.isNotEmpty() }?.let {
                        append(it)
                        append(" · ")
                    }
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
                    // Last, not first: the name is what is being scanned for, and a folder
                    // prefixed to every line would push the useful part of each one rightwards.
                    if (folder != null) {
                        append(" · ")
                        append(folder)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
        // A mark, not a control: it says there is something written here, and the player is where it
        // is read. In a fixed slot beside the star rather than after the name, which is where it
        // started — a name long enough to be clipped still measures the full width it was given, so
        // the mark ended up stranded out at the row's edge on exactly the rows it mattered on, and
        // hugging the name on the short ones. A status mark wants a column that can be scanned, not
        // a position that moves with the text.
        //
        // It keeps its slot when absent, so the star does not shift left and right down the list.
        if (!selecting) {
            Box(Modifier.size(NoteSlot), contentAlignment = Alignment.Center) {
                if (hasNote) {
                    Icon(
                        ImageVector.vectorResource(R.drawable.ic_comment),
                        contentDescription = "Has a note",
                        Modifier.size(16.dp),
                        // The same amber the player's Note button wears once there is one, so the
                        // two say the same thing in the same colour.
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        // Hidden while picking, as the play button is: one meaning per tap, and a row being added
        // to a batch is not the moment to be changing what it is.
        if (!selecting) {
            IconButton(onClick = onToggleStar, modifier = Modifier.size(StarSlot)) {
                Icon(
                    if (starred) Icons.Default.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (starred) "Starred" else "Not starred",
                    tint = if (starred) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        // Faint: an unstarred take is the normal case, and a folder of them should
                        // not read as a column of grey stars down the side of the list.
                        MaterialTheme.colorScheme.onSurface.copy(alpha = UnstarredTint)
                    },
                )
            }
            RowMenu(
                onRename = onRename,
                onDelete = onDelete,
                onShare = onShare,
                onMove = onMove,
            )
        }
    }
}

/**
 * Long-press starts picking, and once picking a tap picks too rather than opening — one meaning per
 * tap, so a batch can be built without a stray tap navigating out of the list halfway through.
 */
private fun Modifier.rowClicks(
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
) = composed {
    val haptic = LocalHapticFeedback.current
    combinedClickable(
        onClick = { if (selecting) onToggleSelect() else onOpen() },
        onLongClick = {
            // Confirm the grab in the hand: the gesture has no on-screen affordance until it fires.
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggleSelect()
        },
    )
}

/**
 * A picked row, as a wash of the accent rather than a slab of it.
 *
 * The usual choice is `secondaryContainer`, which this app maps to the accent itself — a solid gold
 * row, and the accent at full strength across a whole list is exactly what the palette spent its
 * time getting away from. A low-alpha tint reads as "picked" just as plainly and leaves the row's own
 * text colours alone, which matters here because these rows carry two tiers of type.
 */
private fun Modifier.selectionTint(selected: Boolean) = composed {
    if (selected) background(MaterialTheme.colorScheme.primary.copy(alpha = SelectedTint)) else this
}

@Composable
private fun SelectionTick(selected: Boolean) {
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = if (selected) "Selected" else "Not selected",
        Modifier.size(LeadingSlot).padding(12.dp),
        tint = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        },
    )
}

/**
 * Rows are as tall as the 48dp touch targets inside them and no taller: the icon buttons set the
 * height, so the padding only has to keep them off each other rather than reserve room of its own.
 */
private val RowPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val LeadingSlot = 48.dp

/** Enough accent to mark the row, not enough to colour it — see [selectionTint]. */
private const val SelectedTint = 0.14f

/**
 * The star is narrower than the 48dp the play button and the menu take. Three touch targets on one
 * row is already most of its width, and the name is what gets squeezed — 40dp is still comfortably
 * over the 32dp a thumb needs when the targets either side of it are full size.
 */
private val StarSlot = 40.dp

/**
 * The note mark's slot. Narrower than the star's, being a mark rather than a target — nothing here
 * takes a tap, so it needs room for the glyph and no more, and the name keeps the rest.
 */
private val NoteSlot = 24.dp
private const val UnstarredTint = 0.30f

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun RowMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: (() -> Unit)?,
    onMove: (() -> Unit)? = null,
) {
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
            if (onMove != null) {
                DropdownMenuItem(
                    text = { Text("Move to…") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                    onClick = {
                        open = false
                        onMove()
                    },
                )
            }
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
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/** The two ways of looking at the same takes: where they sit, and which ones were kept. */
enum class LibraryTab(val title: String) {
    ALL("All files"),
    STARRED("Starred"),
}

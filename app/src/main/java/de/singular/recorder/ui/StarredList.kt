package de.singular.recorder.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.singular.recorder.PlaybackState
import de.singular.recorder.StarredTake
import de.singular.recorder.storage.Take

/**
 * Every starred take, wherever it lives.
 *
 * The one list in the app that is not a folder. Takes are browsed as the tree they sit in, which is
 * the right way round for finding an afternoon's work but no way at all to find the four keepers
 * scattered across a year of it. Starring marks those; this is where the marks are cashed in — flat,
 * newest first, each row naming the folder it came from because the take's own name does not.
 *
 * It sits beside the files as a tab rather than off in the drawer. A drawer is for the places you
 * visit occasionally, and if the keepers are worth marking they are worth having a thumb's reach
 * away from the list they were marked in.
 *
 * Rename and delete are raised to the caller rather than handled here, so the two tabs share one
 * set of dialogs and a take renamed in either place is renamed the same way.
 */
@Composable
fun StarredList(
    takes: List<StarredTake>?,
    playback: PlaybackState,
    onPlay: (Take) -> Unit,
    onOpen: (Take) -> Unit,
    onToggleStar: (Take) -> Unit,
    onRename: (Take) -> Unit,
    onDelete: (Take) -> Unit,
    onShare: (Take) -> Unit,
    onMove: (Take) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        // Null rather than empty: not yet looked, as against looked and found nothing. Saying
        // "nothing starred yet" before the lookup returns would be a lie for as long as it took.
        takes == null -> LinearProgressIndicator(modifier.fillMaxWidth())
        takes.isEmpty() -> Hint("Nothing starred yet. Tap the star on a take to keep it here.", modifier)
        else -> LazyColumn(modifier) {
            itemsIndexed(takes, key = { _, it -> it.key }) { i, starred ->
                if (i > 0) RowDivider()
                TakeRow(
                    take = starred.take,
                    playing = playback.uri == starred.take.uri && playback.playing,
                    selected = false,
                    // No multi-select here. It exists in the files tab to delete a batch, and
                    // deleting from a list of favourites is not what this one is for — unstarring
                    // is, and that is already one tap on the row.
                    selecting = false,
                    starred = true,
                    onToggleStar = { onToggleStar(starred.take) },
                    onPlay = { onPlay(starred.take) },
                    onOpen = { onOpen(starred.take) },
                    onToggleSelect = {},
                    onRename = { onRename(starred.take) },
                    onDelete = { onDelete(starred.take) },
                    onShare = { onShare(starred.take) },
                    onMove = { onMove(starred.take) },
                    folder = starred.folder,
                )
            }
        }
    }
}

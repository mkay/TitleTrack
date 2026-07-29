package de.singular.recorder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.singular.recorder.audio.AudioFormat
import de.singular.recorder.audio.RecordPhase
import de.singular.recorder.storage.Take
import de.singular.recorder.ui.AboutScreen
import de.singular.recorder.ui.ControlShape
import de.singular.recorder.ui.CompactTab
import de.singular.recorder.ui.CompactTabBar
import de.singular.recorder.ui.LibraryScreen
import de.singular.recorder.ui.LibraryTab
import de.singular.recorder.ui.MiniPlayer
import de.singular.recorder.ui.MoveDialog
import de.singular.recorder.ui.NameDialog
import de.singular.recorder.ui.NoteDialog
import de.singular.recorder.ui.PlayerScreen
import de.singular.recorder.ui.PlayerOverflowAction
import de.singular.recorder.ui.RecordScreen
import de.singular.recorder.ui.SettingsScreen
import de.singular.recorder.ui.TitleTrackTheme
import de.singular.recorder.ui.brandFill
import de.singular.recorder.ui.isDark
import kotlinx.coroutines.launch

private enum class Screen(val title: String) {
    RECORD("Record"),
    LIBRARY("Library"),
    SETTINGS("Settings"),
    ABOUT("About"),

    /** One take, opened from the library. The bar names the folder it came from, not this. */
    PLAYER("Take"),
}

/** The two halves of the app, side by side in the bottom bar: make one, then listen to it. */
private val TABS = listOf(Screen.RECORD, Screen.LIBRARY)

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val view = LocalView.current
            val vm: RecorderViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()

            // Keep the system bar icons legible against whichever theme is in effect (the
            // enableEdgeToEdge default only tracks the OS setting, not our in-app override).
            val dark = isDark(settings.themeMode)
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            TitleTrackTheme(settings.themeMode) {
                val recorderState by vm.recorderState.collectAsStateWithLifecycle()
                val library by vm.library.collectAsStateWithLifecycle()
                val playback by vm.playback.collectAsStateWithLifecycle()
                val openTake by vm.openTake.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                val busy by vm.busy.collectAsStateWithLifecycle()
                val looping by vm.looping.collectAsStateWithLifecycle()
                val notes by vm.notes.collectAsStateWithLifecycle()
                val levelTest by vm.levelTest.collectAsStateWithLifecycle()
                val starred by vm.starred.collectAsStateWithLifecycle()
                val starredTakes by vm.starredTakes.collectAsStateWithLifecycle()
                val movePicker by vm.movePicker.collectAsStateWithLifecycle()

                var screen by rememberSaveable { mutableStateOf(Screen.RECORD) }
                // Where leaving the player goes back to. The player is reached from three places —
                // a row in the library, the mini player from wherever it is showing, and a save on
                // the record screen — and "back to the library" is only right for the first. After
                // a save it is wrong in the way that matters most, because the reason to be on the
                // record screen is that another take is coming.
                var playerReturn by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }

                // The folder picker. Only the tree grant is asked for; everything below it is ours
                // to create and browse without asking again.
                val folderPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { uri -> uri?.let(vm::onFolderPicked) }

                // Recording is the whole app, so the microphone is asked for on the first press
                // rather than at launch — by then it is obvious what it is for. Granting it starts
                // the take immediately, so one press is enough.
                val micPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> if (granted) vm.startRecording() }

                val onRecord = {
                    if (vm.hasMicPermission) vm.startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }

                fun enterPlayer(take: Take) {
                    playerReturn = screen
                    vm.openTake(take)
                    screen = Screen.PLAYER
                }

                LaunchedEffect(message) {
                    message?.let {
                        snackbar.showSnackbar(it)
                        vm.clearMessage()
                    }
                }

                // Saving hands the take straight to the player. A take is saved in order to listen
                // back to it — leaving the record screen empty afterwards makes you go and find in
                // the library the thing you were just holding. The "Saved as …" snackbar still
                // fires, over the player, so the name is confirmed where the take now is.
                val justSaved by vm.justSaved.collectAsStateWithLifecycle()
                LaunchedEffect(justSaved) {
                    justSaved?.let {
                        enterPlayer(it)
                        vm.clearJustSaved()
                    }
                }

                // Hold the display awake while a take is in progress or the setting asks for it.
                // Released on dispose, so it can never leak into whatever is opened next.
                val awake = settings.keepScreenOn || recorderState.phase != RecordPhase.IDLE
                DisposableEffect(awake) {
                    view.keepScreenOn = awake
                    onDispose { view.keepScreenOn = false }
                }

                // Listen to the input, but only with the Record screen in front of a resumed
                // activity and no take running — never in the background, and never once a take
                // has the microphone. Leaving by any route releases it.
                val monitor = (settings.listenBeforeRecording || levelTest != null) &&
                    screen == Screen.RECORD &&
                    recorderState.phase == RecordPhase.IDLE &&
                    vm.hasMicPermission
                LifecycleResumeEffect(monitor) {
                    if (monitor) vm.startMonitoring()
                    onPauseOrDispose { vm.stopMonitoring() }
                }

                fun go(to: Screen) {
                    screen = to
                    scope.launch { drawerState.close() }
                }

                var showKeepAwakeInfo by remember { mutableStateOf(false) }
                if (showKeepAwakeInfo) {
                    AlertDialog(
                        onDismissRequest = { showKeepAwakeInfo = false },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_brightness_alert),
                                contentDescription = null,
                            )
                        },
                        title = { Text("Screen stays on") },
                        text = {
                            Text(
                                "“Keep the screen on” is enabled, so the display won't dim or " +
                                    "lock while Title Track is open. Handy with an instrument in " +
                                    "your hands, but it uses more battery.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showKeepAwakeInfo = false
                                    screen = Screen.SETTINGS
                                },
                            ) { Text("Settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showKeepAwakeInfo = false }) { Text("Got it") }
                        },
                    )
                }

                // Without a folder there is nowhere to put a take, so ask for one straight away —
                // on the first run, and again if the folder we were given is later revoked or
                // unmounted. Dismissing it only lasts for this launch: the app is still unusable,
                // and the Record screen keeps offering the picker.
                var folderPromptDismissed by remember { mutableStateOf(false) }
                LaunchedEffect(library.rootMissing) {
                    if (!library.rootMissing) folderPromptDismissed = false
                }
                if (library.rootMissing && !folderPromptDismissed) {
                    ChooseFolderDialog(
                        hadFolder = library.rootWasSet,
                        onChoose = {
                            folderPromptDismissed = true
                            folderPicker.launch(null)
                        },
                        onDismiss = { folderPromptDismissed = true },
                    )
                }

                fun leavePlayer() {
                    vm.closeTake()
                    screen = playerReturn
                }

                // Which library rows are picked, by document URI. Held here rather than in the
                // library screen because the app bar becomes the selection's own toolbar, and the
                // bar is this far up.
                var selection by remember { mutableStateOf(emptySet<String>()) }
                val selecting = screen == Screen.LIBRARY && selection.isNotEmpty()
                var deletingSelection by remember { mutableStateOf(false) }
                var deletingOpenTake by remember { mutableStateOf(false) }
                var renamingOpenTake by remember { mutableStateOf(false) }
                var notingOpenTake by remember { mutableStateOf(false) }

                // The note editor, opened from the menu or from tapping the note itself. Up here
                // with the rename dialog rather than inside the player, for the same reason: the
                // menu that opens it belongs to the app bar.
                openTake?.takeIf { notingOpenTake }?.let { open ->
                    NoteDialog(
                        initial = vm.noteFor(open.take.uri),
                        onConfirm = {
                            notingOpenTake = false
                            vm.setNote(open.take.uri, it)
                        },
                        onDismiss = { notingOpenTake = false },
                    )
                }

                // Renaming from the menu, which is the app bar's business rather than the player's.
                // The only rename on this screen now — the tools row's button and its own copy of
                // this dialog are gone.
                openTake?.takeIf { renamingOpenTake }?.let { open ->
                    NameDialog(
                        title = "Rename",
                        initial = open.take.name.substringBeforeLast('.'),
                        confirm = "Rename",
                        onConfirm = {
                            renamingOpenTake = false
                            vm.renameOpenTake(it)
                        },
                        onDismiss = { renamingOpenTake = false },
                    )
                }

                // Deleting the take you are listening to also has to get you out of the screen
                // showing it, or the player is left holding a file that is no longer there.
                openTake?.takeIf { deletingOpenTake }?.let { open ->
                    AlertDialog(
                        onDismissRequest = { deletingOpenTake = false },
                        title = { Text("Delete?") },
                        text = {
                            Text(
                                "“${open.take.name}” will be removed from your storage. " +
                                    "This cannot be undone.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                deletingOpenTake = false
                                val uri = open.take.uri
                                leavePlayer()
                                vm.deleteDocument(uri)
                            }) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { deletingOpenTake = false }) { Text("Cancel") }
                        },
                    )
                }
                var libraryTab by rememberSaveable { mutableStateOf(LibraryTab.ALL) }
                val onStarredTab = screen == Screen.LIBRARY && libraryTab == LibraryTab.STARRED

                // Deleting a batch is the one thing this mode exists for, and the one thing in the
                // app that cannot be undone — so it says how many and what kind before it goes.
                if (deletingSelection) {
                    val listing = library.listing
                    val folders = listing?.folders.orEmpty()
                        .count { it.uri.toString() in selection }
                    val takes = selection.size - folders
                    AlertDialog(
                        onDismissRequest = { deletingSelection = false },
                        title = { Text("Delete ${selection.size}?") },
                        text = {
                            Text(
                                buildString {
                                    append(
                                        listOfNotNull(
                                            takes.takeIf { it > 0 }
                                                ?.let { if (it == 1) "1 take" else "$it takes" },
                                            folders.takeIf { it > 0 }?.let {
                                                if (it == 1) "1 folder" else "$it folders"
                                            },
                                        ).joinToString(" and "),
                                    )
                                    if (folders > 0) append(", with everything inside")
                                    append(
                                        " will be removed from your storage. " +
                                            "This cannot be undone.",
                                    )
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                deletingSelection = false
                                selection.forEach { vm.deleteDocument(Uri.parse(it)) }
                                selection = emptySet()
                            }) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { deletingSelection = false }) { Text("Cancel") }
                        },
                    )
                }

                // Aiming a move: the tree, browsed for somewhere to put what is being moved. Held
                // up here with the other dialogs because a move can be started from three places —
                // a row, a batch, the player — and all three land in the same picker.
                movePicker?.let { picker ->
                    MoveDialog(
                        picker = picker,
                        onOpenFolder = vm::openMoveFolder,
                        onUp = vm::moveGoUp,
                        onConfirm = {
                            vm.confirmMove()
                            // The rows are on their way out of this folder; the batch is over.
                            selection = emptySet()
                        },
                        onDismiss = vm::cancelMove,
                    )
                }

                // A selection belongs to the folder it was made in: leave the library, walk into a
                // sub-folder, or have a row deleted underneath it, and it is over. Intersecting
                // rather than clearing keeps it across a bare refresh of the same listing.
                LaunchedEffect(screen, library.listing) {
                    val listing = library.listing
                    selection = when {
                        screen != Screen.LIBRARY || listing == null -> emptySet()
                        else -> {
                            val here = listing.folders.map { it.uri.toString() } +
                                listing.takes.map { it.uri.toString() }
                            selection.intersect(here.toSet())
                        }
                    }
                }

                // The whole back chain, in one place and with mutually exclusive guards — no two
                // of these are ever enabled at once, so which one runs does not depend on the order
                // the compositions happen to register them in.
                //
                // Drop the selection first: while picking, that is what the gesture is for, and
                // walking up a folder mid-batch is never what was meant. Then out of the player,
                // then off the starred tab, then up the folder tree, then home.
                BackHandler(enabled = selecting) { selection = emptySet() }
                BackHandler(enabled = !selecting && screen == Screen.PLAYER) { leavePlayer() }
                // Starred is a tab, not a place: leaving it returns to the files beside it, the
                // same way leaving a sub-folder goes up rather than out.
                BackHandler(enabled = !selecting && onStarredTab) { libraryTab = LibraryTab.ALL }
                BackHandler(
                    enabled = !selecting && !onStarredTab && screen == Screen.LIBRARY &&
                        library.canGoUp,
                ) { vm.goUp() }
                BackHandler(
                    enabled = !selecting && !onStarredTab && screen != Screen.RECORD &&
                        screen != Screen.PLAYER && !library.canGoUp,
                ) { screen = Screen.RECORD }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        // Four fifths of the width, as in RubberRing: the strip of scrim left
                        // over on the right is what you tap to close it again.
                        ModalDrawerSheet(Modifier.fillMaxWidth(0.8f)) {
                            Text(
                                "Title Track",
                                Modifier.padding(24.dp),
                            )
                            NavigationDrawerItem(
                                label = { Text(Screen.SETTINGS.title) },
                                icon = { Icon(Icons.Default.Settings, null) },
                                selected = screen == Screen.SETTINGS,
                                onClick = { go(Screen.SETTINGS) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            NavigationDrawerItem(
                                label = { Text(Screen.ABOUT.title) },
                                icon = { Icon(Icons.Default.Info, null) },
                                selected = screen == Screen.ABOUT,
                                onClick = { go(Screen.ABOUT) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    },
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                // While rows are picked the bar becomes the selection's own: a
                                // count, a way out, and the one action a batch is worth making.
                                // Tinted rather than filled with the accent — `secondaryContainer`
                                // is the accent in this app, and a full-width bar of it is the sort
                                // of thing the palette was tuned away from.
                                colors = if (selecting) {
                                    TopAppBarDefaults.topAppBarColors(
                                        containerColor =
                                            MaterialTheme.colorScheme.primaryContainer,
                                        titleContentColor =
                                            MaterialTheme.colorScheme.onPrimaryContainer,
                                        navigationIconContentColor =
                                            MaterialTheme.colorScheme.onPrimaryContainer,
                                        actionIconContentColor =
                                            MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                } else {
                                    TopAppBarDefaults.topAppBarColors()
                                },
                                title = {
                                    if (selecting) {
                                        Text("${selection.size} selected", maxLines = 1)
                                        return@TopAppBar
                                    }
                                    // The player names the take itself, in the middle of the
                                    // screen; the bar names where the arrow goes instead of saying
                                    // the same long filename twice — the folder the take sits in,
                                    // or the library itself for one at the top level.
                                    Text(
                                        if (screen == Screen.PLAYER) {
                                            library.current
                                                ?.takeIf { library.canGoUp }
                                                ?.name
                                                ?: Screen.LIBRARY.title
                                        } else {
                                            screen.title
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                navigationIcon = {
                                    if (selecting) {
                                        IconButton(onClick = { selection = emptySet() }) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Cancel selection",
                                            )
                                        }
                                    } else if (screen == Screen.PLAYER) {
                                        IconButton(onClick = { leavePlayer() }) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back to the library",
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = { scope.launch { drawerState.open() } },
                                        ) {
                                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                                        }
                                    }
                                },
                                actions = {
                                    if (selecting) {
                                        IconButton(onClick = {
                                            vm.startMove(selection.map(Uri::parse))
                                        }) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.DriveFileMove,
                                                contentDescription = "Move selected",
                                            )
                                        }
                                        IconButton(onClick = { deletingSelection = true }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete selected",
                                            )
                                        }
                                        return@TopAppBar
                                    }
                                    // The star, left of the notice and of the menu: a verdict on
                                    // the take you are listening to, and the one thing here worth
                                    // pressing while it plays rather than after. It was in the menu,
                                    // where a two-state control cannot show its state without being
                                    // opened — as an icon it says whether the take is starred just
                                    // by being on screen.
                                    openTake?.takeIf { screen == Screen.PLAYER }?.let { open ->
                                        val isStarred = vm.starKey(open.take.uri) in starred
                                        IconButton(onClick = { vm.toggleStar(open.take.uri) }) {
                                            Icon(
                                                if (isStarred) {
                                                    Icons.Default.Star
                                                } else {
                                                    Icons.Outlined.StarOutline
                                                },
                                                contentDescription =
                                                    if (isStarred) "Unstar" else "Star",
                                                // Tinted when given, plain when not: a star is the
                                                // only thing in this bar that means anything by
                                                // itself, and the accent is what makes it read as
                                                // set rather than as another available action.
                                                tint = if (isStarred) {
                                                    MaterialTheme.colorScheme.brandFill
                                                } else {
                                                    LocalContentColor.current
                                                },
                                            )
                                        }
                                    }
                                    // A quiet notice that the display is being held awake (it
                                    // drains battery); tap for the explanation and a way out.
                                    if (settings.keepScreenOn) {
                                        IconButton(onClick = { showKeepAwakeInfo = true }) {
                                            // No tint: inherits the app bar's content colour, so
                                            // it matches the menu icon rather than an accent.
                                            Icon(
                                                painterResource(R.drawable.ic_brightness_alert),
                                                contentDescription = "Screen kept on",
                                            )
                                        }
                                    }
                                    // Last, so it stays in the corner whether or not the notice
                                    // beside it is showing — a menu that moves is a menu you have
                                    // to look for.
                                    openTake?.takeIf { screen == Screen.PLAYER }?.let { open ->
                                        PlayerOverflowAction(
                                            onRename = { renamingOpenTake = true },
                                            onShare = { share(open.take) },
                                            onMove = { vm.startMove(listOf(open.take.uri)) },
                                            onDelete = { deletingOpenTake = true },
                                        )
                                    }
                                },
                            )
                        },
                        bottomBar = {
                            Column {
                                // Above the tabs, not inside a screen: a take keeps playing when
                                // you wander off, and this is the only thing that can stop it.
                                // The player screen has its own transport, so it is spared this.
                                if (screen != Screen.PLAYER) {
                                    MiniPlayer(
                                        playback = playback,
                                        onToggle = vm::toggleCurrent,
                                        onSeek = vm::seekTo,
                                        onOpen = { playback.take?.let { enterPlayer(it) } },
                                        onDismiss = vm::stopPlayback,
                                    )
                                }
                                CompactTabBar {
                                    TABS.forEach { tab ->
                                        CompactTab(
                                            selected = screen == tab ||
                                                (tab == Screen.LIBRARY && screen == Screen.PLAYER),
                                            onClick = {
                                                if (screen == Screen.PLAYER) vm.closeTake()
                                                screen = tab
                                            },
                                            label = tab.title,
                                            icon = {
                                                Icon(
                                                    when (tab) {
                                                        Screen.LIBRARY ->
                                                            Icons.Default.LibraryMusic

                                                        else -> ImageVector.vectorResource(
                                                            R.drawable.ic_graphic_eq,
                                                        )
                                                    },
                                                    contentDescription = null,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        // No snackbarHost here: the Scaffold slot is pinned to the bottom, which
                        // on this app is exactly where the buttons a message is reporting on live.
                        // "Looping." landing on top of the button you just held means waiting for
                        // it to clear to see whether it worked.
                    ) { padding ->
                        val content = Modifier.padding(padding)
                        Box(Modifier.fillMaxSize()) {
                            when (screen) {
                                Screen.RECORD -> RecordScreen(
                                    state = recorderState,
                                    settings = settings,
                                    folderLabel = library.current?.name,
                                    onChooseFolder = { folderPicker.launch(null) },
                                    onRecord = onRecord,
                                    onFinish = vm::finishRecording,
                                    onRestart = vm::restartRecording,
                                    onDiscard = vm::discardTake,
                                    onSave = vm::saveTake,
                                    onSetBpm = vm::setBpm,
                                    onSetCountInBars = vm::setCountInBars,
                                    onSetVisualMetronome = vm::setVisualMetronome,
                                    onSetAudioMetronome = vm::setAudioMetronome,
                                    levelTest = levelTest,
                                    onStartLevelTest = vm::startLevelTest,
                                    onRestartLevelTest = vm::restartLevelTest,
                                    onAcceptLevelTest = vm::acceptLevelTest,
                                    onStopLevelTest = vm::stopLevelTest,
                                    defaultName = vm::defaultTakeName,
                                    modifier = content,
                                )

                                Screen.LIBRARY -> LibraryScreen(
                                    state = library,
                                    playback = playback,
                                    onOpenFolder = vm::openFolder,
                                    onBreadcrumb = vm::goTo,
                                    onCreateFolder = vm::createFolder,
                                    onPlay = { vm.togglePlayback(it) },
                                    onOpen = { enterPlayer(it) },
                                    onRename = vm::renameDocument,
                                    onDelete = vm::deleteDocument,
                                    onShare = ::share,
                                    onMove = vm::startMove,
                                    starredKeys = starred,
                                    starKeyOf = vm::starKey,
                                    onToggleStar = { vm.toggleStar(it.uri) },
                                    notedKeys = notes.keys,
                                    starredTakes = starredTakes,
                                    onLoadStarred = vm::loadStarred,
                                    tab = libraryTab,
                                    onTabChange = { libraryTab = it },
                                    selection = selection,
                                    onToggleSelect = { uri ->
                                        selection = if (uri in selection) {
                                            selection - uri
                                        } else {
                                            selection + uri
                                        }
                                    },
                                    modifier = content,
                                )

                                Screen.SETTINGS -> SettingsScreen(
                                    settings = settings,
                                    folderLabel = library.path.firstOrNull()?.name,
                                    onChooseFolder = { folderPicker.launch(null) },
                                    onSetBeatsPerBar = vm::setBeatsPerBar,
                                    onSetAudioMetronome = vm::setAudioMetronome,
                                    onSetListenBeforeRecording = vm::setListenBeforeRecording,
                                    onSetPromptForFilename = vm::setPromptForFilename,
                                    onSetStarredFirst = vm::setStarredFirst,
                                    onSetKeepScreenOn = vm::setKeepScreenOn,
                                    onSetThemeMode = vm::setThemeMode,
                                    modifier = content,
                                )

                                Screen.ABOUT -> AboutScreen(content)

                                // A take that vanished under us (deleted from another app) leaves
                                // nothing to show, so fall back to the list it came from.
                                Screen.PLAYER -> openTake?.let { open ->
                                    PlayerScreen(
                                        open = open,
                                        playback = playback,
                                        busy = busy,
                                        onPlayPause = vm::togglePlayback,
                                        onSeek = vm::seekTo,
                                        onNormalize = vm::normalizeOpenTake,
                                        onTrim = vm::trimOpenTake,
                                        onRestart = vm::restartPlayback,
                                        looping = looping,
                                        onToggleLoop = vm::toggleLoop,
                                        note = notes[vm.starKey(open.take.uri)].orEmpty(),
                                        onEditNote = { notingOpenTake = true },
                                        beatsPerBar = settings.beatsPerBar,
                                        modifier = content,
                                    )
                                } ?: LaunchedEffect(Unit) { screen = Screen.LIBRARY }
                            }

                            // Over the middle of the screen instead: a message is about what just
                            // happened, and the middle is the one place nothing is being pressed.
                            // Slightly transparent, so what it is reporting on still shows through
                            // and it reads as a note laid over the screen, not a new surface.
                            SnackbarHost(
                                snackbar,
                                Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                            ) { data ->
                                Snackbar(
                                    // Only as wide as what it has to say. A full-width slab across
                                    // the middle of the screen is a dialog; this is a remark.
                                    modifier = Modifier
                                        .wrapContentWidth()
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant
                                                .copy(alpha = 0.6f),
                                            ControlShape,
                                        ),
                                    shape = ControlShape,
                                    // The app's own surface rather than Material's inverse pair:
                                    // inverting puts a near-white slab over a dark screen, which is
                                    // louder than any of these messages deserve. This follows the
                                    // theme, and the outline is what separates it from the panel
                                    // underneath now that the two are close in tone.
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                        .copy(alpha = 0.88f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ) {
                                    Text(
                                        data.visuals.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Asks for the folder takes are written into — on the first launch, and again if that folder
     * stops being reachable. Takes live in the user's own storage rather than app-private storage
     * so they can be pulled off over USB, and that is a choice only the user can make.
     */
    @Composable
    private fun ChooseFolderDialog(
        hadFolder: Boolean,
        onChoose: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (hadFolder) "Recordings folder missing" else "Where should takes go?") },
            text = {
                Text(
                    if (hadFolder) {
                        "The folder Title Track was recording into is no longer reachable — it may " +
                            "have been removed, or the permission withdrawn. Choose it again, or " +
                            "pick another one."
                    } else {
                        "Pick a folder to record into. Takes are written there as plain WAV files, " +
                            "so they can be copied off over USB like any other file."
                    },
                )
            },
            confirmButton = { TextButton(onClick = onChoose) { Text("Choose folder…") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
        )
    }

    /** Hand a take to whichever app the share sheet lands on, read-only and for this share only. */
    private fun share(take: Take) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = shareType(take)
            putExtra(Intent.EXTRA_STREAM, take.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share take"))
    }

    /**
     * What to tell the share sheet a take is.
     *
     * The library holds more than the WAVs the app records — takes arrive in any audio format, and
     * an edit writes FLAC back as FLAC. The type is what the system filters the chooser on, so
     * naming the take's own format is what puts an app that handles it in the list at all, and a
     * receiver that trusts the label rather than sniffing the container needs it to be true.
     *
     * The provider is asked first, but is inconsistent about WAV in particular and will call a
     * take `application/octet-stream` — so anything that isn't audio falls through to the
     * extension, which gets the same vote here that it gets in the library listing. [AudioFormat]
     * answers for the two formats the app writes itself; [MimeTypeMap] for the rest. Failing both,
     * the audio wildcard still reaches every audio app, which is better than asserting a concrete
     * type the bytes may not match.
     */
    private fun shareType(take: Take): String {
        contentResolver.getType(take.uri)
            ?.takeIf { it.startsWith("audio/") }
            ?.let { return it }
        val extension = take.name.substringAfterLast('.', "").lowercase()
        AudioFormat.entries.firstOrNull { it.extension == extension }?.let { return it.mime }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?.takeIf { it.startsWith("audio/") }
            ?: "audio/*"
    }
}

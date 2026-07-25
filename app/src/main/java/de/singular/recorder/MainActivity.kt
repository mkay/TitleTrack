package de.singular.recorder

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.singular.recorder.audio.RecordPhase
import de.singular.recorder.storage.Take
import de.singular.recorder.ui.AboutScreen
import de.singular.recorder.ui.LibraryScreen
import de.singular.recorder.ui.MiniPlayer
import de.singular.recorder.ui.PlayerScreen
import de.singular.recorder.ui.RecordScreen
import de.singular.recorder.ui.SettingsScreen
import de.singular.recorder.ui.SparkPlugTheme
import de.singular.recorder.ui.isDark
import kotlinx.coroutines.launch

private enum class Screen(val title: String) {
    RECORD("Record"),
    LIBRARY("Library"),
    SETTINGS("Settings"),
    ABOUT("About"),

    /** One take, opened from the library. Its title is the take's name, not this. */
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

            SparkPlugTheme(settings.themeMode) {
                val recorderState by vm.recorderState.collectAsStateWithLifecycle()
                val library by vm.library.collectAsStateWithLifecycle()
                val playback by vm.playback.collectAsStateWithLifecycle()
                val openTake by vm.openTake.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()

                var screen by rememberSaveable { mutableStateOf(Screen.RECORD) }
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

                LaunchedEffect(message) {
                    message?.let {
                        snackbar.showSnackbar(it)
                        vm.clearMessage()
                    }
                }

                // Hold the display awake while a take is in progress or the setting asks for it.
                // Released on dispose, so it can never leak into whatever is opened next.
                val awake = settings.keepScreenOn || recorderState.phase != RecordPhase.IDLE
                DisposableEffect(awake) {
                    view.keepScreenOn = awake
                    onDispose { view.keepScreenOn = false }
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
                                    "lock while Spark Plug is open. Handy with an instrument in " +
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
                    screen = Screen.LIBRARY
                }

                // Back walks out of the player, then up the folder tree, then home.
                BackHandler(enabled = screen == Screen.PLAYER) { leavePlayer() }
                BackHandler(enabled = screen == Screen.LIBRARY && library.canGoUp) { vm.goUp() }
                BackHandler(
                    enabled = screen != Screen.RECORD && screen != Screen.PLAYER &&
                        !library.canGoUp,
                ) { screen = Screen.RECORD }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        // Four fifths of the width, as in RubberRing: the strip of scrim left
                        // over on the right is what you tap to close it again.
                        ModalDrawerSheet(Modifier.fillMaxWidth(0.8f)) {
                            Text(
                                "Spark Plug",
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
                                title = {
                                    Text(
                                        if (screen == Screen.PLAYER) {
                                            openTake?.take?.name?.substringBeforeLast('.')
                                                ?: screen.title
                                        } else {
                                            screen.title
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                navigationIcon = {
                                    if (screen == Screen.PLAYER) {
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
                                        onOpen = {
                                            playback.take?.let {
                                                vm.openTake(it)
                                                screen = Screen.PLAYER
                                            }
                                        },
                                        onDismiss = vm::stopPlayback,
                                    )
                                }
                                NavigationBar {
                                    TABS.forEach { tab ->
                                        NavigationBarItem(
                                            selected = screen == tab ||
                                                (tab == Screen.LIBRARY && screen == Screen.PLAYER),
                                            onClick = {
                                                if (screen == Screen.PLAYER) vm.closeTake()
                                                screen = tab
                                            },
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
                                            label = { Text(tab.title) },
                                        )
                                    }
                                }
                            }
                        },
                        snackbarHost = { SnackbarHost(snackbar) },
                    ) { padding ->
                        val content = Modifier.padding(padding)
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
                                onOpen = {
                                    vm.openTake(it)
                                    screen = Screen.PLAYER
                                },
                                onRename = vm::renameDocument,
                                onDelete = vm::deleteDocument,
                                onShare = ::share,
                                modifier = content,
                            )

                            Screen.SETTINGS -> SettingsScreen(
                                settings = settings,
                                folderLabel = library.path.firstOrNull()?.name,
                                onChooseFolder = { folderPicker.launch(null) },
                                onSetBeatsPerBar = vm::setBeatsPerBar,
                                onSetPromptForFilename = vm::setPromptForFilename,
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
                                    onPlayPause = vm::togglePlayback,
                                    onSeek = vm::seekTo,
                                    onShare = ::share,
                                    modifier = content,
                                )
                            } ?: LaunchedEffect(Unit) { screen = Screen.LIBRARY }
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
                        "The folder Spark Plug was recording into is no longer reachable — it may " +
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
            type = "audio/x-wav"
            putExtra(Intent.EXTRA_STREAM, take.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share take"))
    }
}

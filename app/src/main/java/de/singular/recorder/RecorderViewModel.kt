package de.singular.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.singular.recorder.audio.AudioRecorder
import de.singular.recorder.audio.MAX_BPM
import de.singular.recorder.audio.MIN_BPM
import de.singular.recorder.audio.RecordPhase
import de.singular.recorder.storage.Folder
import de.singular.recorder.storage.Listing
import de.singular.recorder.storage.RecordingStore
import de.singular.recorder.storage.Take
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the user is in their recordings folder, and what is in it. */
data class LibraryState(
    /** Root first, current folder last; empty until a root has been granted. */
    val path: List<Folder> = emptyList(),
    val listing: Listing? = null,
    val loading: Boolean = false,
    /**
     * No usable recordings folder: none has ever been picked, or the one that was is gone —
     * revoked, unmounted, deleted. Either way nothing can be recorded until one is chosen.
     */
    val rootMissing: Boolean = false,
    /** Whether a folder had been picked before, which is what distinguishes gone from never-set. */
    val rootWasSet: Boolean = false,
) {
    val current: Folder? get() = path.lastOrNull()
    val canGoUp: Boolean get() = path.size > 1
}

/**
 * The take loaded for playback, if any — which outlives playing it.
 *
 * Stopping keeps the take and the position, so the mini player can stay on screen with something
 * to resume. It is cleared outright by [RecorderViewModel.stopPlayback], for when the take is
 * going away: deleted, recorded over, or the app shutting down.
 */
data class PlaybackState(
    val take: Take? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
) {
    val uri: Uri? get() = take?.uri
}

/** One take opened in the player, with the peak envelope it is drawn from. */
data class OpenTake(
    val take: Take,
    /** Null while still being read, and after a read that found nothing drawable. */
    val peaks: FloatArray? = null,
    val loadingWaveform: Boolean = true,
) {
    // FloatArray is an array: the generated equals would compare identities, and a recomposition
    // would see every state carrying the same peaks as a change. Compare what actually matters.
    override fun equals(other: Any?): Boolean =
        other is OpenTake &&
            take == other.take &&
            peaks === other.peaks &&
            loadingWaveform == other.loadingWaveform

    override fun hashCode(): Int =
        (take.hashCode() * 31 + (peaks?.size ?: 0)) * 31 + loadingWaveform.hashCode()
}

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RecordingStore(application)
    private val recorder = AudioRecorder(application, viewModelScope)

    val recorderState = recorder.state

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        Settings(
            bpm = prefs.getInt(KEY_BPM, 100).coerceIn(MIN_BPM.toInt(), MAX_BPM.toInt()),
            beatsPerBar = prefs.getInt(KEY_BEATS_PER_BAR, 4).coerceIn(2, 12),
            countInBars = prefs.getInt(KEY_COUNT_IN_BARS, 1).coerceIn(0, 4),
            visualMetronome = prefs.getBoolean(KEY_VISUAL_METRONOME, true),
            listenBeforeRecording = prefs.getBoolean(KEY_LISTEN_BEFORE_RECORDING, false),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
            promptForFilename = prefs.getBoolean(KEY_PROMPT_FOR_FILENAME, false),
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, null) ?: "")
            }.getOrDefault(ThemeMode.SYSTEM),
        ),
    )
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    /** One-shot user-facing notices ("Saved as …", "Could not create the folder"). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null

    init {
        openRoot()
    }

    // ---- storage ----------------------------------------------------------------------------

    /** Remember the folder the system picker just handed back, and show it. */
    fun onFolderPicked(uri: Uri) {
        if (!store.setRoot(uri)) {
            _message.value = "That folder could not be kept — please pick it again."
            return
        }
        openRoot()
    }

    private fun openRoot() {
        val remembered = store.rootWasSet
        val root = store.rootFolder() ?: run {
            _library.value = LibraryState(rootMissing = true, rootWasSet = remembered)
            return
        }
        viewModelScope.launch {
            val name = store.rootName() ?: "Recordings"
            _library.value = LibraryState(
                path = listOf(Folder(root, name)),
                loading = true,
                rootWasSet = remembered,
            )
            refresh()
        }
    }

    fun refresh() {
        val folder = _library.value.current ?: return
        viewModelScope.launch {
            _library.value = _library.value.copy(loading = true)
            val listing = store.list(folder.uri)
            // A root that will not list is as good as gone — the card it lived on was pulled, or
            // it was deleted from under us. Sub-folders failing only means that sub-folder went.
            val rootGone = !_library.value.canGoUp && listing.error != null
            _library.value = _library.value.copy(
                listing = listing,
                loading = false,
                rootMissing = rootGone,
            )
        }
    }

    fun openFolder(folder: Folder) {
        _library.value = _library.value.copy(path = _library.value.path + folder, listing = null)
        refresh()
    }

    fun goUp() {
        if (!_library.value.canGoUp) return
        _library.value = _library.value.copy(
            path = _library.value.path.dropLast(1), listing = null,
        )
        refresh()
    }

    /** Jump to an ancestor by its position in the breadcrumb. */
    fun goTo(index: Int) {
        val path = _library.value.path
        if (index !in path.indices || index == path.lastIndex) return
        _library.value = _library.value.copy(path = path.take(index + 1), listing = null)
        refresh()
    }

    fun createFolder(name: String) {
        val parent = _library.value.current ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val created = store.createFolder(parent.uri, name)
            if (created == null) _message.value = "The folder could not be created."
            refresh()
        }
    }

    fun renameDocument(uri: Uri, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            if (store.rename(uri, newName) == null) _message.value = "That could not be renamed."
            refresh()
        }
    }

    fun deleteDocument(uri: Uri) {
        viewModelScope.launch {
            if (uri == _playback.value.uri) stopPlayback()
            if (!store.delete(uri)) _message.value = "That could not be deleted."
            refresh()
        }
    }

    // ---- recording --------------------------------------------------------------------------

    val hasMicPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /** Start a take with the current count-in and tempo. Playback, if any, gets out of the way. */
    @SuppressLint("MissingPermission") // the line below is the check lint is looking for
    fun startRecording() {
        if (!hasMicPermission) return
        stopPlayback()
        val s = _settings.value
        recorder.start(
            bpm = s.bpm.toFloat(),
            beatsPerBar = s.beatsPerBar,
            countInBars = s.countInBars,
        )
    }

    /**
     * Watch the input level without recording, so levels can be set before a take rather than
     * discovered after one. Silently does nothing without permission — the microphone is asked
     * for on the first Record press, not to draw a meter.
     */
    @SuppressLint("MissingPermission") // the line below is the check lint is looking for
    fun startMonitoring() {
        if (!hasMicPermission) return
        recorder.startMonitoring()
    }

    fun stopMonitoring() = recorder.stopMonitoring()

    /** End the take and hold it, ready to be saved, restarted or thrown away. */
    fun finishRecording() = recorder.pause()
    fun restartRecording() = recorder.restart()
    fun discardTake() = recorder.discard()

    /** The name offered in the save dialog: the moment it was played, which is what one recalls. */
    fun defaultTakeName(): String =
        SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())

    /**
     * Write the held take into [folder] (the folder on screen by default) as `name.wav`, stamped
     * with the tempo it was played to, then clear the recorder for the next one.
     */
    fun saveTake(name: String, folder: Folder? = null) {
        val target = folder ?: _library.value.current ?: run {
            _message.value = "Choose a folder to record into first."
            return
        }
        val s = _settings.value
        // The tempo only means anything if a click was actually playing to it.
        val bpm = if (s.countInBars > 0 || s.visualMetronome) s.bpm.toFloat() else null
        viewModelScope.launch {
            val result = store.writeTake(target.uri, name.trim().ifBlank { defaultTakeName() }) {
                recorder.writeWavTo(it, bpm = bpm, title = name.trim().ifBlank { null })
            }
            result
                .onSuccess { take ->
                    recorder.discard()
                    _message.value = "Saved as ${take.name}"
                    refresh()
                }
                .onFailure { _message.value = it.message ?: "The take could not be saved." }
        }
    }

    // ---- the player -------------------------------------------------------------------------

    private val _openTake = MutableStateFlow<OpenTake?>(null)
    val openTake: StateFlow<OpenTake?> = _openTake.asStateFlow()

    private var waveformJob: Job? = null

    /** Open [take] in the player and start reading its waveform. Does not start playing it. */
    fun openTake(take: Take) {
        // Whatever was playing was a different take, or the same one from the mini player; either
        // way the player screen takes over the transport from here.
        if (_playback.value.uri != take.uri) stopPlayback()
        _openTake.value = OpenTake(take)
        waveformJob?.cancel()
        waveformJob = viewModelScope.launch {
            val peaks = store.waveform(take)
            // The user may have backed out or opened another take while we were reading.
            if (_openTake.value?.take?.uri == take.uri) {
                _openTake.value = OpenTake(take, peaks, loadingWaveform = false)
            }
        }
    }

    /** Leave the player. Playback carries on in the mini player rather than being cut off. */
    fun closeTake() {
        waveformJob?.cancel()
        waveformJob = null
        _openTake.value = null
    }

    // ---- playback ---------------------------------------------------------------------------

    /**
     * Stop [take] if it is the one playing; otherwise play it.
     *
     * A [fromMs] of -1 means "wherever this take was left" — resuming the position the mini player
     * is showing. The player screen passes its playhead instead, because there the user has just
     * pointed at a spot.
     */
    fun togglePlayback(take: Take, fromMs: Long = -1) {
        val current = _playback.value
        if (current.uri == take.uri && current.playing) {
            pausePlayback()
            return
        }
        if (recorderState.value.phase != RecordPhase.IDLE) return
        val resume = when {
            fromMs >= 0 -> fromMs
            current.uri == take.uri -> current.positionMs
            else -> 0
        }
        play(take, resume)
    }

    /** Give up the audio device but keep the take and where we were in it. */
    private fun pausePlayback() {
        progressJob?.cancel()
        progressJob = null
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        _playback.value = _playback.value.copy(playing = false)
    }

    private fun play(take: Take, fromMs: Long) {
        pausePlayback()
        val mp = MediaPlayer()
        val started = runCatching {
            mp.setDataSource(getApplication<Application>(), take.uri)
            mp.prepare()
            // Seek before starting, so a take opened at a mark does not blurt out its first
            // half-second before jumping.
            if (fromMs > 0) mp.seekTo(fromMs.toInt())
            mp.start()
        }.isSuccess
        if (!started) {
            runCatching { mp.release() }
            _message.value = "That file could not be played."
            return
        }
        // Reaching the end leaves the take loaded at its start, ready to go again.
        mp.setOnCompletionListener {
            pausePlayback()
            _playback.value = _playback.value.copy(positionMs = 0)
        }
        player = mp
        _playback.value = PlaybackState(
            take = take,
            positionMs = fromMs,
            durationMs = mp.duration.toLong().coerceAtLeast(0),
            playing = true,
        )
        progressJob = viewModelScope.launch {
            while (true) {
                delay(100)
                val current = player ?: break
                val at = runCatching { current.currentPosition.toLong() }.getOrNull() ?: break
                _playback.value = _playback.value.copy(positionMs = at)
            }
        }
    }

    /** The mini player's transport, which always has a take to act on. */
    fun toggleCurrent() {
        _playback.value.take?.let { togglePlayback(it) }
    }

    fun seekTo(ms: Long) {
        player?.let { runCatching { it.seekTo(ms.toInt()) } }
        _playback.value = _playback.value.copy(positionMs = ms)
    }

    /** Unload the take entirely — for when it is going away, not merely pausing. */
    fun stopPlayback() {
        pausePlayback()
        _playback.value = PlaybackState()
    }

    // ---- settings ---------------------------------------------------------------------------

    fun setBpm(bpm: Int) {
        val v = bpm.coerceIn(MIN_BPM.toInt(), MAX_BPM.toInt())
        _settings.value = _settings.value.copy(bpm = v)
        prefs.edit { putInt(KEY_BPM, v) }
    }

    fun setBeatsPerBar(beats: Int) {
        val v = beats.coerceIn(2, 12)
        _settings.value = _settings.value.copy(beatsPerBar = v)
        prefs.edit { putInt(KEY_BEATS_PER_BAR, v) }
    }

    fun setCountInBars(bars: Int) {
        val v = bars.coerceIn(0, 4)
        _settings.value = _settings.value.copy(countInBars = v)
        prefs.edit { putInt(KEY_COUNT_IN_BARS, v) }
    }

    fun setVisualMetronome(on: Boolean) {
        _settings.value = _settings.value.copy(visualMetronome = on)
        prefs.edit { putBoolean(KEY_VISUAL_METRONOME, on) }
    }

    fun setListenBeforeRecording(on: Boolean) {
        _settings.value = _settings.value.copy(listenBeforeRecording = on)
        prefs.edit { putBoolean(KEY_LISTEN_BEFORE_RECORDING, on) }
        if (!on) stopMonitoring()
    }

    fun setKeepScreenOn(on: Boolean) {
        _settings.value = _settings.value.copy(keepScreenOn = on)
        prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, on) }
    }

    fun setPromptForFilename(on: Boolean) {
        _settings.value = _settings.value.copy(promptForFilename = on)
        prefs.edit { putBoolean(KEY_PROMPT_FOR_FILENAME, on) }
    }

    fun setThemeMode(mode: ThemeMode) {
        _settings.value = _settings.value.copy(themeMode = mode)
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
    }

    fun clearMessage() {
        _message.value = null
    }

    override fun onCleared() {
        stopPlayback()
        recorder.discard()
        super.onCleared()
    }

    private companion object {
        const val KEY_BPM = "bpm"
        const val KEY_BEATS_PER_BAR = "beats_per_bar"
        const val KEY_COUNT_IN_BARS = "count_in_bars"
        const val KEY_VISUAL_METRONOME = "visual_metronome"
        const val KEY_LISTEN_BEFORE_RECORDING = "listen_before_recording"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_PROMPT_FOR_FILENAME = "prompt_for_filename"
        const val KEY_THEME_MODE = "theme_mode"
    }
}

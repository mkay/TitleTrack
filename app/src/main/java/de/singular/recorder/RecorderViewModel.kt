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
import de.singular.recorder.audio.NormalizeMode
import de.singular.recorder.audio.RecordPhase
import de.singular.recorder.storage.Folder
import de.singular.recorder.storage.Listing
import de.singular.recorder.storage.RecordingStore
import de.singular.recorder.storage.Stars
import de.singular.recorder.storage.Take
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/** Where the user is in their recordings folder, and what is in it. */
/**
 * A starred take, with the key it is starred under.
 *
 * The key doubles as the take's whereabouts: it is a path under the root, so its parent is the
 * folder to show beside the name — the whole point of this list being that the takes in it come
 * from all over the tree and the name alone does not say where from.
 */
data class StarredTake(val take: Take, val key: String) {
    /** The folder this sits in, or null at the root, where there is nothing useful to say. */
    val folder: String? get() = key.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
}

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
    /**
     * The take played all the way through rather than being stopped part-way. It stays loaded
     * either way — that is what lets it be played again from where it sits — but the two are not
     * the same thing to look at: a take someone paused is one they are in the middle of, and a
     * take that ran out is one they are done with. Only the first is worth a bar across the
     * bottom of every other screen.
     */
    val finished: Boolean = false,
) {
    val uri: Uri? get() = take?.uri
}

/**
 * A level test in progress: play something, and this is what was heard.
 *
 * [peak] and [heard] are *post-gain* — the levels that would land on disk with the current
 * [gainDb] — because that is what the meter shows and what would clip. The suggestion works back
 * from there to the raw input, so re-testing with a gain already set gives the same answer as
 * testing from zero.
 */
data class LevelTest(
    /** The gain that was in force while measuring. */
    val gainDb: Int,
    /** Loudest peak heard so far, 0f..1f. */
    val peak: Float = 0f,
    /** The current block's level, for the meter. */
    val heard: Float = 0f,
) {
    /** Loudest peak in dBFS, or null if nothing above the noise floor has been played yet. */
    val peakDb: Float? get() = if (peak <= 0.001f) null else 20f * log10(peak)

    /**
     * What to record at, in whole decibels.
     *
     * Aimed at [TARGET_PEAK_DB] rather than at full scale: this is a rehearsal of the take, and the
     * take will have a louder moment in it than the test did. Never negative — turning a take down
     * is what the player's normalise is for, and quiet-but-clean beats loud-and-clipped.
     */
    val suggestedGainDb: Int?
        get() {
            val db = peakDb ?: return null
            val raw = db - gainDb
            return (TARGET_PEAK_DB - raw).roundToInt().coerceIn(0, MAX_INPUT_GAIN_DB)
        }

    companion object {
        /** Where the loudest thing played should sit, leaving room for a louder take. */
        const val TARGET_PEAK_DB = -9f
    }
}

/**
 * As far as a take is worth lifting. Past this the microphone's own noise and the room are being
 * amplified as much as the instrument, and the answer is to move the phone closer instead.
 */
const val MAX_INPUT_GAIN_DB = 24

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
    private val stars = Stars(application)

    /**
     * The starred takes, as keys — see [Stars]. Held as a flow so the list can reorder itself and
     * the row can fill its star in the same frame the tap lands, rather than waiting for a refresh
     * of the folder to come back from the provider.
     */
    private val _starred = MutableStateFlow(stars.all())
    val starred: StateFlow<Set<String>> = _starred.asStateFlow()

    /**
     * The starred takes, resolved, for the Starred view. Null until they have been looked up once —
     * which is what tells the screen to say "loading" rather than "nothing starred yet".
     */
    private val _starredTakes = MutableStateFlow<List<StarredTake>?>(null)
    val starredTakes: StateFlow<List<StarredTake>?> = _starredTakes.asStateFlow()

    private var starredJob: Job? = null

    /**
     * Resolve every starred key into a take, dropping the ones that no longer exist.
     *
     * That dropping is the index's only garbage collection. A star can be orphaned by a rename or
     * a delete done outside the app, which nothing in here can be told about; going to look is the
     * only way to find out, and this is the one place that has reason to look at every key at once.
     */
    fun loadStarred() {
        starredJob?.cancel()
        starredJob = viewModelScope.launch {
            val keys = _starred.value.sorted()
            val found = keys.mapNotNull { key -> store.takeAt(key)?.let { StarredTake(it, key) } }
            val gone = keys.toSet() - found.map { it.key }.toSet()
            if (gone.isNotEmpty()) _starred.value = stars.removeAll(gone)
            _starredTakes.value = found.sortedByDescending { it.take.modifiedAt }
        }
    }

    /** The key [uri] is starred under, or null if it is not under the granted root. */
    fun starKey(uri: Uri): String? = Stars.keyFor(store.root, uri)

    fun toggleStar(uri: Uri) {
        val key = starKey(uri) ?: return
        _starred.value = stars.toggle(key)
        // Unstarring from inside the Starred view should take the row out of it, rather than
        // leaving a starless entry sitting in a list of favourites until the screen is reopened.
        _starredTakes.value = _starredTakes.value?.filter { it.key in _starred.value }
    }

    private val _settings = MutableStateFlow(
        Settings(
            bpm = prefs.getInt(KEY_BPM, 100).coerceIn(MIN_BPM.toInt(), MAX_BPM.toInt()),
            beatsPerBar = prefs.getInt(KEY_BEATS_PER_BAR, 4).coerceIn(2, 12),
            countInBars = prefs.getInt(KEY_COUNT_IN_BARS, 1).coerceIn(0, 4),
            visualMetronome = prefs.getBoolean(KEY_VISUAL_METRONOME, true),
            listenBeforeRecording = prefs.getBoolean(KEY_LISTEN_BEFORE_RECORDING, false),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
            promptForFilename = prefs.getBoolean(KEY_PROMPT_FOR_FILENAME, false),
            inputGainDb = prefs.getInt(KEY_INPUT_GAIN_DB, 0).coerceIn(0, MAX_INPUT_GAIN_DB),
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, null) ?: "")
            }.getOrDefault(ThemeMode.SYSTEM),
        ),
    )
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())

    /**
     * The folder on screen, with starred takes brought to the front.
     *
     * Ordering happens here rather than in [RecordingStore] because a star is not a property of
     * storage — the store lists what is in a folder, and what a favourite is is none of its
     * business. It also cannot happen there: a star can be given and taken back without the folder
     * changing at all, so the order has to be able to move without a listing being fetched again.
     * Combining the two flows is what gives that; [_library] keeps the provider's own order and is
     * what the rest of this class works from.
     */
    val library: StateFlow<LibraryState> = combine(_library, _starred) { state, stars ->
        state.starredFirst(stars)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    /**
     * Starred takes first, then newest first within each group — which is the order the provider
     * already gives, so unstarring a take drops it back exactly where it would have been. Sorting
     * is stable, so nothing else shifts around it.
     */
    private fun LibraryState.starredFirst(stars: Set<String>): LibraryState {
        val listing = listing ?: return this
        if (stars.isEmpty() || listing.takes.size < 2) return this
        return copy(
            listing = listing.copy(
                takes = listing.takes.sortedWith(
                    compareByDescending<Take> { starKey(it.uri) in stars }
                        .thenByDescending { it.modifiedAt },
                ),
            ),
        )
    }

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    /** One-shot user-facing notices ("Saved as …", "Could not create the folder"). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * The take a save just produced, for the UI to open. A one-shot, cleared once acted on, in the
     * same shape as [message]: the write is asynchronous, so the screen that asked for it cannot
     * simply carry on afterwards — it has to be told what came out.
     */
    private val _justSaved = MutableStateFlow<Take?>(null)
    val justSaved: StateFlow<Take?> = _justSaved.asStateFlow()

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null

    init {
        recorder.setInputGain(_settings.value.inputGainDb)
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
            val before = starKey(uri)
            val renamed = store.rename(uri, newName)
            if (renamed == null) {
                _message.value = "That could not be renamed."
            } else {
                // A star is kept against the document id, which is the path — so renaming moves it.
                // Folders matter more than takes here: renaming one changes the id of everything
                // inside it, and [Stars.rename] rewrites the prefix so those stars come along.
                val after = starKey(renamed)
                if (before != null && after != null && before != after) {
                    _starred.value = stars.rename(before, after)
                }
            }
            refresh()
        }
    }

    fun deleteDocument(uri: Uri) {
        viewModelScope.launch {
            if (uri == _playback.value.uri) stopPlayback()
            if (!store.delete(uri)) {
                _message.value = "That could not be deleted."
            } else {
                // Deleting a folder takes the stars inside it with it, which is what [Stars.remove]
                // does with a prefix — otherwise they would sit in the index forever, pointing at
                // nothing and costing a failed lookup every time the set is read.
                starKey(uri)?.let { _starred.value = stars.remove(it) }
            }
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

    // ---- the level test ---------------------------------------------------------------------

    private val _levelTest = MutableStateFlow<LevelTest?>(null)
    val levelTest: StateFlow<LevelTest?> = _levelTest.asStateFlow()

    private var levelTestJob: Job? = null

    /**
     * Listen, and remember the loudest thing heard: play for a few seconds and the recorder can
     * say what gain the take wants, rather than the user guessing at a number.
     *
     * Nothing is written — this is the monitoring path, which only ever looks at levels.
     */
    @SuppressLint("MissingPermission") // startMonitoring does the check
    fun startLevelTest() {
        if (!hasMicPermission) {
            _message.value = "Title Track needs the microphone to measure the level."
            return
        }
        if (_levelTest.value != null) return
        _levelTest.value = LevelTest(gainDb = _settings.value.inputGainDb)
        startMonitoring()
        levelTestJob = viewModelScope.launch {
            recorderState.collect { state ->
                _levelTest.update { test ->
                    test?.copy(peak = max(test.peak, state.level), heard = state.level)
                }
            }
        }
    }

    /** Throw away what was measured and start listening again — a fluffed test costs one tap. */
    fun restartLevelTest() {
        _levelTest.update { it?.copy(peak = 0f, heard = 0f) }
    }

    fun stopLevelTest() {
        levelTestJob?.cancel()
        levelTestJob = null
        _levelTest.value = null
        // Leave the microphone as we found it: open only if the user asked for it to be.
        if (!_settings.value.listenBeforeRecording) stopMonitoring()
    }

    /** Take the gain the test worked out, and close it. */
    fun acceptLevelTest() {
        _levelTest.value?.suggestedGainDb?.let(::setInputGainDb)
        stopLevelTest()
    }

    fun setInputGainDb(db: Int) {
        val v = db.coerceIn(0, MAX_INPUT_GAIN_DB)
        _settings.value = _settings.value.copy(inputGainDb = v)
        prefs.edit { putInt(KEY_INPUT_GAIN_DB, v) }
        recorder.setInputGain(v)
    }

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
                recorder.writeWavTo(
                    it,
                    bpm = bpm,
                    title = name.trim().ifBlank { null },
                    gainDb = s.inputGainDb,
                )
            }
            result
                .onSuccess { take ->
                    recorder.discard()
                    _message.value = "Saved as ${take.name}"
                    _justSaved.value = take
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

    /**
     * Rename the open take and stay on it — the library list is refreshed behind us.
     *
     * A rename can mint a new uri, and it always changes the name the player is titled with, so the
     * take is re-read rather than patched in place.
     */
    fun renameOpenTake(newName: String) {
        val take = _openTake.value?.take ?: return
        if (newName.isBlank()) return
        viewModelScope.launch {
            val renamed = store.rename(take.uri, newName)
            if (renamed == null) {
                _message.value = "That could not be renamed."
                return@launch
            }
            // The file the player has open is the file that moved; drop it rather than leave the
            // system player holding a uri that no longer resolves.
            if (_playback.value.uri == take.uri) stopPlayback()
            store.take(renamed)?.let { _openTake.value = OpenTake(it, _openTake.value?.peaks, false) }
            refresh()
        }
    }

    /**
     * Normalise the open take — over itself, or into a copy beside it when [asCopy] is set.
     *
     * Either way the player switches to whichever file now holds the normalised audio and reloads
     * it: the waveform on screen is drawn from samples that have just changed, and with a copy the
     * take you want to hear is the new one.
     */
    fun normalizeOpenTake(mode: NormalizeMode, asCopy: Boolean) {
        val take = _openTake.value?.take ?: return
        val folder = _library.value.current
        if (_busy.value) return
        if (asCopy && folder == null) {
            _message.value = "There is nowhere to write a copy."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            if (_playback.value.uri == take.uri) stopPlayback()
            store.normalize(take, mode, copyInto = if (asCopy) folder?.uri else null)
                .onSuccess { result ->
                    val db = String.format(Locale.US, "%+.1f", result.gainDb)
                    _message.value = when {
                        result.gainDb <= 0f -> "Already at level — nothing to lift."
                        asCopy -> "Saved ${result.take.name}, $db dB."
                        else -> "Normalised, $db dB."
                    }
                    if (result.gainDb > 0f) openTake(result.take)
                    refresh()
                }
                .onFailure { _message.value = it.message ?: "That take could not be normalised." }
            _busy.value = false
        }
    }

    /** True while a take is being rewritten — the player disables its edits for the duration. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Keep the selected part of the open take and throw the rest away — over the take, or into a
     * copy beside it.
     *
     * The player switches to whatever now holds the audio and reloads it: the waveform is a picture
     * of samples that have just been cut, and the playhead is somewhere that may no longer exist.
     */
    fun trimOpenTake(startFrac: Float, endFrac: Float, asCopy: Boolean) {
        val take = _openTake.value?.take ?: return
        val folder = _library.value.current
        if (_busy.value) return
        if (asCopy && folder == null) {
            _message.value = "There is nowhere to write a copy."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            if (_playback.value.uri == take.uri) stopPlayback()
            store.trim(take, startFrac, endFrac, copyInto = if (asCopy) folder?.uri else null)
                .onSuccess { trimmed ->
                    _message.value = if (asCopy) "Saved ${trimmed.name}." else "Trimmed."
                    openTake(trimmed)
                    refresh()
                }
                .onFailure { _message.value = it.message ?: "That take could not be trimmed." }
            _busy.value = false
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
        mp.isLooping = _looping.value
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
        // Reaching the end leaves the take loaded at its start, ready to go again — but marked as
        // done with, so nothing has to keep reporting on it. Playing it again clears the mark,
        // since [play] builds the state fresh.
        mp.setOnCompletionListener {
            pausePlayback()
            _playback.value = _playback.value.copy(positionMs = 0, finished = true)
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

    /**
     * Whether playback repeats. A property of listening rather than of a take: you turn it on to
     * learn a part, and it stays on until you turn it off.
     */
    private val _looping = MutableStateFlow(false)
    val looping: StateFlow<Boolean> = _looping.asStateFlow()

    fun toggleLoop() {
        val on = !_looping.value
        _looping.value = on
        // Takes effect on what is already playing, not only on the next press.
        player?.let { runCatching { it.isLooping = on } }
        _message.value = if (on) "Looping." else "Loop off."
    }

    /** Play [take] from its first sample, whatever it was doing before. */
    fun restartPlayback(take: Take) {
        if (recorderState.value.phase != RecordPhase.IDLE) return
        play(take, 0)
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

    fun clearJustSaved() {
        _justSaved.value = null
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
        const val KEY_INPUT_GAIN_DB = "input_gain_db"
    }
}

package de.singular.recorder.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import de.singular.recorder.audio.AudioDecoder
import de.singular.recorder.audio.Wav
import de.singular.recorder.audio.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** A sub-folder of the recordings root, for organising takes. */
data class Folder(val uri: Uri, val name: String)

/** One saved recording. */
data class Take(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** From the WAV header; 0 if it could not be read. */
    val durationMs: Long,
    /** The tempo the take was played to, if it carries one. */
    val bpm: Float?,
)

/** What one folder holds: its sub-folders and its takes, each already sorted for display. */
data class Listing(
    val folder: Uri,
    val folders: List<Folder> = emptyList(),
    val takes: List<Take> = emptyList(),
    val error: String? = null,
)

/**
 * The user's recordings folder, reached through the Storage Access Framework.
 *
 * A tree grant, not a path: the user points at a folder once (`Music/Recordings`, an SD card, a
 * synced folder — the app has no opinion), and the grant is persisted so it survives reboots and
 * reinstalls of nothing in particular. In return we work in document ids rather than files, which
 * is why folders are passed around as `content://` *document* uris built against the tree.
 *
 * Every call here touches a ContentProvider: call them off the main thread (they already move
 * themselves to [Dispatchers.IO]).
 */
class RecordingStore(context: Context) {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences("storage", Context.MODE_PRIVATE)

    /**
     * Header facts keyed by identity-of-content, so re-entering a folder does not re-open every
     * file. A take is rewritten only by being replaced, and size+mtime catch that.
     */
    private val headerCache = HashMap<String, Pair<Long, Float?>>()

    /** The granted root, or null until the user has picked one. */
    val root: Uri?
        get() = prefs.getString(KEY_ROOT, null)
            ?.let(Uri::parse)
            ?.takeIf { hasGrant(it) }

    /**
     * Whether a folder was ever picked, grant or no grant. Together with [root] being null this
     * says the folder is *gone* rather than never chosen, which is a different thing to tell the
     * user.
     */
    val rootWasSet: Boolean
        get() = prefs.getString(KEY_ROOT, null) != null

    /** The root as a document uri — what [list] and the rest want. */
    fun rootFolder(): Uri? = root?.let { documentUri(it, DocumentsContract.getTreeDocumentId(it)) }

    /** Human-readable name of the root, for the "saving into …" line. */
    suspend fun rootName(): String? = withContext(Dispatchers.IO) {
        rootFolder()?.let { nameOf(it) }
    }

    /**
     * Remember the tree the user just granted, and hold onto the grant across restarts. Returns
     * false if the system refused to persist it, in which case the pick has to be repeated.
     */
    fun setRoot(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val ok = runCatching { resolver.takePersistableUriPermission(uri, flags) }.isSuccess
        if (ok) prefs.edit().putString(KEY_ROOT, uri.toString()).apply()
        return ok
    }

    /** Whether we still hold a persisted grant on [uri] — the user can revoke it in Settings. */
    private fun hasGrant(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    /** Everything in [folder]: sub-folders first, then takes, newest take first. */
    suspend fun list(folder: Uri): Listing = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext Listing(folder, error = "No folder chosen yet.")
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(folder),
            )
        }.getOrNull() ?: return@withContext Listing(folder, error = "That folder is unreachable.")

        val folders = ArrayList<Folder>()
        val takes = ArrayList<Take>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = runCatching { resolver.query(children, columns, null, null, null) }.getOrNull()
            ?: return@withContext Listing(folder, error = "That folder could not be read.")

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2) ?: ""
                val size = if (it.isNull(3)) 0L else it.getLong(3)
                val modified = if (it.isNull(4)) 0L else it.getLong(4)
                val uri = documentUri(tree, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders += Folder(uri, name)
                } else if (isAudio(name, mime)) {
                    val (durationMs, bpm) = header(uri, size, modified)
                    takes += Take(uri, name, size, modified, durationMs, bpm)
                }
            }
        }
        Listing(
            folder = folder,
            folders = folders.sortedBy { f -> f.name.lowercase() },
            takes = takes.sortedByDescending { t -> t.modifiedAt },
        )
    }

    /** Create a sub-folder under [parent]. Returns its uri, or null if the provider refused. */
    suspend fun createFolder(parent: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.createDocument(
                resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name.trim(),
            )
        }.getOrNull()
    }

    /**
     * Create `name.wav` in [folder] and hand its stream to [write].
     *
     * The name may come back changed — providers de-duplicate ("take (1).wav") and sanitise — so
     * the created uri is what the caller should trust, not the name it asked for. A write that
     * throws takes the half-written document with it: better no file than a truncated one.
     */
    suspend fun writeTake(
        folder: Uri,
        name: String,
        write: (OutputStream) -> Unit,
    ): Result<Take> = withContext(Dispatchers.IO) {
        val fileName = if (name.endsWith(".wav", ignoreCase = true)) name else "$name.wav"
        val uri = runCatching {
            DocumentsContract.createDocument(resolver, folder, MIME_WAV, fileName)
        }.getOrNull() ?: return@withContext Result.failure(
            IllegalStateException("Could not create a file in that folder."),
        )

        val result = runCatching {
            resolver.openOutputStream(uri, "w")?.use(write)
                ?: throw IllegalStateException("Could not open the new file for writing.")
        }
        if (result.isFailure) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            return@withContext Result.failure(result.exceptionOrNull()!!)
        }

        val saved = describe(uri) ?: Take(uri, fileName, 0, System.currentTimeMillis(), 0, null)
        Result.success(saved)
    }

    /** Rename a take or a folder. Returns the new uri (some providers mint one). */
    suspend fun rename(uri: Uri, newName: String): Uri? = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.renameDocument(resolver, uri, newName.trim()) }.getOrNull()
    }

    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    /**
     * The peak envelope of [take], for drawing it. Null only when the file has no audio this
     * device can decode at all — the player then falls back to a plain seek bar rather than
     * refusing to play.
     *
     * WAV is read straight through as the PCM it already is; starting a codec for it would cost
     * more than the read. Everything else goes to [AudioDecoder]. Cancelling the caller stops the
     * decode between buffers rather than leaving it running for a screen nobody is looking at.
     */
    suspend fun waveform(take: Take): FloatArray? = withContext(Dispatchers.IO) {
        val fromWav = runCatching {
            resolver.openInputStream(take.uri)?.use { Waveform.readWav(it, take.sizeBytes) }
        }.getOrNull()
        fromWav ?: AudioDecoder.peaks(appContext, take.uri) { isActive }
    }

    /** Display name of any document, or null if it has gone. */
    suspend fun nameOf(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /** Re-read one take's row, after writing or renaming it. */
    private fun describe(uri: Uri): Take? = runCatching {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, columns, null, null, null)?.use {
            if (!it.moveToFirst()) return@use null
            val name = it.getString(0) ?: return@use null
            val size = if (it.isNull(1)) 0L else it.getLong(1)
            val modified = if (it.isNull(2)) 0L else it.getLong(2)
            val (durationMs, bpm) = header(uri, size, modified)
            Take(uri, name, size, modified, durationMs, bpm)
        }
    }.getOrNull()

    /**
     * Duration and tempo, read from the first bytes of the file and then remembered.
     *
     * The WAV header is where the tempo lives, and reading it is a few KB. Anything else — an m4a
     * from the stock recorder, an mp3 dropped in from a desktop — has its duration parsed out of
     * the container instead, so those do not sit in the list claiming to be 0:00. That is a second
     * open per file, which is why the answer is cached against size and mtime.
     */
    private fun header(uri: Uri, size: Long, modified: Long): Pair<Long, Float?> {
        val key = "$uri|$size|$modified"
        headerCache[key]?.let { return it }
        val head = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(HEADER_BYTES)
                var got = 0
                while (got < buf.size) {
                    val n = input.read(buf, got, buf.size - got)
                    if (n <= 0) break
                    got += n
                }
                buf.copyOf(got)
            }
        }.getOrNull()
        val info = head?.let { Wav.readInfo(it, fileBytes = size) }
        val durationMs = info?.durationMs ?: AudioDecoder.durationMs(appContext, uri)
        val value = durationMs to info?.bpm
        if (headerCache.size > CACHE_LIMIT) headerCache.clear()
        headerCache[key] = value
        return value
    }

    private fun documentUri(tree: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    /** Providers are inconsistent about WAV's mime type, so the extension gets a vote. */
    private fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") ||
            mime == "application/octet-stream" && name.endsWith(".wav", ignoreCase = true) ||
            AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    private companion object {
        const val KEY_ROOT = "root_tree_uri"
        const val MIME_WAV = "audio/x-wav"

        /** Enough for `fmt `, a generous `LIST/INFO`, and the `data` header behind them. */
        const val HEADER_BYTES = 4_096
        const val CACHE_LIMIT = 512

        val AUDIO_EXTENSIONS = listOf(".wav", ".m4a", ".mp3", ".ogg", ".flac", ".aac")
    }
}

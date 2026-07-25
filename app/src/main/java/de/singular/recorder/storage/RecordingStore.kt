package de.singular.recorder.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import de.singular.recorder.audio.AudioDecoder
import de.singular.recorder.audio.Gain
import de.singular.recorder.audio.NormalizeMode
import de.singular.recorder.audio.PcmSink
import de.singular.recorder.audio.Wav
import de.singular.recorder.audio.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
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

/** A take after [RecordingStore.normalize], and the boost it was given — 0 dB if it was left be. */
data class Normalized(val take: Take, val gainDb: Float)

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
            resolver.openInputStream(take.uri)?.use {
                Waveform.readWav(it, take.sizeBytes, Waveform.DETAIL)
            }
        }.getOrNull()
        fromWav ?: AudioDecoder.peaks(appContext, take.uri, Waveform.DETAIL) { isActive }
    }

    /**
     * Lift [take] to a sensible level, writing the result over the take itself or, with
     * [copyInto] set to a folder, beside it as a second file. Returns the take that now holds the
     * normalised audio and the boost applied — or the take untouched and 0 dB, if it was already
     * loud enough to leave alone.
     *
     * The level has to go into a file either way: takes are played straight off storage by the
     * system player, so there is no signal path to hang a fader on, and a level that only existed
     * inside Spark Plug would be missing from every copy shared out of it.
     *
     * 16-bit PCM WAV — what this app records — is scaled sample for sample, header and all, and can
     * be overwritten in place. Anything else the device can decode goes through
     * [normalizeDecoded] and comes out as a new WAV; see there for why it is never written back
     * over the original.
     *
     * Overwriting goes through a cache file and replaces the original only once the whole rewrite
     * is on disk, so a read that fails or a process that dies part-way leaves the take as it was.
     * A copy needs none of that: the original is never opened for writing at all.
     */
    suspend fun normalize(
        take: Take,
        mode: NormalizeMode,
        copyInto: Uri? = null,
    ): Result<Normalized> = withContext(Dispatchers.IO) {
        val info = readHeader(take.uri, take.sizeBytes)
            ?.takeIf { it.bitsPerSample == 16 && it.dataStart >= 0 && it.dataBytes > 0 }
            ?: return@withContext normalizeDecoded(take, mode, copyInto)

        val meter = Gain.Meter()
        val measured = runCatching {
            resolver.openInputStream(take.uri)?.use { input ->
                input.skipExactly(info.dataStart)
                forEachBlock(input, info.dataBytes) { buf, n -> meter.add(buf, 0, n) }
            } ?: throw IllegalStateException("That file could not be read.")
        }
        measured.exceptionOrNull()?.let { return@withContext Result.failure(it) }

        val gain = Gain.linearFor(mode, meter.peak, meter.rms)
        val gainDb = Gain.linearToDb(gain)
        // Already there: rewriting a whole file — or making a second one — for a fraction of a dB
        // is all cost and no difference.
        if (gainDb < Gain.MIN_USEFUL_BOOST_DB) {
            return@withContext Result.success(Normalized(take, 0f))
        }
        val softClip = Gain.needsSoftClip(gain, meter.peak)

        if (copyInto != null) {
            return@withContext normalizeIntoCopy(take, copyInto, info, gain, softClip, gainDb)
        }

        val scratch = File(appContext.cacheDir, "normalize.wav")
        val written = runCatching {
            scratch.outputStream().use { out -> writeNormalized(take.uri, out, info, gain, softClip) }
            scratch.inputStream().use { src ->
                openTruncating(take.uri).use { dest -> src.copyTo(dest) }
            }
        }
        scratch.delete()
        written.exceptionOrNull()?.let { return@withContext Result.failure(it) }

        Result.success(Normalized(describe(take.uri) ?: take, gainDb))
    }

    /**
     * Normalise a take that is not PCM WAV — an m4a from the stock recorder, an mp3 from a
     * desktop — by decoding it and writing the result out as a new WAV beside it.
     *
     * Always a copy, never in place: putting the level back into the original would mean encoding
     * it again, and a second generation of lossy audio is a real cost for a volume change. WAV is
     * bigger (roughly ten times an m4a) and lossless, which is the honest trade to offer.
     *
     * The decode happens once. The samples go to a cache file as they arrive and are measured on
     * the way past, so the gain is known by the time there is anything to scale — and the length of
     * that file is what the WAV header needs, which is not knowable in advance.
     */
    private fun normalizeDecoded(
        take: Take,
        mode: NormalizeMode,
        copyInto: Uri?,
    ): Result<Normalized> {
        if (copyInto == null) {
            return Result.failure(
                IllegalStateException(
                    "Only WAV takes can be overwritten. Save a normalised copy instead.",
                ),
            )
        }

        val scratch = File(appContext.cacheDir, "normalize.pcm")
        val decoded = runCatching {
            scratch.outputStream().buffered(BLOCK).use { out ->
                val sink = MeasuringPcmWriter(out)
                if (!AudioDecoder.decode(appContext, take.uri, sink)) {
                    throw IllegalStateException("Nothing on this device can decode that file.")
                }
                sink
            }
        }
        val sink = decoded.getOrElse {
            scratch.delete()
            return Result.failure(it)
        }
        if (scratch.length() <= 0 || sink.sampleRate <= 0) {
            scratch.delete()
            return Result.failure(IllegalStateException("That file decoded to no audio."))
        }

        val gain = Gain.linearFor(mode, sink.meter.peak, sink.meter.rms)
        val gainDb = Gain.linearToDb(gain)
        if (gainDb < Gain.MIN_USEFUL_BOOST_DB) {
            scratch.delete()
            return Result.success(Normalized(take, 0f))
        }
        val softClip = Gain.needsSoftClip(gain, sink.meter.peak)

        val base = take.name.substringBeforeLast('.')
        val uri = runCatching {
            DocumentsContract.createDocument(resolver, copyInto, MIME_WAV, "$base normalised.wav")
        }.getOrNull() ?: run {
            scratch.delete()
            return Result.failure(IllegalStateException("The copy could not be created."))
        }

        val written = runCatching {
            resolver.openOutputStream(uri, "w")?.use { out ->
                out.write(
                    Wav.header(
                        dataBytes = scratch.length(),
                        sampleRate = sink.sampleRate,
                        channels = sink.channels,
                        bpm = take.bpm,
                        title = base,
                    ),
                )
                scratch.inputStream().use { pcm -> applyGain(pcm, out, scratch.length(), gain, softClip) }
            } ?: throw IllegalStateException("The copy could not be written.")
        }
        scratch.delete()
        if (written.isFailure) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            return Result.failure(written.exceptionOrNull()!!)
        }

        val copy = describe(uri) ?: return Result.failure(
            IllegalStateException("The copy was written but could not be read back."),
        )
        return Result.success(Normalized(copy, gainDb))
    }

    /**
     * Writes a decode out as little-endian 16-bit PCM, measuring it as it goes.
     *
     * Float buffers are folded down to 16-bit here rather than kept: the take came from a lossy
     * file and is going into a 16-bit WAV, so the extra width has nothing left to carry.
     */
    private class MeasuringPcmWriter(private val out: OutputStream) : PcmSink {

        val meter = Gain.Meter()
        var sampleRate = 0
            private set
        var channels = 1
            private set

        private var scratch = ByteArray(0)

        override fun onStart(sampleRate: Int, channels: Int, totalFrames: Long) {
            this.sampleRate = sampleRate
            this.channels = channels
        }

        override fun onOutputFormat(sampleRate: Int, channels: Int) {
            if (sampleRate > 0) this.sampleRate = sampleRate
            if (channels > 0) this.channels = channels
        }

        override fun onPcm16(pcm: ByteArray, count: Int, channels: Int) {
            this.channels = channels
            meter.add(pcm, 0, count)
            out.write(pcm, 0, count)
        }

        override fun onPcmFloat(pcm: FloatArray, count: Int, channels: Int) {
            this.channels = channels
            if (scratch.size < count * 2) scratch = ByteArray(count * 2)
            for (i in 0 until count) {
                val s = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
                scratch[i * 2] = (s and 0xFF).toByte()
                scratch[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            meter.add(scratch, 0, count * 2)
            out.write(scratch, 0, count * 2)
        }
    }

    /**
     * The same rewrite, into a new document in [folder]. A half-written copy is deleted rather than
     * left in the folder looking like a take.
     */
    private fun normalizeIntoCopy(
        take: Take,
        folder: Uri,
        info: Wav.Info,
        gain: Float,
        softClip: Boolean,
        gainDb: Float,
    ): Result<Normalized> {
        val name = take.name.substringBeforeLast('.') + " normalised.wav"
        val uri = runCatching { DocumentsContract.createDocument(resolver, folder, MIME_WAV, name) }
            .getOrNull()
            ?: return Result.failure(IllegalStateException("The copy could not be created."))

        val written = runCatching {
            resolver.openOutputStream(uri, "w")?.use { out ->
                writeNormalized(take.uri, out, info, gain, softClip)
            } ?: throw IllegalStateException("The copy could not be written.")
        }
        if (written.isFailure) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            return Result.failure(written.exceptionOrNull()!!)
        }

        val copy = describe(uri) ?: return Result.failure(
            IllegalStateException("The copy was written but could not be read back."),
        )
        return Result.success(Normalized(copy, gainDb))
    }

    /** Read [source] and write it out scaled by [gain] — header first, then the audio. */
    private fun writeNormalized(
        source: Uri,
        out: OutputStream,
        info: Wav.Info,
        gain: Float,
        softClip: Boolean,
    ) {
        resolver.openInputStream(source)?.use { input ->
            // The header is copied byte for byte: the length does not change, so everything it
            // says — tempo, title, sizes — stays true of the file it now heads.
            copyExactly(input, out, info.dataStart)
            applyGain(input, out, info.dataBytes, gain, softClip)
            // Anything after the payload (a trailing INFO chunk, say) is not audio.
            input.copyTo(out)
        } ?: throw IllegalStateException("That file could not be read.")
    }

    /**
     * Keep the part of [take] between [startFrac] and [endFrac] and throw the rest away — over the
     * take itself, or into a copy beside it when [copyInto] is a folder.
     *
     * A WAV is cut on frame boundaries with no decode at all: the header is rebuilt for the new
     * length and the selected bytes are copied straight through, so the samples that survive are
     * the exact samples that were recorded. Anything else is decoded and the cut written out as a
     * WAV, for the same reason normalising is — re-encoding a lossy file to shorten it would cost
     * a generation of quality on top of the audio being removed on purpose.
     *
     * The tempo travels with the take, though a trim that does not land on a bar line makes it a
     * claim about the music rather than about the first sample.
     */
    suspend fun trim(
        take: Take,
        startFrac: Float,
        endFrac: Float,
        copyInto: Uri? = null,
    ): Result<Take> = withContext(Dispatchers.IO) {
        if (endFrac <= startFrac) {
            return@withContext Result.failure(IllegalStateException("Nothing selected to keep."))
        }

        val wav = readHeader(take.uri, take.sizeBytes)
            ?.takeIf { it.bitsPerSample == 16 && it.dataStart >= 0 && it.dataBytes > 0 }

        if (wav != null) {
            val frameBytes = (wav.channels * wav.bitsPerSample / 8).coerceAtLeast(1)
            val cut = cut(wav.dataBytes, frameBytes, startFrac, endFrac)
                ?: return@withContext Result.failure(
                    IllegalStateException("That selection is too short to keep."),
                )
            val header = Wav.header(
                dataBytes = cut.bytes,
                sampleRate = wav.sampleRate,
                channels = wav.channels,
                bitsPerSample = wav.bitsPerSample,
                bpm = take.bpm,
                title = take.name.substringBeforeLast('.'),
            )
            return@withContext writeTrimmed(take, copyInto, header) { out ->
                resolver.openInputStream(take.uri)?.use { input ->
                    input.skipExactly(wav.dataStart + cut.offset)
                    copyExactly(input, out, cut.bytes)
                } ?: throw IllegalStateException("That file could not be read.")
            }
        }

        // Not PCM WAV: decode it once into the cache, then cut the cache.
        if (copyInto == null) {
            return@withContext Result.failure(
                IllegalStateException("Only WAV takes can be trimmed in place. Save a copy instead."),
            )
        }
        val scratch = File(appContext.cacheDir, "trim.pcm")
        val decoded = runCatching {
            scratch.outputStream().buffered(BLOCK).use { out ->
                val sink = MeasuringPcmWriter(out)
                if (!AudioDecoder.decode(appContext, take.uri, sink)) {
                    throw IllegalStateException("Nothing on this device can decode that file.")
                }
                sink
            }
        }
        val sink = decoded.getOrElse {
            scratch.delete()
            return@withContext Result.failure(it)
        }
        val frameBytes = (sink.channels * 2).coerceAtLeast(2)
        val cut = cut(scratch.length(), frameBytes, startFrac, endFrac)
        if (cut == null || sink.sampleRate <= 0) {
            scratch.delete()
            return@withContext Result.failure(
                IllegalStateException("That selection is too short to keep."),
            )
        }
        val header = Wav.header(
            dataBytes = cut.bytes,
            sampleRate = sink.sampleRate,
            channels = sink.channels,
            bpm = take.bpm,
            title = take.name.substringBeforeLast('.'),
        )
        val result = writeTrimmed(take, copyInto, header) { out ->
            scratch.inputStream().use { pcm ->
                pcm.skipExactly(cut.offset)
                copyExactly(pcm, out, cut.bytes)
            }
        }
        scratch.delete()
        result
    }

    /** The byte range [startFrac]..[endFrac] of a payload, rounded to whole frames. */
    private fun cut(dataBytes: Long, frameBytes: Int, startFrac: Float, endFrac: Float): Cut? {
        val frames = dataBytes / frameBytes
        if (frames <= 0) return null
        val first = (frames * startFrac.coerceIn(0f, 1f)).toLong().coerceIn(0, frames)
        val last = (frames * endFrac.coerceIn(0f, 1f)).toLong().coerceIn(first, frames)
        val kept = last - first
        if (kept <= 0) return null
        return Cut(offset = first * frameBytes, bytes = kept * frameBytes)
    }

    private data class Cut(val offset: Long, val bytes: Long)

    /**
     * Write [header] and whatever [payload] streams after it, either over [take] or into a new
     * document in [copyInto].
     *
     * Overwriting builds the whole file in the cache first, because a trim shortens the original:
     * a failure half way through a direct write would leave a take with the header of one length
     * and the audio of another.
     */
    private fun writeTrimmed(
        take: Take,
        copyInto: Uri?,
        header: ByteArray,
        payload: (OutputStream) -> Unit,
    ): Result<Take> {
        if (copyInto != null) {
            val name = take.name.substringBeforeLast('.') + " trimmed.wav"
            val uri = runCatching {
                DocumentsContract.createDocument(resolver, copyInto, MIME_WAV, name)
            }.getOrNull()
                ?: return Result.failure(IllegalStateException("The copy could not be created."))

            val written = runCatching {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(header)
                    payload(out)
                } ?: throw IllegalStateException("The copy could not be written.")
            }
            if (written.isFailure) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                return Result.failure(written.exceptionOrNull()!!)
            }
            return Result.success(
                describe(uri) ?: return Result.failure(
                    IllegalStateException("The copy was written but could not be read back."),
                ),
            )
        }

        val scratch = File(appContext.cacheDir, "trim.wav")
        val written = runCatching {
            scratch.outputStream().buffered(BLOCK).use { out ->
                out.write(header)
                payload(out)
            }
            scratch.inputStream().use { src ->
                openTruncating(take.uri).use { dest -> src.copyTo(dest) }
            }
        }
        scratch.delete()
        written.exceptionOrNull()?.let { return Result.failure(it) }
        return Result.success(describe(take.uri) ?: take)
    }

    /** Re-read one take by uri — after a rename, or after anything else has moved under it. */
    suspend fun take(uri: Uri): Take? = withContext(Dispatchers.IO) { describe(uri) }

    /** The WAV header of [uri], or null if it is not a WAVE at all. */
    private fun readHeader(uri: Uri, sizeBytes: Long): Wav.Info? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(HEADER_BYTES)
            var got = 0
            while (got < buf.size) {
                val n = input.read(buf, got, buf.size - got)
                if (n <= 0) break
                got += n
            }
            Wav.readInfo(buf.copyOf(got), fileBytes = sizeBytes)
        }
    }.getOrNull()

    /**
     * Truncating write. Providers differ on whether plain "w" empties the file first; here the
     * replacement is the same length as the original, so either behaviour gives the same bytes.
     */
    private fun openTruncating(uri: Uri): OutputStream =
        runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("That file could not be written.")

    /** Feed [bytes] bytes of [input] to [block], in whatever sized pieces it arrives in. */
    private inline fun forEachBlock(input: InputStream, bytes: Long, block: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(BLOCK)
        var remaining = bytes
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
            block(buf, n)
        }
    }

    /**
     * Copy [bytes] bytes of PCM from [input] to [out], scaled by [gain].
     *
     * A read can end mid-sample, so the odd byte left over is held back and paired with the first
     * byte of the next block rather than being scaled as if it were a sample of its own.
     */
    private fun applyGain(
        input: InputStream,
        out: OutputStream,
        bytes: Long,
        gain: Float,
        softClip: Boolean,
    ) {
        var carry = -1
        forEachBlock(input, bytes) { buf, n ->
            var start = 0
            if (carry >= 0) {
                val s = ((buf[0].toInt() shl 8) or carry).toShort()
                val scaled = Gain.applySample(s, gain, softClip).toInt()
                out.write(scaled and 0xFF)
                out.write((scaled shr 8) and 0xFF)
                carry = -1
                start = 1
            }
            var end = n
            if ((n - start) % 2 == 1) {
                carry = buf[n - 1].toInt() and 0xFF
                end = n - 1
            }
            Gain.applyPcm16(buf, start, end - start, gain, softClip)
            out.write(buf, start, end - start)
        }
        // A file whose payload ends on an odd byte: pass it through rather than swallow it.
        if (carry >= 0) out.write(carry)
    }

    private fun copyExactly(input: InputStream, out: OutputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(BLOCK)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) throw IllegalStateException("That file ended sooner than its header says.")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    /** [InputStream.skip] is allowed to skip fewer bytes than asked; a header offset cannot be. */
    private fun InputStream.skipExactly(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (read() < 0) throw IllegalStateException("That file has no audio in it.")
                remaining--
            }
        }
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
        val info = readHeader(uri, size)
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

        /** Read/write block for the whole-file passes normalising takes. */
        const val BLOCK = 64 * 1024

        val AUDIO_EXTENSIONS = listOf(".wav", ".m4a", ".mp3", ".ogg", ".flac", ".aac")
    }
}

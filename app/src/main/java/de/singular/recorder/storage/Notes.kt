// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.storage

import android.content.Context
import androidx.core.content.edit

/**
 * A note kept against a take: what the idea was, the chords, the words that go with it.
 *
 * ## Why it lives here and not in the take
 *
 * The same trade [Stars] makes, and for a stronger reason. A WAV's `LIST/INFO` comment already
 * carries the tempo and the title and could carry this too — but the chunk sits before the audio, so
 * changing its length means rewriting the whole file, and this is text someone edits a word at a
 * time. Worse, it would cover WAV and FLAC and leave the m4a and mp3 takes in the same folder with
 * nowhere to put a note. A note has to work for any file in the folder, so it lives here.
 *
 * The cost is the same as a star's: invisible from a desktop, and lost if a take is renamed or moved
 * from outside the app. Renames and moves *inside* the app are followed — see [rename].
 *
 * ## The key
 *
 * The same key a star uses — the document id with the root's stripped off, which for the providers
 * this app is used with is a path, `Apache/take.wav`. See [Stars] for why not the uri.
 *
 * ## Never garbage-collected
 *
 * [Stars] drops keys that no longer resolve, on the grounds that a star whose take is gone is
 * clutter. Notes do not, and must not. The two are not worth the same: an orphaned note costs a few
 * bytes, and a wrongly dropped one costs the only record of what an idea was. A key can fail to
 * resolve because the root is momentarily wrong or the storage is unmounted — reasons that have
 * nothing to do with the take being gone — so nothing here treats a failed lookup as permission to
 * delete. Notes go when the take is deleted through the app, and not otherwise.
 */
class Notes(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("notes", Context.MODE_PRIVATE)

    /** Every note, by key. Read whole — this is tens of short strings, not a database. */
    fun all(): Map<String, String> =
        prefs.all.entries.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun get(key: String): String = prefs.getString(key, null).orEmpty()

    /** Write [text], or drop the note entirely when it is blanked — an empty note is no note. */
    fun set(key: String, text: String): Map<String, String> {
        val trimmed = text.trim()
        prefs.edit {
            if (trimmed.isEmpty()) remove(key) else putString(key, trimmed)
        }
        return all()
    }

    /** Drop [key] and anything beneath it, so deleting a folder takes its notes with it. */
    fun remove(key: String): Map<String, String> {
        val gone = prefs.all.keys.filter { it.under(key) }
        if (gone.isNotEmpty()) prefs.edit { for (k in gone) remove(k) }
        return all()
    }

    /**
     * Follow a rename from [from] to [to], prefixes and all.
     *
     * Folders are why this takes prefixes: renaming one changes the key of every take inside it, and
     * the notes beneath it would otherwise orphan while the takes themselves are perfectly fine.
     */
    fun rename(from: String, to: String): Map<String, String> {
        val moving = prefs.all.entries
            .mapNotNull { (k, v) -> (v as? String)?.takeIf { k.under(from) }?.let { k to it } }
        if (moving.isEmpty()) return all()
        prefs.edit {
            for ((k, v) in moving) {
                remove(k)
                putString(if (k == from) to else to + k.removePrefix(from), v)
            }
        }
        return all()
    }

    /** Whether this key is [other] or sits beneath it, [other] being a folder in that case. */
    private fun String.under(other: String) = this == other || startsWith("$other/")
}

// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.storage

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * The takes opened most recently, newest first.
 *
 * **Opened, not played.** The play triangle in the library starts a take where it sits, without
 * entering the player, and that deliberately does not count. It is the skimming gesture — five taps
 * down a folder looking for the right one — and letting it stamp would push the takes actually sat
 * with out of a list five long. `openTake` is the only door.
 *
 * ## Why keys and not takes
 *
 * The same key a star and a note use — the document id with the root's stripped off, a path like
 * `Apache/take.wav`. See [Stars] for why not the uri.
 *
 * A key is also all the drawer needs to *draw* a row: the last segment is the filename, so a list of
 * recents renders without touching storage at all. Resolving happens on the tap, which is the one
 * moment the answer matters and the one moment a wait is affordable. A drawer that had to hit the
 * document provider before it could show anything would be a drawer that opens empty and fills in.
 *
 * ## Ordered, and so not a StringSet
 *
 * [Stars] keeps a `StringSet`, which is right for a set and wrong here — order *is* the data. A
 * JSON array keeps it, and keeps it legible in a prefs dump, which a delimiter-joined string would
 * not once a filename contained the delimiter.
 *
 * ## Never garbage-collected here
 *
 * A key that no longer resolves is dropped when it is *tapped*, not by a sweep. The reasoning is
 * [Notes]': a lookup can fail because the root is momentarily wrong or the storage is unmounted,
 * which has nothing to do with the take being gone, and a recents list is not worth a background
 * job that walks every entry to find out. Deletes and renames made through the app are followed
 * exactly as a star's are.
 */
class Recents(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("recents", Context.MODE_PRIVATE)

    /** The keys, newest first. */
    fun all(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
    }

    /** Put [key] at the front, moving it there if it was already in the list. Capped at [LIMIT]. */
    fun touch(key: String): List<String> =
        write(listOf(key) + all().filterNot { it == key })

    /** Drop [key], and anything beneath it if it names a folder. */
    fun remove(key: String): List<String> = write(all().filterNot { it.under(key) })

    /**
     * Follow a rename or a move from [from] to [to], keeping the entry where it sits in the order.
     *
     * A take renamed from the player is very often the one just opened, so losing it here would
     * empty the top of the list at the exact moment it was being looked at.
     */
    fun rename(from: String, to: String): List<String> =
        write(all().map { if (it.under(from)) to + it.removePrefix(from) else it })

    private fun write(keys: List<String>): List<String> {
        val capped = keys.take(LIMIT)
        prefs.edit { putString(KEY, JSONArray(capped).toString()) }
        return capped
    }

    private companion object {
        const val KEY = "recents"

        /** Long enough to hold a session's worth of takes, short enough to stay a glance. */
        const val LIMIT = 5
    }
}

/** True if this key is [other] or sits beneath it, so a folder's keys go with the folder. */
private fun String.under(other: String): Boolean = this == other || startsWith("$other/")

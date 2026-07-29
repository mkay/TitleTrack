// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit

/**
 * Which takes are starred.
 *
 * Kept in the app rather than in the user's files, which is a trade rather than an oversight. The
 * alternatives were writing the star into the take — the `LIST/INFO` chunk already carries tempo and
 * title — or into its filename. The first means rewriting the whole file on every tap and cannot
 * touch the m4a and mp3 takes the app did not create; the second rewrites names the user chose. A
 * star has to be instant and must work for any file in the folder, so it lives here.
 *
 * The cost is that a star is invisible from a desktop and does not survive a file being renamed or
 * moved from outside the app. Renames *inside* the app are followed — see [rename] — which covers
 * the case that actually happens.
 *
 * ## The key
 *
 * Not the document uri: that carries the tree grant, so re-picking the same folder can mint a
 * different one and orphan every star. What is stored is the document *id* with the root's own id
 * stripped off it, which for the storage providers this app is used with is a path —
 * `Apache/take.wav`. Stable across re-granting, and legible in a prefs dump.
 *
 * A provider handing out opaque ids instead still works, but its keys stop being path-shaped and a
 * rename will lose the star rather than move it. That is a known limit, not something guarded
 * against: the guard would cost a lookup table keyed by something no more stable.
 */
class Stars(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("stars", Context.MODE_PRIVATE)

    /** Every starred key. Read whole — there are tens of these, not thousands. */
    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()

    fun isStarred(key: String): Boolean = key in all()

    fun toggle(key: String): Set<String> {
        val next = all().toMutableSet()
        if (!next.remove(key)) next.add(key)
        return write(next)
    }

    /** Drop [key], and anything beneath it if it names a folder. */
    fun remove(key: String): Set<String> = write(all().filterNot { it.under(key) }.toSet())

    /** Drop several at once — a batch delete, where re-reading the set each time would be waste. */
    fun removeAll(keys: Collection<String>): Set<String> {
        val gone = keys.toSet()
        return write(all().filterNot { k -> gone.any { k.under(it) } }.toSet())
    }

    /**
     * Follow a rename from [from] to [to].
     *
     * Folders are the reason this takes prefixes rather than whole keys: renaming a folder changes
     * the id of every take inside it, and without rewriting those the stars beneath it would all
     * silently orphan while the takes themselves are perfectly fine.
     */
    fun rename(from: String, to: String): Set<String> = write(
        all().map { key ->
            when {
                key == from -> to
                key.startsWith("$from/") -> to + key.removePrefix(from)
                else -> key
            }
        }.toSet(),
    )

    private fun write(next: Set<String>): Set<String> {
        // A new set each time: SharedPreferences hands back the same instance it holds, and
        // mutating that one leaves the store and the caller disagreeing until the next process.
        prefs.edit { putStringSet(KEY, HashSet(next)) }
        return next
    }

    /** Whether [this] is [other] or sits inside it, [other] being a folder. */
    private fun String.under(other: String) = this == other || startsWith("$other/")

    companion object {
        private const val KEY = "starred"

        /**
         * The key for [uri] under the tree [root], or null if the two are unrelated — which happens
         * while a new root is being granted and the listing on screen still belongs to the old one.
         */
        fun keyFor(root: Uri?, uri: Uri): String? {
            if (root == null) return null
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull()
                ?: return null
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            return when {
                id == rootId -> ""
                id.startsWith("$rootId/") -> id.removePrefix("$rootId/")
                else -> null
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.storage

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The stars-and-notes export: a small JSON document written out from Settings and read back on
 * another phone, or against another folder on the same one.
 *
 * The keys are the folder-relative paths [Stars] and [Notes] already use — `Apache/take.wav` — so
 * a file exported from one library lines up with another only where the two folders hold the same
 * takes. That is the point and also the limit: an import can only ever *add*. It unions the stars
 * in, and fills a note in where there is none; it never rewrites a note already on the phone, and
 * never removes anything. Re-importing the same file is a no-op.
 *
 * Not a general backup — the recordings are the recording folder's job, and the recents list is
 * disposable — just the two things the app keeps on the side and cannot otherwise carry across.
 */
data class LibraryData(
    val stars: Set<String>,
    val notes: Map<String, String>,
) {

    /** Pretty JSON, keys sorted so a diff between two exports is legible. */
    fun toJson(): String = JSONObject().apply {
        put(MARKER, 1)
        put(STARS, JSONArray(stars.toSortedSet()))
        put(NOTES, JSONObject(notes.toSortedMap()))
    }.toString(2)

    companion object {
        /** Present on every file this writes; its absence is how a foreign file is told apart. */
        private const val MARKER = "titletrack_stars_notes"
        private const val STARS = "stars"
        private const val NOTES = "notes"

        /**
         * Parse [text], or null if it is not a file this wrote — malformed JSON, or JSON without
         * the [MARKER]. Blank keys and blank note text are dropped on the way in, the same as
         * [Notes.set] would drop them.
         */
        fun fromJson(text: String): LibraryData? = try {
            val root = JSONObject(text)
            if (!root.has(MARKER)) {
                null
            } else {
                LibraryData(
                    stars = root.optJSONArray(STARS).strings().toSet(),
                    notes = buildMap {
                        val obj = root.optJSONObject(NOTES) ?: JSONObject()
                        for (key in obj.keys()) {
                            val value = obj.optString(key).trim()
                            if (key.isNotBlank() && value.isNotEmpty()) put(key, value)
                        }
                    },
                )
            }
        } catch (_: JSONException) {
            null
        }

        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).trim().ifBlank { null } }
        }
    }
}

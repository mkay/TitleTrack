// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder

import java.text.Normalizer
import java.util.Locale

/**
 * What the library search box asks for, as a test on one take.
 *
 * A take matches when every word typed appears somewhere in its key or its note. The key is the
 * path under the recordings folder, so it carries the file name *and* every folder above it:
 * "wilhelm" finds everything in a Wilhelm folder, and "wilhelm refrain" narrows that to the
 * refrain. The note is what was written about a take, which is often the only place an idea's
 * real name lives — "the riff in D" was never a file name.
 *
 * Words rather than one phrase, in any order, because nobody remembers the order of a name they
 * half remember. Case and accents are ignored on both sides: a take saved as "Übung" is found by
 * "ubung", which is what a thumb on a keyboard without the umlaut to hand will type.
 *
 * Pure Kotlin, so the matching is a unit test rather than something checked by typing on a phone.
 */
class TakeQuery private constructor(private val words: List<String>) {

    fun matches(key: String, note: String?): Boolean {
        val haystack = fold(if (note.isNullOrEmpty()) key else "$key\n$note")
        return words.all { it in haystack }
    }

    companion object {
        /** The query typed as [text], or null if there is nothing in it to search for. */
        fun of(text: String): TakeQuery? =
            fold(text).split(Regex("\\s+")).filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.let(::TakeQuery)

        private val marks = Regex("\\p{Mn}+")

        /** Lower-cased, with accents taken off — "Übung" and "ubung" fold to the same thing. */
        private fun fold(s: String): String =
            marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import de.singular.recorder.storage.StorageException
import de.singular.recorder.storage.StorageFailure

/**
 * Something to tell the user, held as a resource and its arguments rather than as finished text.
 *
 * The ViewModel used to put whole English sentences into its message flow. That is fine in a
 * one-language app and wrong the moment there are two, for a reason that is easy to miss: the
 * sentence would be fixed at the moment the *failure* happened, not the moment it is *shown*. A
 * message raised just before the language was changed would still be sitting there in the old
 * language when the snackbar came up. Keeping it unresolved until the snackbar renders it makes
 * that impossible rather than unlikely.
 *
 * It also keeps the ViewModel out of the business of knowing what words look like, which is what let
 * `exception.message` end up on screen in the first place — see [StorageFailure].
 */
sealed interface Message {

    /** A sentence, with anything it interpolates — a take's name, a gain in dB. */
    data class Text(@StringRes val text: Int, val args: List<Any> = emptyList()) : Message

    /**
     * A sentence whose wording depends on a count ("Moved 1 take" / "Moved 4 takes").
     *
     * [count] picks the form; it is *also* passed as the first argument, because every plural here
     * says the number as well as agreeing with it.
     */
    data class Quantity(
        @PluralsRes val plural: Int,
        val count: Int,
        val args: List<Any> = emptyList(),
    ) : Message

    companion object {
        /** The store's own reason for giving up, said in words. */
        fun of(failure: StorageFailure) = Text(failure.message)

        /**
         * A failure, as a message: its [StorageFailure] when it has one, [fallback] when the
         * exception came from somewhere that does not name its causes.
         */
        fun of(error: Throwable, @StringRes fallback: Int): Message =
            (error as? StorageException)?.let { of(it.failure) } ?: Text(fallback)
    }
}

/** The finished sentence, in the locale [resources] is currently configured for. */
fun Message.resolve(resources: Resources): String = when (this) {
    is Message.Text -> resources.getString(text, *args.toTypedArray())
    is Message.Quantity ->
        resources.getQuantityString(plural, count, count, *args.toTypedArray())
}

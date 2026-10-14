// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * The launcher's long-press menu: the take just recorded, the starred ones, and the tuner.
 *
 * Each is somewhere the app is opened *for* that sits more than a tap from where it opens. A "new
 * recording" entry is deliberately absent — the app opens on the record screen, so it would only
 * be a second way to the same place.
 *
 * ## Dynamic, though nothing about them changes
 *
 * Static shortcuts in `shortcuts.xml` would be the obvious home, but a static shortcut is launched
 * with `FLAG_ACTIVITY_CLEAR_TASK`, which no manifest attribute can take off: the running activity
 * is destroyed and the view model with it. Reaching for the tuner mid-take would end the take. A
 * dynamic shortcut carries its own flags, and `SINGLE_TOP | CLEAR_TOP` hands the intent to the
 * activity already running, through `onNewIntent`.
 *
 * The cost is publishing them from code, on every start — which is also what keeps their labels in
 * the language in effect, since changing it recreates the activity.
 */
enum class Shortcut(
    val id: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    LAST_TAKE("last_take", R.string.shortcut_last_take, R.drawable.ic_shortcut_last_take),
    STARRED("starred", R.string.library_tab_starred, R.drawable.ic_shortcut_starred),
    TUNER("tuner", R.string.screen_tuner, R.drawable.ic_shortcut_tuner),
    ;

    /** Not a public API — an action of our own so the intent cannot be mistaken for `MAIN`. */
    val action = "de.singular.recorder.action.${id.uppercase()}"

    companion object {
        /**
         * The shortcut [intent] came from, if any — reported to the launcher as used, which is
         * what lets one that ranks by use put the reached-for entry nearest the finger.
         */
        fun of(context: Context, intent: Intent?): Shortcut? =
            entries.firstOrNull { it.action == intent?.action }?.also {
                ShortcutManagerCompat.reportShortcutUsed(context, it.id)
            }

        /** Publish the menu, replacing whatever was published before. */
        fun publish(context: Context) {
            val shortcuts = entries.mapIndexed { rank, shortcut ->
                val label = context.getString(shortcut.label)
                ShortcutInfoCompat.Builder(context, shortcut.id)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(IconCompat.createWithResource(context, shortcut.icon))
                    .setRank(rank)
                    .setIntent(
                        Intent(shortcut.action, null, context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                    .build()
            }
            // False when rate-limited, which only happens in the background; the next start retries.
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
        }
    }
}

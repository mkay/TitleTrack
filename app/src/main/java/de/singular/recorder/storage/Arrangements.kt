package de.singular.recorder.storage

import android.content.Context
import androidx.core.content.edit
import de.singular.recorder.audio.BandPlayer
import de.singular.recorder.audio.Patterns
import org.json.JSONObject

/**
 * What the band plays under a take, remembered per take.
 *
 * A take is opened, a pattern chosen, the drums pushed under the guitar, the grid nudged until the
 * kick lands with the strum — and none of that should have to be done twice. So it is kept, keyed
 * the way [Stars] keys a star and for the same reasons: the take's document id with the root's own
 * id stripped off, which is path-shaped, stable across re-granting the folder, and legible in a
 * prefs dump. [Stars]' KDoc argues the case for keeping per-take state in the app rather than in
 * the file; every word of it applies here, with more force — an arrangement is a dozen numbers that
 * change while you listen, and rewriting a WAV for each would be absurd.
 *
 * Stored as JSON per key rather than as flattened preference names, because this grows: chords and
 * their corrections land here in phase two, and a nested object takes them without a migration.
 * Unknown fields are ignored and missing ones default, so an arrangement written by a later version
 * degrades to a sensible one rather than to a crash.
 */
class Arrangements(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("arrangements", Context.MODE_PRIVATE)

    fun get(key: String): Arrangement = read(prefs.getString(key, null))

    fun put(key: String, arrangement: Arrangement) {
        // The default arrangement is what a take with no entry already gets, so storing one would
        // be a row that changes nothing — and the store stays as small as the takes people fiddled
        // with rather than as large as the library.
        if (arrangement == Arrangement()) prefs.edit { remove(key) }
        else prefs.edit { putString(key, write(arrangement)) }
    }

    fun remove(key: String) = prefs.edit { remove(key) }

    /** Follow a rename, including a folder's, exactly as [Stars.rename] does. */
    fun rename(from: String, to: String) {
        val moved = prefs.all.keys.filter { it == from || it.startsWith("$from/") }
        if (moved.isEmpty()) return
        prefs.edit {
            for (key in moved) {
                val value = prefs.getString(key, null) ?: continue
                remove(key)
                putString(to + key.removePrefix(from), value)
            }
        }
    }

    private fun read(json: String?): Arrangement {
        if (json.isNullOrBlank()) return Arrangement()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return Arrangement()
        val fallback = Arrangement()
        return Arrangement(
            on = o.optBoolean("on", fallback.on),
            patternId = o.optString("pattern", fallback.patternId),
            beatsPerBar = o.optInt("beatsPerBar", fallback.beatsPerBar),
            bpm = o.optDouble("bpm", fallback.bpm.toDouble()).toFloat(),
            offsetMs = o.optInt("offsetMs", fallback.offsetMs),
            takeLevel = o.optDouble("takeLevel", fallback.takeLevel.toDouble()).toFloat(),
            drumsLevel = o.optDouble("drumsLevel", fallback.drumsLevel.toDouble()).toFloat(),
        )
    }

    private fun write(a: Arrangement): String = JSONObject().apply {
        put("on", a.on)
        put("pattern", a.patternId)
        put("beatsPerBar", a.beatsPerBar)
        put("bpm", a.bpm.toDouble())
        put("offsetMs", a.offsetMs)
        put("takeLevel", a.takeLevel.toDouble())
        put("drumsLevel", a.drumsLevel.toDouble())
    }.toString()
}

/**
 * One take's band settings.
 *
 * [bpm] and [beatsPerBar] are 0 when the take's own tempo is being used — a take carries the tempo
 * it was played to, and copying that in here would freeze a stale number the moment the file's own
 * changed. They hold a value only where the user has overridden it, or where the take never carried
 * one at all.
 */
data class Arrangement(
    val on: Boolean = false,
    val patternId: String = Patterns.default.id,
    /** 0 = follow the app's current setting. */
    val beatsPerBar: Int = 0,
    /** 0 = follow the take's own tempo. */
    val bpm: Float = 0f,
    val offsetMs: Int = 0,
    val takeLevel: Float = 1f,
    val drumsLevel: Float = BandPlayer.DEFAULT_DRUMS_LEVEL,
)

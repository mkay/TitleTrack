package de.singular.recorder

/** How the app picks its light/dark colours: follow the OS, or force one. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User preferences, persisted across launches. */
data class Settings(
    /** Tempo for the count-in, the visual metronome, and the tempo stamped into saved takes. */
    val bpm: Int = 100,
    val beatsPerBar: Int = 4,
    /** Bars of clicks before capture begins; 0 turns the count-in off. */
    val countInBars: Int = 1,
    /**
     * Keep a beat on screen while recording. Silent by design — an audible click on a phone
     * speaker is picked up by the very microphone that is recording you.
     */
    val visualMetronome: Boolean = true,
    /**
     * Show what the microphone hears on the record screen before a take starts.
     *
     * Off by default: it holds the microphone open whenever that screen is in front, which lights
     * the system's microphone indicator. Worth switching on while finding where to put the phone,
     * and worth switching off again after.
     */
    val listenBeforeRecording: Boolean = false,
    /** Hold the display awake — you are holding a guitar, not the phone. */
    val keepScreenOn: Boolean = true,
    /**
     * Ask for a name when saving. Off by default: a take is named after the moment it was played,
     * which is how one recalls it anyway, and the library can rename it later. Naming takes at the
     * point of saving interrupts the loop of play-something, keep-it, play-something-else.
     */
    val promptForFilename: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

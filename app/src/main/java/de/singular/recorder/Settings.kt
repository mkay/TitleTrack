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
     * Lift starred takes to the top of the folder they are in.
     *
     * On by default, because a star is usually given to a take you mean to come back to. Off suits
     * anyone who reads a folder as a record of an afternoon: date order is then the thing being
     * read, and a starred take jumping the queue loses the sequence the takes were made in. The
     * Starred tab gathers them either way, so nothing is out of reach with this off.
     */
    val starredFirst: Boolean = true,
    /**
     * Ask for a name when saving. Off by default: a take is named after the moment it was played,
     * which is how one recalls it anyway, and the library can rename it later. Naming takes at the
     * point of saving interrupts the loop of play-something, keep-it, play-something-else.
     */
    val promptForFilename: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Digital gain applied to every take as it is captured, in whole decibels.
     *
     * Android exposes no microphone preamp gain — there is no input level to turn up — so this is a
     * multiplication, done before the samples are reduced to the 16 bits that go on disk. That is
     * the whole reason it happens at capture rather than afterwards: with float headroom the boost
     * costs no resolution, where boosting a finished 16-bit file spends bits it cannot get back.
     *
     * 0 by default, and set from the level test rather than guessed at.
     */
    val inputGainDb: Int = 0,
)

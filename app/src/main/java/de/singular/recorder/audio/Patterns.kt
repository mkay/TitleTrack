package de.singular.recorder.audio

/** Which voice of the kit a hit asks for, so a pattern can be written without holding one. */
enum class Drum { KICK, SNARE, HAT, HAT_OPEN }

/**
 * One hit: where in the bar, what, and how hard.
 *
 * [step] is a sixteenth from the top of the bar, so 0, 4, 8, 12 are the beats of a four. Velocity
 * is a plain multiplier — the voices are rendered at full tilt and scaled here, which is what lets
 * the same hat sound like an accent or a ghost without a second buffer.
 */
data class Hit(val step: Int, val drum: Drum, val velocity: Float = 1f)

/**
 * A bar of drumming.
 *
 * **Written for four.** Each pattern lays out one bar of 4/4 and is stretched or cut for other
 * meters by [hitsForBar] rather than being written out per time signature: a waltz gets the first
 * three beats of the four, which is not a real waltz pattern but is honest about what it is, and
 * beats leaving the drummer silent whenever the meter is not four.
 *
 * [swing] delays every off-beat eighth by that fraction of a sixteenth — 0f is straight, 0.6f is
 * most of the way to triplets. It is a property of the pattern rather than a control because the
 * two shuffle patterns are the reason it exists and nobody wants a swung backbeat by accident.
 */
data class DrumPattern(
    val id: String,
    val label: String,
    val hits: List<Hit>,
    val swing: Float = 0f,
) {
    /**
     * The hits of one bar of [beatsPerBar], with anything past the end of a short bar dropped.
     *
     * A bar longer than four repeats the pattern's first beat to fill, which keeps a five or a
     * seven walking rather than leaving a hole where the drummer ran out of bar.
     */
    fun hitsForBar(beatsPerBar: Int): List<Hit> {
        val steps = beatsPerBar.coerceAtLeast(1) * Beats.STEPS_PER_BEAT
        if (steps == BAR_STEPS) return hits
        if (steps < BAR_STEPS) return hits.filter { it.step < steps }
        return buildList {
            addAll(hits)
            var at = BAR_STEPS
            while (at < steps) {
                // Fill with beat one, which every one of these patterns starts on.
                hits.filter { it.step < Beats.STEPS_PER_BEAT }
                    .forEach { add(it.copy(step = it.step + at)) }
                at += Beats.STEPS_PER_BEAT
            }
        }
    }

    companion object {
        private const val BAR_STEPS = 4 * Beats.STEPS_PER_BEAT
    }
}

/**
 * The patterns on offer. Four, chosen to be *different from each other* rather than to cover a
 * genre map: a guitar idea wants a pulse, a backbeat, a shuffle, or something that stays out of
 * the way, and a longer list is a longer decision every time you press play.
 */
object Patterns {

    /** Eighths on the hat with a kick on one and three: the metronome's more musical cousin. */
    val STRAIGHT = DrumPattern(
        id = "straight",
        label = "Straight",
        hits = buildList {
            for (s in 0 until 16 step 2) add(Hit(s, Drum.HAT, if (s % 4 == 0) 0.9f else 0.6f))
            add(Hit(0, Drum.KICK))
            add(Hit(8, Drum.KICK, 0.9f))
        },
    )

    /** Snare on two and four, and a kick that pushes the second half. The default for a reason. */
    val BACKBEAT = DrumPattern(
        id = "backbeat",
        label = "Backbeat",
        hits = buildList {
            for (s in 0 until 16 step 2) add(Hit(s, Drum.HAT, if (s % 4 == 0) 0.85f else 0.55f))
            add(Hit(0, Drum.KICK))
            add(Hit(4, Drum.SNARE))
            add(Hit(10, Drum.KICK, 0.85f))
            add(Hit(12, Drum.SNARE))
        },
    )

    /** The same backbeat with its off-beats dragged late — a blues or anything with a lilt. */
    val SHUFFLE = DrumPattern(
        id = "shuffle",
        label = "Shuffle",
        swing = 0.62f,
        hits = buildList {
            for (s in 0 until 16 step 2) add(Hit(s, Drum.HAT, if (s % 4 == 0) 0.85f else 0.5f))
            add(Hit(0, Drum.KICK))
            add(Hit(4, Drum.SNARE))
            add(Hit(8, Drum.KICK, 0.8f))
            add(Hit(12, Drum.SNARE))
        },
    )

    /** Quarters, quiet, no snare: a pulse to play over when a kit would be in the way. */
    val BRUSHES = DrumPattern(
        id = "brushes",
        label = "Brushes",
        hits = buildList {
            for (s in 0 until 16 step 4) add(Hit(s, Drum.HAT, if (s == 0) 0.5f else 0.32f))
            add(Hit(0, Drum.KICK, 0.55f))
            add(Hit(8, Drum.HAT_OPEN, 0.28f))
        },
    )

    val all = listOf(BACKBEAT, STRAIGHT, SHUFFLE, BRUSHES)

    val default = BACKBEAT

    fun byId(id: String?): DrumPattern = all.firstOrNull { it.id == id } ?: default
}

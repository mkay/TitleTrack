package de.singular.recorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import de.singular.recorder.ThemeMode

/**
 * The app's accent, from the Tailwind amber scale: amber-500 on dark, amber-600 on light.
 *
 * The scale before this was lime, and it was replaced for reading as poisonous rather than for any
 * number — a chartreuse mid-tone is the one part of the green family that suggests something you
 * would not want to touch. Amber gives up nothing that mattered: it is still a single hue in two to
 * four flat tones, still the icon's own colour, and a gold on near-black is the colour a level meter
 * has always been. Green (hue 142) and emerald (160) were tried in full during the lime era and
 * rejected on sight for reading as generic; that finding stands and neither is worth revisiting.
 *
 * On dark, amber-500: 8.9:1 against the page and 8.1:1 on the tab bar. Lime took a step *down* here
 * (lime-600) because lime-500's comparable 9.6:1 dominated a dark screen, and that step is not taken
 * again — what dominated was the chartreuse, not the luminance. Amber at this level reads as lit
 * rather than as loud, and the ground being tinted onto its own hue (below) does the rest.
 *
 * On light, amber-600 — and the number is worse than the step above it, deliberately. This was
 * amber-700 at **4.8:1**, chosen because the accent is not only a button fill but the *text* colour
 * of the selected tab, the setting values and the played part of a waveform, and 4.5:1 is the bar
 * for type. **amber-600 reads 3.2:1** on this page and does not clear it.
 *
 * It was taken anyway, on 2026-07-28, as a look: amber-700 on a warm page reads brown rather than
 * gold, which is not the colour the mark is. The contrast floor was a floor for a reason and there
 * is no arguing it away — the accent is small text and 3.0:1 is a real cost to someone reading it in
 * sunlight. What makes it survivable is that nothing is *only* this colour: a selected tab also has
 * its indicator, a setting value also has its label, and the record button also has its stripes.
 * If it proves hard to read, the fix is amber-700 again, not a lighter page — the page is white as
 * of 2026-07-29 and has nowhere left to go.
 *
 * Do not "correct" this back to 4.5:1 as a tidy-up. It is a decision, not a slip.
 *
 * For the record: amber-500 measures **2.1:1** here and lime-500 measured 1.9:1, so the family is
 * still the first one where a legible light accent was even in reach. That number is why amber-500's
 * arrival on this theme is confined to the button fills — see [brandFill] — and does not take the
 * accent's text roles with it. And the light accent lands nearer the record red than amber-700 did,
 * which was once the objection to going dark — no longer live, since the record button is the accent
 * with stripes over it (see [recordBand]) and red has been narrowed to the running state and to
 * clipping.
 *
 * Filled controls set their own content colour, and the two themes need different ones here — see
 * [OnBrandDark].
 */
private val BrandOnDark = Color(0xFFF59E0B) // amber-500
private val BrandOnLight = Color(0xFFD97706) // amber-600

/**
 * The accent as a **shape** rather than as type: a filled button, a picked chip, a switch that is
 * on, a trim handle, and every icon the accent tints — the play triangles, the stars, the folders,
 * the note bubble. As of 2026-07-29 that is amber-500 on both themes, and so the one place the
 * accent is not `primary`.
 *
 * The split is the light theme's alone, and it exists because a fill and a label are not the same
 * job. `primary` there is amber-600 at 3.2:1 because it is *text* — the selected tab, the setting
 * values — and text is what the contrast floor is for. None of that applies to a slab of colour with
 * its own dark label on it, nor to a solid glyph: what these owe the page is to look like the mark,
 * and against white the same amber-600 that reads dim as text reads muddy as a shape. amber-500 is
 * the icon's own colour and the colour the dark theme has always used.
 *
 * The line is **drawn or set**, and glyphs fall on the drawn side of it. An icon has no counters to
 * lose and no stroke thin enough for 3.2:1 to be doing the work a contrast floor is for — a play
 * triangle is a 24dp area, and it was reading as a brown triangle beside a gold button.
 *
 * What stays on `primary` is what is set, and what is drawn *of* the audio: the tab bar (its icon
 * and its label are one colour two pixels apart, and splitting them would put two golds side by
 * side), the level meter, the mini player's progress, the metronome's dots, the played part of a
 * waveform, the selection wash. Those either are type or sit beside it.
 *
 * The cost is a step off the ramp, which the record button was explicitly kept on ([recordBand]) —
 * but that was about the record button differing from *other buttons*, and this moves everything
 * drawn together. What it does not do is give the light theme a second accent to keep in agreement
 * with the first: the two ambers do not touch, one being a shape and the other being type.
 *
 * The dark theme was already amber-500 throughout, so this collapses rather than adds — both themes
 * now fill with the same colour, label it amber-950 at 7.0:1, and band it identically.
 *
 * Reached through `brandButtonColors` and `brandSwitchColors` in Common.kt rather than at each call
 * site: Material's own defaults all point at `primary`, so a control that forgets to ask lands back
 * on the text step and is the one gold in the app that does not match.
 */
@Suppress("UnusedReceiverParameter")
val ColorScheme.brandFill: Color get() = BrandFill

private val BrandFill = Color(0xFFF59E0B) // amber-500

/**
 * Content sitting *on* the accent, which the two themes have to answer differently because their
 * accents are four rungs apart.
 *
 * On amber-500 the scale's darkest step reads 7.0:1 and white only 2.2:1, so dark content is not a
 * stylistic choice but the only legible one. On amber-700 that inverted — white 5.0:1, amber-950
 * 3.0:1 — and white is what the light theme used while it sat there.
 *
 * **amber-600 crosses back over**: white falls to 3.2:1 and amber-950 rises to 4.7:1, so the light
 * theme takes dark content too and the themes agree here for the first time. This is the one place
 * the lighter accent *gains* contrast rather than spending it, the fill having moved out from under
 * its own label. Each theme still takes whichever its accent can carry; the accent simply moved.
 */
private val OnBrandDark = Color(0xFF451A03) // amber-950
private val OnBrandLight = Color(0xFF451A03) // amber-950

/**
 * The scale's ends, which is what a tinted surface wants: amber-100 is too light to be an accent on
 * a white page but exactly right as the *ground* for one, and amber-950 is too dark to be anything
 * else. Each fills whichever role the theme leaves it — the container on a light screen, the
 * container's content on a dark one — and the pairing reads at 13.5:1 either way round.
 */
private val BrandPale = Color(0xFFFEF3C7) // amber-100
private val BrandDeep = Color(0xFF451A03) // amber-950
private val OnBrandPale = Color(0xFF451A03) // amber-950

/**
 * The recording *state*: the elapsed time while a take runs, the beat flash under it, the top of a
 * level meter, and the message when something has gone wrong.
 *
 * Deliberately not the accent: while a take is running, "is it recording?" is the only question the
 * screen has to answer from across a room, and it should never be answered in the same colour as an
 * ordinary control.
 *
 * This used to fill the record button too, and no longer does — see [recordBand] for the split and
 * why the two are allowed to differ. What is left here is the half that has to stay red: a running
 * clock, and a meter whose top says the ADC is clipping. Nothing else in the app is red now, which
 * is the point — red that appears in the ordinary run of things stops being read as a warning.
 *
 * Fixed per theme rather than derived. It was computed from the accent for a while, on the argument
 * that the two should stay a matched pair while the accent was still moving; now that the palette is
 * settled that buys nothing, and a derived red inherits whatever the accent does — one softening of
 * the accent pushed the red to a washed #F5685C, which is the opposite of what this colour is for.
 * Urgency does not follow the accent around, and with the accent now warm it must not: amber-700 and
 * this red are 1.5:1 apart, so a red that drifted toward its own accent would arrive at it.
 *
 * Both numbers below survived the move from lime to amber unchanged, the page having only been
 * rehued.
 *
 * The light theme uses oklch(50.5% 0.213 27.518) = #C10007 — 6.1:1 against its page, white label at
 * 6.4:1.
 *
 * The dark theme takes that same red down to #A01A1F: the hue held, saturation 100% → 72%,
 * lightness barely moved. On a near-black screen a fully saturated red slab 64dp tall and the full
 * width of the page dominates everything else on it, including the waveform it sits under. Note
 * that saturation is what was cut, not lightness — going *darker* was tried and is the wrong lever,
 * because it drops the button toward the page without making it any less strident, and the point is
 * to make the red recede in intensity while staying exactly where it is in the layout. That slab is
 * gone, but the reasoning is kept: the same colour still has to carry a large running clock.
 */
val ColorScheme.record: Color get() =
    if (surface.luminance() < 0.5f) RecordRedOnDark else RecordRed

/**
 * What sits *on* [record] — white on both themes, both reds being dark enough to take it (6.4:1 on
 * light, 7.9:1 on dark). Not `onPrimary`, which follows the accent: that is amber-950 on dark, and
 * amber-950 on either red is under 2.5:1.
 */
@Suppress("UnusedReceiverParameter")
val ColorScheme.onRecord: Color get() = OnRecord

private val RecordRed = Color(0xFFC10007)
private val RecordRedOnDark = Color(0xFFA01A1F)
private val OnRecord = Color(0xFFFFFFFF)

/**
 * Oblique bands over the record button — the whole of what marks it out, and the only colour it does
 * not share with every other filled action.
 *
 * The button was red for as long as the recording state was red, on the argument that they are one
 * thing. They are not, and the giveaway is that **they never appear at once** — the button is on
 * screen only before a take, and the clock only during one. So the button is an accent button like
 * Play or Save, down to the same [brandFill] and `onPrimary` label, while [record] keeps the red
 * for the state. A red slab 64dp tall and the full width of the page was also simply the loudest
 * thing in the app, on a screen whose whole palette had been tuned toward being quiet.
 *
 * What is left to distinguish it is texture, which is a channel the palette was not using and the
 * only one that survives two buttons being the same colour on purpose. Nothing here is a step off
 * the ramp: no separate fill, no separate label, no third amber to keep in agreement with the other
 * two. See `obliqueBands` in Common.kt for the geometry.
 *
 * A **white tint at 20%**, one value for both themes, which over a saturated amber-500 mostly
 * desaturates rather than lightens and lands at about 1.15:1 against the fill — subtle, which is the
 * point: this is a marking on a button, not a second colour competing with the label over it.
 *
 * It was 8% on light for as long as that theme filled with amber-700 and then amber-600, white over
 * a darker amber lightening it far more readily. [brandFill] made both buttons amber-500, so a
 * per-theme alpha would now produce a *visibly* different texture on the same colour, which is the
 * opposite of what the split was for.
 *
 * Dark bands were the first cut, hazard-tape fashion, and are what the amber-950 label rules out: a
 * dark band is the label's own colour thinned, and took it from 7.0:1 down to 4.8:1 where one
 * crossed a letter — the worst number in the palette, in the one place it must not be. Lightening
 * inverts that, to 8.2:1.
 */
@Suppress("UnusedReceiverParameter")
val ColorScheme.recordBand: Color get() = RecordBand

private val RecordBand = Color(0x33FFFFFF) // white at 20%

/**
 * The ground a waveform is drawn on — the record screen's panel, the player's, and the trim rows
 * that belong to them.
 *
 * The top of the surface ramp, which is the step the tab bar takes, so the two largest tinted areas
 * on a screen agree rather than each being their own shade of nearly-the-page.
 *
 * It was ink over the page for a while — `onSurface` at 4.5% — on the argument that mixing toward the
 * text colour desaturates as it darkens, and that the largest area on the screen wants *less* of the
 * theme's hue than everything else does. That argument was made against a lime accent, where the page
 * carried a hue the panel had to be protected from. On the warm ramp it costs more than it buys: the
 * ink panel lands within a hundredth of `surfaceContainer` in lightness and differs from it only by
 * being greyer, which is a distinction nobody reads as anything but a slightly dead patch. This is
 * 1.33:1 against the page on dark and 1.14:1 on light, and the waveform still reads on it at 12:1.
 * The light theme's ground is a plain grey rather than a tint of its page — see [TrackLightColors].
 */
val ColorScheme.waveformPanel: Color get() = surfaceContainerHighest

/**
 * The ground the accent stands on, and the reason it stops reading as neon.
 *
 * Material's own neutrals are not neutral — they carry a faint violet, and an accent on a violet-grey
 * is pushed away from its background by hue as well as by level. That hue contrast was most of what
 * "neon" was describing back when the accent was lime; the accent itself was only ever half the
 * problem, which is why moving to amber does not make the retint unnecessary.
 *
 * So the surfaces are re-tinted onto the accent's own hue (38°) at a saturation low enough to still
 * read as a dark grey — 20–30% at the near-black end, tapering as the ramp lightens so the higher
 * containers do not turn into a colour in their own right. The accent sits *within* its background's
 * family rather than opposite it, which reads as considered rather than electric. The whole ramp is
 * the lime one rehued, holding each step's saturation and lightness, so a warm grey now where there
 * was a cool one and every level unchanged.
 *
 * Body text holds at 16.1:1 and secondary text at 12.2:1 over this ground.
 */
private val TrackDarkColors = darkColorScheme(
    primary = BrandOnDark,
    onPrimary = OnBrandDark,
    primaryContainer = BrandDeep,
    onPrimaryContainer = BrandPale,
    secondaryContainer = BrandOnDark,
    onSecondaryContainer = OnBrandDark,
    background = Color(0xFF120F0A),
    onBackground = Color(0xFFEEEBE7),
    surface = Color(0xFF120F0A),
    onSurface = Color(0xFFEEEBE7),
    surfaceVariant = Color(0xFF413A2D),
    onSurfaceVariant = Color(0xFFD3CEC5),
    surfaceContainerLowest = Color(0xFF0C0A06),
    surfaceContainerLow = Color(0xFF18140E),
    surfaceContainer = Color(0xFF1E1A12),
    surfaceContainerHigh = Color(0xFF272219),
    surfaceContainerHighest = Color(0xFF2F2920),
    outline = Color(0xFF9A907E),
    outlineVariant = Color(0xFF4D4538),
)

/**
 * The light theme is **white and grey**, and is the one place the two themes do not answer the same
 * question the same way.
 *
 * Four pages have been tried. Material's own default is not white — it is #FEF7FF, a near-white
 * carrying a faint *violet*, which is the accent's opposite and quietly works against it. A page
 * committed fully to the accent's own 50 step fixed that but committed harder than a light theme
 * wants to. #FEF9F1 sat between them, a hair over 1.05:1 against true white, on the argument that
 * the dark theme's retint (see [TrackDarkColors]) is what stops the accent reading as neon and the
 * light theme should be built the same way.
 *
 * **On 2026-07-29 it went white.** The argument did not carry across: what the warm tint buys on
 * near-black is a ground the accent can sit *within*, and at the white end there is no room to sit
 * within anything — 1.05:1 is below the threshold at which a hue reads as a decision, so all it did
 * was make the page look slightly unclean beside a white system UI. A hue this faint is either
 * invisible or it is a smudge, and on a phone's own white it was the second.
 *
 * So the surfaces are neutral here, and the accent carries the warmth on its own. The ramp is
 * Tailwind's neutral scale, descending from white so the containers deepen as plain grey: body text
 * at 17.9:1, secondary at 7.8:1, both better than the warm ramp they replace.
 *
 * The top step — the waveform panel, the largest area on the record screen — is #F0F0F0 at 1.14:1.
 * That is close to the 1.15:1 the warm ramp was compressed *away from* on 2026-07-28 for reading as
 * a slab laid on the page. It reads differently now for the reason the tint was dropped: a grey
 * panel on white is a panel, where a warm panel on a warm page was the page failing to be one
 * colour. This is the step the tab bar takes too, so the two largest tinted areas agree.
 */
private val TrackLightColors = lightColorScheme(
    primary = BrandOnLight,
    onPrimary = OnBrandLight,
    primaryContainer = BrandPale,
    onPrimaryContainer = OnBrandPale,
    secondaryContainer = BrandOnLight,
    onSecondaryContainer = OnBrandLight,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF171717),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFE5E5E5),
    onSurfaceVariant = Color(0xFF525252),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF7F7F7),
    surfaceContainerHigh = Color(0xFFF4F4F4),
    surfaceContainerHighest = Color(0xFFF0F0F0),
    outline = Color(0xFFA3A3A3),
    outlineVariant = Color(0xFFD4D4D4),
)

/** Controls use a gentle corner rather than the fully-rounded Material default, as in RubberRing. */
val ControlShape = RoundedCornerShape(5.dp)

/** Whether [mode] means dark right now — resolving SYSTEM against the OS setting. */
@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun TitleTrackTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark(mode)) TrackDarkColors else TrackLightColors,
        content = content,
    )
}

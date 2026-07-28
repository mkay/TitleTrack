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
 * The app's accent, from the Tailwind amber scale: amber-500 on dark, amber-700 on light.
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
 * On light, amber-700, and this is where the palette differs from every version of the lime one. The
 * accent is not only a button fill — it is the *text* colour of the selected tab, the setting values,
 * the played part of a waveform — and a mid-scale accent on a near-white page cannot clear 4.5:1 in
 * these families: amber-500 measures **2.1:1** there, lime-500 measured 1.9:1, and the page colour
 * does not move it. Three palettes shipped that washed-out state as a known cost. Amber is the first
 * family where the fix is close enough to take: **amber-700 reads 4.8:1** on this page, two rungs
 * down rather than the four the greens needed, and it stays recognisably the same colour as the mark
 * it comes from. Setting values and tab labels are legible on the light theme for the first time.
 *
 * The cost is that the two themes no longer share one step, and that the light accent lands 1.5:1
 * from the record red. That was the objection to amber-700 while the record button was a red slab —
 * two filled controls of nearly one luminance, told apart by hue alone. It no longer applies: the
 * record button is the accent with stripes over it now (see [recordBand]) and red has been narrowed to
 * the running state and to clipping, so nothing on the light theme puts a red fill beside an accent
 * one.
 *
 * Filled controls set their own content colour, and the two themes need different ones here — see
 * [OnBrandDark].
 */
private val BrandOnDark = Color(0xFFF59E0B) // amber-500
private val BrandOnLight = Color(0xFFB45309) // amber-700

/**
 * Content sitting *on* the accent, which the two themes have to answer differently because their
 * accents are four rungs apart.
 *
 * On amber-500 the scale's darkest step reads 7.0:1 and white only 2.2:1, so dark content is not a
 * stylistic choice but the only legible one. On amber-700 it inverts: white is 5.0:1 and amber-950
 * falls to 3.0:1. Each theme takes whichever its accent can carry.
 */
private val OnBrandDark = Color(0xFF451A03) // amber-950
private val OnBrandLight = Color(0xFFFFFFFF) // white

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
 * Play or Save, down to the same `primary` fill and `onPrimary` label, while [record] keeps the red
 * for the state. A red slab 64dp tall and the full width of the page was also simply the loudest
 * thing in the app, on a screen whose whole palette had been tuned toward being quiet.
 *
 * What is left to distinguish it is texture, which is a channel the palette was not using and the
 * only one that survives two buttons being the same colour on purpose. Nothing here is a step off
 * the ramp: no separate fill, no separate label, no third amber to keep in agreement with the other
 * two. See `obliqueBands` in Common.kt for the geometry.
 *
 * A **white tint**, and the alpha differs per theme so that the *visible* texture does not. White
 * over amber-700 lightens it readily and over a saturated amber-500 mostly desaturates it, so 8% on
 * light and 20% on dark both land at about 1.15:1 against their fill — subtle, which is the point:
 * this is a marking on a button, not a second colour competing with the label over it.
 *
 * Dark bands were the first cut, hazard-tape fashion, and are what the per-theme labels rule out. On
 * dark, where the label is amber-950, a dark band is the label's own colour thinned and took it from
 * 7.0:1 down to 4.8:1 where one crossed a letter — the worst number in the palette, in the one place
 * it must not be. Lightening inverts that, to 8.2:1. The light theme pays a little for the same
 * choice in reverse, its white label going 5.0:1 → 4.4:1 over a band, which is why the alpha there is
 * as low as it is: past about 12% the label starts to be the thing paying for the texture.
 */
val ColorScheme.recordBand: Color get() =
    if (surface.luminance() < 0.5f) RecordBandOnDark else RecordBandOnLight

private val RecordBandOnDark = Color(0x33FFFFFF) // white at 20%
private val RecordBandOnLight = Color(0x14FFFFFF) // white at 8%

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
 * 1.33:1 against the page on dark and 1.15:1 on light, and the waveform still reads on it at 12:1.
 */
val ColorScheme.waveformPanel: Color get() = surfaceContainerHighest

/**
 * What to paint the wordmark with, or `null` to leave it as drawn.
 *
 * The mark is exported for a dark ground, where its three ambers are a ramp of light: the letters
 * sit back and the waveform between them is the lit part. The light page has no such light to give
 * — the same three land between 3.0:1 and 1.6:1 on it — and the ramp stops reading as depth and
 * starts reading as three washed-out golds next to each other. So light mode flattens the whole
 * mark to amber-900, 8.7:1: drawn rather than lit. A dark brown at that depth is what the amber
 * family has in place of lime-900's dark green, and it reads better than the green did.
 *
 * A tint rather than a second drawable, because `-night` resources follow the *system's* night
 * setting while this app's theme is its own preference. Anyone running the app light on a dark
 * phone got the dark mark on the light page, which is exactly the pairing this is meant to avoid.
 */
val ColorScheme.wordmarkTint: Color? get() =
    if (surface.luminance() < 0.5f) null else WordmarkOnLight

private val WordmarkOnLight = Color(0xFF78350F) // amber-900

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
 * The light theme is tinted onto the same hue as the dark one, but far more lightly.
 *
 * Three pages have been tried here. Material's own default is not white — it is #FEF7FF, a
 * near-white carrying a faint *violet*, which is the accent's opposite and quietly works against it.
 * A page committed fully to the accent's own 50 step fixed that but committed harder than a light
 * theme wants to. This sits between them at #FEF9F1: a hair over 1.05:1 against true white, so it
 * reads as a warm white rather than as a coloured page — enough to take the violet out and put the
 * accent on its own ground, and not enough to be a colour in its own right. Amber costs nothing here
 * that lime did not: a warm off-white is the easier of the two to keep this side of being a colour,
 * a faint yellow being what paper does anyway.
 *
 * The ramp below descends from it on the same hue with the saturation tapering as it darkens, so the
 * containers deepen without turning into a colour either. Body text holds at 14.7:1, secondary at
 * 7.1:1 — both a shade better than the lime ramp they were rehued from, warm neutrals of a given
 * saturation coming out slightly darker than cool ones.
 */
private val TrackLightColors = lightColorScheme(
    primary = BrandOnLight,
    onPrimary = OnBrandLight,
    primaryContainer = BrandPale,
    onPrimaryContainer = OnBrandPale,
    secondaryContainer = BrandOnLight,
    onSecondaryContainer = OnBrandLight,
    background = Color(0xFFFEF9F1),
    onBackground = Color(0xFF2F230E),
    surface = Color(0xFFFEF9F1),
    onSurface = Color(0xFF2F230E),
    surfaceVariant = Color(0xFFEEDEC4),
    onSurfaceVariant = Color(0xFF68522C),
    surfaceContainerLowest = Color(0xFFFFFEFB),
    surfaceContainerLow = Color(0xFFFDF5E8),
    surfaceContainer = Color(0xFFFCF2E0),
    surfaceContainerHigh = Color(0xFFF9EDD8),
    surfaceContainerHighest = Color(0xFFF6E8CF),
    outline = Color(0xFF978059),
    outlineVariant = Color(0xFFDDCCAF),
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

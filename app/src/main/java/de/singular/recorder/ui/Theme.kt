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
 * The app's accent, from the Tailwind lime scale: lime-600 on dark, lime-500 on light.
 *
 * Green (hue 142) and emerald (160) were both tried in full and both rejected on sight: they are
 * calmer, and calmer turned out to be the wrong target — they read as generic where the lime reads
 * as this app's. The chartreuse edge is the point, not a defect to be engineered out.
 *
 * The two themes take different steps, and note that they move in the *opposite* direction to the
 * intuition: on a near-black page a darker accent has less contrast, not more. lime-600 on dark is
 * 6.1:1 against the page and 4.5:1 on the tab bar, down from lime-500's 9.6:1 and 7.0:1. It is
 * chosen for presence rather than legibility — lime-500 dominated a dark screen, and a step down
 * settles it without dropping below the bar anywhere it is used as text.
 *
 * On the light theme lime-500 stays, and there it does not clear the bar. The accent is not only a
 * button fill — it is the *text* colour of the selected tab, the setting values, the played part of
 * a waveform — and lime-500 on Material's near-white page is **1.9:1** against the 4.5:1 body text
 * needs; on the tab bar, where the selected tab's label is the only thing marking which tab you are
 * on, it is **1.7:1**. The page colour does not move this: lime-50, Material's default and the
 * near-white tint now in use all land within a few hundredths of each other for a mid-lime.
 *
 * This is chosen with that known, and it survived three palettes and two page colours: the numbers
 * shifted (green 2.2/1.9, emerald 2.4/2.1) without ever reaching legibility, because the cause is
 * structural rather than chromatic. A mid-scale accent on any near-white page cannot clear 4.5:1 in
 * these families; only the 700 step and below does, two steps away. lime-600 — the step the dark
 * theme already takes — would bring it to 3.0:1, which is most of the fix if it is ever wanted.
 *
 * Filled controls are unaffected either way — those set their own content colour, see [OnBrandDark].
 */
private val BrandOnDark = Color(0xFF65A30D) // lime-600
private val BrandOnLight = Color(0xFF84CC16) // lime-500

/**
 * Content sitting *on* the accent — the scale's darkest step, at 4.7:1 on lime-600 and 7.4:1 on
 * lime-500. White would be 2.0:1 against either, so dark content is not a stylistic choice here but
 * the only legible one.
 */
private val OnBrandDark = Color(0xFF1A2E05) // lime-950
private val OnBrandLight = Color(0xFF1A2E05) // lime-950

/**
 * The scale's ends, which is what a tinted surface wants: lime-100 is too light to be an accent on
 * a white page but exactly right as the *ground* for one, and lime-950 is too dark to be anything
 * else. Each fills whichever role the theme leaves it — the container on a light screen, the
 * container's content on a dark one — and the pairing reads at 13.5:1 either way round.
 */
private val BrandPale = Color(0xFFECFCCB) // lime-100
private val BrandDeep = Color(0xFF1A2E05) // lime-950
private val OnBrandPale = Color(0xFF1A2E05) // lime-950

/**
 * Recording, everywhere it appears — the button, the elapsed time, the beat flash.
 *
 * Deliberately not the accent: while a take is running, "is it recording?" is the only question the
 * screen has to answer from across a room, and it should never be answered in the same colour as an
 * ordinary control.
 *
 * Fixed per theme rather than derived. It was computed from the accent for a while, on the argument
 * that the two should stay a matched pair while the accent was still moving; now that the palette is
 * settled that buys nothing, and a derived red inherits whatever the accent does — one softening of
 * the accent pushed the red to a washed #F5685C, which is the opposite of what this colour is for.
 * Urgency does not follow the accent around.
 *
 * The light theme uses oklch(50.5% 0.213 27.518) = #C10007 — 6.1:1 against its page, white label at
 * 6.4:1.
 *
 * The dark theme takes that same red down to #A01A1F: the hue held, saturation 100% → 72%,
 * lightness barely moved. On a near-black screen a fully saturated red slab 64dp tall and the full
 * width of the page dominates everything else on it, including the waveform it sits under. Note
 * that saturation is what was cut, not lightness — going *darker* was tried and is the wrong lever,
 * because it drops the button toward the page without making it any less strident, and the point is
 * to make the red recede in intensity while staying exactly where it is in the layout.
 *
 * The cost is that it reads 2.4:1 as a shape, down from 2.9:1. Both are under the 3:1 usually asked
 * of a control's boundary, and neither matters here: a full-width filled slab carrying a white label
 * at 7.9:1 is not something anyone fails to find. The label is what gets read, and it got better.
 */
val ColorScheme.record: Color get() =
    if (surface.luminance() < 0.5f) RecordRedOnDark else RecordRed

/**
 * What sits *on* [record] — white on both themes, both reds being dark enough to take it (6.4:1 on
 * light, 7.9:1 on dark). Not `onPrimary`, which follows the accent: that is lime-950, and lime-950
 * on either red is under 2.5:1.
 */
@Suppress("UnusedReceiverParameter")
val ColorScheme.onRecord: Color get() = OnRecord

private val RecordRed = Color(0xFFC10007)
private val RecordRedOnDark = Color(0xFFA01A1F)
private val OnRecord = Color(0xFFFFFFFF)

/**
 * What to paint the wordmark with, or `null` to leave it as drawn.
 *
 * The mark is exported for a dark ground, where its three greens are a ramp of light: the letters
 * sit back and the waveform between them is the lit part. The light page has no such light to give
 * — the same three land between 3.0:1 and 1.5:1 on it — and the ramp stops reading as depth and
 * starts reading as three washed-out greens next to each other. So light mode flattens the whole
 * mark to lime-900, 6.9:1: drawn rather than lit.
 *
 * A tint rather than a second drawable, because `-night` resources follow the *system's* night
 * setting while this app's theme is its own preference. Anyone running the app light on a dark
 * phone got the dark mark on the light page, which is exactly the pairing this is meant to avoid.
 */
val ColorScheme.wordmarkTint: Color? get() =
    if (surface.luminance() < 0.5f) null else WordmarkOnLight

private val WordmarkOnLight = Color(0xFF3F6212) // lime-900

/**
 * The ground the accent stands on, and the reason it stops reading as neon.
 *
 * Material's own neutrals are not neutral — they carry a faint violet, and a green on a violet-grey
 * is being pushed nearly as far from its background as the wheel allows. That hue contrast was most
 * of what "neon" was describing; the accent itself was only ever half the problem.
 *
 * So the surfaces are re-tinted onto the accent's own hue (85°) at a saturation low enough to still
 * read as a dark grey — 20–30% at the near-black end, tapering as the ramp lightens so the higher
 * containers do not turn into a colour in their own right. The accent sits *within* its background's
 * family rather than opposite it, which reads as considered rather than electric.
 *
 * Body text holds at 16.1:1 and secondary text at 12.3:1 over this ground.
 */
private val TrackDarkColors = darkColorScheme(
    primary = BrandOnDark,
    onPrimary = OnBrandDark,
    primaryContainer = BrandDeep,
    onPrimaryContainer = BrandPale,
    secondaryContainer = BrandOnDark,
    onSecondaryContainer = OnBrandDark,
    background = Color(0xFF0F120A),
    onBackground = Color(0xFFEBEEE7),
    surface = Color(0xFF0F120A),
    onSurface = Color(0xFFEBEEE7),
    surfaceVariant = Color(0xFF38412D),
    onSurfaceVariant = Color(0xFFCDD3C5),
    surfaceContainerLowest = Color(0xFF090C06),
    surfaceContainerLow = Color(0xFF14180E),
    surfaceContainer = Color(0xFF191E12),
    surfaceContainerHigh = Color(0xFF212719),
    surfaceContainerHighest = Color(0xFF292F20),
    outline = Color(0xFF8F9A7E),
    outlineVariant = Color(0xFF444D38),
)

/**
 * The light theme is tinted onto the same hue as the dark one, but far more lightly.
 *
 * Three pages have been tried here. Material's own default is not white — it is #FEF7FF, a
 * near-white carrying a faint *violet*, which is the accent's opposite and quietly works against it.
 * A full lime-50 page (#F7FEE7) fixed that but committed harder than a light theme wants to. This
 * sits between them at #FAFEF1: a hair over 1.02:1 against true white, so it reads as a warm white
 * that happens to lean green rather than as a green page — enough to take the violet out and put the
 * accent on its own ground, and not enough to be a colour in its own right.
 *
 * The ramp below descends from it on hue 80 with the saturation tapering as it darkens, so the
 * containers deepen without turning into a colour either. Body text holds at 13.8:1, secondary at
 * 6.0:1.
 */
private val TrackLightColors = lightColorScheme(
    primary = BrandOnLight,
    onPrimary = OnBrandLight,
    primaryContainer = BrandPale,
    onPrimaryContainer = OnBrandPale,
    secondaryContainer = BrandOnLight,
    onSecondaryContainer = OnBrandLight,
    background = Color(0xFFFAFEF1),
    onBackground = Color(0xFF242F0E),
    surface = Color(0xFFFAFEF1),
    onSurface = Color(0xFF242F0E),
    surfaceVariant = Color(0xFFE0EEC4),
    onSurfaceVariant = Color(0xFF54682C),
    surfaceContainerLowest = Color(0xFFFEFFFB),
    surfaceContainerLow = Color(0xFFF6FDE8),
    surfaceContainer = Color(0xFFF2FCE0),
    surfaceContainerHigh = Color(0xFFEEF9D8),
    surfaceContainerHighest = Color(0xFFE9F6CF),
    outline = Color(0xFF829759),
    outlineVariant = Color(0xFFCDDDAF),
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

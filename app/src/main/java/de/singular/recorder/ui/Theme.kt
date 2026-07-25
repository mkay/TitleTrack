package de.singular.recorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.singular.recorder.ThemeMode
import kotlin.math.abs

/**
 * The app's accent, taken from the spark in the icon — one for each theme, because a single yellow
 * cannot serve both.
 *
 * The accent is not only a button fill: it is the *text* colour of the selected tab, the setting
 * values, the played part of a waveform. On a dark screen the bright amber does that at 11:1. On a
 * light one it managed 1.6:1 — a yellow label on a white page, effectively invisible — so the light
 * theme takes the orange from the icon and darkens it until it reads: same hue, 4.8:1 against the
 * page, 5.1:1 for white sitting on it.
 */
private val BrandOnDark = Color(0xFFF2C14E)
private val BrandOnLight = Color(0xFFB84E15)

/**
 * Content sitting *on* the accent, which flips with it: near-black over the bright amber (white
 * there lands nowhere near 4.5:1), white over the dark one, each clearing 5:1.
 */
private val OnBrandDark = Color(0xFF1B1C22)
private val OnBrandLight = Color(0xFFFFFFFF)

/**
 * Recording, everywhere it appears — the button, the elapsed time, the beat flash.
 *
 * Deliberately not the accent: while a take is running, "is it recording?" is the only question the
 * screen has to answer from across a room, and it should never be answered in the same colour as an
 * ordinary control.
 *
 * Derived from the accent rather than fixed, so the two stay a matched pair while the accent is
 * still being chosen: the accent's own saturation and lightness, moved to red. Deriving per theme
 * falls out of that for free — the light theme's darker accent yields a deeper red
 * (#941C11 against #F14B3C), which is what a light background needed anyway. That is what makes
 * them look like they come from the same palette instead of one being stapled on. Note that the
 * accent's *hue* is discarded by design — this has to stay red wherever the accent goes — so two
 * accents of equal saturation and lightness derive the same red however far apart they look.
 *
 * An accent in the red-orange quadrant is the hard case: derive naively and this lands on top of
 * it, and the distinction the colour exists to make is gone. So the closer the accent comes, the
 * deeper this goes — see [crowding]. Past that there is no rescuing it; an accent *at* hue 5 would
 * need a fixed red here instead.
 */
val ColorScheme.record: Color get() = recordFrom(primary)

/** Red at [accent]'s lightness, and a little below its saturation — deeper as the accent nears it. */
private fun recordFrom(accent: Color): Color {
    val r = accent.red
    val g = accent.green
    val b = accent.blue
    val high = maxOf(r, g, b)
    val low = minOf(r, g, b)
    val lightness = (high + low) / 2f
    val delta = high - low
    val saturation = if (delta == 0f) {
        0f
    } else {
        delta / (1f - abs(2f * lightness - 1f)).coerceAtLeast(1e-4f)
    }
    val crowding = crowding(hueOf(r, g, b, high, delta))
    return Color.hsl(
        hue = RecordHue,
        // Floors, not just a scale: a muted or near-grey accent would otherwise derive a grey
        // "red", and a very pale or very dark one a red nobody would call a record button.
        saturation = saturation.coerceIn(MinSaturation, 1f),
        lightness = (lightness * RecordLightness * (1f - crowding * CrowdedDarkening))
            .coerceIn(
                // A crowded red is allowed deeper than the usual floor: that depth is the whole
                // difference between it and the orange sitting next to it.
                MinLightness - crowding * (MinLightness - CrowdedMinLightness),
                MaxLightness,
            ),
    )
}

/** Standard HSL hue in degrees, from components already to hand. */
private fun hueOf(r: Float, g: Float, b: Float, high: Float, delta: Float): Float {
    if (delta == 0f) return 0f
    val h = when (high) {
        r -> ((g - b) / delta) % 6f
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    return (h * 60f + 360f) % 360f
}

/**
 * How much an accent at [hue] crowds the record colour: 0 for anything a comfortable distance away,
 * rising to 1 as it arrives on top of it. An amber accent at 42° is clear of this; the icon's
 * orange at 21° is halfway into it.
 */
private fun crowding(hue: Float): Float {
    val gap = abs(hue - RecordHue).let { minOf(it, 360f - it) }
    return ((CrowdingWindow - gap) / CrowdingWindow).coerceIn(0f, 1f)
}

/** Far enough round to read as red, not so far as to look pink. */
private const val RecordHue = 5f

/**
 * Red at the same nominal lightness as the accent comes out looking washed rather than urgent, so
 * it is taken down a little. Saturation is inherited whole: a record button is the one control
 * that should not look restrained. Against the dark theme's amber this gives #F14B3C — that accent
 * is far enough round to be left alone — and against the light theme's orange a deep #95210F.
 */
private const val RecordLightness = 0.94f
private const val MinSaturation = 0.60f
private const val MinLightness = 0.38f
private const val MaxLightness = 0.66f

/** How near an accent has to come, in degrees, before the record colour starts moving away. */
private const val CrowdingWindow = 30f

/** How much deeper a fully crowded accent drives it, and how far the floor gives way. */
private const val CrowdedDarkening = 0.35f
private const val CrowdedMinLightness = 0.26f

private val SparkDarkColors = darkColorScheme(
    primary = BrandOnDark,
    onPrimary = OnBrandDark,
    secondaryContainer = BrandOnDark,
    onSecondaryContainer = OnBrandDark,
)

private val SparkLightColors = lightColorScheme(
    primary = BrandOnLight,
    onPrimary = OnBrandLight,
    secondaryContainer = BrandOnLight,
    onSecondaryContainer = OnBrandLight,
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
fun SparkPlugTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark(mode)) SparkDarkColors else SparkLightColors,
        content = content,
    )
}

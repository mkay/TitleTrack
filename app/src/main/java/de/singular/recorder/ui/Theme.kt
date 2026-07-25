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

/** The app's accent, taken from the spark in the icon. */
private val BrandPrimary = Color(0xFFF2C14E)

/**
 * Content sitting *on* the accent. Near-black, not white: the accent is a bright amber, so white
 * over it lands nowhere near the 4.5:1 needed to read, while this clears it comfortably. The same
 * colour serves both themes, since the accent itself does not change.
 */
private val OnBrand = Color(0xFF1B1C22)

/**
 * Recording, everywhere it appears — the button, the elapsed time, the beat flash.
 *
 * Deliberately not the accent: while a take is running, "is it recording?" is the only question the
 * screen has to answer from across a room, and it should never be answered in the same colour as an
 * ordinary control.
 *
 * Derived from the accent rather than fixed, so the two stay a matched pair while the accent is
 * still being chosen: the accent's own saturation and lightness, moved to red. That is what makes
 * them look like they come from the same palette instead of one being stapled on. Note that the
 * accent's *hue* is discarded by design — this has to stay red wherever the accent goes — so two
 * accents of equal saturation and lightness derive the same red however far apart they look.
 *
 * The one accent this cannot serve is a red or orange one — then this lands on top of it and the
 * distinction the colour exists to make is gone. Pick the accent elsewhere on the wheel, or go
 * back to a fixed red here.
 */
val ColorScheme.record: Color get() = recordFrom(primary)

/** Red at [accent]'s lightness, and a little below its saturation. */
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
    return Color.hsl(
        hue = RecordHue,
        // Floors, not just a scale: a muted or near-grey accent would otherwise derive a grey
        // "red", and a very pale or very dark one a red nobody would call a record button.
        saturation = saturation.coerceIn(MinSaturation, 1f),
        lightness = (lightness * RecordLightness).coerceIn(MinLightness, MaxLightness),
    )
}

/** Far enough round to read as red, not so far as to look pink. */
private const val RecordHue = 5f

/**
 * Red at the same nominal lightness as the accent comes out looking washed rather than urgent, so
 * it is taken down a little. Saturation is inherited whole: a record button is the one control
 * that should not look restrained. Against the current amber this gives #F14B3C.
 */
private const val RecordLightness = 0.94f
private const val MinSaturation = 0.60f
private const val MinLightness = 0.38f
private const val MaxLightness = 0.66f

private val SparkDarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = OnBrand,
    secondaryContainer = BrandPrimary,
    onSecondaryContainer = OnBrand,
)

private val SparkLightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = OnBrand,
    secondaryContainer = BrandPrimary,
    onSecondaryContainer = OnBrand,
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

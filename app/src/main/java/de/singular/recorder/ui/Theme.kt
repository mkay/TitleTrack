package de.singular.recorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.singular.recorder.ThemeMode

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
 */
val RecordRed = Color(0xFFE5675C)

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

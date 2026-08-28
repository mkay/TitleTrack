// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.singular.recorder.R
import de.singular.recorder.audio.TunerState

/**
 * The tuner, as bare numbers.
 *
 * Scaffolding, and meant to be read as such: it exists so that what [de.singular.recorder.audio
 * .Pitch] and [de.singular.recorder.audio.Strobe] say can be checked against a known reference on a
 * real phone, in a real room, before either display is drawn on top of them. The needle and the
 * strobe replace this wholesale.
 */
@Composable
fun TunerScreen(
    state: TunerState,
    /** A take has the microphone. The tuner never takes it back — see [TunerState.micUnavailable]. */
    takeInProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val reading = state.reading
        when {
            takeInProgress || state.micUnavailable -> Text(
                text = stringResource(R.string.tuner_mic_busy),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )

            reading == null -> Text(
                text = stringResource(R.string.tuner_waiting),
                style = MaterialTheme.typography.titleMedium,
            )

            else -> {
                // The letter alone. Which octave it is in is not a thing anyone tunes by, and the
                // octave still does its work out of sight — it is what the strobe is pointed at.
                Text(
                    text = reading.note.name,
                    style = MaterialTheme.typography.displayLarge,
                    color = if (reading.inTune) {
                        MaterialTheme.colorScheme.brandFill
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = stringResource(R.string.tuner_cents, "%+.1f".format(reading.cents)),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(
                        R.string.tuner_hz,
                        "%.2f".format(reading.hz),
                        "%.2f".format(reading.note.hz),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // Units and numbers only, no prose: this line is a probe for checking the strobe against a
        // reference, and it goes away with the rest of the scaffolding.
        state.rings?.let { rings ->
            Text(
                text = "%.2f / %.2f rad · %+.3f Hz · %.0f%%".format(
                    rings.fundamental,
                    rings.octave,
                    rings.turnsPerSecond,
                    rings.strength * 100,
                ),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

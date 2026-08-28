// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.singular.recorder.R
import de.singular.recorder.TunerDisplay
import de.singular.recorder.audio.TunerState
import de.singular.recorder.audio.Tuning

/**
 * The tuner: one of two faces, and a line of text when there is nothing to show on either.
 *
 * **Both faces read the same two measurements**, and which is on show changes nothing about what is
 * measured — see [de.singular.recorder.audio.TunerEngine]. The choice sits on this screen rather
 * than in Settings because the moment you want to change it is while a string is still ringing and
 * you are deciding which of the two you can actually read.
 *
 * A note on comparing this against a pedal tuner, since it will come up: this reports **equal
 * temperament**, where a note's frequency is exactly what the arithmetic says. Several well-regarded
 * tuners default to *sweetened* presets, which deliberately stretch a guitar away from that — flat
 * at the bottom, sharp at the top — because it sounds better in first position. A guitar tuned on
 * one of those reads several cents out here, most visibly on the low E, and neither tuner is wrong.
 * There is no sweetening here and no setting for it.
 */
/** Kept clear for the one line of guidance, so that the face never moves when it appears. */
private val WaitingLineHeight = 24.dp

/** How far the screen fades while the tuner is not listening: legible, plainly not live. */
private const val SilencedAlpha = 0.25f

@Composable
fun TunerScreen(
    state: TunerState,
    display: TunerDisplay,
    onDisplayChange: (TunerDisplay) -> Unit,
    /** A take has the microphone. The tuner never takes it back — see [TunerState.micUnavailable]. */
    takeInProgress: Boolean,
    /** A take is playing. The tuner would only hear it, so it stays off until playback stops. */
    playbackInProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    val silenced = takeInProgress || playbackInProgress || state.micUnavailable

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                // Faded rather than replaced: the face stays where it was, so it is obvious that
                // the tuner is the thing waiting rather than a screen that has gone somewhere else.
                .alpha(if (silenced) SilencedAlpha else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The face fills everything between the top of the screen and the switch, the same way
            // the record screen gives the whole middle to its waveform. The instrument inside it
            // keeps its own size — see [TunerFacePanel].
            TunerFacePanel(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            ) {
                if (display == TunerDisplay.NEEDLE) {
                    NeedleTuner(reading = state.reading, modifier = Modifier.fillMaxSize())
                } else {
                    StrobeTuner(
                        rings = state.rings,
                        reading = state.reading,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Only ever one thing to say, and only while there is nothing on the face to read: with
            // a note showing, the face is the answer and a line of prose beneath it is noise. The
            // room it needs is held whether or not it is speaking, so nothing moves when it appears.
            Box(Modifier.height(WaitingLineHeight), contentAlignment = Alignment.Center) {
                if (!silenced && state.reading == null) {
                    Text(
                        text = stringResource(R.string.tuner_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // The record screen's row of cells, and deliberately the same object rather than a
            // lookalike — a label read at a glance over the value it currently has. Two of the
            // three only report for now; laid out this way they are already the right shape for the
            // day the reference pitch or the temperament becomes something to choose.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingCell(
                    label = stringResource(R.string.cell_display),
                    value = stringResource(
                        when (display) {
                            TunerDisplay.NEEDLE -> R.string.tuner_needle
                            TunerDisplay.STROBE -> R.string.tuner_strobe
                        },
                    ),
                    // A boolean does not need a menu to choose between its two values, as on the
                    // record screen's metronome cell. Dead while the screen is faded, so that the
                    // one control here obeys the notice over it.
                    onClick = if (silenced) {
                        null
                    } else {
                        {
                            onDisplayChange(
                                if (display == TunerDisplay.NEEDLE) TunerDisplay.STROBE
                                else TunerDisplay.NEEDLE,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                CellDivider()
                SettingCell(
                    label = stringResource(R.string.cell_reference),
                    value = stringResource(
                        R.string.cell_reference_value,
                        "%.0f".format(Tuning.A4_HZ),
                    ),
                    dimValue = true,
                    modifier = Modifier.weight(1f),
                )
                CellDivider()
                SettingCell(
                    label = stringResource(R.string.cell_temperament),
                    value = stringResource(R.string.cell_temperament_value),
                    dimValue = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (silenced) {
            // Swallows everything aimed at the faded screen, and carries the one line saying why.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when {
                            takeInProgress -> R.string.tuner_busy_recording
                            playbackInProgress -> R.string.tuner_busy_playback
                            else -> R.string.tuner_mic_busy
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(ControlShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

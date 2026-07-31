// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.singular.recorder.BuildConfig
import de.singular.recorder.R
import kotlinx.coroutines.launch

// Shared with the Support dialog next door, which offers the same two places to go with more
// words around them — see [SupportDialog].
const val REPO_URL = "https://github.com/mkay/TitleTrack"
private const val ISSUES_URL = "$REPO_URL/issues"
const val KOFI_URL = "https://ko-fi.com/s1ngular"

/**
 * About: what the app is, where it lives, and how to report a bug or chip in.
 *
 * A tab inside Settings rather than an item in the drawer — the drawer's bottom slot belongs to
 * Quick help, which is what you want mid-take; this is the page you visit once out of curiosity and
 * once when filing an issue. It was a screen of its own until the settings grew tabs and it became a
 * destination buried in one of them; see [SettingsTab] for why a tab is where it ended up.
 *
 * What used to be here was a description of the recording chain — sample rate, microphone source,
 * what happens when the app leaves the foreground. That is documentation, and it belongs in the
 * README where it can be read *before* installing rather than after. This page answers the two
 * questions someone actually opens it with: which build am I running, and where do I take a bug.
 *
 * Laid out as the sibling apps do it, down to the section headings, so all three read as one hand.
 * It brings neither a Scaffold nor a way back out: the app's own bar is above it and already says
 * Settings, and a tab is switched away from rather than left.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    // The clipboard is written from a coroutine: setting a clip suspends, since on some devices it
    // crosses to another process. The press itself is not made to wait on it.
    val scope = rememberCoroutineScope()
    // The versionCode rides along in the copied string: it is what pins a bug report to an exact
    // build when a version has been re-released under the same name.
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        // On its own ground, as a launcher composites it. The foreground is open artwork on
        // transparency and its three ambers are pitched at the near-black tile behind them — drawn
        // straight onto this page they read 1.2:1 to 1.7:1 and the mark washes out. The tile comes
        // from the same colour resource the adaptive icon uses, so there is one near-black.
        Box(
            Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(IconCorner))
                .background(colorResource(R.color.ic_launcher_background)),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(IconFill),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        // Long-press to copy, mirroring the library's press-and-hold idiom. Android 13 and up pops
        // its own clipboard confirmation, so only older versions get a toast.
        val appName = stringResource(R.string.app_name)
        val copiedNotice = stringResource(R.string.about_version_copied)
        Text(
            stringResource(R.string.about_version, version),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(ControlShape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val copied = "$appName $version"
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copied, copied)))
                        }
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, copiedNotice, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        Spacer(Modifier.height(28.dp))
        AboutSection(R.string.about_section_about) {
            AboutBody(R.string.about_body)
        }
        AboutSection(R.string.about_section_website) {
            AboutBody(R.string.about_website_body)
            AboutLink(REPO_URL) { uriHandler.openUri(REPO_URL) }
        }
        AboutSection(R.string.about_section_bugs) {
            AboutBody(R.string.about_bugs_body)
            AboutLink(ISSUES_URL) { uriHandler.openUri(ISSUES_URL) }
        }
        AboutSection(R.string.about_section_support) {
            AboutBody(R.string.about_support_body)
            AboutLink(KOFI_URL) { uriHandler.openUri(KOFI_URL) }
        }
        // GPL §5 requires a derivative to preserve legal notices, so a licence stated *in the app*
        // rather than only in the repo is worth more than it looks: a clone that stripped this
        // screen has done so deliberately, and the before-and-after is a screenshot.
        AboutSection(R.string.about_section_license) {
            AboutBody(R.string.about_copyright)
            AboutBody(R.string.about_license)
            AboutLink(REPO_URL) { uriHandler.openUri(REPO_URL) }
        }
    }
}

@Composable
private fun AboutSection(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun AboutBody(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
}

/** The tile's corner: a quarter of its side, which is about what a launcher's own mask cuts. */
private val IconCorner = 28.dp

/**
 * The foreground, enlarged to fill the tile it is drawn on.
 *
 * An adaptive icon's foreground keeps its artwork inside a safe zone — the outer third is margin a
 * launcher's mask may cut into — which puts the mark at 54% of its own canvas. That is a constraint
 * of the launcher and not of this page, where the tile is shown whole and nothing is cut, so the
 * margin is only padding and the mark reads as lost in its own square. 1.33 spends it, landing the
 * mark at about 72%: near what a round mask leaves on a home screen, which is where the icon is
 * usually seen.
 */
private const val IconFill = 1.33f

/** A tappable URL. Kept full-length rather than hidden behind link text so it stays readable. */
@Composable
private fun AboutLink(url: String, onClick: () -> Unit) {
    Text(
        url,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(ControlShape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The "easy way" out of [SupportDialog]: a page carrying a like button the visitor presses. The
 * count deliberately does not come from the visit itself — a Do-Not-Track browser suppresses a
 * plain pixel, so a silent hit counter would have quietly under-counted exactly the privacy-minded
 * audience this app has. A button press is an explicit act the browser has no reason to withhold,
 * and it is also the honest thing to ask for: nothing is recorded unless the visitor means it.
 *
 * The query string names the app, so one page serves all of them.
 */
private const val LIKE_URL = "https://singular.de/apps/feedback/?titletrack"

/**
 * The three ways to signal that the app is worth keeping up, in ascending order of effort.
 *
 * Every one of them is a link out, which is the point: an app that says it doesn't track anything
 * shouldn't then open a socket to say so. The browser does the fetching in its own process, so the
 * manifest stays free of INTERNET and the no-tracking claim remains checkable from the manifest
 * alone — see [openCustomTab]. The price is that we never learn what happened in the tab, so
 * nothing here reports success; each page has to confirm for itself. That suits the easy way in
 * particular, where the act being counted is a button press on the page — see [LIKE_URL].
 */
@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.surface

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep this app alive") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    "This app doesn't track. Anything.\n" +
                        "The downside is I have no idea if or how often it's used or downloaded.\n" +
                        "Could be zero, could be a secret cult following. I genuinely don't know.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Am I wasting my time publishing it? You tell me.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "There are three (maybe more) ways to keep me motivated:",
                    style = MaterialTheme.typography.bodyMedium,
                )

                SupportWay(
                    title = "The easy way",
                    action = "Give it a like",
                    icon = Icons.Default.Favorite,
                    // The one that costs nothing, so it gets the filled button.
                    emphasised = true,
                    note = "Opens a page on my website with a like button. Nothing is counted " +
                        "until you press it — just the press and an anonymised IP.",
                    onClick = { openCustomTab(context, LIKE_URL, toolbarColor) },
                )
                SupportWay(
                    title = "The nerdy way",
                    action = "Star it on GitHub",
                    icon = Icons.Default.Star,
                    note = "A star is a number I can actually see, and it helps others find it.",
                    onClick = { openCustomTab(context, REPO_URL, toolbarColor) },
                )
                SupportWay(
                    title = "The generous way",
                    action = "Support it on Ko-fi",
                    icon = Icons.Default.Paid,
                    note = "Entirely optional. The app is and stays free either way.",
                    onClick = { openCustomTab(context, KOFI_URL, toolbarColor) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** One row of [SupportDialog]: a heading, the button that does the thing, and the small print. */
@Composable
private fun SupportWay(
    title: String,
    action: String,
    icon: ImageVector,
    note: String,
    onClick: () -> Unit,
    emphasised: Boolean = false,
) {
    // The icon and label are the same either way; only the button's weight differs, so the
    // content is built once rather than duplicated into both branches.
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(action)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (emphasised) {
            Button(onClick = onClick, shape = ControlShape, content = content)
        } else {
            OutlinedButton(onClick = onClick, shape = ControlShape, content = content)
        }
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Hands [url] to the user's browser as a Custom Tab: it renders over our task with the app's own
 * surface colour in its toolbar, so it reads as part of the app, but the fetch happens in the
 * browser's process under the browser's UID. That is why none of this needs INTERNET — the
 * permission is a per-UID kernel check on whoever opens the socket, and that is never us.
 *
 * A device with no browser at all is rare but possible (and the Custom Tab degrades to a plain
 * ACTION_VIEW there), so the failure is caught rather than left to crash the app.
 */
private fun openCustomTab(context: android.content.Context, url: String, toolbarColor: Color) {
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setDefaultColorSchemeParams(
            CustomTabColorSchemeParams.Builder()
                .setToolbarColor(toolbarColor.toArgb())
                .build(),
        )
        .build()
    try {
        intent.launchUrl(context, Uri.parse(url))
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
    }
}

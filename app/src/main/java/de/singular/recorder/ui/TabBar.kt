// SPDX-License-Identifier: GPL-3.0-only

package de.singular.recorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The tabs, at the height a two-tab app needs rather than the height the spec assumes.
 *
 * Material 3's `NavigationBar` is a fixed 80dp with a pill sliding behind the selected icon —
 * proportionate to five destinations, and a lot of screen to give up for two. This is the same
 * arrangement (icon over label, tinted when selected) in about 58dp. On the Record screen that
 * difference is the count-in digit's breathing room.
 *
 * Its ground is the mini player's, which stacks directly on top of it with nothing in between — see
 * [BottomBarElevation] for the shared value and for what was given up to share it. This took an
 * explicit step off the surface ramp until then (`surfaceContainerHighest`, 1.5:1 from the page on
 * dark and 1.14:1 on light) and refused tonal elevation for tinting toward the accent; the seam
 * between two bottom bars a shade apart was the worse of the two problems.
 */
@Composable
fun CompactTabBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), tonalElevation = BottomBarElevation) {
        Row(
            Modifier
                .fillMaxWidth()
                // Whatever the system reserves at the bottom — gesture handle or button bar —
                // stays clear; only the part above it is ours to shrink.
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(TabBarHeight)
                .selectableGroup(),
            content = content,
        )
    }
}

/** One destination. Selection is a colour, not a shape — there is no indicator to slide. */
@Composable
fun RowScope.CompactTab(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: @Composable () -> Unit,
) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color) { icon() }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private val TabBarHeight = 58.dp

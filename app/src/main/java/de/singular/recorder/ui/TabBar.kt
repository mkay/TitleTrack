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
 * It takes the top of the surface ramp outright rather than a tonal elevation. Elevation derives its
 * tint from `primary`, which on a page tinted onto the accent's own hue is the one colour the bar
 * must not drift toward — it would blend into the page instead of ending it. An explicit step keeps
 * the bar a deliberate shade apart: 1.33:1 from the page on the dark theme, 1.15:1 on the light one.
 */
@Composable
fun CompactTabBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
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

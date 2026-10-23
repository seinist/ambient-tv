package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.ambient.tv.ui.player.LocalVisibleBackgroundVideo
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.theme.menuFocusMotion

@Composable
fun PageBackground(modifier: Modifier = Modifier) {
    val visibleBackgroundVideo = LocalVisibleBackgroundVideo.current
    if (!visibleBackgroundVideo && !LocalHomeBackgroundVisible.current) AppBackground(modifier)
}

/** App-wide ambient backdrop. It adds depth to otherwise empty TV-sized surfaces without
 * competing with artwork, cards or text. OLED deliberately remains pure black. */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val raised = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val isOled = background == Color.Black

    Canvas(modifier = modifier) {
        drawRect(background)
        if (!isOled) {
            val longestEdge = maxOf(size.width, size.height)

            // A restrained tonal lift prevents the upper content area from reading as a flat
            // grey slab while preserving the dark-room character of the application.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        raised.copy(alpha = 0.24f),
                        surface.copy(alpha = 0.10f),
                        background.copy(alpha = 0.04f),
                        Color.Black.copy(alpha = 0.28f),
                    ),
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.13f),
                        accent.copy(alpha = 0.045f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.84f, size.height * 0.04f),
                    radius = longestEdge * 0.68f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        raised.copy(alpha = 0.20f),
                        surface.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.12f, size.height * 0.92f),
                    radius = longestEdge * 0.52f,
                ),
            )
            // Gentle edge falloff gives large panels depth and keeps the visual emphasis on
            // the content rather than on the background treatment itself.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.20f)),
                    center = Offset(size.width * 0.52f, size.height * 0.43f),
                    radius = longestEdge * 0.78f,
                ),
            )
        }
    }
}

/** Compact utility rail for nested screens. It makes Back and Settings discoverable on a TV
 * remote without consuming the hero/content area. */
@Composable
fun PageUtilityBar(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onDown: (() -> Unit)? = null,
    requestInitialFocus: Boolean = false,
    leftNavigatesBack: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val backFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    RegisterScrollListActions(backFocus)
    var restoreSettings by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(requestInitialFocus) {
        if (!requestInitialFocus) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (runCatching { backFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (restoreSettings) {
                repeat(3) {
                    withFrameNanos { }
                    if (runCatching { settingsFocus.requestFocus() }.getOrDefault(false)) {
                        restoreSettings = false
                        return@repeatOnLifecycle
                    }
                }
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = tokens.screenPadding, end = tokens.screenPadding, top = tokens.screenPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Same icon chips as every page header (Settings, Files, …), so Back looks alike everywhere.
        TvChip(
            stringResource(R.string.back),
            modifier = Modifier.focusRequester(backFocus).downAction(onDown)
                .then(if (leftNavigatesBack) Modifier.leftKeyAction(onBack) else Modifier),
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            iconOnly = true,
            onClick = onBack,
        )
        TvChip(
            stringResource(R.string.nav_settings),
            modifier = Modifier.focusRequester(settingsFocus).downAction(onDown),
            icon = Icons.Filled.Settings,
            iconOnly = true,
            onClick = { restoreSettings = true; onSettings() },
        )
    }
}

/** D-pad Left on a leftmost Back chip does what clicking it does (go back or open the menu). */
fun Modifier.leftKeyAction(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key != Key.DirectionLeft) false else {
        if (event.type == KeyEventType.KeyDown) action()
        true
    }
}

private fun Modifier.downAction(onDown: (() -> Unit)?): Modifier =
    if (onDown == null) this else onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionDown) false else {
            if (event.type == KeyEventType.KeyDown) onDown()
            true
        }
    }

/**
 * Top line of a page: the [title] block left, its [actions] right-aligned on the same line
 * (wrapping below the title on handheld). Header controls are [TvChip]s with an icon, tabs and
 * actions alike, so every page header shares one size and focus look. On a phone, pass
 * [TvChip]'s iconOnly so those actions stay icon buttons; television keeps the labels.
 */
@Composable
fun PageHeader(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    title: @Composable ColumnScope.() -> Unit,
) {
    val handheld = LocalHandheldUi.current
    val actionFocus = remember { FocusRequester() }
    RegisterScrollListActions(actionFocus)
    DeviceActionRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        // The title yields to the actions, so a long library or folder name ellipsizes instead of pushing them out.
        Column(if (handheld) Modifier else Modifier.weight(1f).padding(end = 18.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = title)
        // On handheld the actions never wrap among themselves: they sit beside the title or move as one block to the next line, right-aligned.
        if (handheld) Row(Modifier.weight(1f).focusRequester(actionFocus).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
        else DeviceActionRow(modifier = Modifier.focusRequester(actionFocus).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}


@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(4.dp, 22.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
        )
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

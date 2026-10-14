package com.ambient.tv.ui.common.projectm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.RightPanelSectionTitle
import com.ambient.tv.ui.common.RightSideLazyPanel
import com.ambient.tv.ui.common.TvChip

@Composable
fun ProjectMPresetPanel(
    browser: ProjectMBrowser,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    settingsView: Boolean = false,
    onOpenSettings: () -> Unit,
) {
    val hdrSupported = rememberProjectMHdrSupported("auto")
    val displayLines = LocalView.current.display?.mode?.let { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
    val presets = browser.presets()
    val currentPreset = browser.currentPath?.let(::ProjectMPreset)
    val navigation = com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current
    val requestInitialFocus = remember(settingsView) { navigation?.restoreKey == null }
    RightSideLazyPanel(
        title = stringResource(if (settingsView) R.string.projectm_display_settings else R.string.player_visualizer),
        subtitle = "projectM · MilkDrop",
        onBack = onBack,
        modifier = modifier,
        closeInsteadOfBack = false,
        requestHeaderFocus = requestInitialFocus || navigation?.restoreFailed == true,
        onOutsideTap = com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current?.let { nav -> { nav.close() } } ?: onBack,
    ) {
        if (!settingsView) {
            item("current") {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(
                            Brush.linearGradient(listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            )),
                            RoundedCornerShape(18.dp),
                        )
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.projectm_current_preset),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        currentPreset?.displayName ?: stringResource(R.string.player_visualizer),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    currentPreset?.let {
                        Text(it.category, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    TvChip(
                        text = stringResource(if (browser.locked) R.string.projectm_unlock else R.string.projectm_lock),
                        icon = if (browser.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        selected = browser.locked,
                        onClick = { browser.locked = !browser.locked },
                    )
                }
            }
            item("settings_toggle") {
                com.ambient.tv.ui.common.PlayerPanelEntry(
                    title = stringResource(R.string.projectm_display_settings),
                    value = "${browser.intervalSeconds} s · ${browser.transitionSeconds} s · ${projectMResolutionLabel(browser.resolutionKey)}",
                    icon = Icons.Filled.GraphicEq,
                    onClick = onOpenSettings,
                )
            }
        }
        if (settingsView) {
            item("interval") {
                ProjectMOptionRow(stringResource(R.string.settings_projectm_interval)) {
                    listOf(15, 30, 60, 120, 300).forEach { seconds ->
                        TvChip(
                            text = stringResource(R.string.settings_projectm_seconds, seconds),
                            selected = browser.intervalSeconds == seconds,
                            onClick = { browser.setInterval(seconds) },
                        )
                    }
                }
            }
            item("transition") {
                ProjectMOptionRow(stringResource(R.string.settings_projectm_transition)) {
                    listOf(0, 1, 2, 5).forEach { seconds ->
                        TvChip(
                            text = stringResource(R.string.settings_projectm_seconds, seconds),
                            selected = browser.transitionSeconds == seconds,
                            onClick = { browser.setTransition(seconds) },
                        )
                    }
                }
            }
            item("quality") {
                ProjectMOptionRow(stringResource(R.string.settings_projectm_quality)) {
                    listOf("economy", "balanced", "high").forEach { quality ->
                        TvChip(
                            text = when (quality) {
                                "economy" -> stringResource(R.string.settings_projectm_quality_economy)
                                "high" -> stringResource(R.string.settings_projectm_quality_high)
                                else -> stringResource(R.string.settings_projectm_quality_balanced)
                            },
                            selected = browser.qualityKey == quality,
                            onClick = { browser.setQuality(quality) },
                        )
                    }
                }
            }
            item("resolution") {
                ProjectMOptionRow(
                    stringResource(R.string.settings_projectm_resolution),
                    stringResource(if (hdrSupported) R.string.settings_projectm_resolution_hint
                        else R.string.settings_projectm_resolution_sdr_hint),
                ) {
                    ProjectMRenderResolution.options(displayLines).forEach { key ->
                        TvChip(
                            text = projectMResolutionLabel(key),
                            selected = ProjectMRenderResolution.displayedKey(browser.resolutionKey, displayLines) == key,
                            onClick = { browser.setResolution(key) },
                        )
                    }
                }
            }
            if (hdrSupported && ProjectMRenderResolution.hdr4kChoiceVisible(android.os.Build.VERSION.SDK_INT, displayLines)) {
                item("hdr4k") {
                    ProjectMOptionRow(
                        stringResource(R.string.settings_projectm_hdr_4k),
                        stringResource(R.string.settings_projectm_hdr_4k_hint),
                    ) {
                        TvChip(
                            text = stringResource(R.string.player_off),
                            selected = !browser.hdr4kAllowed,
                            onClick = { browser.hdr4kAllowed = false },
                        )
                        TvChip(
                            text = stringResource(R.string.player_on),
                            selected = browser.hdr4kAllowed,
                            onClick = { browser.hdr4kAllowed = true },
                        )
                    }
                }
            }
        }
        if (!settingsView) {
            item("presets_heading") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RightPanelSectionTitle(stringResource(R.string.settings_projectm_title))
                    Text(presets.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            item("filters") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvChip(
                        text = stringResource(R.string.settings_projectm_all_presets),
                        selected = browser.filter != "favorites",
                        onClick = { browser.applyFilter("all") },
                    )
                    TvChip(
                        text = stringResource(R.string.settings_projectm_favorites_only),
                        icon = Icons.Filled.FavoriteBorder,
                        selected = browser.filter == "favorites",
                        onClick = { browser.applyFilter("favorites") },
                    )
                }
            }
            if (presets.isEmpty()) {
                item("empty") {
                    Text(
                        stringResource(if (browser.filter == "favorites") R.string.projectm_no_favorites else R.string.projectm_no_presets),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }
            } else {
                items(presets, key = { it.assetPath }) { preset ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RightPanelChoice(
                            text = preset.displayName,
                            icon = if (preset.assetPath == browser.currentPath) Icons.Filled.GraphicEq else null,
                            subtitle = preset.category,
                            selected = preset.assetPath == browser.currentPath,
                            onClick = { browser.select(preset) },
                            modifier = Modifier.weight(1f),
                        )
                        PlayerIconButton(
                            icon = if (browser.isFavorite(preset.assetPath)) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            label = stringResource(if (browser.isFavorite(preset.assetPath)) R.string.projectm_remove_favorite else R.string.projectm_favorite),
                            onClick = { browser.toggleFavorite(preset.assetPath) },
                            selected = browser.isFavorite(preset.assetPath),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectMOptionRow(
    title: String,
    description: String? = null,
    chips: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RightPanelSectionTitle(title, description)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = { chips() },
        )
    }
}

@Composable
fun projectMResolutionLabel(key: String): String = when (key) {
    ProjectMRenderResolution.AUTO -> stringResource(R.string.settings_projectm_resolution_auto)
    "2160" -> "4K"
    else -> "${key}p"
}

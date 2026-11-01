package com.ambient.tv.ui.navigation

import androidx.compose.ui.focus.focusRequester

import androidx.compose.ui.focus.FocusRequester

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.SystemNameOverrides
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.OverlayLazyScrollbar
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.mobile.LocalHandheldUi

/** One editable menu entry: the systems the television sidebar and the handheld menu both list. */
internal data class MenuOrderItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val system: MediaSystem? = null,
    /** Configured but without a menu row right now (offline, no libraries, suppressed). */
    val hidden: Boolean = false,
)

/** Mirrors the icons the television sidebar gives each system. */
internal fun menuSystemIcon(system: MediaSystem): ImageVector = when (system) {
    MediaSystem.FILES -> Icons.Filled.Folder
    MediaSystem.TRAILERS -> Icons.Filled.Movie
    MediaSystem.YOUTUBE -> Icons.Filled.SmartDisplay
    MediaSystem.TELEGRAM -> Icons.Filled.Subscriptions
    MediaSystem.SPOTIFY -> Icons.Filled.Headphones
    MediaSystem.TVHEADEND, MediaSystem.IPTV -> Icons.Filled.Tv
    else -> Icons.Filled.VideoLibrary
}

/**
 * The menu systems in their stored order: every configured system, regardless of whether a server
 * is currently reachable, so hiding one does not drop it out of the editor.
 */
@Composable
internal fun menuOrderItems(state: SidebarUiState): List<MenuOrderItem> {
    return menuOrderSystems(state)
        .map { MenuOrderItem(it.key, systemName(it), menuSystemIcon(it), it) }
}

internal fun menuOrderSystems(state: SidebarUiState): List<MediaSystem> =
    buildList {
        if (state.dlnaServers.isNotEmpty()) add(MediaSystem.DLNA)
        if (state.filesEnabled) add(MediaSystem.FILES)
        if (state.plexEnabled) add(MediaSystem.PLEX)
        if (state.jellyfinEnabled) add(MediaSystem.JELLYFIN)
        if (state.embyEnabled) add(MediaSystem.EMBY)
        if (state.trailersConfigured) add(MediaSystem.TRAILERS)
        if (state.youtubeEnabled) add(MediaSystem.YOUTUBE)
        if (state.telegramEnabled) add(MediaSystem.TELEGRAM)
        if (state.spotifyEnabled && state.spotifyAuthorized) add(MediaSystem.SPOTIFY)
        if (state.tvheadendEnabled) add(MediaSystem.TVHEADEND)
        if (state.liveTvSources.any { it.source.system == "iptv" }) add(MediaSystem.IPTV)
    }
        .sortedBy { state.systemOrder.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index } }

/**
 * One list row per system: its place, its mark, an inline name field and up/down arrows.
 * Order and names are drafted here; Done applies, Cancel discards. Television and handheld
 * landscape put the editor beside the list; a portrait phone is too narrow for two columns and
 * stacks it underneath.
 */
@Composable
internal fun MenuOrderDialog(
    systems: List<MenuOrderItem>,
    onApplyOrder: (List<String>) -> Unit,
    onRename: (Map<String, String>, Set<String>) -> Unit,
    onDismiss: () -> Unit,
    dividersAfter: Set<String> = emptySet(),
    onToggleDivider: ((String) -> Unit)? = null,
    /** False for a system's entries (library/sub-page names come from the source). */
    renamable: Boolean = true,
    title: String? = null,
) {
    // Each field starts with the name the menu shows; one left empty or set back to the
    // provider's name stores no override.
    val providers = systems.associate { item ->
        item.id to (item.system?.let { it.brand ?: stringResource(it.label) } ?: item.label)
    }
    val names = remember { mutableStateMapOf<String, String>().apply { systems.forEach { put(it.id, it.label) } } }
    var draftOrder by remember { mutableStateOf(systems.map { it.id }) }
    val draftItems = remember(draftOrder, systems) {
        draftOrder.mapNotNull { id -> systems.find { it.id == id } }
    }
    fun moveDraft(id: String, delta: Int) {
        val from = draftOrder.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, draftOrder.lastIndex)
        if (to == from) return
        draftOrder = draftOrder.toMutableList().also {
            it.removeAt(from)
            it.add(to, id)
        }
        // Saved on change (like settings): no separate save step.
        onApplyOrder(draftOrder)
    }
    // Names are saved while typing, like every other setting (no separate save step).
    val saveNames = {
        onRename(names.filter { (id, name) -> name.trim() != providers[id] }, systems.map { it.id }.toSet())
    }
    val applyAndClose = {
        onApplyOrder(draftOrder)
        onRename(names.filter { (id, name) -> name.trim() != providers[id] }, systems.map { it.id }.toSet())
        onDismiss()
    }
    // Closing keeps what was changed; names are applied when the editor closes.
    val cancel = applyAndClose
    var selectedId by remember { mutableStateOf(systems.firstOrNull()?.id) }
    val selected = draftItems.firstOrNull { it.id == selectedId } ?: draftItems.firstOrNull()
    val selectedIndex = selected?.let { draftItems.indexOf(it) } ?: -1
    val listState = rememberLazyListState()
    val hiddenNow = stringResource(R.string.sidebar_hidden_now)
    val scrollbarContentFocus = remember { FocusRequester() }
    val stacked = com.ambient.tv.ui.mobile.isNarrowHandheldWindow()
    AppDialog(
        onDismiss = cancel,
        title = title ?: stringResource(R.string.sidebar_manage_menu),
        hint = stringResource(if (renamable) R.string.sidebar_order_hint else R.string.sidebar_entry_order_hint),
        fillHeight = true,
        scrollable = false,
        widthFraction = if (LocalHandheldUi.current) null else 0.78f,
        maxWidth = if (LocalHandheldUi.current) Dp.Unspecified else 1080.dp,
        // The editor always has three controls (name, up, down), even when the system list is
        // short. Keep enough vertical room for all of them.
        maxHeightFraction = 0.9f,
        footer = {
            TvActionButton(text = stringResource(R.string.settings_home_genre_done), onClick = applyAndClose)
        },
    ) {
        // Only the list is virtualized. The old dialog rendered an input and two move
        // buttons for every system, which became a long, difficult TV form.
        val list: @Composable (Modifier) -> Unit = { listModifier ->
            Box(listModifier) {
                LazyColumn(
                    modifier = Modifier.focusRequester(scrollbarContentFocus).focusGroup().fillMaxSize().padding(end = 14.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(draftItems, key = { it.id }) { item ->
                        val position = draftItems.indexOf(item)
                        TvActionButton(
                            text = "${position + 1}. ${names[item.id].orEmpty().ifBlank { item.label }}" +
                                if (item.hidden) " · $hiddenNow" else "",
                            icon = item.icon,
                            selected = item.id == selected?.id,
                            fillWidth = true,
                            onClick = { selectedId = item.id },
                            modifier = Modifier.onFocusChanged { if (it.isFocused) selectedId = item.id },
                        )
                        if (item.id in dividersAfter && position < draftItems.lastIndex) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).height(1.dp)
                                .background(com.ambient.tv.ui.theme.AppOutline))
                        }
                    }
                }
                // OverlayLazyScrollbar stays hidden while the complete list fits and becomes
                // visible as soon as the system list needs scrolling.
                OverlayLazyScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), contentFocus = scrollbarContentFocus)
            }
        }
        val editor: @Composable (Modifier) -> Unit = { editorModifier ->
            Column(editorModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.sidebar_customize_selected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected != null) {
                    val mediaSystem = selected.system
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (mediaSystem != null) SystemLogo(mediaSystem, size = 30.dp)
                        else Icon(selected.icon, null, Modifier.size(30.dp))
                        Column {
                            Text(selected.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                stringResource(R.string.sidebar_customize_position, selectedIndex + 1),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (renamable) TvTextField(
                        value = names[selected.id].orEmpty(),
                        onValueChange = {
                            names[selected.id] = it.take(SystemNameOverrides.MAX_LENGTH)
                            saveNames()
                        },
                        label = stringResource(R.string.sidebar_customize_name),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    // Stacked (portrait) keeps the two arrows on one line so the list above
                    // still has room; beside the list they are full-width rows as on television.
                    val moveUp: @Composable (Modifier) -> Unit = { buttonModifier ->
                        TvActionButton(
                            text = stringResource(R.string.sidebar_move_up),
                            icon = Icons.Filled.ArrowUpward,
                            onClick = { moveDraft(selected.id, -1) },
                            modifier = buttonModifier,
                            enabled = selectedIndex > 0,
                            fillWidth = true,
                        )
                    }
                    val moveDown: @Composable (Modifier) -> Unit = { buttonModifier ->
                        TvActionButton(
                            text = stringResource(R.string.sidebar_move_down),
                            icon = Icons.Filled.ArrowDownward,
                            onClick = { moveDraft(selected.id, 1) },
                            modifier = buttonModifier,
                            enabled = selectedIndex >= 0 && selectedIndex < draftItems.lastIndex,
                            fillWidth = true,
                        )
                    }
                    if (stacked) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            moveUp(Modifier.weight(1f))
                            moveDown(Modifier.weight(1f))
                        }
                    } else {
                        moveUp(Modifier)
                        moveDown(Modifier)
                    }
                    if (onToggleDivider != null && selectedIndex in 0 until draftItems.lastIndex) {
                        val hasDivider = selected.id in dividersAfter
                        TvActionButton(
                            text = stringResource(if (hasDivider) R.string.sidebar_divider_remove else R.string.sidebar_divider_add),
                            icon = Icons.Filled.HorizontalRule,
                            onClick = { onToggleDivider(selected.id) },
                            fillWidth = true,
                        )
                    }
                }
            }
        }
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxSize().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The list keeps the leftover height so the editor below stays reachable.
                list(Modifier.fillMaxWidth().weight(1f))
                // With the keyboard open the editor may not fit next to the list: let it scroll.
                editor(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                list(Modifier.weight(1f).fillMaxHeight())
                editor(Modifier.widthIn(min = 250.dp, max = 320.dp).fillMaxHeight())
            }
        }
    }
}

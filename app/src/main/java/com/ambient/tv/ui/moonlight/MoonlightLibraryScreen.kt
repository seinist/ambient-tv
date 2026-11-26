package com.ambient.tv.ui.moonlight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.tv.material3.Surface
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.ambient.tv.R
import com.ambient.tv.moonlight.MoonlightHost
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.common.pageHeaderTop

/**
 * Moonlight's ordinary Browse page, shared by TV and phone. A cached catalog remains readable
 * offline, but the Play button in details is disabled until the host has responded over
 * authenticated HTTPS. Images are loaded only when visible/focused, never for the whole catalog.
 */
@Composable
fun MoonlightLibraryScreen(
    initialSection: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onDetails: (String, Int) -> Unit,
    onSectionSelected: ((String) -> Unit)? = null,
    model: MoonlightLibraryViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    val section = state.sections
    LaunchedEffect(initialSection) { model.section(initialSection) }
    MoonlightRefreshWhileVisible(model)
    ReportSidebarContentReady(state.ready)
    BackHandler(enabled = !searchVisible) { onBack() }
    BackHandler(enabled = searchVisible) { searchVisible = false; model.search("") }

    val games = remember(state.hosts, state.catalogs, state.favorites, state.recent, section, state.query) {
        state.games
    }
    // Also retry initial prefetch after the host becomes authenticated and available.
    LaunchedEffect(section, games.take(8).map { it.key }, state.onlineHosts) {
        games.take(8).forEach { model.prioritizeArtwork(it, visible = false) }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(),
            top = pageHeaderTop(screenGutter()), bottom = screenGutter()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.moonlight_library_title),
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TvActionButton(stringResource(R.string.action_search), { searchVisible = !searchVisible })
            TvActionButton(stringResource(R.string.moonlight_refresh), { model.load(force = true) }, loading = state.loadingHosts.isNotEmpty())
            TvActionButton(stringResource(R.string.moonlight_view_toggle), {
                model.view(if (state.view == "grid") "list" else "grid")
            })
            TvActionButton(stringResource(R.string.moonlight_open_settings), onOpenSettings)
        }
        if (searchVisible) {
            TvTextField(state.query, model::search, stringResource(R.string.action_search),
                modifier = Modifier.fillMaxWidth())
        }
        com.ambient.tv.ui.common.SettingsActionRow {
            val sections = listOf(
                "all" to stringResource(R.string.game_library_all),
                "favorites" to stringResource(R.string.game_library_favorites),
                "recent" to stringResource(R.string.game_library_recent),
            ) + state.hosts.map { host ->
                "host:" + host.id to host.name.ifBlank { host.host }
            }
            sections.forEach { (key, label) ->
                TvActionButton(label, { if (onSectionSelected != null) onSectionSelected(key) else model.section(key) }, selected = section == key)
            }
        }
        if (state.errors.values.any { it == "identity" }) {
            Text(stringResource(R.string.moonlight_identity_changed),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.hosts.isEmpty()) {
            Text(stringResource(R.string.moonlight_library_unconfigured),
                style = MaterialTheme.typography.bodyMedium)
            TvActionButton(stringResource(R.string.moonlight_open_settings), onOpenSettings)
        } else if (games.isEmpty()) {
            Text(stringResource(if (state.loadingHosts.isNotEmpty()) R.string.player_loading
                else R.string.moonlight_library_empty), style = MaterialTheme.typography.bodyMedium)
        } else if (state.view == "list") {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lazyItems(games, key = { it.key }) { game ->
                    MoonlightLibraryTile(game, model, true, state.artworkVersion) { onDetails(game.host.id, game.app.id) }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(184.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                gridItems(games, key = { it.key }) { game ->
                    MoonlightLibraryTile(game, model, false, state.artworkVersion) { onDetails(game.host.id, game.app.id) }
                }
            }
        }
    }
}

@Composable
private fun MoonlightLibraryTile(
    game: MoonlightBrowseGame,
    model: MoonlightLibraryViewModel,
    horizontal: Boolean,
    artworkVersion: Long,
    onOpen: () -> Unit,
) {
    val cached by produceState<java.io.File?>(null, game.key, artworkVersion) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { model.artwork(game) }
    }
    val image: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (cached != null) {
                AsyncImage(
                    model = cached,
                    contentDescription = game.app.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Filled.SportsEsports, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val title: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(game.app.title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(game.host.name.ifBlank { game.host.host } +
                if (game.favorite) "  ♥" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) model.prioritizeArtwork(game) },
    ) {
        if (horizontal) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(0.8f)) { image() }
                Box(Modifier.weight(1.2f)) { title() }
            }
        } else {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                image()
                title()
            }
        }
    }
}

/** Refresh only while this navigation entry is actually resumed; cached data stays visible. */
@Composable
internal fun MoonlightRefreshWhileVisible(model: MoonlightLibraryViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.enter()
            try {
                kotlinx.coroutines.coroutineScope {
                    launch { model.observeRecovery() }
                    while (isActive) {
                        delay(30_000)
                        model.load()
                    }
                }
            } finally { model.leave() }
        }
    }
}

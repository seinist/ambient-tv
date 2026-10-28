package com.ambient.tv.ui.mobile

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.companion.handheldPageOpen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.ambient.tv.R
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.SidebarViewModel
import com.ambient.tv.ui.navigation.sidebarShowsGlobalSearch
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackArtworkCard
import com.ambient.tv.ui.player.miniFormatDuration

/** No TV layout wrapper: the existing television composition is invoked verbatim. */
@Composable
fun HandheldNavigationHost(
    currentRoute: String,
    showNavigation: Boolean,
    playerVisible: Boolean,
    onNavigate: (String) -> Unit,
    onOpenPlayback: (String) -> Unit,
    onOpenSystemSettings: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (!LocalHandheldUi.current) { content(); return }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var manageMenu by rememberSaveable { mutableStateOf(false) }
    val sidebarVm: SidebarViewModel = hiltViewModel()
    val sidebarState by sidebarVm.uiState.collectAsState()
    val hasIptv = sidebarState.liveTvSources.any { it.source.system == "iptv" }
    val showHome = sidebarState.trailersConfigured || com.ambient.tv.ui.navigation.sidebarShowsHome(
        plexConfigured = sidebarState.plexEnabled,
        jellyfinConfigured = sidebarState.jellyfinEnabled,
        embyConfigured = sidebarState.embyEnabled,
        tvheadendConfigured = sidebarState.tvheadendEnabled && !sidebarState.tvheadendLoginRejected,
        youtubeEnabled = sidebarState.youtubeEnabled,
        spotifyEnabled = sidebarState.spotifyEnabled && sidebarState.spotifyAuthorized,
    )
    val showSearch = sidebarShowsGlobalSearch(
        plexConfigured = sidebarState.plexEnabled,
        jellyfinConfigured = sidebarState.jellyfinEnabled,
        embyConfigured = sidebarState.embyEnabled,
        tvheadendConfigured = sidebarState.tvheadendEnabled && !sidebarState.tvheadendLoginRejected || hasIptv,
        youtubeEnabled = sidebarState.youtubeEnabled,
        spotifyCanSearch = sidebarState.spotifyEnabled && sidebarState.spotifyAuthorized && sidebarState.spotifyNativeAvailable,
    )
    // Rotation switches between the Row and Column branches below. Moving the content instead of
    // recreating it keeps the NavHost (and a running trailer, which stops on dispose) alive.
    val companion: com.ambient.tv.companion.CompanionViewModel = hiltViewModel()
    // Phone companion pages (pairing, remote, "Handy & TV") cover the content area, in this priority.
    val companionPage by remember(companion) {
        companion.manager.state.map { when { it.pairingFlow -> 1; it.remotePanel -> 2; it.handheldPageOpen() -> 3; else -> 0 } }.distinctUntilChanged()
    }.collectAsState(0)
    val remoteOpen = companionPage == 2
    // Any other navigation target leaves a companion page; the remote tab itself keeps it open.
    val navigate: (String) -> Unit = { route -> if (companionPage != 0) companion.manager.closeHandheldPages(); onNavigate(route) }
    val openSources: () -> Unit = { if (companionPage != 0) companion.manager.closeHandheldPages(); showSources = true }
    val currentContent by rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { currentContent() } }
    val panelBounds = remember { com.ambient.tv.ui.common.HandheldPanelBounds() }
    val page: @Composable ColumnScope.() -> Unit = {
        Box(
            Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { c ->
                val top = c.positionInWindow().y.toInt()
                panelBounds.top = top
                panelBounds.bottom = top + c.size.height
            },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.ambient.tv.ui.common.LocalHandheldPanelBounds provides panelBounds,
            ) { movableContent() }
            // Drawn over the page instead of replacing it, so the NavHost and a running trailer survive.
            when (companionPage) {
                1 -> com.ambient.tv.companion.CompanionPairingScreen(companion.manager)
                2 -> com.ambient.tv.companion.CompanionRemoteScreen(companion.manager)
                3 -> com.ambient.tv.companion.CompanionDevicesScreen(companion.manager)
            }
        }
        // The remote page carries its own playback controls and target name.
        if (showNavigation && companionPage == 0) com.ambient.tv.companion.CompanionOutputBar()
        if ((showNavigation || !playerVisible) && !remoteOpen && companionPage != 1) com.ambient.tv.companion.CompanionRemotePlaybackBar()
        if (!playerVisible) HandheldMiniPlayer(onOpenPlayback)
    }
    // safeDrawing would pad the portrait status bar / cutout onto the short edge in
    // landscape and leave a dead column beside the player. handheldContentWindowInsets
    // keeps that edge clear on every handheld page; television never reaches this branch.
    if (isHandheldLandscape()) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(handheldContentWindowInsets()).imePadding()) {
            if (showNavigation) {
                HandheldNavigationRail(
                    currentRoute = currentRoute,
                    showHome = showHome,
                    showSearch = showSearch,
                    onNavigate = navigate,
                    onOpenSources = openSources,
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight(), content = page)
        }
    } else {
        Column(Modifier.fillMaxSize().windowInsetsPadding(handheldContentWindowInsets()).imePadding()) {
            page()
            if (showNavigation) {
                HandheldNavigationBar(
                    currentRoute = currentRoute,
                    showHome = showHome,
                    showSearch = showSearch,
                    onNavigate = navigate,
                    onOpenSources = openSources,
                )
            }
        }
    }
    if (showSources) HandheldSources(
        onDismiss = { showSources = false },
        onNavigate = { showSources = false; onNavigate(it) },
        onOpenSystemSettings = { showSources = false; onOpenSystemSettings(it) },
        onManageMenu = { showSources = false; manageMenu = true },
    )
    // Same editor as the television sidebar: the stored order also drives this navigation bar.
    // Shown from the host so the sheet is gone before the dialog opens.
    val menuItems = com.ambient.tv.ui.navigation.menuOrderItems(sidebarState)
    if (manageMenu && menuItems.isNotEmpty()) {
        com.ambient.tv.ui.navigation.MenuOrderDialog(
            systems = menuItems,
            onApplyOrder = sidebarVm::applySystemOrder,
            onRename = sidebarVm::renameSystems,
            onDismiss = { manageMenu = false },
        )
    }
}

@Composable
private fun HandheldNavigationBar(
    currentRoute: String,
    showHome: Boolean,
    showSearch: Boolean,
    onNavigate: (String) -> Unit,
    onOpenSources: () -> Unit,
) {
    val home = stringResource(R.string.nav_home)
    val sources = stringResource(R.string.mobile_sources)
    val search = stringResource(R.string.nav_search)
    val settings = stringResource(R.string.settings_title)
    val companion: com.ambient.tv.companion.CompanionViewModel = hiltViewModel()
    val companionState by companion.manager.state.collectAsState()
    val remote = stringResource(R.string.companion_remote_control)
    NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0), tonalElevation = 0.dp) {
        if (showHome) NavigationBarItem(!companionState.handheldPageOpen() && currentRoute == Destinations.HOME, { onNavigate(Destinations.HOME) },
            { Icon(Icons.Default.Home, home) }, label = { Text(home) })
        NavigationBarItem(!companionState.handheldPageOpen() && currentRoute !in setOf(Destinations.HOME, Destinations.SEARCH, Destinations.SETTINGS), onOpenSources,
            { Icon(Icons.Default.Apps, sources) }, label = { Text(sources) })
        if (showSearch) {
            NavigationBarItem(!companionState.handheldPageOpen() && currentRoute == Destinations.SEARCH, { onNavigate(Destinations.SEARCH) },
                { Icon(Icons.Default.Search, search) }, label = { Text(search) })
        }
        NavigationBarItem(companionState.handheldPageOpen(), companion.manager::showRemoteOrDevices,
            { Icon(Icons.Default.SettingsRemote, remote) }, label = { Text(remote, maxLines = 1) })
        NavigationBarItem(!companionState.handheldPageOpen() && currentRoute == Destinations.SETTINGS, { onNavigate(Destinations.SETTINGS) },
            { Icon(Icons.Default.Settings, settings) }, label = { Text(settings, maxLines = 1) })
    }
}

@Composable
private fun HandheldNavigationRail(
    currentRoute: String,
    showHome: Boolean,
    showSearch: Boolean,
    onNavigate: (String) -> Unit,
    onOpenSources: () -> Unit,
) {
    val home = stringResource(R.string.nav_home)
    val sources = stringResource(R.string.mobile_sources)
    val search = stringResource(R.string.nav_search)
    val settings = stringResource(R.string.settings_title)
    val companion: com.ambient.tv.companion.CompanionViewModel = hiltViewModel()
    val companionState by companion.manager.state.collectAsState()
    val remote = stringResource(R.string.companion_remote_control)
    // Parent padding no longer reserves the side status/cutout (that was the dead column).
    // Keep the inset inside the rail so its icons clear the notch while the rail itself
    // still reaches the screen edge.
    NavigationRail(
        windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) {
        Spacer(Modifier.weight(1f))
        if (showHome) NavigationRailItem(!companionState.handheldPageOpen() && currentRoute == Destinations.HOME, { onNavigate(Destinations.HOME) },
            { Icon(Icons.Default.Home, home) }, label = { Text(home, maxLines = 1) })
        NavigationRailItem(!companionState.handheldPageOpen() && currentRoute !in setOf(Destinations.HOME, Destinations.SEARCH, Destinations.SETTINGS), onOpenSources,
            { Icon(Icons.Default.Apps, sources) }, label = { Text(sources, maxLines = 1) })
        if (showSearch) {
            NavigationRailItem(!companionState.handheldPageOpen() && currentRoute == Destinations.SEARCH, { onNavigate(Destinations.SEARCH) },
                { Icon(Icons.Default.Search, search) }, label = { Text(search, maxLines = 1) })
        }
        NavigationRailItem(companionState.handheldPageOpen(), companion.manager::showRemoteOrDevices,
            { Icon(Icons.Default.SettingsRemote, remote) }, label = { Text(remote, maxLines = 1) })
        NavigationRailItem(!companionState.handheldPageOpen() && currentRoute == Destinations.SETTINGS, { onNavigate(Destinations.SETTINGS) },
            { Icon(Icons.Default.Settings, settings) }, label = { Text(settings, maxLines = 1) })
        Spacer(Modifier.weight(1f))
    }
}

private data class SourceEntry(
    val key: String,
    val title: String,
    val route: String,
    val settingsCategory: String? = null,
)
private data class SourceGroup(val system: MediaSystem, val entries: List<SourceEntry>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandheldSources(
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenSystemSettings: (String) -> Unit = {},
    onManageMenu: () -> Unit = {},
    viewModel: SidebarViewModel = hiltViewModel(),
) {
    val sidebarState by viewModel.uiState.collectAsState()
    // Unreachable servers stay listed, as in the television sidebar.
    val plex = sidebarState.plexEnabled
    val jellyfin = sidebarState.jellyfinEnabled
    val emby = sidebarState.embyEnabled
    val youtube = sidebarState.youtubeEnabled
    val spotify = sidebarState.spotifyEnabled && sidebarState.spotifyAuthorized
    val files = sidebarState.filesEnabled
    val networkFileSources = sidebarState.networkFileSources
    val dlnaServers = sidebarState.dlnaServers
    val tv = sidebarState.tvheadendEnabled
    val sections = sidebarState.sections
    val jellyfinSections = sidebarState.jellyfinSections
    val embySections = sidebarState.embySections
    val feeds = sidebarState.youtubeFeedKeys
    val spotifyKeys = sidebarState.spotifySubmenuKeys
    val spotifyAuthorized = sidebarState.spotifyAuthorized
    val spotifyNativeAvailable = sidebarState.spotifyNativeAvailable
    val tvKeys = sidebarState.tvheadendSubmenuKeys
    val settingsShortcutHidden = sidebarState.settingsShortcutHidden
    val order = sidebarState.systemOrder
    val canManageMenu = com.ambient.tv.ui.navigation.menuOrderItems(sidebarState).isNotEmpty()
    // Fetch menus only when opened. Content repositories retain their existing caches/async jobs.
    LaunchedEffect(Unit) { viewModel.refresh() }
    val overview = stringResource(R.string.nav_overview)
    val liveTvLabels = listOf("live" to stringResource(R.string.tvh_live), "guide" to stringResource(R.string.tvh_guide),
        "recordings" to stringResource(R.string.tvh_recordings), "timers" to stringResource(R.string.tvh_timers))
    fun tvEntries(system: String): List<SourceEntry> = sidebarState.liveTvSources.filter { it.source.system == system }.flatMap { source ->
        liveTvLabels.filter { (tab, _) -> when (tab) {
            "guide" -> source.capabilities.guide; "recordings" -> source.capabilities.recordings; "timers" -> source.capabilities.timers; else -> true
        } }.map { (tab, title) -> SourceEntry("${source.source.id}:$tab", "${source.source.name} · $title", Destinations.liveTvSection(tab, source.source.id)) }
    }
    @Composable
    fun withSettings(system: MediaSystem, entries: List<SourceEntry>): SourceGroup {
        val settings = if (system.key in settingsShortcutHidden) null else SourceEntry(
            key = "settings",
            title = stringResource(R.string.nav_system_settings, systemName(system)),
            route = Destinations.SETTINGS,
            settingsCategory = system.key,
        )
        return SourceGroup(system, entries + listOfNotNull(settings))
    }
    val groups = buildList {
        if (dlnaServers.isNotEmpty()) add(withSettings(MediaSystem.DLNA,
            dlnaServers.map { SourceEntry(it.id, it.name, Destinations.dlnaServer(it.id)) } +
                listOf(SourceEntry("manage", stringResource(R.string.dlna_servers), Destinations.DLNA))))
        if (files) add(withSettings(MediaSystem.FILES,
            networkFileSources.map { SourceEntry("source:${it.id}", it.name, Destinations.fileSource(it.id)) } + listOf(
                SourceEntry("browse", stringResource(R.string.files_browse), Destinations.FILES),
                SourceEntry("favorites", stringResource(R.string.files_favorites), Destinations.FILE_FAVORITES),
                SourceEntry("recent", stringResource(R.string.files_recent), Destinations.FILE_RECENT),
                SourceEntry("continue", stringResource(R.string.files_continue), Destinations.FILE_CONTINUE),
            )))
        if (plex) add(withSettings(MediaSystem.PLEX, listOf(SourceEntry("home", overview, Destinations.PLEX)) + sections.map { SourceEntry(it.key, it.title, Destinations.section(it.key)) } + tvEntries("plex")))
        if (jellyfin) add(withSettings(MediaSystem.JELLYFIN, listOf(SourceEntry("home", overview, Destinations.JELLYFIN)) + jellyfinSections.map { SourceEntry(it.key, it.title, Destinations.section(it.key)) } + tvEntries("jellyfin")))
        if (emby) add(withSettings(MediaSystem.EMBY, listOf(SourceEntry("home", overview, Destinations.EMBY)) + embySections.map { SourceEntry(it.key, it.title, Destinations.section(it.key)) } + tvEntries("emby")))
        if (sidebarState.liveTvSources.any { it.source.system == "iptv" }) {
            add(withSettings(MediaSystem.IPTV, tvEntries("iptv")))
        }
        if (sidebarState.trailersConfigured) add(withSettings(MediaSystem.TRAILERS,
            com.ambient.tv.trailers.TrailerFeed.entries.map { feed ->
                SourceEntry(
                    key = feed.name.lowercase(),
                    title = stringResource(feed.label),
                    route = if (feed == com.ambient.tv.trailers.TrailerFeed.CINEMA) Destinations.TRAILERS else Destinations.trailerFeed(feed.name),
                )
            }))
        if (youtube) add(withSettings(MediaSystem.YOUTUBE, feeds.ifEmpty { listOf("home") }.map { key ->
            SourceEntry(key, stringResource(when (key) {
                "subscriptions" -> R.string.nav_youtube_subscriptions
                "channels" -> R.string.nav_youtube_channels
                "history" -> R.string.nav_youtube_history
                "playlists" -> R.string.nav_youtube_playlists
                "shorts" -> R.string.nav_youtube_shorts
                "music" -> R.string.nav_youtube_music
                "news" -> R.string.nav_youtube_news
                "gaming" -> R.string.nav_youtube_gaming
                "live" -> R.string.nav_youtube_live
                "kids" -> R.string.nav_youtube_kids
                else -> R.string.nav_youtube_home
            }), Destinations.youtubeSection(key, sidebarState.youtubeStartFeedKey))
        }))
        // Match TV sidebar: Spotify appears only once signed in.
        if (spotify) {
            val entries = if (spotifyAuthorized && spotifyNativeAvailable) {
                spotifyKeys.mapNotNull { key ->
                    val label = when (key) {
                        "home" -> R.string.nav_spotify_home
                        "liked" -> R.string.nav_spotify_liked
                        "playlists" -> R.string.nav_spotify_playlists
                        "albums" -> R.string.nav_spotify_albums
                        "artists" -> R.string.nav_spotify_artists
                        "now_playing" -> R.string.nav_spotify_now_playing
                        else -> null
                    } ?: return@mapNotNull null
                    SourceEntry(key, stringResource(label), when (key) {
                        "home" -> Destinations.SPOTIFY
                        "now_playing" -> Destinations.spotifyPlayer()
                        else -> Destinations.spotifySection(key)
                    })
                }.ifEmpty { listOf(SourceEntry("home", overview, Destinations.SPOTIFY)) }
            } else {
                listOf(SourceEntry("unavailable", stringResource(R.string.spotify_native_missing), Destinations.SETTINGS,
                    settingsCategory = MediaSystem.SPOTIFY.key))
            }
            add(withSettings(MediaSystem.SPOTIFY, entries))
        }
        if (tv && sidebarState.tvheadendLoginRejected) add(withSettings(MediaSystem.TVHEADEND, listOf(SourceEntry("login",
            stringResource(R.string.tvh_login_check), Destinations.SETTINGS, settingsCategory = MediaSystem.TVHEADEND.key))))
        else if (tv) add(withSettings(MediaSystem.TVHEADEND, tvKeys.mapNotNull { key ->
            val label = when (key) { "live" -> R.string.tvh_live; "guide" -> R.string.tvh_guide; "recordings" -> R.string.tvh_recordings; "timers" -> R.string.tvh_timers; else -> null } ?: return@mapNotNull null
            SourceEntry(key, stringResource(label), if (key == "live") Destinations.LIVE_TV else Destinations.liveTvSection(key))
        }.ifEmpty { listOf(SourceEntry("live", stringResource(R.string.tvh_live), Destinations.LIVE_TV)) }))
    }.sortedBy { order.indexOf(it.system.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mobile_sources), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { group ->
                val open = groups.size == 1 || expanded == group.system.key
                item(group.system.key) {
                    ListItem(
                        headlineContent = { Text(systemName(group.system)) },
                        supportingContent = if (group.system == MediaSystem.TVHEADEND && sidebarState.tvheadendOffline) {
                            { Text(stringResource(R.string.tvh_offline_badge)) }
                        } else null,
                        leadingContent = { SystemLogo(group.system) },
                        trailingContent = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                        modifier = Modifier.clickable { expanded = if (open) null else group.system.key },
                    )
                }
                if (open) items(group.entries, key = { "${group.system.key}:${it.key}" }) { entry ->
                    ListItem(headlineContent = { Text(entry.title) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.padding(start = 32.dp).clickable {
                            if (entry.settingsCategory != null) onOpenSystemSettings(entry.settingsCategory)
                            else onNavigate(entry.route)
                        })
                }
            }
            if (canManageMenu) item("order") {
                ListItem(headlineContent = { Text(stringResource(R.string.sidebar_manage_menu)) }, leadingContent = { Icon(Icons.Default.SwapVert, null) },
                    modifier = Modifier.clickable(onClick = onManageMenu))
            }
            item("add") {
                ListItem(headlineContent = { Text(stringResource(R.string.sidebar_add_system)) }, leadingContent = { Icon(Icons.Default.Add, null) },
                    modifier = Modifier.clickable { onNavigate(Destinations.INITIAL_SETUP) })
            }
        }
    }
}

@Composable
private fun HandheldMiniPlayer(onOpen: (String) -> Unit) {
    val session by BackgroundPlaybackSession.snapshot.collectAsState()
    val controls: HandheldPlaybackViewModel = hiltViewModel()
    val spotify by controls.spotifyPlayback.collectAsState()
    val useSpotify = spotify.current != null && (spotify.playing || session.metadata == null)
    val track = spotify.current
    val metadata = if (useSpotify && track != null) com.ambient.tv.ui.player.PlaybackMetadata(
        route = Destinations.spotifyPlayer(), title = track.name,
        subtitle = track.artists.joinToString(", ") { it.name }, source = "Spotify", mediaId = track.id,
        hasVideo = false, artworkUrl = track.album?.images?.firstOrNull()?.url, durationMs = spotify.durationMs,
    ) else session.metadata
    metadata ?: return
    val playing = if (useSpotify) spotify.playing else session.playing
    val positionMs = if (useSpotify) spotify.positionMs else session.status.positionMs + metadata.timelineOffsetMs
    val durationMs = if (useSpotify) spotify.durationMs
        else metadata.durationMs.takeIf { it > 0L }
            ?: (session.status.durationMs + metadata.timelineOffsetMs).coerceAtLeast(0L)
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { onOpen(metadata.route) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaybackArtworkCard(metadata.artworkUrl, metadata.title, Modifier.size(44.dp),
                    fallbackUrls = metadata.artworkFallbackUrls, headers = metadata.artworkHeaders, contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f)) {
                    Text(metadata.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    metadata.subtitle.takeIf { it.isNotBlank() }?.let { subtitle ->
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        stringResource(R.string.companion_this_phone) + " · " + if (metadata.isLive) "LIVE"
                        else "${miniFormatDuration(positionMs)} / ${miniFormatDuration(durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { if (useSpotify) controls.toggleSpotify() else BackgroundPlaybackSession.toggle() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.player_pause else R.string.player_play)) }
            IconButton(onClick = { if (useSpotify) controls.stopSpotify() else BackgroundPlaybackSession.stop() }) { Icon(Icons.Default.Close, stringResource(R.string.player_stop)) }
        }
    }
}

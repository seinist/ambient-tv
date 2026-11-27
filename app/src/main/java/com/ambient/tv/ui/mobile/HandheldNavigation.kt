package com.ambient.tv.ui.mobile

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.GAMES_MENU_ID
import com.ambient.tv.ui.navigation.SidebarUiState
import com.ambient.tv.ui.navigation.SidebarViewModel
import com.ambient.tv.ui.navigation.WEATHER_MENU_ID
import com.ambient.tv.ui.navigation.menuOrderItems
import com.ambient.tv.ui.navigation.menuSystemName
import com.ambient.tv.ui.navigation.gamesMenuDestinations
import com.ambient.tv.ui.navigation.gamesMenuName
import com.ambient.tv.ui.navigation.sidebarShowsGlobalSearch
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackArtworkCard
import com.ambient.tv.ui.player.miniFormatDuration
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.theme.LocalAccentStyle
import com.ambient.tv.ui.theme.LocalPlaybackAccent
import com.ambient.tv.ui.theme.accentBrush

/** No TV layout wrapper: the existing television composition is invoked verbatim. */
@Composable
fun HandheldNavigationHost(
    currentRoute: String,
    /** Concrete selected menu entry, including section/feed/source IDs. */
    activeSidebarRoute: String? = currentRoute,
    showNavigation: Boolean,
    playerVisible: Boolean,
    onNavigate: (String) -> Unit,
    onOpenPlayback: (String) -> Unit,
    onOpenSystemSettings: (String) -> Unit = {},
    /** Extra space above system insets occupied by navigation, companion bars and the mini-player. */
    onOverlayBottomInsetChanged: (androidx.compose.ui.unit.Dp) -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (!LocalHandheldUi.current) { content(); return }
    val overlayInsetChanged by rememberUpdatedState(onOverlayBottomInsetChanged)
    val windowHeight = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    val overlayDensity = androidx.compose.ui.platform.LocalDensity.current
    val safeBottom = WindowInsets.safeDrawing.getBottom(overlayDensity)
    DisposableEffect(Unit) { onDispose { overlayInsetChanged(0.dp) } }
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
        radioHomeEnabled = sidebarState.radioHomeEnabled,
        telegramHomeEnabled = sidebarState.telegramHomeEnabled && sidebarState.telegramEnabled && sidebarState.telegramAuthorized,
    )
    val showSearch = sidebarShowsGlobalSearch(
        plexConfigured = sidebarState.plexEnabled,
        jellyfinConfigured = sidebarState.jellyfinEnabled,
        embyConfigured = sidebarState.embyEnabled,
        tvheadendConfigured = sidebarState.tvheadendEnabled && !sidebarState.tvheadendLoginRejected || hasIptv,
        youtubeEnabled = sidebarState.youtubeEnabled,
        spotifyCanSearch = sidebarState.spotifyEnabled && sidebarState.spotifyAuthorized && sidebarState.spotifyNativeAvailable,
        radioEnabled = sidebarState.radioEnabled,
        telegramCanSearch = com.ambient.tv.data.telegram.telegramSearchAvailable(
            com.ambient.tv.modules.SystemModuleAvailability.monogramOffered, sidebarState.telegramEnabled, sidebarState.telegramAuthorized),
    )
    val companion: com.ambient.tv.companion.CompanionViewModel = hiltViewModel()
    // Phone companion pages (pairing, remote, "Handy & TV") cover the content area, in this priority.
    val companionPage by remember(companion) {
        companion.manager.state.map { when { it.pairingFlow -> 1; it.gamepadPanel -> 4; it.remotePanel -> 2; it.handheldPageOpen() -> 3; else -> 0 } }.distinctUntilChanged()
    }.collectAsState(0)
    val remoteOpen = companionPage == 2 || companionPage == 4
    val gamepadOpen = companionPage == 4
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
                // Measure the actual stack; bars can appear or change height independently of
                // the navigation mode, orientation and the currently playing media.
                overlayInsetChanged(with(overlayDensity) {
                    (windowHeight - panelBounds.bottom - safeBottom).coerceAtLeast(0).toDp()
                })
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
                4 -> com.ambient.tv.companion.CompanionGamepadScreen(companion.manager)
            }
        }
        // The remote page carries its own playback controls and target name.
        if (showNavigation && companionPage == 0) com.ambient.tv.companion.CompanionOutputBar()
        if ((showNavigation || !playerVisible) && !remoteOpen && companionPage != 1) com.ambient.tv.companion.CompanionRemotePlaybackBar()
        if (!playerVisible && !gamepadOpen) HandheldMiniPlayer(onOpenPlayback)
    }
    // safeDrawing would pad the portrait status bar / cutout onto the short edge in
    // landscape and leave a dead column beside the player. handheldContentWindowInsets
    // keeps that edge clear on every handheld page; television never reaches this branch.
    // Keep the same Row -> Column -> page parent path in both orientations. Moving a retained
    // AndroidView between parents still detaches its native Surface and terminates its GL thread.
    // Only navigation siblings change; the NavHost and any running player stay attached.
    val landscape = isHandheldLandscape()
    Row(Modifier.fillMaxSize().windowInsetsPadding(handheldContentWindowInsets()).imePadding()) {
        if (landscape && showNavigation && !gamepadOpen) {
            HandheldNavigationRail(
                currentRoute = currentRoute,
                showHome = showHome,
                showSearch = showSearch,
                onNavigate = navigate,
                onOpenSources = openSources,
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            page()
            if (!landscape && showNavigation && !gamepadOpen) {
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
        activeRoute = activeSidebarRoute,
        onDismiss = { showSources = false },
        onNavigate = { showSources = false; onNavigate(it) },
        onOpenSystemSettings = { showSources = false; onOpenSystemSettings(it) },
        onManageMenu = { showSources = false; manageMenu = true },
    )
    // Same editor as the television sidebar: the stored order also drives this navigation bar.
    // Shown from the host so the sheet is gone before the dialog opens.
    val menuItems = menuOrderItems(sidebarState)
    if (manageMenu && menuItems.isNotEmpty()) {
        com.ambient.tv.ui.navigation.MenuOrderDialog(
            systems = menuItems,
            onApplyOrder = sidebarVm::applySystemOrder,
            onRename = sidebarVm::renameSystems,
            onDismiss = { manageMenu = false },
        )
    }
}

/** A selected destination is different from touch focus; companion screens own the
 * selection exclusively, so Sources and Remote never appear selected at the same time. */
internal enum class HandheldNavTab { HOME, SOURCES, SEARCH, REMOTE, SETTINGS }

internal fun handheldNavTabSelected(
    route: String,
    companionActive: Boolean,
    tab: HandheldNavTab,
): Boolean = if (companionActive) {
    tab == HandheldNavTab.REMOTE
} else when (tab) {
    HandheldNavTab.HOME -> route == Destinations.HOME
    HandheldNavTab.SEARCH -> route == Destinations.SEARCH
    HandheldNavTab.SETTINGS -> route == Destinations.SETTINGS
    HandheldNavTab.REMOTE -> false
    HandheldNavTab.SOURCES -> route !in setOf(Destinations.HOME, Destinations.SEARCH, Destinations.SETTINGS)
}

/** Small inset material underline, not another large filled focus ring. The native
 * Material3 item still handles the whole tap target and ripple. */
@Composable
private fun HandheldNavAccentIcon(icon: ImageVector, label: String, selected: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val style = LocalAccentStyle.current
    val underline = remember(accent, style) { accentBrush(accent, style) }
    val allowMotion = LocalPerformanceProfile.current.allowSelectionIndicatorMotion
    val visible by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (allowMotion) tween(if (selected) 160 else 110) else snap(),
        label = "handheld navigation selection",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, label, Modifier.size(24.dp))
        Box(
            Modifier.width(24.dp).height(2.dp)
                .graphicsLayer { alpha = visible; scaleX = visible }
                .background(underline, RoundedCornerShape(50)),
        )
    }
}

@Composable
private fun RowScope.HandheldBottomTab(
    selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected, onClick = onClick,
        icon = { HandheldNavAccentIcon(icon, label, selected) },
        label = { Text(label, maxLines = 1) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
        ),
    )
}

@Composable
private fun HandheldRailTab(
    selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit,
) {
    NavigationRailItem(
        selected = selected, onClick = onClick,
        icon = { HandheldNavAccentIcon(icon, label, selected) },
        label = { Text(label, maxLines = 1) },
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
        ),
    )
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
    val companionActive = companionState.handheldPageOpen()
    fun selected(tab: HandheldNavTab) = handheldNavTabSelected(currentRoute, companionActive, tab)
    NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0), tonalElevation = 0.dp) {
        if (showHome) HandheldBottomTab(selected(HandheldNavTab.HOME), Icons.Default.Home, home) {
            onNavigate(Destinations.HOME)
        }
        HandheldBottomTab(selected(HandheldNavTab.SOURCES), Icons.Default.Apps, sources, onOpenSources)
        if (showSearch) HandheldBottomTab(selected(HandheldNavTab.SEARCH), Icons.Default.Search, search) {
            onNavigate(Destinations.SEARCH)
        }
        HandheldBottomTab(selected(HandheldNavTab.REMOTE), Icons.Default.SettingsRemote, remote,
            companion.manager::showRemoteOrDevices)
        HandheldBottomTab(selected(HandheldNavTab.SETTINGS), Icons.Default.Settings, settings) {
            onNavigate(Destinations.SETTINGS)
        }
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
    val companionActive = companionState.handheldPageOpen()
    fun selected(tab: HandheldNavTab) = handheldNavTabSelected(currentRoute, companionActive, tab)
    // Preserve the old notch/safe-area behavior and native Material3 item touch targets.
    NavigationRail(
        windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) {
        Spacer(Modifier.weight(1f))
        if (showHome) HandheldRailTab(selected(HandheldNavTab.HOME), Icons.Default.Home, home) {
            onNavigate(Destinations.HOME)
        }
        HandheldRailTab(selected(HandheldNavTab.SOURCES), Icons.Default.Apps, sources, onOpenSources)
        if (showSearch) HandheldRailTab(selected(HandheldNavTab.SEARCH), Icons.Default.Search, search) {
            onNavigate(Destinations.SEARCH)
        }
        HandheldRailTab(selected(HandheldNavTab.REMOTE), Icons.Default.SettingsRemote, remote,
            companion.manager::showRemoteOrDevices)
        HandheldRailTab(selected(HandheldNavTab.SETTINGS), Icons.Default.Settings, settings) {
            onNavigate(Destinations.SETTINGS)
        }
        Spacer(Modifier.weight(1f))
    }
}

internal data class SourceEntry(
    val key: String,
    val title: String,
    val route: String,
    val settingsCategory: String? = null,
    val offline: Boolean = false,
    val icon: ImageVector? = null,
)
internal data class SourceGroup(val key: String, val system: MediaSystem?, val entries: List<SourceEntry>)

/** Configured game folders are navigable before a scan and while their NAS is asleep. */
@Composable
internal fun handheldGameSourceGroup(state: SidebarUiState): SourceGroup? {
    val destinations = gamesMenuDestinations(state)
    if (destinations.isEmpty()) return null
    val settings = if (GAMES_MENU_ID in state.settingsShortcutHidden) null else SourceEntry(
        key = "settings",
        title = stringResource(R.string.nav_system_settings, gamesMenuName()),
        route = Destinations.SETTINGS,
        settingsCategory = GAMES_MENU_ID,
        icon = Icons.Default.Settings,
    )
    return SourceGroup(GAMES_MENU_ID, null, destinations.map {
        SourceEntry(it.id, it.label, it.route, icon = it.icon)
    } + listOfNotNull(settings))
}

/** A submenu only marks its exact active concrete route; settings shortcuts and
 * unrelated sections must never be highlighted just because they share a route pattern. */
internal fun handheldSourceSelected(activeRoute: String?, entryRoute: String, settingsCategory: String? = null): Boolean =
    settingsCategory == null && !activeRoute.isNullOrBlank() && activeRoute == entryRoute

/** Selection shows through the row's accent tint and icon (no line marker); native ripples stay. */
private fun Modifier.handheldSourceRow(selected: Boolean, onClick: () -> Unit): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = 8.dp, vertical = 1.dp)
    .clip(RoundedCornerShape(12.dp))
    .clickable(onClick = onClick)
    .semantics { this.selected = selected }

@Composable
private fun handheldSourceColors(selected: Boolean): ListItemColors = ListItemDefaults.colors(
    containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else Color.Transparent,
    leadingIconColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Same enabled Radio Browser categories and fallback as the TV sidebar. */
internal fun handheldRadioSubmenuKeys(enabled: List<String>): List<String> =
    listOf("popular", "favorites", "recent", "genres", "countries", "languages")
        .filter { it in enabled }
        .ifEmpty { listOf("popular") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandheldSources(
    activeRoute: String?,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenSystemSettings: (String) -> Unit = {},
    onManageMenu: () -> Unit = {},
    viewModel: SidebarViewModel = hiltViewModel(),
) {
    val sidebarState by viewModel.uiState.collectAsState()
    com.ambient.tv.ui.common.RefreshOnReconnect(viewModel.reconnectSources, viewModel::refreshAfterReconnect)
    // Unreachable servers stay listed, as in the television sidebar.
    val plex = sidebarState.plexEnabled
    val jellyfin = sidebarState.jellyfinEnabled
    val emby = sidebarState.embyEnabled
    val youtube = sidebarState.youtubeEnabled
    val spotify = sidebarState.spotifyEnabled && sidebarState.spotifyAuthorized
    val radio = sidebarState.radioEnabled
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
    val menuItems = menuOrderItems(sidebarState)
    val canManageMenu = menuItems.isNotEmpty()
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
            title = stringResource(R.string.nav_system_settings, menuSystemName(system)),
            route = Destinations.SETTINGS,
            settingsCategory = system.key,
        )
        return SourceGroup(system.key, system, entries + listOfNotNull(settings))
    }
    val games = handheldGameSourceGroup(sidebarState)
    val romm = com.ambient.tv.ui.navigation.rommMenuDestinations(sidebarState)
    val groups = buildList {
        games?.let(::add)
        if (romm.isNotEmpty()) add(withSettings(MediaSystem.ROMM, romm.map {
            SourceEntry(it.id, it.label, it.route, icon = it.icon, offline = "romm" in sidebarState.offlineSystems)
        }))
        if (dlnaServers.isNotEmpty()) add(withSettings(MediaSystem.DLNA,
            dlnaServers.map { SourceEntry(it.id, it.name, Destinations.dlnaServer(it.id), offline = "dlna:${it.id}" in sidebarState.offlineSystems) } +
                listOf(SourceEntry("manage", stringResource(R.string.dlna_servers), Destinations.DLNA))))
        if (files) add(withSettings(MediaSystem.FILES,
            networkFileSources.map { SourceEntry("source:${it.id}", it.name, Destinations.fileSource(it.id), offline = "files:${it.id}" in sidebarState.offlineSystems) } + listOf(
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
        // Same entries as the TV sidebar; like there, only once signed in.
        if (sidebarState.telegramEnabled && sidebarState.telegramAuthorized) add(withSettings(
            MediaSystem.TELEGRAM,
            com.ambient.tv.ui.navigation.telegramMenuEntries(sidebarState.telegramSubmenuKeys).map { entry ->
                SourceEntry(entry.key, stringResource(entry.title), Destinations.telegramSection(entry.key))
            },
        ))
        if (radio) add(withSettings(
            MediaSystem.RADIO,
            handheldRadioSubmenuKeys(sidebarState.radioSubmenuKeys).map { key ->
                val label = stringResource(when (key) {
                    "favorites" -> R.string.radio_favorites
                    "recent" -> R.string.radio_recent
                    "genres" -> R.string.radio_genres
                    "countries" -> R.string.radio_countries
                    "languages" -> R.string.radio_languages
                    else -> R.string.radio_popular
                })
                SourceEntry(key, label, Destinations.radioSection(key))
            },
        ))
        if (tv && sidebarState.tvheadendLoginRejected) add(withSettings(MediaSystem.TVHEADEND, listOf(SourceEntry("login",
            stringResource(R.string.tvh_login_check), Destinations.SETTINGS, settingsCategory = MediaSystem.TVHEADEND.key))))
        else if (tv) add(withSettings(MediaSystem.TVHEADEND, tvKeys.mapNotNull { key ->
            val label = when (key) { "live" -> R.string.tvh_live; "guide" -> R.string.tvh_guide; "recordings" -> R.string.tvh_recordings; "timers" -> R.string.tvh_timers; else -> null } ?: return@mapNotNull null
            SourceEntry(key, stringResource(label), if (key == "live") Destinations.LIVE_TV else Destinations.liveTvSection(key))
        }.ifEmpty { listOf(SourceEntry("live", stringResource(R.string.tvh_live), Destinations.LIVE_TV)) }))
    }
    val groupsById = groups.associateBy { it.key }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mobile_sources), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
            // The editor and this sheet share both the names and the complete order, including
            // Weather's direct route between media groups.
            menuItems.forEach { menuItem ->
                if (menuItem.id == WEATHER_MENU_ID) {
                    item(menuItem.id) {
                        val active = handheldSourceSelected(activeRoute, Destinations.WEATHER)
                        ListItem(
                            content = { Text(menuItem.label) },
                            supportingContent = if ("weather" in sidebarState.offlineSystems) {
                                { Text(stringResource(R.string.offline_badge)) }
                            } else null,
                            leadingContent = { Icon(menuItem.icon, null) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            colors = handheldSourceColors(active),
                            modifier = Modifier.handheldSourceRow(active) { onNavigate(Destinations.WEATHER) },
                        )
                    }
                    return@forEach
                }
                val group = groupsById[menuItem.id] ?: return@forEach
                val open = groups.size == 1 || expanded == group.key
                val groupActive = group.entries.any { handheldSourceSelected(activeRoute, it.route, it.settingsCategory) }
                item(group.key) {
                    ListItem(
                        content = { Text(menuItem.label) },
                        supportingContent = if (group.key in sidebarState.offlineSystems) {
                            { Text(stringResource(R.string.offline_badge)) }
                        } else null,
                        leadingContent = {
                            group.system?.let { SystemLogo(it) } ?: Icon(menuItem.icon, null)
                        },
                        trailingContent = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                        colors = handheldSourceColors(groupActive),
                        modifier = Modifier.handheldSourceRow(groupActive) { expanded = if (open) null else group.key },
                    )
                }
                if (open) items(group.entries, key = { "${group.key}:${it.key}" }) { entry ->
                    val active = handheldSourceSelected(activeRoute, entry.route, entry.settingsCategory)
                    ListItem(
                        content = { Text(entry.title) },
                        leadingContent = entry.icon?.let { icon -> { Icon(icon, null, Modifier.size(28.dp)) } },
                        supportingContent = if (entry.offline) { { Text(stringResource(R.string.offline_badge)) } } else null,
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        colors = handheldSourceColors(active),
                        modifier = Modifier.padding(start = 24.dp).handheldSourceRow(active) {
                            if (entry.settingsCategory != null) onOpenSystemSettings(entry.settingsCategory)
                            else onNavigate(entry.route)
                        },
                    )
                }
            }
            if (canManageMenu) item("order") {
                ListItem(content = { Text(stringResource(R.string.sidebar_manage_menu)) }, leadingContent = { Icon(Icons.Default.SwapVert, null) },
                    modifier = Modifier.clickable(onClick = onManageMenu))
            }
            item("add") {
                ListItem(content = { Text(stringResource(R.string.sidebar_add_system)) }, leadingContent = { Icon(Icons.Default.Add, null) },
                    modifier = Modifier.clickable { onNavigate(Destinations.INITIAL_SETUP) })
            }
        }
    }
}

/** No false progress for radio/live/unknown durations; always clamp stale decoder positions. */
internal fun handheldMiniPlayerProgress(positionMs: Long, durationMs: Long, live: Boolean): Float =
    if (live || durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

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
    val progress = handheldMiniPlayerProgress(positionMs, durationMs, metadata.isLive)
    val accent = LocalPlaybackAccent.current.indicator
    val accentStyle = LocalAccentStyle.current
    val progressBrush = remember(accent, accentStyle) { accentBrush(accent, accentStyle) }
    Surface(tonalElevation = 3.dp) {
        Column {
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
            IconButton(
                onClick = { if (useSpotify) controls.toggleSpotify() else BackgroundPlaybackSession.userToggle() },
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (playing) R.string.player_pause else R.string.player_play))
            }
            IconButton(onClick = { if (useSpotify) controls.stopSpotify() else BackgroundPlaybackSession.stop() }) {
                Icon(Icons.Default.Close, stringResource(R.string.player_stop))
            }
        }
        // Reserve the slim accent timeline even while metadata loads; never advertise
        // seek progress for a live/unknown-duration stream. No extra focus or click target.
        Box(Modifier.fillMaxWidth().height(2.dp)
            .graphicsLayer { alpha = if (!metadata.isLive && durationMs > 0L) 1f else 0f }
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .10f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(progressBrush))
        }
        }
    }
}

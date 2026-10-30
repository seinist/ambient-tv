@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.ambient.tv.ui.navigation

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.theme.menuFocusMotion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.branding.BrandMark
import com.ambient.tv.branding.BrandWordmark
import com.ambient.tv.ui.common.rememberUhdScale
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.SystemWordmark
import com.ambient.tv.ui.common.overlayPanelBorder
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.common.librarySectionIcon
import com.ambient.tv.ui.common.liveTvMenuIcon
import com.ambient.tv.ui.common.spotifyMenuIcon
import com.ambient.tv.ui.common.youtubeFeedIcon
import com.ambient.tv.ui.player.BackgroundPlaybackBar
import com.ambient.tv.ui.player.NextUpCandidate
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.Gold
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.trailers.TrailerFeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

// Layout space the rail reserves; the 56 dp pill overhangs it so that even the narrowest screen
// inset (32 dp) leaves 16 dp to the pill. Scrolled rows end at the opaque strip.
private val SIDEBAR_BRAND_SLOT_HEIGHT = 36.dp
private val SIDEBAR_BRAND_HEIGHT = 28.dp
private val SIDEBAR_SUBMENU_WORDMARK_HEIGHT = 26.dp
private val SIDEBAR_SUBMENU_WORDMARK_Y_OFFSET = 2.dp
private val SIDEBAR_SUBMENU_SEPARATOR_GAP = 19.dp
// Submenu rows (2dp outer padding + this) put their icon 17dp in from the row edge, like the main rail.
private val SUBMENU_ICON_INSET = 15.dp
private const val PAGE_BACK_ID = "page-back"
// A group unfolds with the rail's own timing, so opening the rail and opening a group feel alike.
private const val SIDEBAR_GROUP_MS = 220
private const val SIDEBAR_OPEN_MS = 260
private val SIDEBAR_ROW_MIN_HEIGHT = 36.dp
private val SIDEBAR_ROW_PAD_H = 14.dp
private val SIDEBAR_ROW_ICON_GAP = 14.dp
private val SIDEBAR_ROW_TEXT = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
// [settingsCategory] marks a group's shortcut to its system's Settings page; it is never the active row.
internal data class MenuDestination(val id: String, val label: String, val icon: ImageVector, val route: String, val settingsCategory: String? = null) {
    fun isActive(activeRoute: String?) = settingsCategory == null && route == activeRoute
}
@Composable
private fun offlineLabel(name: String, offline: Boolean): String =
    if (offline) "$name · ${stringResource(R.string.offline_badge)}" else name

private data class MenuSystem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val entries: List<MenuDestination>,
    val system: MediaSystem? = null,
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Sidebar(
    currentRoute: String?,
    currentSectionKey: String?,
    currentYouTubeFeedKey: String? = null,
    currentTrailerFeedKey: String? = null,
    currentSpotifySectionKey: String? = null,
    currentTvheadendTab: String? = null,
    currentLiveTvSourceId: String? = null,
    currentFileSourceId: String? = null,
    currentDlnaServerId: String? = null,
    onNavigate: (String) -> Unit,
    onOpenSystemSettings: (String) -> Unit = {},
    onOpenPlayback: (String) -> Unit = {},
    onPlayNext: (NextUpCandidate) -> Unit = {},
    requestContentFocus: () -> Boolean = { false },
    contentReady: Boolean = true,
    isEntryRequested: () -> Boolean = { true },
    onEntryRequestConsumed: () -> Unit = {},
    onFocusOwnershipChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SidebarViewModel = hiltViewModel(),
    currentRadioSectionKey: String? = null,
) {
    val state by viewModel.uiState.collectAsState()
    com.ambient.tv.ui.common.RefreshOnReconnect(viewModel.reconnectSources, viewModel::refreshAfterReconnect)
    val actions = remember(viewModel) {
        object : SidebarActions {
            override fun prefetch(route: String) = viewModel.metadataPrefetcher.route(route)
            override fun refresh(delayMs: Long) = viewModel.refresh(delayMs)
            override fun applySystemOrder(order: List<String>) = viewModel.applySystemOrder(order)
            override fun renameSystems(names: Map<String, String>, edited: Set<String>) = viewModel.renameSystems(names, edited)
            override fun toggleDivider(id: String) = viewModel.toggleDivider(id)
            override fun applyEntryOrder(systemId: String, order: List<String>) = viewModel.applyEntryOrder(systemId, order)
            override fun toggleEntryDivider(systemId: String, id: String) = viewModel.toggleEntryDivider(systemId, id)
        }
    }
    SidebarContent(
        state, actions, currentRoute, currentSectionKey, currentYouTubeFeedKey, currentTrailerFeedKey, currentSpotifySectionKey,
        currentTvheadendTab, currentLiveTvSourceId, currentFileSourceId, currentDlnaServerId, onNavigate, onOpenSystemSettings,
        onOpenPlayback, onPlayNext, requestContentFocus, contentReady, isEntryRequested, onEntryRequestConsumed,
        onFocusOwnershipChanged, modifier, currentRadioSectionKey,
    )
}

/** What the panel needs from [SidebarViewModel]; a fake drives it in UI tests. */
internal interface SidebarActions {
    fun prefetch(route: String)
    fun refresh(delayMs: Long)
    fun applySystemOrder(order: List<String>)
    fun renameSystems(names: Map<String, String>, edited: Set<String>)
    fun toggleDivider(id: String)
    fun applyEntryOrder(systemId: String, order: List<String>)
    fun toggleEntryDivider(systemId: String, id: String)
}

@Composable
internal fun SidebarContent(
    sidebarState: SidebarUiState,
    viewModel: SidebarActions,
    currentRoute: String?,
    currentSectionKey: String?,
    currentYouTubeFeedKey: String? = null,
    currentTrailerFeedKey: String? = null,
    currentSpotifySectionKey: String? = null,
    currentTvheadendTab: String? = null,
    currentLiveTvSourceId: String? = null,
    currentFileSourceId: String? = null,
    currentDlnaServerId: String? = null,
    onNavigate: (String) -> Unit,
    onOpenSystemSettings: (String) -> Unit = {},
    onOpenPlayback: (String) -> Unit = {},
    onPlayNext: (NextUpCandidate) -> Unit = {},
    requestContentFocus: () -> Boolean = { false },
    contentReady: Boolean = true,
    isEntryRequested: () -> Boolean = { true },
    onEntryRequestConsumed: () -> Unit = {},
    onFocusOwnershipChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    currentRadioSectionKey: String? = null,
) {
    val uiScale = rememberUhdScale()
    val menuMotionEnabled = LocalPerformanceProfile.current.allowSpringFocus
    val plexEnabled = sidebarState.plexEnabled
    val jellyfinEnabled = sidebarState.jellyfinEnabled
    val embyEnabled = sidebarState.embyEnabled
    val youtubeEnabled = sidebarState.youtubeEnabled
    // Like every system with a login: in the menu only once signed in (sign-in lives in Settings).
    val spotifyEnabled = sidebarState.spotifyEnabled && sidebarState.spotifyAuthorized
    val radioEnabled = sidebarState.radioEnabled
    val tvheadendEnabled = sidebarState.tvheadendEnabled
    val tvheadendUsable = tvheadendEnabled && !sidebarState.tvheadendLoginRejected
    val spotifyAuthorized = sidebarState.spotifyAuthorized
    val spotifyNativeAvailable = sidebarState.spotifyNativeAvailable
    val youtubeFeedKeys = sidebarState.youtubeFeedKeys
    val spotifySubmenuKeys = sidebarState.spotifySubmenuKeys
    val radioSubmenuKeys = sidebarState.radioSubmenuKeys
    val tvheadendSubmenuKeys = sidebarState.tvheadendSubmenuKeys
    val settingsShortcutHidden = sidebarState.settingsShortcutHidden
    val systemOrder = sidebarState.systemOrder
    val overview = stringResource(R.string.nav_overview)
    val filesEnabled = sidebarState.filesEnabled
    // An unreachable server stays listed; opening it offers to reconnect (MainActivity).
    val plexShown = plexEnabled
    val jellyfinShown = jellyfinEnabled
    val embyShown = embyEnabled
    val networkFileSources = sidebarState.networkFileSources
    val dlnaServers = sidebarState.dlnaServers
    val systems = buildList {
        if (dlnaServers.isNotEmpty()) add(MenuSystem("dlna", systemName(MediaSystem.DLNA), Icons.Filled.VideoLibrary, system = MediaSystem.DLNA, entries =
            dlnaMenuDestinations(sidebarState)))
        if (filesEnabled) add(MenuSystem("files", systemName(MediaSystem.FILES), Icons.Filled.Folder, system = MediaSystem.FILES, entries =
            filesMenuDestinations(sidebarState)))
        if (plexShown) add(MenuSystem("plex", offlineLabel(systemName(MediaSystem.PLEX, "Plex"), "plex" in sidebarState.offlineSystems), Icons.Filled.VideoLibrary, system = MediaSystem.PLEX, entries =
            providerMenuDestinations(sidebarState, "plex")))
        if (jellyfinShown) add(MenuSystem("jellyfin", offlineLabel(systemName(MediaSystem.JELLYFIN, "Jellyfin"), "jellyfin" in sidebarState.offlineSystems), Icons.Filled.VideoLibrary, system = MediaSystem.JELLYFIN, entries =
            providerMenuDestinations(sidebarState, "jellyfin")))
        if (embyShown) add(MenuSystem("emby", offlineLabel(systemName(MediaSystem.EMBY, "Emby"), "emby" in sidebarState.offlineSystems), Icons.Filled.VideoLibrary, system = MediaSystem.EMBY, entries =
            providerMenuDestinations(sidebarState, "emby")))
        if (sidebarState.trailersConfigured) add(MenuSystem("trailers", systemName(MediaSystem.TRAILERS), Icons.Filled.Movie, system = MediaSystem.TRAILERS,
            entries = TrailerFeed.entries.map { feed ->
                MenuDestination(
                    id = "trailers:${feed.name.lowercase()}",
                    label = stringResource(feed.label),
                    icon = when (feed) {
                        TrailerFeed.CINEMA -> Icons.Filled.Movie
                        TrailerFeed.UPCOMING -> Icons.Filled.Schedule
                        TrailerFeed.MOVIES -> Icons.Filled.LocalMovies
                        TrailerFeed.SERIES -> Icons.Filled.Tv
                    },
                    route = if (feed == TrailerFeed.CINEMA) Destinations.TRAILERS else Destinations.trailerFeed(feed.name),
                )
            }))
        if (youtubeEnabled) add(MenuSystem("youtube", systemName(MediaSystem.YOUTUBE, "SmartTube"), Icons.Filled.SmartDisplay, system = MediaSystem.YOUTUBE, entries =
            youtubeFeedKeys.ifEmpty { listOf("home") }.map { key ->
                val label = stringResource(when (key) {
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
                })
                MenuDestination("youtube:$key", label, youtubeFeedIcon(key), Destinations.youtubeSection(key, sidebarState.youtubeStartFeedKey))
            }))
        // Signed in: native playback unlocks the full submenu; otherwise only Home.
        if (spotifyEnabled) {
            val entries = if (spotifyAuthorized && spotifyNativeAvailable) {
                listOf(
                    Triple("home", stringResource(R.string.nav_spotify_home), spotifyMenuIcon("home")),
                    Triple("liked", stringResource(R.string.nav_spotify_liked), spotifyMenuIcon("liked")),
                    Triple("playlists", stringResource(R.string.nav_spotify_playlists), spotifyMenuIcon("playlists")),
                    Triple("albums", stringResource(R.string.nav_spotify_albums), spotifyMenuIcon("albums")),
                    Triple("artists", stringResource(R.string.nav_spotify_artists), spotifyMenuIcon("artists")),
                )
                    .filter { it.first in spotifySubmenuKeys }
                    .map { (key, label, icon) -> MenuDestination("spotify:$key", label, icon, if (key == "home") Destinations.SPOTIFY else Destinations.spotifySection(key)) }
                    .toMutableList()
                    .also { list ->
                        if ("now_playing" in spotifySubmenuKeys) {
                            list.add(MenuDestination("spotify:now_playing", stringResource(R.string.nav_spotify_now_playing), spotifyMenuIcon("now_playing"), Destinations.spotifyPlayer()))
                        }
                        if (list.isEmpty()) list.add(MenuDestination("spotify:home", overview, spotifyMenuIcon("home"), Destinations.SPOTIFY))
                    }
            } else {
                // Authorization is already known here; a missing native core needs a truthful
                // explanation and a route to its settings, not another sign-in attempt.
                mutableListOf(MenuDestination("spotify:unavailable", stringResource(R.string.spotify_native_missing), Icons.Filled.ErrorOutline,
                    Destinations.SETTINGS, settingsCategory = MediaSystem.SPOTIFY.key))
            }
            add(MenuSystem("spotify", systemName(MediaSystem.SPOTIFY, "Spotify"), Icons.Filled.Headphones, entries, system = MediaSystem.SPOTIFY))
        }
        if (radioEnabled) add(MenuSystem(
            "radio", systemName(MediaSystem.RADIO), Icons.Filled.Headphones, system = MediaSystem.RADIO,
            entries = listOf(
                "popular" to stringResource(R.string.radio_popular),
                "favorites" to stringResource(R.string.radio_favorites),
                "recent" to stringResource(R.string.radio_recent),
                "genres" to stringResource(R.string.radio_genres),
                "countries" to stringResource(R.string.radio_countries),
                "languages" to stringResource(R.string.radio_languages),
            ).filter { (key, _) -> key in radioSubmenuKeys }
                .map { (key, label) ->
                    MenuDestination("radio:$key", label, com.ambient.tv.ui.common.radioMenuIcon(key), Destinations.radioSection(key))
                }
                .ifEmpty {
                    listOf(MenuDestination(
                        "radio:popular",
                        stringResource(R.string.radio_popular),
                        com.ambient.tv.ui.common.radioMenuIcon("popular"),
                        Destinations.RADIO,
                    ))
                },
        ))
        if (tvheadendEnabled && sidebarState.tvheadendLoginRejected) {
            // Rejected login: keep TVHeadend in the menu, pointing at its settings instead of vanishing.
            val entries = listOf(MenuDestination("tvheadend:login", stringResource(R.string.tvh_login_check), Icons.AutoMirrored.Filled.Login,
                Destinations.SETTINGS, settingsCategory = MediaSystem.TVHEADEND.key))
            add(MenuSystem("tvheadend", offlineLabel(systemName(MediaSystem.TVHEADEND, "TVHeadend"), "tvheadend" in sidebarState.offlineSystems), Icons.Filled.Tv, entries, system = MediaSystem.TVHEADEND))
        } else if (tvheadendEnabled) {
            val entries = listOf(
                Triple("live", stringResource(R.string.tvh_live), liveTvMenuIcon("live")),
                Triple("guide", stringResource(R.string.tvh_guide), liveTvMenuIcon("guide")),
                Triple("recordings", stringResource(R.string.tvh_recordings), liveTvMenuIcon("recordings")),
                Triple("timers", stringResource(R.string.tvh_timers), liveTvMenuIcon("timers")),
            ).filter { it.first in tvheadendSubmenuKeys }.map { (key, label, icon) ->
                MenuDestination("tvheadend:$key", label, icon, if (key == "live") Destinations.LIVE_TV else Destinations.liveTvSection(key))
            }.ifEmpty { listOf(MenuDestination("tvheadend:live", stringResource(R.string.tvh_live), liveTvMenuIcon("live"), Destinations.LIVE_TV)) }
            val label = offlineLabel(systemName(MediaSystem.TVHEADEND, "TVHeadend"), "tvheadend" in sidebarState.offlineSystems)
            add(MenuSystem("tvheadend", label, Icons.Filled.Tv, entries, system = MediaSystem.TVHEADEND))
        }
        // IPTV playlists (M3U + XMLTV): one entry per playlist and tab, through the shared Live TV screens.
        val iptvSources = sidebarState.liveTvSources.filter { it.source.system == "iptv" }
        if (iptvSources.isNotEmpty()) {
            val live = stringResource(R.string.tvh_live)
            val guide = stringResource(R.string.tvh_guide)
            val entries = iptvSources.flatMap { available ->
                fun label(tab: String) = if (iptvSources.size > 1) "${available.source.name} · $tab" else tab
                listOfNotNull(
                    MenuDestination("tv:${available.source.id}:live", label(live), liveTvMenuIcon("live"), Destinations.liveTvSection("live", available.source.id)),
                    MenuDestination("tv:${available.source.id}:guide", label(guide), liveTvMenuIcon("guide"), Destinations.liveTvSection("guide", available.source.id))
                        .takeIf { available.capabilities.guide },
                )
            }
            add(MenuSystem("iptv", systemName(MediaSystem.IPTV), Icons.Filled.LiveTv, entries, system = MediaSystem.IPTV))
        }
    }.sortedBy { systemOrder.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        .map { it.copy(entries = sidebarOrderedEntries(it.entries, sidebarState.entryOrder[it.id]) { entry -> entry.id }) }
    val systemIds = systems.map { it.id }
    // Exactly one system: its page is the main level (no extra step through a one-row main page).
    val singleSystem: MenuSystem? = systems.singleOrNull()
    val activeRoute = sidebarConcreteRoute(
        currentRoute, currentSectionKey, currentYouTubeFeedKey, currentTrailerFeedKey,
        currentSpotifySectionKey, currentTvheadendTab, currentLiveTvSourceId, currentFileSourceId, currentDlnaServerId,
        currentRadioSectionKey,
    )
    val activeSystem = systems.firstOrNull { system -> system.entries.any { it.isActive(activeRoute) } }
    // Home: systems that contribute home-like rows (media servers, Live TV, YT, Spotify). Files-only has no Home.
    // Search: only backends SearchViewModel can query — FilesRepository.search is a stub, so Files is excluded.
    val showHome = sidebarState.trailersConfigured || sidebarShowsHome(plexShown, jellyfinShown, embyShown, tvheadendUsable, youtubeEnabled, spotifyEnabled, sidebarState.radioHomeEnabled)
    val showSearch = sidebarShowsGlobalSearch(
        plexConfigured = plexShown,
        jellyfinConfigured = jellyfinShown,
        embyConfigured = embyShown,
        tvheadendConfigured = tvheadendUsable || sidebarState.liveTvSources.any { it.source.system == "iptv" },
        youtubeEnabled = youtubeEnabled,
        spotifyCanSearch = spotifyEnabled && spotifyAuthorized && spotifyNativeAvailable,
        radioEnabled = radioEnabled,
    )
    val globals = buildList {
        // Plex-style order: Search above Home.
        if (showSearch) {
            add(MenuDestination("search", stringResource(R.string.nav_search), Icons.Filled.Search, Destinations.SEARCH))
        }
        if (showHome) {
            add(MenuDestination("home", stringResource(R.string.nav_home), Icons.Filled.Home, Destinations.HOME))
        }
        if (systems.isEmpty()) add(MenuDestination("setup", stringResource(R.string.sidebar_add_system), Icons.Filled.Add, Destinations.INITIAL_SETUP))
    }
    val filesLabel = stringResource(R.string.nav_files)
    val configurationLabel = stringResource(R.string.nav_configuration)
    fun systemLabel(system: MenuSystem) = if (system.id == "files") filesLabel else system.label
    // Row lists are built once per data change, not on every recomposition.
    val mainRows = remember(globals, systems, activeRoute, activeSystem?.id, filesLabel, settingsShortcutHidden, configurationLabel) {
        val single = systems.singleOrNull()
        if (single != null) {
            // Keep global Settings at the bottom while exposing the single inlined provider's
            // own configuration directly, just like the nested system pages on multi-source TVs.
            val sourceSettings = if (single.id in settingsShortcutHidden) null else MenuDestination(
                "system-settings:${single.id}", configurationLabel, Icons.Filled.Settings,
                Destinations.SETTINGS, settingsCategory = single.id,
            )
            sidebarMainRows(globals, emptyList(), activeRoute) +
                sidebarPageRows(single.id, single.entries, sourceSettings, activeRoute)
        } else {
            sidebarMainRows(globals, systems.map { system ->
                SidebarNavSystem(system.id, if (system.id == "files") filesLabel else system.label, system.entries, system.id == activeSystem?.id, system.icon)
            }, activeRoute)
        }
    }
    val pageRowsById = remember(systems, activeRoute) {
        systems.associate { it.id to sidebarPageRows(it.id, it.entries, null, activeRoute) }
    }
    fun rowsFor(pageId: String?): List<SidebarNavRow.Item> = pageId?.let { pageRowsById[it] } ?: mainRows
    var expanded by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    var miniPlayerHasFocus by remember { mutableStateOf(false) }
    // The open system page (null = main page). Survives config changes; not reset on close.
    var openSystemId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    var lastFocusedId by remember { mutableStateOf<String?>(null) }
    var contentFocusRequested by remember { mutableStateOf(false) }
    var requestedContentRoute by remember { mutableStateOf<String?>(null) }
    var showOrderDialog by remember { mutableStateOf(false) }
    // While a page switch is in flight the pill anchor holds focus, so the panel never loses it.
    var anchorHold by remember { mutableStateOf(false) }
    val rememberedChildren = remember { mutableMapOf<String, String>() }
    // Keep identity stable when metadata refreshes; labels and artwork are not focus keys.
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val openGroup = if (singleSystem != null) null else systems.firstOrNull { it.id == openSystemId }
    // Derived, never stored separately: on a system page there is a parent level.
    val inSubmenu = openGroup != null
    val navRows = rowsFor(openGroup?.id)
    // On a system page the back header is the first focusable row.
    val navItemIds = (if (inSubmenu) listOf(PAGE_BACK_ID) else emptyList()) + navRows.map { it.destination.id }
    val konfigurationShown = openGroup != null && openGroup.id !in settingsShortcutHidden
    val visibleIds = navItemIds + "settings" + "order" + "pill"
    val allRowIds = remember(mainRows, pageRowsById) { (mainRows + pageRowsById.values.flatten()).map { it.destination.id }.toSet() }
    // Drop requesters of rows that no longer exist anywhere.
    SideEffect { requesters.keys.retainAll(allRowIds + setOf("settings", "order", "pill", PAGE_BACK_ID)) }
    val safeAnchor = globals.firstOrNull()?.id ?: "settings"
    fun childTarget(system: MenuSystem): String = chooseSidebarChild(
        system.entries.map { it.id },
        rememberedChildren[system.id],
        system.entries.firstOrNull { it.isActive(activeRoute) }?.id,
    ) ?: "settings"
    fun parkFocus() {
        // Route focus through the always-present pill anchor before the visible page changes.
        anchorHold = true
        runCatching { requester("pill").requestFocus() }
    }
    fun restoreEntry() {
        // Opening the bar while a system is active starts on that system's page, on the active
        // entry; otherwise on the main page's active row. Never on the first row by default.
        val system = activeSystem?.takeIf { singleSystem == null }
        if (system != null) {
            openSystemId = system.id
            pendingFocus = sidebarActiveRowId(rowsFor(system.id)) ?: childTarget(system)
        } else {
            openSystemId = null
            pendingFocus = sidebarEntryTarget(
                sidebarActiveRowId(mainRows) ?: if (currentRoute == Destinations.SETTINGS) "settings" else null,
                mainRows.map { it.destination.id } + "settings",
                safeAnchor,
            )
        }
    }
    fun openSystem(system: MenuSystem) {
        expanded = true
        contentFocusRequested = false
        parkFocus()
        openSystemId = system.id
        pendingFocus = sidebarActiveRowId(rowsFor(system.id)) ?: childTarget(system)
    }
    fun closePanel() {
        // The open page stays as it is: closing must not slide the panel back to the main page.
        pendingFocus = null
        // Collapsing disables the selected menu row. Keep focus on the permanent anchor
        // until the content accepts the handoff, so automatic focus cannot overwrite its
        // remembered tile with the first header/chip in the meantime.
        parkFocus()
        onEntryRequestConsumed()
        expanded = false
        requestedContentRoute = activeRoute
        contentFocusRequested = true
    }
    fun leaveLevel() {
        contentFocusRequested = false
        val page = openGroup
        if (page != null) {
            parkFocus()
            pendingFocus = sidebarPageBackTarget(page.id)
            openSystemId = null
        } else {
            closePanel()
        }
    }
    fun navigate(entry: MenuDestination) {
        pendingFocus = null
        parkFocus()
        requestedContentRoute = entry.route
        contentFocusRequested = true
        expanded = false
        if (entry.settingsCategory != null) onOpenSystemSettings(entry.settingsCategory) else onNavigate(entry.route)
    }
    // Only visibility matters here; collecting the snapshot itself recomposed the rail every tick.
    val miniPlayerVisible by remember {
        BackgroundPlaybackSession.snapshot.map { it.metadata != null }.distinctUntilChanged()
    }.collectAsState(initial = remember { BackgroundPlaybackSession.snapshot.value.metadata != null })
    // The editor edits the level on screen: the systems on the main page, a system's entries on
    // its page, and with a single system (inlined into the main level) that system's entries.
    val editorSystem = openGroup ?: singleSystem
    val hasEditor = if (editorSystem != null) editorSystem.entries.size > 1 else menuEditorAvailable(systems.size)
    fun wrapFocus(id: String, key: Key): Boolean {
        val order = navItemIds + "settings"
        val rowId = if (id == "order") "settings" else id
        // Wrap-around at both ends; with the mini player below, Down from the last row reaches it.
        val target = sidebarWrapTarget(order, rowId, key == Key.DirectionUp, miniPlayerVisible) ?: return false
        if (target != rowId) pendingFocus = target
        return true
    }
    fun markFocus(id: String, systemId: String? = null) {
        systems.asSequence().flatMap { it.entries.asSequence() }.firstOrNull { it.id == id }?.let { viewModel.prefetch(it.route) }
        // Browsing elsewhere while a destination is loading cancels the old handoff.
        if (lastFocusedId != id && pendingFocus == null) contentFocusRequested = false
        lastFocusedId = id
        // Keep the rail collapsed after a destination click; recomposition of the still-focused
        // row must not reopen the rail while the view spinner holds the wait.
        if (!contentFocusRequested) expanded = true
        if (systemId != null) rememberedChildren[systemId] = id
    }

    LaunchedEffect(currentRoute, currentSectionKey, currentYouTubeFeedKey, currentSpotifySectionKey, currentRadioSectionKey, currentTvheadendTab, currentLiveTvSourceId, currentFileSourceId) {
        viewModel.refresh(if (currentRoute == null || currentRoute == Destinations.HOME) 2_500L else 0L)
    }
    LaunchedEffect(systemIds) {
        if (openSystemId != null && openSystemId !in systemIds) openSystemId = null
    }
    LaunchedEffect(pendingFocus, expanded, navItemIds) {
        val target = pendingFocus ?: return@LaunchedEffect
        if (!expanded) return@LaunchedEffect
        // Request once successfully. Never reaffirm focus after success: a repeated request can
        // override the user's next D-pad movement.
        repeat(4) {
            withFrameNanos { }
            if (pendingFocus != target || contentFocusRequested || !expanded) return@LaunchedEffect
            if (runCatching { requester(target).requestFocus() }.getOrDefault(false)) {
                pendingFocus = null
                anchorHold = false
                onEntryRequestConsumed()
                return@LaunchedEffect
            }
        }
        pendingFocus = null
        // Fallback always names a row that exists on the visible page.
        val fallback = sidebarFocusFallback(
            onPage = openGroup != null,
            pageRowIds = navRows.map { it.destination.id },
            lastFocused = lastFocusedId,
            safeAnchor = safeAnchor,
        )
        runCatching { requester(fallback).requestFocus() }
        anchorHold = false
        onEntryRequestConsumed()
    }
    LaunchedEffect(contentFocusRequested, activeRoute, contentReady, requestedContentRoute) {
        if (!sidebarContentFocusAllowed(contentFocusRequested, activeRoute, requestedContentRoute)) {
            return@LaunchedEffect
        }
        delay(48)
        var attempts = 0
        while (attempts < 4 || SidebarContentEntry.pending) {
            if (requestContentFocus()) {
                expanded = false
                pendingFocus = null
                anchorHold = false
                if (contentReady) {
                    contentFocusRequested = false
                    requestedContentRoute = null
                }
                return@LaunchedEffect
            }
            attempts++
            withFrameNanos { }
        }
        if (!contentReady) return@LaunchedEffect
        contentFocusRequested = false
        requestedContentRoute = null
        expanded = true
        pendingFocus = lastFocusedId?.takeIf { it in visibleIds } ?: safeAnchor
    }
    LaunchedEffect(hasFocus, showOrderDialog) {
        if (!hasFocus && !showOrderDialog) {
            delay(180)
            if (!hasFocus) expanded = false
        }
    }
    // A removed library/feed never leaves its old focus requester as the sole way back in.
    LaunchedEffect(visibleIds, lastFocusedId) {
        if (expanded && !showOrderDialog && pendingFocus == null && !anchorHold && lastFocusedId != null && lastFocusedId !in visibleIds) {
            pendingFocus = sidebarFocusFallback(openGroup != null, navRows.map { it.destination.id }, null, safeAnchor)
        }
    }
    val currentBackHandler = rememberUpdatedState<() -> Unit> {
        when (sidebarBackAction(miniPlayerHasFocus = miniPlayerHasFocus, hasParentLevel = inSubmenu)) {
            SidebarBackAction.FOCUS_SETTINGS -> pendingFocus = "settings"
            SidebarBackAction.LEAVE_NESTED_LEVEL -> leaveLevel()
            SidebarBackAction.RETURN_TO_CONTENT -> closePanel()
        }
    }
    // Dialog changes also update ownership; focus events register synchronously below.
    val registerBack = rememberSidebarBackRegistration()
    DisposableEffect(hasFocus, showOrderDialog) {
        if (hasFocus && !showOrderDialog) {
            registerBack { currentBackHandler.value() }
        } else {
            registerBack(null)
        }
        onDispose { registerBack(null) }
    }
    DisposableEffect(Unit) { onDispose { onFocusOwnershipChanged(false) } }

    // ---- Plex-style floating navigation ----
    // Collapsed: a narrow pill, vertically centred at the left edge (icons only).
    // Expanded: a floating panel over dimmed content; content is never pushed.
    val reducedEffects = LocalPerformanceProfile.current.reducedEffects
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // The scrim must span the real window: screenWidthDp ignores the UI-scale density override.
    val windowWidth = with(LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
    // Width = what the widest possible row needs on either page, so switching pages never changes
    // it. It only ever widens while the screen stays the same. Clamped to 200 dp … 35 %.
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val settingsLabel = stringResource(R.string.nav_settings)
    val candidateLabels = remember(globals, systems, filesLabel) {
        globals.map { it.label } + systems.flatMap { system -> listOf(systemLabel(system)) + system.entries.map { it.label } }
    }
    val headerLabels = remember(systems, filesLabel) { systems.map(::systemLabel) }
    var widestPanel by remember(screenWidth, uiScale) { mutableStateOf(0.dp) }
    val measuredPanel = remember(candidateLabels, headerLabels, settingsLabel, configurationLabel, screenWidth, uiScale, density, systems.size) {
        fun textDp(label: String, bold: Boolean = false) = with(density) {
            val style = if (bold) SIDEBAR_ROW_TEXT.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else SIDEBAR_ROW_TEXT
            textMeasurer.measure(label, style, maxLines = 1).size.width.toDp().value
        }
        sidebarPanelWidthDp(
            // +18 dp: main rows carry a chevron; the page header is bold and has none.
            widestLabelDp = maxOf(
                (candidateLabels.maxOfOrNull { textDp(it) } ?: 0f) + 18f,
                headerLabels.maxOfOrNull { textDp(it, bold = true) } ?: 0f,
            ),
            settingsLabelDp = maxOf(textDp(settingsLabel), textDp(configurationLabel)),
            hasReorder = systems.size > 1 || systems.any { it.entries.size > 1 },
            screenWidthDp = screenWidth.value / uiScale,
        ).dp * uiScale
    }
    SideEffect { if (measuredPanel > widestPanel) widestPanel = measuredPanel }
    val panelWidth = maxOf(measuredPanel, widestPanel)
    val motion = if (menuMotionEnabled) androidx.compose.animation.core.tween<Float>(SIDEBAR_OPEN_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        else androidx.compose.animation.core.snap()
    // Read only inside layout/draw lambdas (and derivedStateOf), never during composition.
    val openAnim = androidx.compose.animation.core.animateFloatAsState(if (expanded) 1f else 0f, motion, label = "sidebarOpen")
    val panelShown by remember { derivedStateOf { expanded || openAnim.value > 0f } }
    val labelAlpha: () -> Float = remember { { ((openAnim.value - 0.5f) * 2f).coerceIn(0f, 1f) } }
    fun selectRow(item: SidebarNavRow.Item) {
        if (item.opensPage) {
            systems.firstOrNull { it.id == item.systemId }?.let(::openSystem)
            return
        }
        navigate(item.destination)
    }
    val menuLabel = stringResource(R.string.nav_menu)
    val opensSubmenu = stringResource(R.string.nav_opens_submenu)
    // Zero width: the collapsed menu takes no space, content uses the full screen width.
    Box(modifier.width(0.dp).fillMaxHeight().zIndex(10f)) {
        // Closed menu: background playback stays visible at the bottom edge; Left opens the menu
        // and Down from its last row reaches the full mini player.
        if (!panelShown && miniPlayerVisible) {
            com.ambient.tv.ui.player.BackgroundPlaybackIndicator(
                Modifier.align(Alignment.BottomStart)
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .padding(start = 16.dp * uiScale, bottom = 24.dp * uiScale),
            )
        }
        // Content behind the open panel is dimmed (not blurred; Sparsam keeps it a flat scrim).
        if (panelShown) {
            Box(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .requiredWidth(maxOf(screenWidth, windowWidth))
                    .fillMaxHeight()
                    .graphicsLayer { alpha = openAnim.value }
                    .background(Color.Black.copy(alpha = if (reducedEffects) 0.6f else 0.5f)),
            )
        }
        Box(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || miniPlayerHasFocus) return@onPreviewKeyEvent false
                    when (event.key) {
                        // Right closes the panel and hands focus back to the element that had it.
                        Key.DirectionRight -> {
                            if (lastFocusedId == "settings" && expanded && hasEditor) return@onPreviewKeyEvent false
                            closePanel(); true
                        }
                        // Left: from the editor button back to the settings row, on a system page back
                        // to the main page (focus on that system's row), on the main page nothing.
                        Key.DirectionLeft -> {
                            if (contentFocusRequested) {
                                contentFocusRequested = false
                                requestedContentRoute = null
                                expanded = true
                                restoreEntry()
                            } else when {
                                lastFocusedId == "order" -> pendingFocus = "settings"
                                openGroup != null -> leaveLevel()
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusProperties {
                    onEnter = {
                        val userEntry = isEntryRequested()
                        if (contentFocusRequested && !userEntry) cancelFocusChange()
                        else if (!hasFocus && pendingFocus == null && !userEntry) cancelFocusChange()
                        else {
                            if (contentFocusRequested) {
                                contentFocusRequested = false
                                requestedContentRoute = null
                            }
                            if (!hasFocus && pendingFocus == null) { expanded = true; restoreEntry() }
                            // Single redirect: the pending row if it is already placed, otherwise
                            // the always-present pill anchor; the effect above moves on from there.
                            val target = pendingFocus?.takeIf { expanded && it in visibleIds && requesters.containsKey(it) }
                            val landed = target != null && runCatching { requester(target).requestFocus() }.getOrDefault(false)
                            if (!landed) {
                                anchorHold = true
                                requester("pill").requestFocus()
                            }
                        }
                    }
                }
                .onFocusChanged {
                    hasFocus = it.hasFocus
                    // A remote Back can arrive before the next composition applies effects.
                    registerBack(if (it.hasFocus && !showOrderDialog) ({ currentBackHandler.value() }) else null)
                    onFocusOwnershipChanged(it.hasFocus)
                }
                .focusGroup(),
        ) {
            // Invisible focus anchor at the left edge: holds focus while the panel opens or
            // switches pages and is not focusable otherwise, so D-pad never lands on it.
            var pillFocused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(1.dp)
                    .focusRequester(requester("pill"))
                    .testTag("sidebar:pill")
                    .semantics { contentDescription = menuLabel }
                    .onFocusChanged { pillFocused = it.isFocused }
                    .focusProperties { canFocus = !expanded || pillFocused || anchorHold }
                    .focusable(),
            )
            if (panelShown) {
                Column(
                    Modifier
                        .padding(start = 16.dp * uiScale, top = 12.dp * uiScale, bottom = 12.dp * uiScale)
                        // The pill grows into the panel: the width follows the open fraction in the
                        // layout phase only, labels fade in during the second half, closing reverses.
                        .layout { measurable, constraints ->
                            val collapsed = (56.dp * uiScale).roundToPx()
                            val w = (collapsed + (panelWidth.roundToPx() - collapsed) * openAnim.value).toInt().coerceAtLeast(0)
                            val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = (openAnim.value * 2f).coerceAtMost(1f)
                            translationX = (1f - openAnim.value) * -12.dp.toPx()
                        }
                        .clip(RoundedCornerShape(LocalUiTokens.current.panelRadius * uiScale))
                        .background(Brush.verticalGradient(listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                        )))
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(LocalUiTokens.current.panelRadius * uiScale))
                        .padding(horizontal = 14.dp * uiScale, vertical = 12.dp * uiScale),
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalSidebarLabelAlpha provides labelAlpha) {
                    SidebarPanelHead(uiScale)
                    Spacer(Modifier.height(8.dp * uiScale))
                    val rowPadPx = with(LocalDensity.current) { 40.dp.toPx() * uiScale }
                    val scrollSpec = remember(rowPadPx) { SidebarScrollSpec(rowPadPx) }
                    val pageMs = if (menuMotionEnabled) SIDEBAR_OPEN_MS else 0
                    val focusPage = openGroup?.id
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides scrollSpec) {
                    // System page slides in from the right over the main page (and back), with a fade.
                    androidx.compose.animation.AnimatedContent(
                        targetState = focusPage,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            val forward = targetState != null
                            val ease = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(pageMs, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                            val fade = androidx.compose.animation.core.tween<Float>(pageMs)
                            (androidx.compose.animation.slideInHorizontally(ease) { w -> if (forward) w / 3 else -w / 3 } + androidx.compose.animation.fadeIn(fade)) togetherWith
                                (androidx.compose.animation.slideOutHorizontally(ease) { w -> if (forward) -w / 3 else w / 3 } + androidx.compose.animation.fadeOut(fade))
                        },
                        label = "sidebarPage",
                    ) { pageId ->
                        val pageSystem = systems.firstOrNull { it.id == pageId }
                        Column(Modifier.fillMaxSize()) {
                            if (pageSystem != null) SidebarPageHeader(systemLabel(pageSystem), uiScale, requester(PAGE_BACK_ID),
                                focusable = expanded && pageId == focusPage,
                                onFocused = { markFocus(PAGE_BACK_ID) },
                                onWrapKey = { wrapFocus(PAGE_BACK_ID, it) }) { leaveLevel() }
                            Column(
                                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(1.dp * uiScale),
                            ) {
                                val pageRows = rowsFor(pageId)
                                val shown = when {
                                    pageId != null -> sidebarRowsWithDividers(pageRows, sidebarState.entryDividers[pageId].orEmpty().toSet(),
                                        sidebarState.entryOrder[pageId].orEmpty())
                                    singleSystem != null -> sidebarRowsWithDividers(pageRows,
                                        sidebarState.dividersAfter + sidebarState.entryDividers[singleSystem.id].orEmpty(),
                                        sidebarState.entryOrder[singleSystem.id].orEmpty())
                                    else -> sidebarRowsWithDividers(pageRows, sidebarState.dividersAfter, systemOrder)
                                }
                                shown.forEach { entry ->
                                    when (entry) {
                                        SidebarNavRow.Divider -> SidebarDivider(uiScale)
                                        is SidebarNavRow.Item -> key(entry.destination.id) {
                                            SidebarItem(
                                                entry.destination.icon, entry.destination.label, entry.active, true,
                                                { selectRow(entry) }, requester(entry.destination.id),
                                                focusable = expanded && pageId == focusPage,
                                                onFocused = { markFocus(entry.destination.id, entry.systemId?.takeUnless { entry.opensPage }) },
                                                onWrapKey = { wrapFocus(entry.destination.id, it) },
                                                trailingChevron = entry.opensPage,
                                                stateDescription = if (entry.opensPage) opensSubmenu else null,
                                                logo = when {
                                                    entry.opensPage -> systems.firstOrNull { it.id == entry.systemId }?.system?.takeIf { it.hasBrandMark }
                                                    else -> null
                                                },
                                                testTag = "sidebar:${entry.destination.id}",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                    Spacer(Modifier.height(6.dp * uiScale))
                    // Bottom row = settings + menu editor for the current level: app settings on the
                    // main page, the system's own configuration on a system page (unless hidden).
                    val levelSystem = openGroup?.takeIf { konfigurationShown }
                    val settingsRow = if (levelSystem != null) {
                        MenuDestination("settings", configurationLabel, Icons.Filled.Settings, Destinations.SETTINGS, settingsCategory = levelSystem.id)
                    } else {
                        MenuDestination("settings", settingsLabel, Icons.Filled.Settings, Destinations.SETTINGS)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // On a system page the row carries that system's logo/icon, tinted like its text.
                        SidebarItem(levelSystem?.icon ?: Icons.Filled.Settings, settingsRow.label, currentRoute == Destinations.SETTINGS && levelSystem == null, true,
                            { navigate(settingsRow) }, requester("settings"),
                            focusable = expanded,
                            onFocused = { markFocus("settings") }, onWrapKey = { wrapFocus("settings", it) },
                            logo = levelSystem?.system?.takeIf { it.hasBrandMark },
                            testTag = "sidebar:settings",
                            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                                if (hasEditor && event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                    pendingFocus = "order"; true
                                } else false
                            })
                        if (hasEditor) {
                            SidebarItem(Icons.Filled.Tune, stringResource(R.string.sidebar_customize), false, false,
                                { showOrderDialog = true }, requester("order"),
                                focusable = expanded,
                                onFocused = { markFocus("order") }, onWrapKey = { wrapFocus("order", it) },
                                testTag = "sidebar:order",
                                modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    BackgroundPlaybackBar(
                        onOpen = onOpenPlayback, onPlayNext = onPlayNext,
                        compact = false,
                        onFocusOwnershipChanged = { miniPlayerHasFocus = it },
                        onFocusExit = { pendingFocus = "settings"; expanded = true },
                        onClose = ::closePanel,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp * uiScale),
                    )
                    }
                }
            }
        }
    }
    if (showOrderDialog) {
        val close = { showOrderDialog = false; pendingFocus = "order"; expanded = true }
        val level = editorSystem
        if (level != null) {
            MenuOrderDialog(
                systems = level.entries.map { MenuOrderItem(it.id, it.label, it.icon) },
                onApplyOrder = { order -> viewModel.applyEntryOrder(level.id, order) },
                onRename = { _, _ -> },
                onDismiss = close,
                dividersAfter = sidebarState.entryDividers[level.id].orEmpty().toSet(),
                onToggleDivider = { id -> viewModel.toggleEntryDivider(level.id, id) },
                renamable = false,
                title = systemLabel(level),
            )
        } else {
            val shownIds = systems.map { it.id }.toSet()
            MenuOrderDialog(
                // Every configured system; ones without a visible row right now are marked hidden.
                systems = menuOrderItems(sidebarState).map { it.copy(hidden = it.id !in shownIds) },
                onApplyOrder = { order -> viewModel.applySystemOrder(order) },
                onRename = viewModel::renameSystems,
                onDismiss = close,
                dividersAfter = sidebarState.dividersAfter,
                onToggleDivider = viewModel::toggleDivider,
            )
        }
    }

}

/** Thin user-placed separator, inset to the row text (pad 14 + icon 20 + gap 14 = 48 dp). */
@Composable
private fun SidebarDivider(uiScale: Float) {
    Box(Modifier.fillMaxWidth()
        .padding(start = (SIDEBAR_ROW_PAD_H + 20.dp + SIDEBAR_ROW_ICON_GAP) * uiScale, end = SIDEBAR_ROW_PAD_H * uiScale, top = 6.dp * uiScale, bottom = 6.dp * uiScale)
        .height(1.dp)
        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
}

/** The editor is reachable once there is more than one system to order. */
private fun menuEditorAvailable(shownSystems: Int): Boolean = shownSystems > 1

/** Top of the open panel: the Ambient word mark (no profile). */
@Composable
private fun SidebarPanelHead(uiScale: Float) {
    Row(
        Modifier.fillMaxWidth().height(SIDEBAR_BRAND_SLOT_HEIGHT * uiScale).padding(horizontal = 10.dp * uiScale),
        verticalAlignment = Alignment.CenterVertically,
        // Centred in the panel; follows the width while the panel grows open.
        horizontalArrangement = Arrangement.spacedBy(2.dp * uiScale, Alignment.CenterHorizontally),
    ) {
        BrandMark(size = SIDEBAR_BRAND_HEIGHT * uiScale, contentDescription = null)
        val wordAlpha = LocalSidebarLabelAlpha.current
        BrandWordmark(modifier = Modifier.graphicsLayer { alpha = wordAlpha() }, height = SIDEBAR_BRAND_HEIGHT * uiScale, contentDescription = null)
    }
}

/** System page header: back arrow + system name, a focusable row that returns to the main menu. */
@Composable
private fun SidebarPageHeader(
    label: String,
    uiScale: Float,
    focusRequester: FocusRequester,
    focusable: Boolean,
    onFocused: () -> Unit,
    onWrapKey: (Key) -> Boolean,
    onBack: () -> Unit,
) {
    SidebarItem(Icons.AutoMirrored.Filled.ArrowBack, label, false, true, onBack, focusRequester,
        focusable = focusable, onFocused = onFocused, onWrapKey = onWrapKey, testTag = "sidebar:$PAGE_BACK_ID")
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp * uiScale, vertical = 4.dp * uiScale).height(1.dp).background(com.ambient.tv.ui.theme.AppOutline))
}


@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    showLabel: Boolean,
    onSelect: () -> Unit,
    focusRequester: FocusRequester,
    focusable: Boolean = true,
    onFocused: () -> Unit = {},
    modifier: Modifier = Modifier,
    onWrapKey: (Key) -> Boolean = { false },
    trailingChevron: Boolean = false,
    logo: com.ambient.tv.ui.common.MediaSystem? = null,
    stateDescription: String? = null,
    testTag: String? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val uiSounds = LocalUiSoundPlayer.current
    val uiScale = rememberUhdScale()
    val menuMotionEnabled = LocalPerformanceProfile.current.allowSpringFocus
    val activate = com.ambient.tv.ui.sound.rememberUiSoundAction(onClick = onSelect)
    // Focused row: solid white pill, dark text and icon. Active row: a faint white wash.
    val scheme = MaterialTheme.colorScheme
    val hdrHeadroom = com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f
    val fill by androidx.compose.animation.animateColorAsState(
        when {
            // Plex-style white focus pill, lifted in HDR UI like focused tiles (MediaTile).
            isFocused -> com.ambient.tv.ui.display.uiHdrHighlight(scheme.onSurface, hdrHeadroom)
            selected -> androidx.compose.ui.graphics.lerp(scheme.surface, scheme.primary, .16f)
            else -> scheme.surface.copy(alpha = 0f)
        },
        animationSpec = if (menuMotionEnabled) androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing) else androidx.compose.animation.core.snap(),
        label = "sidebarItemFill",
    )
    val content by androidx.compose.animation.animateColorAsState(
        if (isFocused) scheme.background else scheme.onSurface,
        animationSpec = if (menuMotionEnabled) androidx.compose.animation.core.tween(160) else androidx.compose.animation.core.snap(),
        label = "sidebarItemContent",
    )
    // Small, bounce-free lift on focus; graphicsLayer only, so no relayout.
    val lift by androidx.compose.animation.core.animateFloatAsState(
        if (isFocused && menuMotionEnabled) 1.02f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 800f),
        label = "sidebarItemLift",
    )
    Row(
        modifier = modifier
            .then(if (showLabel) Modifier.fillMaxWidth() else Modifier.wrapContentWidth())
            .sizeIn(minWidth = 40.dp * uiScale, minHeight = SIDEBAR_ROW_MIN_HEIGHT * uiScale)
            .activateOnTvKey(onClick = activate)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> onWrapKey(event.key)
                    else -> false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    uiSounds?.playFocus()
                    onFocused()
                }
            }
            .focusProperties { canFocus = focusable }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics(mergeDescendants = true) {
                // TalkBack: system rows announce that they open a sub-menu.
                if (stateDescription != null) this.stateDescription = stateDescription
            }
            .focusable()
            .pointerInput(activate) { detectTapGestures(onTap = { activate() }) }
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(RoundedCornerShape(LocalUiTokens.current.controlRadius * uiScale))
            .background(fill)
            .padding(horizontal = SIDEBAR_ROW_PAD_H * uiScale, vertical = 6.dp * uiScale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SIDEBAR_ROW_ICON_GAP * uiScale),
    ) {
        // Active (not focused) rows carry the accent on their icon, like selected rows elsewhere.
        val iconTint = if (selected && !isFocused) com.ambient.tv.ui.theme.accessibleAccent(scheme.primary, scheme.surface) else content
        if (logo != null) SystemLogo(logo, size = 20.dp * uiScale, tint = iconTint)
        else Icon(icon, contentDescription = if (showLabel) null else label, modifier = Modifier.size(20.dp * uiScale), tint = iconTint)
        if (showLabel) {
            val labelAlpha = LocalSidebarLabelAlpha.current
            MarqueeTitle(
                text = label,
                focused = isFocused,
                style = SIDEBAR_ROW_TEXT,
                color = content,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = labelAlpha() },
            )
            if (trailingChevron) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    Modifier.size(18.dp * uiScale).graphicsLayer { alpha = labelAlpha() * 0.7f }, tint = content)
            }
        }
    }
}

/** Register on focus acquisition so this callback follows the current destination's handlers. */
@Composable
private fun rememberSidebarBackRegistration(): ((() -> Unit)?) -> Unit {
    val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val callback = remember(dispatcher) {
        object : androidx.activity.OnBackPressedCallback(false) {
            var action: (() -> Unit)? = null
            override fun handleOnBackPressed() { action?.invoke() }
        }
    }
    DisposableEffect(callback) { onDispose { callback.remove() } }
    return remember(dispatcher, callback) {
        { action ->
            callback.remove()
            callback.action = action
            callback.isEnabled = action != null
            if (action != null) dispatcher?.addCallback(callback)
        }
    }
}

/** Files sub-pages: shown as tabs on the Files pages and as the panel's Files entry. */
@Composable
internal fun filesMenuDestinations(state: SidebarUiState): List<MenuDestination> = listOf(
    MenuDestination("files:browse", stringResource(R.string.files_browse), Icons.Filled.Folder, Destinations.FILES),
) + state.networkFileSources.map { source ->
    MenuDestination("files:source:${source.id}", offlineLabel(source.name, "files:${source.id}" in state.offlineSystems),
        Icons.Filled.Storage, Destinations.fileSource(source.id))
} + listOf(
    MenuDestination("files:favorites", stringResource(R.string.files_favorites), Icons.Filled.Favorite, Destinations.FILE_FAVORITES),
    MenuDestination("files:recent", stringResource(R.string.files_recent), Icons.Filled.History, Destinations.FILE_RECENT),
    MenuDestination("files:continue", stringResource(R.string.files_continue), Icons.Filled.PlayArrow, Destinations.FILE_CONTINUE),
)

@Composable
internal fun dlnaMenuDestinations(state: SidebarUiState): List<MenuDestination> =
    listOf(MenuDestination("dlna:manage", stringResource(R.string.dlna_servers), Icons.Filled.Settings, Destinations.DLNA)) +
        state.dlnaServers.map { server -> MenuDestination("dlna:${server.id}", offlineLabel(server.name, "dlna:${server.id}" in state.offlineSystems),
            Icons.Filled.Storage, Destinations.dlnaServer(server.id)) }


@Composable
internal fun providerMenuDestinations(state: SidebarUiState, system: String): List<MenuDestination> {
    val sections = when (system) {
        "plex" -> state.sections
        "jellyfin" -> state.jellyfinSections
        "emby" -> state.embySections
        else -> return emptyList()
    }
    val home = when (system) {
        "plex" -> Destinations.PLEX
        "jellyfin" -> Destinations.JELLYFIN
        else -> Destinations.EMBY
    }
    val liveTvLabels = listOf(
        Triple("live", stringResource(R.string.tvh_live), liveTvMenuIcon("live")),
        Triple("guide", stringResource(R.string.tvh_guide), liveTvMenuIcon("guide")),
        Triple("recordings", stringResource(R.string.tvh_recordings), liveTvMenuIcon("recordings")),
        Triple("timers", stringResource(R.string.tvh_timers), liveTvMenuIcon("timers")),
    )
    fun tvEntries(system: String): List<MenuDestination> {
        val sources = state.liveTvSources.filter { it.source.system == system }
        return sources.flatMap { available -> liveTvLabels.filter { (tab, _, _) ->
            when (tab) { "guide" -> available.capabilities.guide; "recordings" -> available.capabilities.recordings; "timers" -> available.capabilities.timers; else -> true }
        }.map { (tab, label, icon) ->
            MenuDestination("tv:${available.source.id}:$tab", if (sources.size > 1) "${available.source.name} · $label" else label,
                icon, Destinations.liveTvSection(tab, available.source.id))
        } }
    }
    return listOf(MenuDestination("$system:home", stringResource(R.string.nav_overview), Icons.Filled.Home, home)) +
        sections.map { MenuDestination("$system:${it.key}", it.title, librarySectionIcon(it.type), Destinations.section(it.key)) } +
        tvEntries(system)
}

/** Keeps one row of space above/below the focused row while the panel scrolls. */
private class SidebarScrollSpec(private val padPx: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        sidebarScrollDistance(offset, size, containerSize, padPx)
}

/** Label opacity while the panel opens/closes (labels follow the width, not lead it). */
private val LocalSidebarLabelAlpha = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 1f } }

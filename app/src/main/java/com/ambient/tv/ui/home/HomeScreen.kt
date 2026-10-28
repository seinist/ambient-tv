package com.ambient.tv.ui.home

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.common.requestHeaders
import coil3.request.crossfade
import com.ambient.tv.tvheadend.domain.TvhChannel
import androidx.activity.compose.BackHandler
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import com.ambient.tv.ui.common.glideToItem
import com.ambient.tv.ui.common.ExplicitRowScrollSpec
import com.ambient.tv.ui.common.TvBringIntoViewSpec
import com.ambient.tv.ui.common.RowColumnFocus
import com.ambient.tv.ui.common.rowColumnFocusNavigation
import com.ambient.tv.ui.common.rowColumnFocusTarget
import androidx.compose.ui.platform.LocalContext
import com.ambient.tv.ui.common.displayTitle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ambient.tv.ui.performance.LocalPerformanceProfile

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.ambient.tv.ui.common.rememberBrowseListState as rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.HomeHeroStyle
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.SoftViewChange
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.acceleratedDpadNavigation
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.ContentRatingBadge
import com.ambient.tv.ui.common.RatingScoreBadge
import com.ambient.tv.ui.common.ContentRow
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.SkeletonRow
import com.ambient.tv.ui.common.StreamPropertyRow
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.rememberRowCenteringState
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.ScreensaverArtworkRegistry
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.formatDuration
import com.ambient.tv.ui.common.sanitizeUiErrorMessage
import com.ambient.tv.ui.common.rememberArtworkSampledAccent
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

@Composable
fun HomeScreen(
    focusStateKey: String = "home",
    onOpenSettings: () -> Unit,
    /** Opens the system picker (initial setup) to add a first system. */
    onAddSystem: () -> Unit = onOpenSettings,
    onItemClick: (String) -> Unit,
    onWatchLiveChannel: (TvhChannel) -> Unit = {},
    onOpenHomeContent: (HomeRowDefinition) -> Unit = {},
    /** System overview: the app home screen. Unused on the app home itself. */
    onOpenAppHome: () -> Unit = {},
    /** Root Back: focus sidebar like Files/Section. */
    onBack: (() -> Unit)? = null,
    plexOnly: Boolean = false,
    jellyfinOnly: Boolean = false,
    embyOnly: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val homeHeroStyleKey = viewModel.homeHeroStyleKey
    val homeHeroIntensityPercent = viewModel.homeHeroIntensityPercent
    val homeHeroSpreadKey = viewModel.homeHeroSpreadKey
    val focusSnapshot = viewModel.focusSnapshot()
    val openItem: (String) -> Unit = remember(viewModel, onItemClick) {
        { key -> viewModel.captureFocusForReturn(); onItemClick(key) }
    }
    val watchLiveChannel: (TvhChannel) -> Unit = remember(viewModel, onWatchLiveChannel) {
        { channelId -> viewModel.captureFocusForReturn(); onWatchLiveChannel(channelId) }
    }
    val openHomeContent: (HomeRowDefinition) -> Unit = remember(viewModel, onOpenHomeContent) {
        { row -> viewModel.captureFocusForReturn(); onOpenHomeContent(row) }
    }
    val openSettings: () -> Unit = remember(viewModel, onOpenSettings) {
        { viewModel.captureFocusForReturn(); onOpenSettings() }
    }
    val refreshGeneration by viewModel.refreshGeneration.collectAsState()
    // Keep entrance work separate from the pinned hero overlap. The list is drawn once into
    // a display list; only the narrow top overlap uses an offscreen alpha mask.
    val entranceProgressProvider: () -> Float = remember { { 1f } }
    val parkInitialFocus = when (val state = uiState) {
        HomeUiState.LoadingLibrary, is HomeUiState.Waking -> true
        is HomeUiState.Loaded -> state.initialFocusTarget == HomeInitialFocusTarget.RESOLVING ||
            (state.loading && (state.rowKeys.isEmpty() || state.initialFocusTarget != HomeInitialFocusTarget.NONE))
        else -> false
    }
    ReportSidebarContentReady(!parkInitialFocus)
    val systemOverview = plexOnly || jellyfinOnly || embyOnly
    var showOverviewOptions by remember { mutableStateOf(false) }
    val overviewViewMode by viewModel.overviewViewMode.collectAsState()
    val overviewOptionsFocus = remember { FocusRequester() }
    if (onBack != null) {
        BackHandler(enabled = !showOverviewOptions) {
            viewModel.captureFocusForReturn()
            onBack()
        }
    }
    BackHandler(enabled = showOverviewOptions) { showOverviewOptions = false }
    LaunchedEffect(plexOnly, jellyfinOnly, embyOnly, focusStateKey) {
        viewModel.start(
            plexOnly = plexOnly,
            jellyfinOnly = jellyfinOnly,
            embyOnly = embyOnly,
            focusStateKey = focusStateKey,
        )
    }
    val entryRefreshGeneration = remember { refreshGeneration }
    LaunchedEffect(refreshGeneration) {
        if (refreshGeneration != entryRefreshGeneration) viewModel.refresh()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel.autoRefreshIntervalSeconds) {
        val seconds = viewModel.autoRefreshIntervalSeconds
        if (seconds > 0) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(seconds * 1_000L)
                viewModel.refresh()
            }
        }
    }
    LaunchedEffect(uiState) {
        val loaded = uiState as? HomeUiState.Loaded ?: return@LaunchedEffect
        ScreensaverArtworkRegistry.offerAll(
            loaded.rows.asSequence().flatMap { it.items.asSequence() }.take(20).map { it.landscapeArtUrl }.asIterable(),
        )
        ScreensaverArtworkRegistry.offerAll((loaded.liveTvItems + loaded.recentTvItems).map { it.event?.imageUrl ?: it.channel.iconUrl })
    }
    val sharedBackgroundArtwork = com.ambient.tv.ui.common.LocalHomeBackgroundArtwork.current
    LaunchedEffect(viewModel.heroContent, sharedBackgroundArtwork) {
        viewModel.heroContent.collect { hero ->
            sharedBackgroundArtwork?.value = hero
            ScreensaverArtworkRegistry.offer(hero?.artworkUrl, hero?.thumbnailUrl)
        }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(15_000); viewModel.retryOffline() }
        }
    }

    // The overview's focus backdrop runs behind its header too, like the library pages: no dark
    // strip above the rows, the header actions sit on the artwork beside the title.
    val overviewRowsScrolling = remember { MutableStateFlow(false) }
    Box(Modifier.fillMaxSize()) {
    if (systemOverview && !LocalHandheldUi.current) HomeFocusBackdrop(viewModel.heroContent, overviewRowsScrolling)
    Column(Modifier.fillMaxSize()) {
        if (!systemOverview) com.ambient.tv.update.UpdateHomeCard()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        // The actions and clock stay put while loading and loaded, floating on the hero's title
        // line: the hero starts at the screen top like the app home's.
        if (systemOverview) {
            SystemOverviewHeader(
                system = when {
                    jellyfinOnly -> com.ambient.tv.ui.common.MediaSystem.JELLYFIN
                    embyOnly -> com.ambient.tv.ui.common.MediaSystem.EMBY
                    else -> com.ambient.tv.ui.common.MediaSystem.PLEX
                },
                onOpenHome = {
                    viewModel.captureFocusForReturn()
                    onOpenAppHome()
                },
                onOpenOptions = { showOverviewOptions = true },
                optionsModifier = Modifier.focusRequester(overviewOptionsFocus),
                modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
            )
        }
            when (val state = uiState) {
                HomeUiState.NotConfigured -> NotConfiguredDialog(onAddSystem)
                // Connection progress is presented by the global, non-focusable notification host.
                HomeUiState.LoadingLibrary, is HomeUiState.Waking -> LoadingRows()
                is HomeUiState.Loaded -> SoftViewChange(
                    if (systemOverview) overviewViewMode else Unit,
                    Modifier.fillMaxSize(),
                ) {
                    LibraryContent(
                        state = state,
                        heroContent = viewModel.heroContent,
                        homeHeroStyleKey = homeHeroStyleKey,
                        homeHeroIntensityPercent = homeHeroIntensityPercent,
                        homeHeroSpreadKey = homeHeroSpreadKey,
                        focusSnapshot = focusSnapshot,
                        onItemClick = openItem,
                        onItemFocused = viewModel::onItemFocused,
                        onOpenSettings = openSettings,
                        onWatchLiveChannel = watchLiveChannel,
                        onLiveTvFocused = viewModel::onLiveTvFocused,
                        onShortcutFocused = viewModel::onShortcutFocused,
                        onOpenHomeContent = openHomeContent,
                        tvheadendImageHeaders = viewModel.tvheadendImageHeaders,
                        entranceProgress = entranceProgressProvider,
                        externalBackdropScrolling = if (systemOverview) overviewRowsScrolling else null,
                    )
                }
                is HomeUiState.Offline -> Centered {
                    ServerOfflineContent(state.serverName, viewModel::retry, onOpenSettings)
                }
                is HomeUiState.Error -> Centered {
                    ConnectionErrorContent(state.message, viewModel::retry, onOpenSettings)
                }
                is HomeUiState.WakeTimedOut -> Centered {
                    WakeTimeoutContent(state.serverName, viewModel::retry, onOpenSettings)
                }
            }

            com.ambient.tv.ui.common.OfflineBrowseNotice(viewModel.availability, viewModel.browseProviders, Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
    }
        // The app home has no header row: the clock floats where the library pages put it.
        if (!systemOverview && !LocalHandheldUi.current && com.ambient.tv.ui.common.LocalClockSettings.current.onHome) {
            com.ambient.tv.ui.common.HeaderClock(
                Modifier.align(Alignment.TopEnd).padding(top = com.ambient.tv.ui.common.PageHeaderTopTv, end = screenGutter()).zIndex(2f),
            )
        }
        if (showOverviewOptions) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
            Box(Modifier.align(Alignment.TopEnd)) {
                com.ambient.tv.ui.common.ViewOptionsPanel(
                    showDensity = false,
                    hint = stringResource(R.string.library_view),
                    views = listOf(
                        com.ambient.tv.ui.common.ViewOption(HomeRowDefinition.DISPLAY_POSTER, stringResource(R.string.settings_home_display_poster), com.ambient.tv.ui.common.ViewPreview.POSTER),
                        com.ambient.tv.ui.common.ViewOption(HomeRowDefinition.DISPLAY_LANDSCAPE, stringResource(R.string.settings_home_display_landscape), com.ambient.tv.ui.common.ViewPreview.LANDSCAPE),
                    ),
                    selectedView = overviewViewMode,
                    onView = viewModel::setOverviewViewMode,
                    gridColumns = 6,
                    listRows = 6,
                    onGridColumns = { _ -> },
                    onListRows = { _ -> },
                    onClose = {
                        showOverviewOptions = false
                        runCatching { overviewOptionsFocus.requestFocus() }
                    },
                )
            }
        }
    }
}

@Composable
private fun SystemOverviewHeader(
    system: com.ambient.tv.ui.common.MediaSystem,
    onOpenHome: () -> Unit,
    onOpenOptions: () -> Unit,
    optionsModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()
        // The header floats over the hero, so no row lies in the focus search's beam below it:
        // Down hands focus to the rows' remembered tile directly.
        .onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
            com.ambient.tv.ui.navigation.SidebarContentEntry.enter?.invoke() == true
        }
        .padding(start = screenGutter(), end = screenGutter(), top = com.ambient.tv.ui.common.pageHeaderTop(24.dp), bottom = 10.dp)) {
        com.ambient.tv.ui.common.PageHeader(actions = { OverviewActions(onOpenHome, onOpenOptions, optionsModifier) }) {
            // The hero's title shares this line on TV; handheld has no such hero.
            if (LocalHandheldUi.current) com.ambient.tv.ui.common.SystemPageTitle(system)
        }
    }
}

@Composable
private fun RowScope.OverviewActions(onOpenHome: () -> Unit, onOpenOptions: () -> Unit, optionsModifier: Modifier) {
    com.ambient.tv.ui.common.TvChip(stringResource(R.string.nav_home), icon = Icons.Filled.Home, iconOnly = true, onClick = onOpenHome)
    com.ambient.tv.ui.common.TvChip(stringResource(R.string.action_view), modifier = optionsModifier, icon = Icons.Filled.Tune, iconOnly = true, onClick = onOpenOptions)
}

/** Focus previews have their own state/draw layer, so their crossfade never invalidates rows. */
@Composable
internal fun HomeFocusBackdrop(heroContent: StateFlow<HomeHeroContent?>, rowsScrolling: StateFlow<Boolean> = RowsNeverScrolling) {
    if (com.ambient.tv.ui.player.LocalVisibleBackgroundVideo.current || !com.ambient.tv.ui.common.LocalFocusArtworkBackdrop.current) return
    var displayed by remember(heroContent) { mutableStateOf(heroContent.value) }
    LaunchedEffect(heroContent) {
        heroContent.collectLatest { hero ->
            // Do not decode every intermediate backdrop while a direction is held, and never
            // start the full-screen decode and fade while the rows glide to a new row.
            delay(250)
            awaitRowScrollSettled(rowsScrolling)
            displayed = hero
        }
    }
    val hero = displayed
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().graphicsLayer()) {
        com.ambient.tv.ui.common.CrossfadeBackdropImage(
            model = hero?.artworkUrl?.takeUnless { hero.artworkIsLogo },
            contentDescription = null,
            headers = hero?.imageHeaders.orEmpty(),
            modifier = Modifier.fillMaxSize(),
            // Under the 78-94 % scrim half resolution looks the same with a quarter of the pixels.
            decodeSize = HomeBackdropDecodeSize,
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to background.copy(alpha = 0.78f),
            0.55f to background.copy(alpha = 0.82f),
            1f to background.copy(alpha = 0.94f),
        )))
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun NotConfiguredDialog(onAddSystem: () -> Unit) {
    AppDialog(
        onDismiss = onAddSystem,
        title = stringResource(R.string.home_not_configured),
        hint = stringResource(R.string.home_not_configured_body),
        footer = {
            TvActionButton(
                text = stringResource(R.string.settings_add_system),
                onClick = onAddSystem,
            )
        },
    )
}

@Composable
private fun LoadingRows() {
    // Mirror the final Home composition while data is loading so the screen does not
    // reflow from a centered spinner into a completely different TV layout.
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(homeHeroHeight())
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = screenGutter(), bottom = HomeHeroFadeTail),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.widthIn(min = 320.dp, max = 420.dp).height(26.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(999.dp)))
                Box(Modifier.widthIn(min = 210.dp, max = 270.dp).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp)))
            }
        }
        LazyColumn(
            allowUpExit = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 36.dp),
        ) {
            item { SkeletonRow(title = "", modifier = Modifier.padding(top = 8.dp), style = com.ambient.tv.ui.common.MediaTileStyle.POSTER, itemCount = 7) }
            items(2) { SkeletonRow(title = "", modifier = Modifier.padding(top = 8.dp), style = com.ambient.tv.ui.common.MediaTileStyle.POSTER, itemCount = 7) }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun LibraryContent(
    state: HomeUiState.Loaded,
    heroContent: StateFlow<HomeHeroContent?>,
    homeHeroStyleKey: String,
    homeHeroIntensityPercent: Int,
    homeHeroSpreadKey: String,
    focusSnapshot: HomeFocusSnapshot,
    onItemClick: (String) -> Unit,
    onItemFocused: (String, MediaItem) -> Unit,
    onOpenSettings: () -> Unit,
    onWatchLiveChannel: (TvhChannel) -> Unit,
    onLiveTvFocused: (HomeLiveTvItem) -> Unit,
    onShortcutFocused: (HomeRowDefinition) -> Unit,
    onOpenHomeContent: (HomeRowDefinition) -> Unit,
    tvheadendImageHeaders: Map<String, String>,
    entranceProgress: () -> Float,
    /** Set when the caller draws the focus backdrop behind its own header; rows report scrolling to it. */
    externalBackdropScrolling: MutableStateFlow<Boolean>? = null,
    /** System overview: its page actions on the hero's title line instead of a header row. */
    heroTitleActions: (@Composable RowScope.() -> Unit)? = null,
) {
    // Keep partial rows out of the focus tree until the initial library load settles.
    if (state.loading && (state.rowKeys.isEmpty() || state.initialFocusTarget != HomeInitialFocusTarget.NONE)) {
        LoadingRows()
        return
    }
    if (state.rowKeys.isEmpty()) {
        Centered {
            StatePanel(
                title = stringResource(R.string.home_library_empty),
                body = stringResource(R.string.home_library_empty_body),
                actionLabel = stringResource(R.string.nav_settings),
                onAction = onOpenSettings,
            )
        }
        return
    }

    val performance = LocalPerformanceProfile.current
    // Fokus eases from the hero down onto the parked row each time the row changes.
    // On a TV the focused row stays parked; on a phone the light settles at the bottom of the hero.
    val focusTravel = remember { Animatable(0.24f) }
    val parkedTravel = if (LocalHandheldUi.current) 0.88f else 0.52f
    LaunchedEffect(focusSnapshot.rowId, parkedTravel) {
        val row = focusSnapshot.rowId
        val target = if (row == null) 0.24f else parkedTravel
        if (row != null && focusTravel.value > 0.40f) focusTravel.animateTo(0.32f, tween(220))
        focusTravel.animateTo(target, tween(860, easing = FastOutSlowInEasing))
    }
    val travel = { focusTravel.value }
    if (LocalHandheldUi.current) {
        LazyColumn(
            allowUpExit = true,
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item("mobile-hero") {
                HomeHero(
                    heroContent, homeHeroStyleKey, homeHeroIntensityPercent, homeHeroSpreadKey, entranceProgress,
                    focusTravel = travel,
                )
            }
            items(state.rowKeys, key = { it }) { rowKey ->
                when {
                    rowKey in state.tvRows -> {
                        val recent = rowKey.startsWith("recent-tv")
                        HomeLiveTvRow(
                            items = state.tvRows[rowKey]?.items.orEmpty(),
                            title = state.tvRows[rowKey]?.title.orEmpty(),
                            imageHeaders = tvheadendImageHeaders, onWatch = onWatchLiveChannel,
                            requestInitialFocus = false,
                            onItemFocused = onLiveTvFocused,
                            onOpenChannels = if (recent) {
                                { onOpenHomeContent(homeTvShortcut(rowKey, "live")) }
                            } else null,
                        )
                    }
                    rowKey.startsWith("shortcut:") -> state.shortcuts.firstOrNull { "shortcut:${it.id}" == rowKey }?.let {
                        HomeShortcutRow(it, onOpenHomeContent, onShortcutFocused, requestInitialFocus = false)
                    }
                    else -> state.rows.firstOrNull { it.id == rowKey }?.let { row ->
                        ContentRow(row = row, onItemClick = { onItemClick(it.ratingKey) }, onItemFocused = { onItemFocused(row.id, it) })
                    }
                }
            }
        }
        return
    }

    // Rows always move behind the pinned hero. Full mode uses a true alpha mask; reduced mode
    // gets the same visual handoff from the hero's cheap opaque-to-transparent overlay.
    val maskRows = performance.allowRowFadeSaveLayer
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Snapshot of rowListState.isScrollInProgress for the hero/backdrop collectors, which
        // must not read composition state (BrowseWorkBudget.scrolling is not observable).
        val rowsScrolling = externalBackdropScrolling ?: remember { MutableStateFlow(false) }
        if (externalBackdropScrolling == null) HomeFocusBackdrop(heroContent, rowsScrolling)
        HomeHeroAmbient(
            heroContent, homeHeroStyleKey, homeHeroIntensityPercent, homeHeroSpreadKey, Modifier.fillMaxSize(), rowsScrolling,
            focusTravel = travel,
        )
        // Only restore focus/scroll once per screen entry (e.g. returning from Detail),
        // captured from the key present when this composable first entered composition.
        // Rebinding this to the live state.focusedRatingKey instead would re-fire on every
        // ordinary focus move, and - whenever the same item also appears in another row
        // (e.g. "Recently Added" and "Continue Watching" both listing it) - would yank both
        // scroll position and D-pad focus into that other row mid-browse.
        val initialFocusKey = remember {
            focusSnapshot.ratingKey?.takeIf { key ->
                state.rows.any { row -> row.items.any { key in it.allRatingKeys } }
            }
        }
        val initialFocusRowId = remember { focusSnapshot.rowId }
        var rowsHaveFocus by remember { mutableStateOf(false) }
        val refreshRecovery = remember(state.focusRecovery) { state.focusRecovery.takeIf { rowsHaveFocus } }

        // A fresh entry uses the first target from the completed initial load.
        var restoreArmed by remember {
            mutableStateOf(initialFocusKey != null && state.initialFocusTarget == HomeInitialFocusTarget.NONE)
        }
        val restoreRowIndex = remember(state.rows, initialFocusRowId, initialFocusKey) {
            state.rows.indexOfFirst { row ->
                row.id == initialFocusRowId && initialFocusKey != null && row.items.any { initialFocusKey in it.allRatingKeys }
            }.takeIf { it >= 0 }
                ?: state.rows.indexOfFirst { row -> initialFocusKey != null && row.items.any { initialFocusKey in it.allRatingKeys } }
        }
        fun contentListIndex(index: Int): Int = state.rowKeys.indexOf(state.rows.getOrNull(index)?.id).coerceAtLeast(0)
        val heroHeight = homeHeroHeight()
        // Same line the first row already occupies. Later rows, including a short live row at
        // the bottom, must be able to reach it or the hero mask cuts them in half.
        val rowPark = homeRowParkOffset(heroHeight)
        val listViewport = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val focusedItemStillExists = focusSnapshot.ratingKey == null || state.rows.any { row ->
            row.items.any { item -> focusSnapshot.ratingKey in item.allRatingKeys }
        }
        // While the selection from before Detail is still waiting to be restored, a transient
        // row list without it must not pull focus to the first tile instead.
        val missingFocusFallback = if (!focusedItemStillExists && !restoreArmed) {
            state.rows.firstNotNullOfOrNull { row -> row.items.firstOrNull()?.let { row.id to it.ratingKey } }
        } else {
            null
        }
        val rowListState = rememberLazyListState(
            initialFirstVisibleItemIndex = if (restoreArmed) contentListIndex(restoreRowIndex) else 0,
        )
        LaunchedEffect(rowListState) {
            snapshotFlow { rowListState.isScrollInProgress }.collect { rowsScrolling.value = it }
        }
        LaunchedEffect(refreshRecovery) {
            val index = state.rowKeys.indexOf(refreshRecovery?.rowId)
            if (index >= 0 && rowListState.layoutInfo.visibleItemsInfo.none { it.index == index }) rowListState.scrollToItem(index)
        }
        // Every row type is parked directly below the pinned hero, where the first row rests;
        // a plain bring-into-view would count the hero-covered top as visible and park the
        // row's heading behind the banner.
        val rowCentering = rememberRowCenteringState(rowListState)
        val columnFocus = com.ambient.tv.ui.common.rememberRowColumnFocus()
        // Survives a live row leaving the lazy list, so the first entry of this visit
        // still starts on card 0 after the row is scrolled away and back.
        val liveRowEntry = remember { LiveRowEntry() }
        // Right from the sidebar returns to the exact tile left, not the top-left focusable
        // the plain container request would pick.
        val enterContent: () -> Boolean? = remember(columnFocus) { { columnFocus.restoreLast() } }
        androidx.compose.runtime.DisposableEffect(enterContent) {
            com.ambient.tv.ui.navigation.SidebarContentEntry.enter = enterContent
            onDispose {
                if (com.ambient.tv.ui.navigation.SidebarContentEntry.enter === enterContent) {
                    com.ambient.tv.ui.navigation.SidebarContentEntry.enter = null
                }
            }
        }
        val glideWrap = !com.ambient.tv.ui.performance.LocalPerformanceProfile.current.reducedEffects
        val centeredRowKey = remember { MutableStateFlow<String?>(null) }
        LaunchedEffect(restoreArmed, state.rowKeys) {
            if (restoreArmed && restoreRowIndex in state.rows.indices) {
                rowListState.scrollToItem(contentListIndex(restoreRowIndex))
            }
        }
        LaunchedEffect(state.initialFocusTarget, state.rowKeys.firstOrNull()) {
            if (state.initialFocusTarget != HomeInitialFocusTarget.NONE && rowListState.firstVisibleItemIndex != 0) {
                rowListState.scrollToItem(0)
            }
        }
        LaunchedEffect(centeredRowKey, state.rowKeys, rowListState) {
            // Row-entry events do not invalidate the whole Home composition.
            centeredRowKey.collectLatest { key ->
                val index = state.rowKeys.indexOf(key)
                val alreadyAtTop = index == 0 && rowListState.firstVisibleItemIndex == 0 &&
                    rowListState.firstVisibleItemScrollOffset == 0
                if (index >= 0 && !alreadyAtTop) {
                    rowCentering.centerItem(index, alignTop = true)
                }
            }
        }
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides ExplicitRowScrollSpec,
            com.ambient.tv.ui.common.LocalHeroRowTiles provides true,
        ) {
            LazyColumn(
                allowUpExit = true,
                state = rowListState,
                modifier = Modifier.onFocusChanged { rowsHaveFocus = it.hasFocus }.then(Modifier
                    .fillMaxSize()
                    .rowColumnFocusNavigation(
                        columnFocus,
                        scrollToTop = { rowListState.glideToItem(glideWrap, 0) },
                        scrollToBottom = { rowListState.glideToItem(glideWrap, state.rowKeys.lastIndex) },
                        scrollToRow = { rowKey ->
                            val index = state.rowKeys.indexOfFirst { it == rowKey }
                            if (index >= 0) rowCentering.centerItem(index, alignTop = true)
                        },
                    ) { state.rowKeys }
                    .clipToBounds()
                    .acceleratedDpadNavigation()
                    .fadeRowsBehindHomeHero(maskRows, heroHeight)
                    .graphicsLayer {
                        val progress = entrancePhase(entranceProgress(), 0.24f, 1f)
                        alpha = progress
                        translationY = (1f - progress) * 28.dp.toPx()
                    }),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = rowPark,
                    bottom = homeLastRowBottomPadding(listViewport, rowPark),
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.rowKeys, key = { it }, contentType = { rowKey ->
                    when {
                        rowKey in state.tvRows -> "live-tv-row"
                        rowKey.startsWith("shortcut:") -> "shortcut-row"
                        else -> "media-row"
                    }
                }) { rowKey ->
                    when {
                        rowKey in state.tvRows -> {
                            val recent = rowKey.startsWith("recent-tv")
                            HomeLiveTvRow(
                                items = state.tvRows[rowKey]?.items.orEmpty(),
                                title = state.tvRows[rowKey]?.title.orEmpty(),
                                imageHeaders = tvheadendImageHeaders,
                                onWatch = onWatchLiveChannel,
                                requestInitialFocus = rowKey == state.rowKeys.firstOrNull() && state.initialFocusTarget ==
                                    if (recent) HomeInitialFocusTarget.RECENT_TV else HomeInitialFocusTarget.LIVE_TV,
                                onItemFocused = { item ->
                                    restoreArmed = false
                                    centeredRowKey.value = rowKey
                                    onLiveTvFocused(item)
                                },
                                bringContextIntoView = false,
                                columnFocus = columnFocus,
                                rowKey = rowKey,
                                onOpenChannels = if (recent) {
                                    { onOpenHomeContent(homeTvShortcut(rowKey, "live")) }
                                } else null,
                                onOpenGuide = if (recent) null else {
                                    { onOpenHomeContent(homeTvShortcut(rowKey, "guide")) }
                                },
                                entry = liveRowEntry,
                            )
                        }
                        else -> if (rowKey.startsWith("shortcut:")) {
                            state.shortcuts.firstOrNull { "shortcut:${it.id}" == rowKey }?.let { shortcut ->
                                HomeShortcutRow(shortcut, onOpenHomeContent, { row ->
                                    restoreArmed = false
                                    centeredRowKey.value = rowKey
                                    onShortcutFocused(row)
                                },
                                    requestInitialFocus = rowKey == state.rowKeys.firstOrNull() && state.initialFocusTarget == HomeInitialFocusTarget.SHORTCUT,
                                    columnFocus = columnFocus, rowKey = rowKey)
                            }
                        } else {
                            val index = state.rows.indexOfFirst { it.id == rowKey }
                            state.rows.getOrNull(index)?.let { row ->
                                ContentRow(
                                    row = row,
                                    onItemClick = { item -> onItemClick(item.ratingKey) },
                                    onItemFocused = { item -> restoreArmed = false; onItemFocused(row.id, item) },
                                    onRowFocused = { centeredRowKey.value = rowKey },
                                    columnFocus = columnFocus,
                                    restoreRatingKey = when {
                                        refreshRecovery?.rowId == row.id -> refreshRecovery.ratingKey
                                        state.initialFocusTarget == HomeInitialFocusTarget.CONTENT ->
                                        focusSnapshot.ratingKey.takeIf { row.id == focusSnapshot.rowId }
                                        restoreArmed && index == restoreRowIndex -> initialFocusKey
                                        missingFocusFallback?.first == row.id -> missingFocusFallback.second
                                        else -> null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        HomeHero(
            heroContent = heroContent,
            heroStyleKey = homeHeroStyleKey,
            intensityPercent = homeHeroIntensityPercent,
            spreadKey = homeHeroSpreadKey,
            entranceProgress = entranceProgress,
            drawAmbient = false,
            rowsScrolling = rowsScrolling,
            height = heroHeight,
            titleActions = heroTitleActions,
            atScreenTop = true,
            // The overview's floating actions and clock, or the app home's clock.
            clockCorner = externalBackdropScrolling != null || com.ambient.tv.ui.common.LocalClockSettings.current.onHome,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f),
        )
    }
}

@Composable
private fun HomeShortcutRow(
    row: HomeRowDefinition,
    onOpen: (HomeRowDefinition) -> Unit,
    onFocused: (HomeRowDefinition) -> Unit,
    requestInitialFocus: Boolean = false,
    columnFocus: RowColumnFocus? = null,
    rowKey: String = "",
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(start = screenGutter(), bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.ambient.tv.ui.common.MediaSystem.fromKey(row.system)?.let { com.ambient.tv.ui.common.SystemLogo(it, size = 20.dp, tint = MaterialTheme.colorScheme.primary) }
            Text(row.displayTitle(LocalContext.current), style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.padding(horizontal = screenGutter()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvActionButton(
                text = when (row.displayMode) {
                    HomeRowDefinition.DISPLAY_POSTER -> "${row.displayTitle(LocalContext.current)}  •  ${stringResource(R.string.settings_home_display_poster)}"
                    HomeRowDefinition.DISPLAY_LIST -> "${row.displayTitle(LocalContext.current)}  •  ${stringResource(R.string.settings_home_display_list)}"
                    else -> row.displayTitle(LocalContext.current)
                },
                onClick = { onOpen(row) },
                modifier = Modifier.rowColumnFocusTarget(columnFocus, rowKey, row.id, soundsOnFocus = true).focusRequester(firstFocus).onFocusChanged { if (it.isFocused) onFocused(row) },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeLiveTvRow(
    items: List<HomeLiveTvItem>,
    title: String,
    imageHeaders: Map<String, String>,
    onWatch: (TvhChannel) -> Unit,
    requestInitialFocus: Boolean,
    onItemFocused: (HomeLiveTvItem) -> Unit,
    // False where the parent list centers the row itself; a second relocation would fight it.
    bringContextIntoView: Boolean = true,
    columnFocus: RowColumnFocus? = null,
    rowKey: String = "",
    /** First tile of a recently-watched row: opens the channel list. */
    onOpenChannels: (() -> Unit)? = null,
    /** First tile of the on-now row: opens the programme guide. Ignored when [onOpenChannels] is set. */
    onOpenGuide: (() -> Unit)? = null,
    /** TV only. Keeps the first entry of this Home visit on card 0. */
    entry: LiveRowEntry? = null,
) {
    val tv = !LocalHandheldUi.current
    val firstItemFocusRequester = remember { FocusRequester() }
    val leadingKey = when {
        onOpenChannels != null -> ChannelsTileKey
        onOpenGuide != null -> GuideTileKey
        else -> null
    }
    val channelKeys = remember(items) { items.map { "${it.channel.sourceId}:${it.channel.uuid}" } }
    // A saved landing on the last card is not a choice the user made. Drop it before the
    // row reads its start index, until they move left or right in the row.
    if (entry != null && columnFocus != null) entry.resetUnlessChosen(rowKey, columnFocus)
    val remembered = columnFocus?.rememberedItem(rowKey) as? String
    val entryIndex = when {
        entry == null || entry.chosen(rowKey) -> homeLiveRowEntryIndex(remembered, leadingKey, channelKeys)
        else -> 0
    }
    // While set, a positive bring-into-view is dropped. That distance flung the row to its
    // last card. Cleared on Left/Right, before focus moves, so the next card can scroll in.
    val pinStart = remember(rowKey) { mutableStateOf(entry != null && !entry.chosen(rowKey)) }
    val horizontalScrollSpec = if (!tv) LocalBringIntoViewSpec.current else remember(pinStart) {
        object : BringIntoViewSpec {
            @Suppress("OVERRIDE_DEPRECATION")
            override val scrollAnimationSpec = TvBringIntoViewSpec.scrollAnimationSpec

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                homeLiveRowFocusScroll(
                    if (pinStart.value) 0 else -1,
                    TvBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize),
                )
        }
    }
    val rowState = com.ambient.tv.ui.common.rememberBrowseRowState(
        initialFirstVisibleItemIndex = (entryIndex - 1).coerceAtLeast(0),
    )
    com.ambient.tv.ui.common.RegisterRowColumnFocus(columnFocus, rowKey, rowState,
        remember(channelKeys, leadingKey) { listOfNotNull(leadingKey) + channelKeys })
    // One clock per row moves every tile's progress bar each minute.
    val nowSeconds by androidx.compose.runtime.produceState(System.currentTimeMillis() / 1000L) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = System.currentTimeMillis() / 1000L
        }
    }
    var anchorPlaced by remember(rowKey) { mutableStateOf(false) }
    var rowHasFocus by remember(rowKey) { mutableStateOf(false) }
    LaunchedEffect(rowKey, channelKeys) {
        if (entry == null) return@LaunchedEffect
        snapshotFlow { Triple(rowHasFocus, anchorPlaced, rowState.firstVisibleItemIndex) }
            .collect { (focused, placed, first) ->
                if (!pinStart.value) return@collect
                if (first != 0) rowState.scrollToItem(0)
                // Focus that landed on a later card would scroll itself back to the end.
                if (!focused || !placed) return@collect
                withFrameNanos { }
                if (!pinStart.value || !rowHasFocus) return@collect
                runCatching { firstItemFocusRequester.requestFocus() }
            }
    }
    LaunchedEffect(requestInitialFocus, anchorPlaced, channelKeys) {
        if (entry == null || !requestInitialFocus || !anchorPlaced || !pinStart.value) return@LaunchedEffect
        if (items.isEmpty() && leadingKey == null) return@LaunchedEffect
        if (rowState.firstVisibleItemIndex != 0) rowState.scrollToItem(0)
        withFrameNanos { }
        if (!pinStart.value) return@LaunchedEffect
        runCatching { firstItemFocusRequester.requestFocus() }
    }
    val anchorModifier = Modifier
        .focusRequester(firstItemFocusRequester)
        .onPlaced { anchorPlaced = true }
    Column(
        Modifier
            .fillMaxWidth()
            // Same rhythm as ContentRow: 8 dp below the cards plus 8 dp above the next heading.
            .padding(vertical = 8.dp)
            .then(if (bringContextIntoView) Modifier.bringFocusedContextIntoView() else Modifier),
        // Matches ContentRow's heading-to-artwork gap: the header's 10 dp plus MediaTile's 8 dp gutter.
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.padding(horizontal = screenGutter()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.ambient.tv.ui.common.SystemLogo(com.ambient.tv.ui.common.MediaSystem.fromKey(items.firstOrNull()?.channel?.sourceId?.substringBefore(':').orEmpty()) ?: com.ambient.tv.ui.common.MediaSystem.TVHEADEND, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalScrollSpec) {
            LazyRow(
                state = rowState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = screenGutter()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = if (!tv) Modifier else Modifier
                    .onFocusChanged { rowHasFocus = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                        ) {
                            entry?.choose(rowKey)
                            pinStart.value = false
                        }
                        false
                    }
                    .focusProperties {
                        // Default search into this row picks a visible card at the far end.
                        onEnter = {
                            if (entry?.chosen(rowKey) != true &&
                                runCatching { firstItemFocusRequester.requestFocus() }.getOrDefault(false)
                            ) cancelFocusChange()
                        }
                    },
            ) {
                if (onOpenChannels != null) {
                    item(key = ChannelsTileKey) {
                        LiveShortcutTile(
                            label = stringResource(R.string.home_live_channels),
                            icon = Icons.Filled.LiveTv,
                            onClick = onOpenChannels,
                            modifier = Modifier
                                .rowColumnFocusTarget(columnFocus, rowKey, ChannelsTileKey)
                                .then(anchorModifier),
                        )
                    }
                } else if (onOpenGuide != null) {
                    item(key = GuideTileKey) {
                        LiveShortcutTile(
                            label = stringResource(R.string.tvh_guide),
                            icon = Icons.AutoMirrored.Filled.ViewList,
                            onClick = onOpenGuide,
                            modifier = Modifier
                                .rowColumnFocusTarget(columnFocus, rowKey, GuideTileKey)
                                .then(anchorModifier),
                        )
                    }
                }
                itemsIndexed(items, key = { _, item -> "${item.channel.sourceId}:${item.channel.uuid}" }) { index, item ->
                    val lazyIndex = index + if (leadingKey != null) 1 else 0
                    LiveChannelTile(
                        item = item,
                        nowSeconds = { nowSeconds },
                        onClick = { onWatch(item.channel) },
                        onFocused = { onItemFocused(item) },
                        modifier = Modifier
                            .rowColumnFocusTarget(columnFocus, rowKey, "${item.channel.sourceId}:${item.channel.uuid}")
                            .then(if (lazyIndex == 0) anchorModifier else Modifier),
                    )
                }
            }
        }
    }
}

private const val GuideTileKey = "live-tv-guide"
private const val ChannelsTileKey = "live-tv-channels"

/**
 * First entry of a live row in this Home visit. Until the user moves left or right,
 * a restored landing on the last card is dropped so the row starts at card 0.
 */
internal class LiveRowEntry {
    private val chosen = HashSet<String>()

    fun chosen(rowKey: String): Boolean = rowKey in chosen

    fun choose(rowKey: String) { chosen.add(rowKey) }

    fun resetUnlessChosen(rowKey: String, focus: RowColumnFocus) {
        if (rowKey !in chosen) focus.forget(rowKey)
    }
}

/** Lazy index of the card a live row should enter on. An unknown key starts at the first card. */
internal fun homeLiveRowEntryIndex(rememberedKey: String?, leadingKey: String?, channelKeys: List<String>): Int {
    if (rememberedKey == null) return 0
    if (leadingKey != null && rememberedKey == leadingKey) return 0
    val channel = channelKeys.indexOf(rememberedKey)
    if (channel < 0) return 0
    return channel + if (leadingKey != null) 1 else 0
}

/**
 * Card 0 is the start of the row. A positive distance while it is focused is the lazy row
 * scrolling that card to the far end before it has a real position.
 */
internal fun homeLiveRowFocusScroll(focusedIndex: Int, distance: Float): Float =
    if (focusedIndex == 0 && distance > 0f) 0f else distance

/** Home shortcut for one live-TV row. `recent-tv:plex` keeps the system; a bare key is TVHeadend. */
internal fun homeTvShortcut(rowKey: String, contentKey: String) = HomeRowDefinition(
    system = rowKey.substringAfter(':', HomeRowDefinition.SYSTEM_TVHEADEND),
    contentKey = contentKey,
)

/** Where the first Home row rests, and the padding that lets the last row reach that same line. */
internal fun homeRowParkOffset(heroHeight: Dp): Dp = heroHeight - HomeRowsLift + 12.dp

internal fun homeLastRowBottomPadding(viewport: Dp, parkOffset: Dp, minimum: Dp = 36.dp): Dp =
    (viewport - parkOffset).coerceAtLeast(minimum)

@Composable
private fun rememberLiveTileFocus(): Pair<androidx.compose.runtime.MutableState<Boolean>, Float> {
    val focused = remember { mutableStateOf(false) }
    val tokens = com.ambient.tv.ui.theme.LocalUiTokens.current
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused.value) tokens.focusScale else 1f, label = "liveTileScale")
    return focused to scale
}

@Composable
private fun liveTileWidth(): Dp = deviceDp(252.dp, 240.dp) * com.ambient.tv.ui.common.rememberUhdScale()

/** Channel logo on the home surface wash, the running programme in one line, and airing progress. */
@Composable
private fun LiveChannelTile(
    item: HomeLiveTvItem,
    nowSeconds: () -> Long,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (focusState, scale) = rememberLiveTileFocus()
    val focused = focusState.value
    val tokens = com.ambient.tv.ui.theme.LocalUiTokens.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(tokens.cardRadius)
    val logo = item.channel.iconUrl
    val scheme = MaterialTheme.colorScheme
    val event = item.event
    androidx.tv.material3.Surface(
        onClick = onClick,
        colors = com.ambient.tv.ui.common.transparentSurfaceColors(),
        scale = com.ambient.tv.ui.common.noSurfaceScale(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = shape),
        modifier = modifier
            .padding(vertical = 8.dp)
            .width(liveTileWidth())
            .aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .activateOnTvKey(onClick = onClick)
            .onFocusChanged {
                focusState.value = it.isFocused
                if (it.isFocused) onFocused()
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(liveTileBrush())
                .border(if (focused) 2.dp else 1.dp, if (focused) scheme.onSurface.copy(alpha = 0.90f) else scheme.onSurface.copy(alpha = 0.08f), shape),
        ) {
            logo?.let {
                ArtworkImage(
                    model = it,
                    title = item.channel.name,
                    contentScale = ContentScale.Fit,
                    headers = item.imageHeaders,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.46f).fillMaxHeight(0.42f).padding(bottom = 10.dp),
                )
            } ?: Text(item.channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.align(Alignment.Center))
            if (event != null) {
                LiveBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(scheme.background.copy(alpha = 0.72f))
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp + com.ambient.tv.ui.common.PlaybackStatusBarHeight),
                )
                com.ambient.tv.ui.common.PlaybackStatusBar(
                    progress = ((nowSeconds() - event.startEpochSeconds).toFloat() / event.durationSeconds.coerceAtLeast(1L)).coerceIn(0f, 1f),
                    trackColor = scheme.onSurface.copy(alpha = 0.16f),
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                )
            }
            val playbackSource = com.ambient.tv.companion.LocalPlaybackBrowseSources.current.live[item.channel.sourceId]
            if (playbackSource != null) {
                Box(Modifier.align(Alignment.TopStart).padding(7.dp)) {
                    com.ambient.tv.companion.PlaybackBadge(com.ambient.tv.companion.playbackIndicator(
                        com.ambient.tv.companion.CompanionMedia("live:channel", item.channel.uuid, playbackSource)))
                }
            }
        }
    }
}

/** Leading tile of a live row: same frame as the channel cards, icon and label only. */
@Composable
private fun LiveShortcutTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (focusState, scale) = rememberLiveTileFocus()
    val tokens = com.ambient.tv.ui.theme.LocalUiTokens.current
    val scheme = MaterialTheme.colorScheme
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(tokens.cardRadius)
    androidx.tv.material3.Surface(
        onClick = onClick,
        colors = com.ambient.tv.ui.common.transparentSurfaceColors(),
        scale = com.ambient.tv.ui.common.noSurfaceScale(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = shape),
        modifier = modifier
            .padding(vertical = 8.dp)
            .width(liveTileWidth())
            .aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .activateOnTvKey(onClick = onClick)
            .onFocusChanged { focusState.value = it.isFocused },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(liveTileBrush())
                .border(if (focusState.value) 2.dp else 1.dp, if (focusState.value) scheme.onSurface.copy(alpha = 0.90f) else scheme.onSurface.copy(alpha = 0.08f), shape),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.tv.material3.Icon(
                icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1)
        }
    }
}

/** Same wash as the home surfaces: accent only tints the top, then the card falls into the background. */
@Composable
private fun liveTileBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    return Brush.linearGradient(
        0f to scheme.primary.copy(alpha = 0.22f),
        0.45f to scheme.surfaceVariant,
        1f to scheme.background,
    )
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = "LIVE",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        color = scheme.onPrimary,
        maxLines = 1,
        modifier = modifier
            .background(scheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun HomeHero(
    heroContent: StateFlow<HomeHeroContent?>,
    heroStyleKey: String,
    intensityPercent: Int,
    spreadKey: String,
    entranceProgress: () -> Float,
    modifier: Modifier = Modifier,
    drawAmbient: Boolean = true,
    rowsScrolling: StateFlow<Boolean> = RowsNeverScrolling,
    height: Dp? = null,
    focusTravel: () -> Float = { 0.42f },
    /** Page actions on the right of the title line (system overview), outside the metadata crossfade. */
    titleActions: (@Composable RowScope.() -> Unit)? = null,
    /** The hero starts at the screen top: its title line sits level with the header corner. */
    atScreenTop: Boolean = false,
    /** False where the page header already shows the system logo (a library page). */
    showSystemBadge: Boolean = true,
    /** Something floats in the top-right corner (clock, page actions): the logo stays clear of it. */
    clockCorner: Boolean = false,
) {
    // Collect focus in effects: transient targets must not recompose the complete hero while
    // the lazy rows are busy bringing new cards into view.
    var metadataItem by remember(heroContent) { mutableStateOf(heroContent.value) }
    var artworkItem by remember(heroContent) { mutableStateOf(heroContent.value) }
    LaunchedEffect(heroContent) {
        heroContent.collectLatest { item ->
            if (metadataItem != null) {
                delay(150)
                // New text layout and the crossfade land after the row glide, not inside it.
                awaitRowScrollSettled(rowsScrolling)
            }
            metadataItem = item
        }
    }
    LaunchedEffect(heroContent) {
        heroContent.collectLatest { item ->
            if (artworkItem != null) {
                delay(230)
                awaitRowScrollSettled(rowsScrolling)
            }
            artworkItem = item
        }
    }
    val displayedItem = metadataItem ?: return
    val heroArtwork = artworkItem ?: displayedItem
    val performance = LocalPerformanceProfile.current
    val artworkWidth = deviceDp(240.dp, 96.dp)
    val horizontalPadding = screenGutter()
    val artworkGap = deviceDp(32.dp, 16.dp)
    val heroHeight = height ?: homeHeroHeight()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight),
    ) {
        if (drawAmbient) {
            HomeHeroAmbient(
                heroContent, heroStyleKey, intensityPercent, spreadKey, Modifier.matchParentSize(), rowsScrolling,
                focusTravel = focusTravel,
            )
        }
        HomeStaticImage(
            model = heroArtwork.titleLogoUrl,
            headers = heroArtwork.imageHeaders,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.CenterEnd)
                .padding(end = horizontalPadding, bottom = HomeHeroFadeTail)
                .width(artworkWidth).height(
                    if (clockCorner) {
                        // Still centred on the metadata, so only the height shrinks to stay under the clock.
                        val reserve = homeClockReserve(com.ambient.tv.ui.common.LocalClockSettings.current)
                        (heroHeight - HomeHeroFadeTail - reserve * 2).coerceIn(48.dp, deviceDp(160.dp, 120.dp))
                    } else deviceDp(160.dp, 120.dp),
                ),
        )
        Box(
            modifier = Modifier
                .align(if (atScreenTop && !LocalHandheldUi.current) Alignment.TopStart else Alignment.CenterStart)
                .padding(
                    top = if (atScreenTop && !LocalHandheldUi.current) com.ambient.tv.ui.common.PageHeaderTopTv else 0.dp,
                    start = horizontalPadding,
                    end = horizontalPadding + if (displayedItem.titleLogoUrl.isNullOrBlank() && heroArtwork.titleLogoUrl.isNullOrBlank()) 0.dp else artworkWidth + artworkGap,
                    bottom = HomeHeroFadeTail,
                )
                .fillMaxWidth()
                .graphicsLayer {
                    val progress = entrancePhase(entranceProgress(), 0.14f, 0.72f)
                    alpha = progress
                    translationX = (1f - progress) * -24.dp.toPx()
                },
        ) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Crossfade(
                targetState = displayedItem,
                modifier = Modifier.weight(1f),
                animationSpec = tween(
                    durationMillis = if (performance.allowComplexHero) 320 else 0,
                    easing = FastOutSlowInEasing,
                ),
                label = "homeHeroMetadata",
            ) { heroItem ->
                Column(
                    modifier = Modifier.fillMaxWidth().clipToBounds(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val metadataShadow = if (performance.allowComplexHero) {
                        Shadow(
                            color = Color.Black.copy(alpha = 0.92f),
                            offset = Offset(0f, 2f),
                            blurRadius = 5f,
                        )
                    } else {
                        Shadow(color = Color.Transparent, offset = Offset.Zero, blurRadius = 0f)
                    }
                    // Reserve title, context and metadata slots for every provider, including missing data.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showSystemBadge) com.ambient.tv.ui.common.MediaSystem.fromKey(heroItem.providerLabel)?.let {
                            com.ambient.tv.ui.common.SystemBadge(it, showName = false)
                        }
                        // Episodes lead with the series name; the episode title moves to the context line.
                        val heroSeriesTitle = heroItem.mediaItem
                            ?.takeIf { it.type == "episode" }
                            ?.grandparentTitle
                            ?.takeIf { it.isNotBlank() }
                        Text(
                            text = heroSeriesTitle ?: heroItem.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineMedium.copy(shadow = metadataShadow),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    val mediaItem = heroItem.mediaItem
                    val episode = mediaItem?.takeIf { it.type == "episode" }
                    // Like Plex, an episode's own title gets the line under the series name in
                    // place of the genres; its S/E marker leads the metadata row below.
                    val episodeTitle = episode?.takeIf { !it.grandparentTitle.isNullOrBlank() }
                        ?.let { heroItem.title.takeIf { title -> title.isNotBlank() } }
                    val emphasizedContext = episodeTitle ?: heroItem.subtitle
                    val contextLabel = if (heroItem.subtitle != null) heroItem.subtitle else buildList {
                        addAll(heroItem.metadata)
                        if (episode != null) {
                            episodeTitle?.let(::add)
                        } else if (mediaItem?.type == "season") {
                            mediaItem.parentTitle?.takeIf { it.isNotBlank() }?.let(::add)
                        } else {
                            addAll(mediaItem?.genres.orEmpty().take(2))
                        }
                    }.joinToString("  ·  ")
                    Text(
                        text = contextLabel,
                        style = if (emphasizedContext != null) {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, shadow = metadataShadow)
                        } else {
                            MaterialTheme.typography.bodyMedium.copy(shadow = metadataShadow)
                        },
                        color = if (emphasizedContext != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
                    )
                    val live = heroItem.live
                    val metaBeforeRating = live?.timeLabel ?: mediaItem?.year?.toString().orEmpty()
                    val durationLabel = formatDuration(
                        live?.let { (it.stopEpochSeconds - it.startEpochSeconds) * 1000L } ?: mediaItem?.durationMs,
                    )
                    val liveProgress = live?.let {
                        val now = System.currentTimeMillis() / 1000L
                        ((now - it.startEpochSeconds).toFloat() / (it.stopEpochSeconds - it.startEpochSeconds).coerceAtLeast(1L)).coerceIn(0f, 1f)
                    }
                    val heroScore = mediaItem?.rating ?: mediaItem?.audienceRating ?: mediaItem?.criticRating
                    val heroScoreMax = when {
                        mediaItem?.rating != null -> 10.0
                        mediaItem?.audienceRating != null -> 10.0
                        mediaItem?.criticRating != null -> 100.0
                        else -> 10.0
                    }
                    val remainingLabel = (live?.let { (it.stopEpochSeconds - System.currentTimeMillis() / 1000L).coerceAtLeast(0L) * 1000L }
                        ?: mediaItem?.remainingMs?.takeIf { (mediaItem.progressFraction ?: 0f) in 0.01f..0.94f })
                        ?.let { remaining ->
                            formatDuration(remaining)?.let { stringResource(R.string.media_remaining, it) }
                        }
                    // Keep the year and badges on one stable baseline.
                    Row(
                        modifier = Modifier.heightIn(min = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (live != null) LiveBadge()
                        episode?.index?.let { number ->
                            val marker = episode.parentIndex?.let { season -> "S$season · E$number" }
                                ?: stringResource(R.string.media_episode_short_label, number)
                            Text(marker, style = MaterialTheme.typography.bodyMedium.copy(shadow = metadataShadow), maxLines = 1)
                        }
                        if (metaBeforeRating.isNotBlank()) {
                            Text(metaBeforeRating, style = MaterialTheme.typography.bodyMedium.copy(shadow = metadataShadow), maxLines = 1)
                        }
                        mediaItem?.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
                        heroScore?.let { RatingScoreBadge(score = it, maxScore = heroScoreMax) }
                        if (mediaItem?.isWatched == true) com.ambient.tv.ui.common.WatchedBadge()
                    }
                    // Runtime and playback progress get their own line, which leaves the badge
                    // row readable and gives continuing items a small visual progress cue.
                    Row(
                        modifier = Modifier.heightIn(min = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        durationLabel?.let { duration ->
                            Text(
                                text = duration,
                                style = MaterialTheme.typography.bodyMedium.copy(shadow = metadataShadow),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        remainingLabel?.let { label ->
                            val progress = liveProgress ?: mediaItem?.progressFraction ?: 0f
                            com.ambient.tv.ui.common.PlaybackStatusBar(
                                progress = progress,
                                modifier = Modifier.width(112.dp),
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                            )
                            Text(
                                text = label,
                                modifier = Modifier.weight(1f),
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium.copy(shadow = metadataShadow),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                    // Only the tall TV hero has room for the summary; the slot stays reserved
                    // so the metadata does not jump between items with and without one.
                    if (heroHeight > HomeHeroMinHeight) {
                        Text(
                            text = (heroItem.summary ?: mediaItem?.summary)?.takeIf { it.isNotBlank() }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(shadow = metadataShadow),
                            maxLines = 3,
                            minLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            // Plex-like measure: long lines across the whole TV are hard to read.
                            modifier = Modifier.widthIn(max = 600.dp).padding(top = 4.dp),
                        )
                    }
                }
            }
            if (titleActions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = titleActions)
          }
        }
    }
}

/** Bounded decode, no transition or placeholder; logos retain their transparent pixels. */
@Composable
private fun HomeStaticImage(
    model: String?,
    headers: Map<String, String>,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    Box(modifier.onSizeChanged { size = it }) {
        if (!model.isNullOrBlank() && size.width > 0 && size.height > 0) {
            val request = remember(model, headers, size, context) {
                ImageRequest.Builder(context).data(model).size(size.width, size.height)
                    .crossfade(false)
                    .requestHeaders(headers)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                colorFilter = com.ambient.tv.ui.display.rememberUiHdrBitmapColorFilter(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Backdrop decode bound (half of a 1080p UI); see [HomeFocusBackdrop]. */
private val HomeBackdropDecodeSize = IntSize(960, 540)

/** Kept behind the rows so the light can extend below the pinned metadata. */
@Composable
internal fun HomeHeroAmbient(
    heroContent: StateFlow<HomeHeroContent?>,
    heroStyleKey: String,
    intensityPercent: Int,
    spreadKey: String,
    modifier: Modifier,
    rowsScrolling: StateFlow<Boolean> = RowsNeverScrolling,
    focusTravel: () -> Float = { 0.42f },
) {
    // The visible background video is the backdrop; animated light on top only competes with it.
    if (com.ambient.tv.ui.player.LocalVisibleBackgroundVideo.current) return
    var heroArtwork by remember(heroContent) { mutableStateOf(heroContent.value) }
    LaunchedEffect(heroContent) {
        heroContent.collectLatest {
            if (heroArtwork != null) {
                delay(230)
                awaitRowScrollSettled(rowsScrolling)
            }
            heroArtwork = it
        }
    }
    val performance = LocalPerformanceProfile.current
    val effectiveStyle = HomeHeroStyle.effective(
        key = heroStyleKey,
        performanceModeKey = performance.mode.key,
        reducedEffects = performance.reducedEffects,
    )
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val sampledArtworkAccent = rememberArtworkSampledAccent(
        model = heroArtwork?.artworkUrl?.takeIf { heroArtwork?.artworkIsLogo != true },
        enabled = HomeHeroStyle.samplesArtwork(effectiveStyle),
        fallback = accent,
        headers = heroArtwork?.imageHeaders.orEmpty(),
    )
    val ambientAccent = animateColorAsState(
        targetValue = sampledArtworkAccent,
        animationSpec = tween(1_200, easing = FastOutSlowInEasing),
        label = "homeHeroArtworkAccent",
    )
    val motionStrength = HomeHeroStyle.motionStrength(performance.mode.key, performance.reducedEffects)
    val motionPhase: () -> Float = if (HomeHeroStyle.isAnimated(effectiveStyle)) {
        val phase = rememberIdleAmbientPhase(HomeHeroStyle.ambientPeriodMs(effectiveStyle))
        ({ phase.value })
    } else {
        ({ 0.5f })
    }
    val reveal = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(heroArtwork?.key, effectiveStyle) {
        if (effectiveStyle == HomeHeroStyle.PRISM) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(1_600, easing = FastOutSlowInEasing))
        }
    }
    if (HomeHeroStyle.isFancy(effectiveStyle)) {
        Box(modifier.graphicsLayer().fancyHeroAmbient(
            style = effectiveStyle,
            artworkAccent = { ambientAccent.value },
            secondary = secondary,
            phase = motionPhase,
            reveal = { reveal.value },
            motionStrength = motionStrength,
            intensityScale = HomeHeroStyle.intensityScale(intensityPercent),
            radiusScale = HomeHeroStyle.radiusScale(spreadKey),
            focusTravel = focusTravel,
        ))
        return
    }
    Box(modifier.graphicsLayer().homeHeroAmbient(
        accent = accent,
        secondary = secondary,
        artworkAccent = { ambientAccent.value },
        style = effectiveStyle,
        phase = motionPhase,
        motionStrength = motionStrength,
        intensityScale = HomeHeroStyle.intensityScale(intensityPercent),
        radiusScale = HomeHeroStyle.radiusScale(spreadKey),
        heroHeight = homeHeroHeight(),
    ))
}

/**
 * On TV the hero takes the upper part of the screen, so the first row rests in the lower half
 * like Plex's home and the freed space holds the summary. Handheld keeps the compact banner.
 */
@Composable
private fun homeHeroHeight(): Dp {
    if (LocalHandheldUi.current) return HomeHeroMinHeight
    val screenHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // Rows keep their dp size, so a smaller UI scale (4K starts at 70 %) would fit more of them
    // under a proportional hero. Keep the space below at its 100 % share: one row, like Plex.
    return maxOf(screenHeight * 0.44f, screenHeight - HomeRowsArea).coerceAtLeast(HomeHeroMinHeight)
}

// Below the hero at 100 % scale on a 540 dp tall TV screen (56 %).
private val HomeRowsArea = 302.dp

private val HomeHeroMinHeight = 190.dp

// Rows sit this far up into the hero's fade tail, leaving room below the focused row's labels.
internal val HomeRowsLift = 30.dp

// Top of the screen the floating home clock covers, its padding included.
private fun homeClockReserve(clock: com.ambient.tv.data.local.ClockSettings): Dp {
    val twoLines = !clock.dateBeside && clock.date != com.ambient.tv.data.local.ClockSettings.DATE_OFF
    return when (clock.size) {
        com.ambient.tv.data.local.ClockSettings.SIZE_SMALL -> if (twoLines) 64.dp else 52.dp
        com.ambient.tv.data.local.ClockSettings.SIZE_LARGE -> if (twoLines) 88.dp else 70.dp
        else -> if (twoLines) 72.dp else 58.dp
    }
}

// Keep the metadata/logo above the ambient surface's transparent lower edge.
private val HomeHeroFadeTail = 24.dp

private fun entrancePhase(progress: Float, start: Float, end: Float): Float =
    ((progress - start) / (end - start)).coerceIn(0f, 1f)

@Composable
internal fun Modifier.fadeRowsBehindHomeHero(allowSaveLayer: Boolean, heroHeight: Dp): Modifier {
    if (!allowSaveLayer) {
        // Reduced mode avoids the saveLayer with a hard cut where the mask would fade in. An
        // opaque veil over the hero instead also hid the backdrop behind it as a black band.
        return drawWithContent {
            clipRect(top = heroHeight.toPx() * 0.96f) { this@drawWithContent.drawContent() }
        }
    }
    val contentLayer = rememberGraphicsLayer()
    return drawWithCache {
        val fadeHeight = heroHeight.toPx().coerceAtMost(size.height)
        val fadeBounds = Rect(0f, 0f, size.width, fadeHeight)
        val layerPaint = Paint()
        val visibilityMask = Brush.verticalGradient(
            // The tall hero's summary reaches far down, so rows only fade in at its lower edge.
            0f to Color.Transparent,
            0.94f to Color.Transparent,
            0.98f to Color.Black.copy(alpha = 0.55f),
            1f to Color.Black,
            startY = 0f,
            endY = fadeHeight,
        )
        onDrawWithContent draw@{
            // Record the child tree once. Replay its display list in the two clipped regions;
            // alpha=1/default Auto does not rasterize a second full-screen texture.
            contentLayer.record { this@draw.drawContent() }
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.clipRect(Rect(0f, fadeHeight, size.width, size.height))
                this@draw.drawLayer(contentLayer)
                canvas.restore()

                canvas.saveLayer(fadeBounds, layerPaint)
                canvas.clipRect(fadeBounds)
                this@draw.drawLayer(contentLayer)
                drawRect(
                    brush = visibilityMask,
                    size = Size(size.width, fadeHeight),
                    blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }
        }
    }
}

/** Translucent washes with draw-phase animation reads; no blur or offscreen layer. */
internal fun Modifier.homeHeroAmbient(
    accent: Color,
    secondary: Color,
    artworkAccent: () -> Color,
    style: String,
    phase: () -> Float,
    motionStrength: Float,
    intensityScale: Float,
    radiusScale: Float,
    heroHeight: Dp,
    boundedPaint: Boolean = true,
): Modifier = drawWithCache {
    val artworkAccent = artworkAccent()
    val intensity = intensityScale.coerceIn(0f, 100f / HomeHeroStyle.DEFAULT_INTENSITY)
    val spread = radiusScale.coerceIn(0.72f, 1.32f)
    val heroHeight = heroHeight.toPx().coerceAtLeast(1f)
    val glowRadius = heroHeight * when (style) {
        HomeHeroStyle.SWEEP -> 1.05f
        HomeHeroStyle.DEPTH, HomeHeroStyle.ARTWORK_COLOR -> 1.50f
        else -> 1.40f
    } * spread
    val glowOrigin = when (style) {
        HomeHeroStyle.ARTWORK_COLOR -> Offset(size.width * 0.80f, -heroHeight * 0.30f)
        HomeHeroStyle.SWEEP, HomeHeroStyle.DEPTH -> Offset.Zero
        else -> Offset(size.width * 0.80f, -heroHeight * 0.40f)
    }
    val glow = when (style) {
        HomeHeroStyle.SWEEP -> Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.16f * motionStrength * intensity),
                accent.copy(alpha = 0.055f * motionStrength * intensity),
                accent.copy(alpha = 0f),
            ),
            center = Offset.Zero,
            radius = glowRadius,
        )
        HomeHeroStyle.ARTWORK_COLOR -> Brush.radialGradient(
            colors = listOf(
                artworkAccent.copy(alpha = 0.19f * intensity),
                artworkAccent.copy(alpha = 0.065f * intensity),
                artworkAccent.copy(alpha = 0f),
            ),
            center = Offset(size.width * 0.80f, -heroHeight * 0.30f),
            radius = glowRadius,
        )
        HomeHeroStyle.DEPTH -> Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.28f * motionStrength * intensity),
                secondary.copy(alpha = 0.11f * motionStrength * intensity),
                accent.copy(alpha = 0f),
            ),
            center = Offset.Zero,
            radius = glowRadius,
        )
        else -> Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.14f * intensity), accent.copy(alpha = 0.055f * intensity), accent.copy(alpha = 0f)),
            center = Offset(size.width * 0.80f, -heroHeight * 0.40f),
            radius = glowRadius,
        )
    }
    val depthCounterGlow = if (style == HomeHeroStyle.DEPTH) Brush.radialGradient(
        colors = listOf(
            secondary.copy(alpha = 0.16f * motionStrength * intensity),
            secondary.copy(alpha = 0.05f * motionStrength * intensity),
            secondary.copy(alpha = 0f),
        ),
        center = Offset.Zero,
        radius = heroHeight * 1.25f * spread,
    ) else null
    onDrawBehind {
        // Move cached shaders rather than allocating every gradient on every ambient frame.
        val currentPhase = phase()
        val glowCenter = when (style) {
            HomeHeroStyle.SWEEP -> Offset(size.width * (-0.15f + 1.30f * currentPhase), heroHeight * 0.30f)
            HomeHeroStyle.DEPTH -> Offset(size.width * (0.45f + 0.38f * currentPhase), heroHeight * (0.02f + 0.32f * currentPhase))
            else -> Offset.Zero
        }
        drawAmbientGlow(glow, glowOrigin, glowRadius, glowCenter, boundedPaint)
        depthCounterGlow?.let { brush ->
            val center = Offset(size.width * (1.08f - 0.52f * currentPhase), heroHeight * (0.80f - 0.42f * currentPhase))
            drawAmbientGlow(brush, Offset.Zero, heroHeight * 1.25f * spread, center, boundedPaint)
        }
    }
}

/** Transparent shader tails need no fill/blend work, especially on a physical 4K TV surface. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAmbientGlow(
    brush: Brush, origin: Offset, radius: Float, translation: Offset, bounded: Boolean,
) {
    val left = if (bounded) maxOf(-translation.x, origin.x - radius) else -translation.x
    val top = if (bounded) maxOf(-translation.y, origin.y - radius) else -translation.y
    val right = if (bounded) minOf(size.width - translation.x, origin.x + radius) else size.width - translation.x
    val bottom = if (bounded) minOf(size.height - translation.y, origin.y + radius) else size.height - translation.y
    if (right <= left || bottom <= top) return
    withTransform({ translate(translation.x, translation.y) }) {
        // Keep the brush's drawing size constant so moving bounds do not rebuild its shader.
        if (bounded) clipRect(left, top, right, bottom) {
            drawRect(brush, topLeft = -translation, size = size)
        } else drawRect(brush, topLeft = -translation, size = size)
    }
}

@Composable
private fun ServerOfflineContent(serverName: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    StatePanel(
        title = stringResource(R.string.home_server_unreachable_title),
        body = stringResource(R.string.home_server_unreachable_body, serverName),
        actionLabel = stringResource(R.string.home_retry),
        onAction = onRetry,
        secondaryActionLabel = stringResource(R.string.home_check_settings),
        onSecondaryAction = onOpenSettings,
        modifier = Modifier.widthIn(max = 700.dp),
    )
}

@Composable
private fun WakeTimeoutContent(serverName: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    StatePanel(
        title = stringResource(R.string.home_wake_timeout_title),
        body = stringResource(R.string.home_wake_timeout_body, serverName),
        actionLabel = stringResource(R.string.home_retry),
        onAction = onRetry,
        secondaryActionLabel = stringResource(R.string.home_check_settings),
        onSecondaryAction = onOpenSettings,
        modifier = Modifier.widthIn(max = 700.dp),
    )
}

@Composable
private fun ConnectionErrorContent(message: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val sanitized = remember(message) { sanitizeUiErrorMessage(message) }
    val body = buildString {
        append(stringResource(R.string.home_connection_error_body))
        if (sanitized != null) {
            append('\n')
            append(stringResource(R.string.home_connection_error_details, sanitized))
        }
    }
    StatePanel(
        title = stringResource(R.string.home_connection_problem),
        body = body,
        actionLabel = stringResource(R.string.home_retry),
        onAction = onRetry,
        secondaryActionLabel = stringResource(R.string.home_check_settings),
        onSecondaryAction = onOpenSettings,
        modifier = Modifier.widthIn(max = 700.dp),
    )
}

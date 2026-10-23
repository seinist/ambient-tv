package com.ambient.tv.ui.home

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.common.requestHeaders
import coil3.request.crossfade
import com.ambient.tv.tvheadend.domain.TvhChannel
import androidx.activity.compose.BackHandler
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.HomeHeroStyle
import com.ambient.tv.domain.model.MediaItem
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
    if (onBack != null) {
        BackHandler {
            viewModel.captureFocusForReturn()
            onBack()
        }
    }
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
            loaded.rows.asSequence().flatMap { it.items.asSequence() }.take(20).map { it.artUrl ?: it.thumbUrl }.asIterable(),
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

    Column(Modifier.fillMaxSize()) {
        if (!plexOnly && !jellyfinOnly && !embyOnly) com.ambient.tv.update.UpdateHomeCard()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val state = uiState) {
                HomeUiState.NotConfigured -> NotConfiguredDialog(onAddSystem)
                // Connection progress is presented by the global, non-focusable notification host.
                HomeUiState.LoadingLibrary, is HomeUiState.Waking -> LoadingRows()
                is HomeUiState.Loaded -> LibraryContent(
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
                )
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
}

/** Focus previews have their own state/draw layer, so their crossfade never invalidates rows. */
@Composable
private fun HomeFocusBackdrop(heroContent: StateFlow<HomeHeroContent?>) {
    if (com.ambient.tv.ui.player.LocalVisibleBackgroundVideo.current || !com.ambient.tv.ui.common.LocalFocusArtworkBackdrop.current) return
    var displayed by remember(heroContent) { mutableStateOf(heroContent.value) }
    LaunchedEffect(heroContent) {
        heroContent.collectLatest { hero ->
            // Do not decode every intermediate backdrop while a direction is held.
            delay(250)
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
                .height(HomeHeroHeight)
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
    if (LocalHandheldUi.current) {
        LazyColumn(
            allowUpExit = true,
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item("mobile-hero") { HomeHero(heroContent, homeHeroStyleKey, homeHeroIntensityPercent, homeHeroSpreadKey, entranceProgress) }
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
    Box(modifier = Modifier.fillMaxSize()) {
        HomeFocusBackdrop(heroContent)
        HomeHeroAmbient(heroContent, homeHeroStyleKey, homeHeroIntensityPercent, homeHeroSpreadKey, Modifier.fillMaxSize())
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
        val heroInsetPx = with(LocalDensity.current) { (HomeHeroHeight - HomeRowsLift).roundToPx() }
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
        LaunchedEffect(refreshRecovery) {
            val index = state.rowKeys.indexOf(refreshRecovery?.rowId)
            if (index >= 0 && rowListState.layoutInfo.visibleItemsInfo.none { it.index == index }) rowListState.scrollToItem(index)
        }
        // Every row type is parked directly below the pinned hero, where the first row rests;
        // a plain bring-into-view would count the hero-covered top as visible and park the
        // row's heading behind the banner.
        val rowCentering = rememberRowCenteringState(rowListState)
        val columnFocus = remember { RowColumnFocus() }
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
        LaunchedEffect(centeredRowKey, state.rowKeys, rowListState, heroInsetPx) {
            // Row-entry events do not invalidate the whole Home composition.
            centeredRowKey.collectLatest { key ->
                val index = state.rowKeys.indexOf(key)
                val alreadyAtTop = index == 0 && rowListState.firstVisibleItemIndex == 0 &&
                    rowListState.firstVisibleItemScrollOffset == 0
                if (index >= 0 && !alreadyAtTop) {
                    rowCentering.centerItem(index, topInset = heroInsetPx, alignTop = true)
                }
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides ExplicitRowScrollSpec) {
            LazyColumn(
                allowUpExit = true,
                state = rowListState,
                modifier = Modifier.onFocusChanged { rowsHaveFocus = it.hasFocus }.then(Modifier
                    .fillMaxSize()
                    .rowColumnFocusNavigation(columnFocus, scrollToTop = { rowListState.glideToItem(glideWrap, 0) }) { state.rowKeys }
                    .clipToBounds()
                    .acceleratedDpadNavigation()
                    .fadeRowsBehindHomeHero(maskRows)
                    .graphicsLayer {
                        val progress = entrancePhase(entranceProgress(), 0.24f, 1f)
                        alpha = progress
                        translationY = (1f - progress) * 28.dp.toPx()
                    }),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = HomeHeroHeight - HomeRowsLift + 12.dp,
                    bottom = 36.dp,
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
                modifier = Modifier.rowColumnFocusTarget(columnFocus, rowKey, row.id).focusRequester(firstFocus).onFocusChanged { if (it.isFocused) onFocused(row) },
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
) {
    val horizontalScrollSpec = if (LocalHandheldUi.current) LocalBringIntoViewSpec.current else TvBringIntoViewSpec
    val firstItemFocusRequester = remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus, items.map { it.channel.uuid }) {
        if (requestInitialFocus && items.isNotEmpty()) {
            withFrameNanos { }
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = screenGutter()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(items, key = { _, item -> "${item.channel.sourceId}:${item.channel.uuid}" }) { index, item ->
                    var focused by remember { mutableStateOf(false) }
                    val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                    androidx.tv.material3.Surface(
                        onClick = { onWatch(item.channel) },
                        colors = com.ambient.tv.ui.common.transparentSurfaceColors(),
                        scale = com.ambient.tv.ui.common.noSurfaceScale(),
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = cardShape),
                        modifier = Modifier
                            .width(250.dp)
                            .height(96.dp)
                            .rowColumnFocusTarget(columnFocus, rowKey, "${item.channel.sourceId}:${item.channel.uuid}")
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .activateOnTvKey { onWatch(item.channel) }
                            .onFocusChanged {
                                focused = it.isFocused
                                if (it.isFocused) onItemFocused(item)
                            },
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), cardShape)
                                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), cardShape)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(68.dp, 38.dp), contentAlignment = Alignment.Center) {
                                    item.channel.iconUrl?.let { ArtworkImage(model = it, title = item.channel.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit, headers = item.imageHeaders) }
                                }
                                MarqueeTitle(listOf(item.sourceName, item.channel.name).filter { it.isNotBlank() }.joinToString(" · "), focused = focused, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                            val event = item.event
                            // Empty title line keeps the bar aligned with cards that have guide data.
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                MarqueeTitle(event?.title.orEmpty(), focused = focused, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                val playbackSource = com.ambient.tv.companion.LocalPlaybackBrowseSources.current.live[item.channel.sourceId]
                                if (playbackSource != null) com.ambient.tv.companion.PlaybackBadge(com.ambient.tv.companion.playbackIndicator(
                                    com.ambient.tv.companion.CompanionMedia("live:channel", item.channel.uuid, playbackSource)))
                            }
                            if (event != null) {
                                val now = System.currentTimeMillis() / 1000L
                                val progress = ((now - event.startEpochSeconds).toFloat() / event.durationSeconds.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            } else {
                                androidx.compose.material3.LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                            }
                        }
                    }
                }
            }
        }
    }
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
) {
    // Collect focus in effects: transient targets must not recompose the complete hero while
    // the lazy rows are busy bringing new cards into view.
    var metadataItem by remember(heroContent) { mutableStateOf(heroContent.value) }
    var artworkItem by remember(heroContent) { mutableStateOf(heroContent.value) }
    LaunchedEffect(heroContent) {
        heroContent.collectLatest { item ->
            if (metadataItem != null) delay(150)
            metadataItem = item
        }
    }
    LaunchedEffect(heroContent) {
        heroContent.collectLatest { item ->
            if (artworkItem != null) delay(230)
            artworkItem = item
        }
    }
    val displayedItem = metadataItem ?: return
    val heroArtwork = artworkItem ?: displayedItem
    val performance = LocalPerformanceProfile.current
    val artworkWidth = deviceDp(240.dp, 96.dp)
    val horizontalPadding = screenGutter()
    val artworkGap = deviceDp(32.dp, 16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HomeHeroHeight),
    ) {
        if (drawAmbient) {
            HomeHeroAmbient(heroContent, heroStyleKey, intensityPercent, spreadKey, Modifier.matchParentSize())
        }
        Box(Modifier.matchParentSize().homeHeroVeil(MaterialTheme.colorScheme.background, performance.allowRowFadeSaveLayer))
        HomeStaticImage(
            model = heroArtwork.titleLogoUrl,
            headers = heroArtwork.imageHeaders,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.CenterEnd)
                .padding(end = horizontalPadding, bottom = HomeHeroFadeTail)
                .width(artworkWidth).height(120.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(
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
            Crossfade(
                targetState = displayedItem,
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
                        com.ambient.tv.ui.common.MediaSystem.fromKey(heroItem.providerLabel)?.let {
                            com.ambient.tv.ui.common.SystemBadge(it, showName = false)
                        }
                        Text(
                            text = heroItem.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineMedium.copy(shadow = metadataShadow),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    val mediaItem = heroItem.mediaItem
                    val contextLabel = buildList {
                        addAll(heroItem.metadata)
                        if (mediaItem?.type == "episode") {
                            mediaItem.grandparentTitle?.takeIf { it.isNotBlank() }?.let(::add)
                            mediaItem.parentIndex?.let { add("S$it") }
                            mediaItem.index?.let { add(stringResource(R.string.media_episode_short_label, it)) }
                        } else if (mediaItem?.type == "season") {
                            mediaItem.parentTitle?.takeIf { it.isNotBlank() }?.let(::add)
                        } else {
                            addAll(mediaItem?.genres.orEmpty().take(2))
                        }
                    }.joinToString("  ·  ")
                    Text(
                        text = contextLabel,
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = metadataShadow),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
                    )
                    val metaBeforeRating = mediaItem?.year?.toString().orEmpty()
                    val durationLabel = formatDuration(mediaItem?.durationMs)
                    val heroScore = mediaItem?.rating ?: mediaItem?.audienceRating ?: mediaItem?.criticRating
                    val heroScoreMax = when {
                        mediaItem?.rating != null -> 10.0
                        mediaItem?.audienceRating != null -> 10.0
                        mediaItem?.criticRating != null -> 100.0
                        else -> 10.0
                    }
                    val remainingLabel = mediaItem?.remainingMs
                        ?.takeIf { (mediaItem.progressFraction ?: 0f) in 0.01f..0.94f }
                        ?.let { remaining ->
                            formatDuration(remaining)?.let { stringResource(R.string.media_remaining, it) }
                        }
                    // Keep the year and badges on one stable baseline.
                    Row(
                        modifier = Modifier.heightIn(min = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (metaBeforeRating.isNotBlank()) {
                            Text(metaBeforeRating, style = MaterialTheme.typography.bodyMedium.copy(shadow = metadataShadow), maxLines = 1)
                        }
                        mediaItem?.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
                        heroScore?.let { RatingScoreBadge(score = it, maxScore = heroScoreMax) }
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
                            val progress = mediaItem.progressFraction ?: 0f
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
                }
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
                colorFilter = com.ambient.tv.ui.display.uiHdrBitmapColorFilter(
                    com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Kept behind the rows so the light can extend below the pinned metadata. */
@Composable
internal fun HomeHeroAmbient(
    heroContent: StateFlow<HomeHeroContent?>,
    heroStyleKey: String,
    intensityPercent: Int,
    spreadKey: String,
    modifier: Modifier,
) {
    // The visible background video is the backdrop; animated light on top only competes with it.
    if (com.ambient.tv.ui.player.LocalVisibleBackgroundVideo.current) return
    var heroArtwork by remember(heroContent) { mutableStateOf(heroContent.value) }
    LaunchedEffect(heroContent) {
        heroContent.collectLatest {
            if (heroArtwork != null) delay(230)
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
        val phase = rememberIdleAmbientPhase(if (effectiveStyle == HomeHeroStyle.SWEEP) 20_000 else 18_000)
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
    ))
}

private fun Modifier.homeHeroVeil(background: Color, maskRows: Boolean): Modifier = drawWithCache {
    val top = background.copy(alpha = if (maskRows) 0.55f else 1f)
    val veil = Brush.verticalGradient(
        0f to top,
        0.55f to top,
        0.75f to top.copy(alpha = top.alpha * 0.65f),
        0.90f to top.copy(alpha = top.alpha * 0.20f),
        1f to Color.Transparent,
    )
    onDrawBehind { drawRect(veil) }
}

private val HomeHeroHeight = 190.dp

// Rows sit this far up into the hero's fade tail, leaving room below the focused row's labels.
private val HomeRowsLift = 30.dp

// Keep the metadata/logo above the ambient surface's transparent lower edge.
private val HomeHeroFadeTail = 24.dp

private fun entrancePhase(progress: Float, start: Float, end: Float): Float =
    ((progress - start) / (end - start)).coerceIn(0f, 1f)

@Composable
internal fun Modifier.fadeRowsBehindHomeHero(allowSaveLayer: Boolean): Modifier {
    if (!allowSaveLayer) {
        // Reduced mode avoids the saveLayer; HomeHero's foreground veil covers rows smoothly.
        return this
    }
    val contentLayer = rememberGraphicsLayer()
    return drawWithCache {
        // The row list is recorded into one layer. This read redraws that recording while
        // cover and landscape placeholders pulse; a stable value does not.
        com.ambient.tv.ui.common.observeSkeletonPulse()
        val fadeHeight = HomeHeroHeight.toPx().coerceAtMost(size.height)
        val fadeBounds = Rect(0f, 0f, size.width, fadeHeight)
        val layerPaint = Paint()
        val visibilityMask = Brush.verticalGradient(
            0f to Color.Transparent,
            0.72f to Color.Transparent,
            0.84f to Color.Black.copy(alpha = 0.18f),
            0.94f to Color.Black.copy(alpha = 0.72f),
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
    boundedPaint: Boolean = true,
): Modifier = drawWithCache {
    val artworkAccent = artworkAccent()
    val intensity = intensityScale.coerceIn(0f, 100f / HomeHeroStyle.DEFAULT_INTENSITY)
    val spread = radiusScale.coerceIn(0.72f, 1.32f)
    val heroHeight = HomeHeroHeight.toPx().coerceAtLeast(1f)
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

package com.ambient.tv.ui.detail

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi

import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.handheldDetailStacks

import com.ambient.tv.ui.player.LocalVisibleBackgroundVideo
import com.ambient.tv.ui.player.ThemeMusicPlayer

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import com.ambient.tv.ui.common.animateTvScrollToItem
import com.ambient.tv.ui.common.TvScrollAnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.containRowFocusExit
import com.ambient.tv.ui.common.RowColumnFocus
import com.ambient.tv.ui.common.RegisterRowColumnFocus
import com.ambient.tv.ui.common.rowColumnFocusTarget
import com.ambient.tv.ui.common.rowColumnFocusNavigation
import com.ambient.tv.ui.common.bringFocusedContextIntoView
import com.ambient.tv.ui.common.noSurfaceBorder
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.domain.model.ThemeMusic
import com.ambient.tv.domain.model.dedupeCastMembers
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.TitleLogo
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.MediaTile
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.MetaBadgeRow
import com.ambient.tv.ui.common.ContentRatingBadge
import com.ambient.tv.ui.common.PrimaryRatingScoreBadge
import com.ambient.tv.ui.common.StreamPropertyAdaptiveRows
import com.ambient.tv.ui.common.PageUtilityBar
import com.ambient.tv.ui.common.StreamPropertyRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.SkeletonRow
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.formatDuration
import com.ambient.tv.ui.common.formatResolution
import com.ambient.tv.ui.common.resolutionPixelCount
import com.ambient.tv.ui.common.rememberArtworkAccent
import com.ambient.tv.ui.theme.LocalAccentModeKey
import com.ambient.tv.trailers.rememberTrailerAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun DetailScreen(
    onTrailer: ((MediaItem) -> Unit)? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (MediaItem, mediaIndex: Int, restart: Boolean) -> Unit,
    onOpenChild: (MediaItem) -> Unit,
    onOpenEpisode: (MediaItem) -> Unit,
    onOpenSeason: (MediaItem) -> Unit,
    onOpenSeries: (String) -> Unit,
    onBrowseActor: (String, String) -> Unit,
    onBrowseCollection: (String, String) -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    com.ambient.tv.ui.common.RetryOfflineBrowse(viewModel::retryOffline)
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(viewModel.reconnectSources, viewModel::load)
    val uiState by viewModel.uiState.collectAsState()
    ThemeMusicEffect(viewModel.themeMusicPlayer, (uiState as? DetailUiState.Loaded)?.themeMusic)
    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            DetailUiState.Loading -> DetailLoadingSkeleton(onBack = onBack, onOpenSettings = onOpenSettings)
            is DetailUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StatePanel(
                    title = stringResource(R.string.detail_load_failed),
                    body = state.messageRes?.let { stringResource(it) } ?: state.message.ifBlank { stringResource(R.string.error_generic) },
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = viewModel::load,
                )
                PageUtilityBar(
                    onBack = onBack,
                    onSettings = onOpenSettings,
                    leftNavigatesBack = false,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            is DetailUiState.Loaded -> {
                val focusScope = viewModel.focusScope
                androidx.compose.runtime.key(focusScope, state.item.ratingKey) {
                    DetailContent(
                        fallbackBackdrop = viewModel.fallbackBackdrop.collectAsState().value,
                        onTrailer = onTrailer,
                        item = state.item,
                        focusScope = focusScope,
                        playbackAvailable = !state.offline && !state.removed,
                        availabilityMessage = if (state.removed) R.string.browse_item_removed else if (state.offline) R.string.browse_offline else null,
                        children = state.children,
                        childrenLoading = state.childrenLoading,
                        childrenLoadFailed = state.childrenLoadFailed,
                        seasons = state.seasons,
                        onRetryChildren = viewModel::load,
                        nextEpisode = state.nextEpisode,
                        similar = state.similar,
                        similarLoading = state.similarLoading,
                        actorRows = state.actorRows,
                        actionInProgress = state.actionInProgress,
                        autoSelectDisplayVersion = state.autoSelectDisplayVersion,
                        onPlay = onPlay,
                        onOpenChild = onOpenChild,
                        onOpenEpisode = onOpenEpisode,
                        onOpenSeason = onOpenSeason,
                        onOpenSeries = onOpenSeries,
                        onBack = onBack,
                        onOpenSettings = onOpenSettings,
                        onBrowseActor = onBrowseActor,
                        onBrowseCollection = onBrowseCollection,
                        onSetWatched = viewModel::setWatched,
                        onRemoveFromContinue = viewModel::removeFromContinueWatching,
                        editSupport = viewModel.editSupport.collectAsState().value.takeIf { !state.offline && !state.removed }
                            ?: com.ambient.tv.domain.model.MetadataEditSupport.NONE,
                        onMetadataEdited = viewModel::onMetadataEdited,
                    )
                }
            }
        }
    }
}

/** Theme music follows the page: it stops when the page is left or the app goes to the background. */
@Composable
private fun ThemeMusicEffect(player: ThemeMusicPlayer, music: ThemeMusic?) {
    val owner = remember { Any() }
    val silenced by player.silenced.collectAsState(initial = player.isSilenced)
    LifecycleResumeEffect(music?.id, silenced) {
        if (music != null && !silenced) player.play(music, owner)
        onPauseOrDispose { player.stop(owner) }
    }
}

@Composable
private fun DetailLoadingSkeleton(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val visibleBackgroundVideo = LocalVisibleBackgroundVideo.current
    Box(modifier = Modifier.fillMaxSize().background(if (visibleBackgroundVideo) Color.Transparent else MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = screenGutter(), end = screenGutter(), top = screenGutter() + 80.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(Modifier.width(180.dp).height(14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)))
            Box(Modifier.fillMaxWidth(0.46f).height(38.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
            Box(Modifier.fillMaxWidth(0.31f).height(15.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)))
            Spacer(Modifier.height(5.dp))
            repeat(3) { index ->
                Box(
                    Modifier
                        .fillMaxWidth(if (index == 2) 0.60f else 0.74f)
                        .height(14.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(176.dp).height(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
                Box(Modifier.width(132.dp).height(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Spacer(Modifier.height(18.dp))
            SkeletonRow(title = "", style = MediaTileStyle.LANDSCAPE, itemCount = 4)
            SkeletonRow(title = "", style = MediaTileStyle.POSTER, itemCount = 7)
        }
        PageUtilityBar(
            onBack = onBack,
            onSettings = onOpenSettings,
            requestInitialFocus = true,
            leftNavigatesBack = false,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DetailContent(
    /** Wide backdrop the server offers but has not selected, else TMDb's, when the item has only a poster. */
    fallbackBackdrop: String? = null,
    playbackAvailable: Boolean,
    availabilityMessage: Int?,
    onTrailer: ((MediaItem) -> Unit)?,
    item: MediaItem,
    focusScope: String,
    children: List<MediaItem>,
    childrenLoading: Boolean,
    childrenLoadFailed: Boolean,
    seasons: List<MediaItem>,
    onRetryChildren: () -> Unit,
    nextEpisode: MediaItem?,
    similar: List<MediaItem>,
    similarLoading: Boolean,
    actorRows: List<ActorRow>,
    actionInProgress: Boolean,
    autoSelectDisplayVersion: Boolean,
    onPlay: (MediaItem, Int, Boolean) -> Unit,
    onOpenChild: (MediaItem) -> Unit,
    onOpenEpisode: (MediaItem) -> Unit,
    onOpenSeason: (MediaItem) -> Unit,
    onOpenSeries: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onBrowseActor: (String, String) -> Unit,
    onBrowseCollection: (String, String) -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onRemoveFromContinue: () -> Unit,
    editSupport: com.ambient.tv.domain.model.MetadataEditSupport,
    onMetadataEdited: (Boolean) -> Unit,
) {
    var showMore by remember(item.ratingKey) { mutableStateOf(false) }
    var showEdit by remember(item.ratingKey) { mutableStateOf(false) }
    // One process-session memory per source and detail. Only string identifiers survive a
    // popped route; the controller and its requesters remain owned by this composition.
    val focusMemory = remember(focusScope, item.ratingKey) { DetailFocusSessions.page(focusScope, item.ratingKey) }
    val columnFocus = remember(focusScope, item.ratingKey) { RowColumnFocus(focusMemory.rowItems()) }
    var restoreFocusPending by remember(focusScope, item.ratingKey) { mutableStateOf(focusMemory.key != null) }
    var childFocusPending by remember(focusScope, item.ratingKey) {
        mutableStateOf(item.type in setOf("season", "collection") && focusMemory.key == null)
    }
    val preferredChild = detailPreferredChildKey(item, children, nextEpisode)
    val childKeys = children.map { "children:${it.ratingKey}" }
    val childFocusKey = if (childFocusPending) {
        (columnFocus.rememberedItem("children") as? String)?.takeIf { it in childKeys } ?: preferredChild
    } else null
    val restoreFocusKey = focusMemory.key.takeIf { restoreFocusPending } ?: childFocusKey
    var enterPrimaryPending by remember(focusScope, item.ratingKey) { mutableStateOf(false) }
    var initialFocusHandled by remember(focusScope, item.ratingKey) { mutableStateOf(false) }
    var requestUtilityFocus by remember(focusScope, item.ratingKey) { mutableStateOf(false) }
    fun rememberFocused(key: String?) {
        initialFocusHandled = true
        requestUtilityFocus = false
        focusMemory.record(key)
        childFocusPending = false
        if (restoreFocusPending) restoreFocusPending = false
        enterPrimaryPending = false
    }
    var restoreTrailer by androidx.compose.runtime.saveable.rememberSaveable(item.ratingKey) { mutableStateOf(false) }
    val trailerAvailable = rememberTrailerAvailable(item, playbackAvailable && onTrailer != null)
    val activePlayback = com.ambient.tv.companion.playbackIndicator(item)
    val heroHasFocusTarget = remember(
        item.type, item.mediaVersions, item.progressFraction, nextEpisode?.ratingKey,
        playbackAvailable, trailerAvailable, activePlayback, editSupport,
    ) {
        val directPlayback = playbackAvailable && item.type !in setOf("show", "season") &&
            (activePlayback != null || item.mediaVersions.any { it.streamUrl != null })
        val nextPlayback = playbackAvailable && nextEpisode?.mediaVersions?.isNotEmpty() == true
        directPlayback || nextPlayback || trailerAvailable || detailActionCapabilities(
            item.type,
            playbackAvailable,
            item.mediaVersions.isNotEmpty(),
            (item.progressFraction ?: 0f) in 0.01f..0.94f,
            editSupport.any,
        ).hasMenuActions
    }
    var moreOpened by remember(item.ratingKey) { mutableStateOf(false) }
    val visibleBackgroundVideo = LocalVisibleBackgroundVideo.current
    // Both utility actions sit in an overlay above the list. Give their Down navigation an
    // explicit destination instead of making Compose infer one from distant geometry.
    val initialActionFocus = remember(item.ratingKey) { FocusRequester() }
    val moreActionFocus = remember(item.ratingKey) { FocusRequester() }
    LaunchedEffect(showMore, moreOpened) {
        if (!showMore && moreOpened) {
            delay(80)
            runCatching { moreActionFocus.requestFocus() }
        }
    }
    LaunchedEffect(item.ratingKey, trailerAvailable, restoreTrailer) {
        if (trailerAvailable && restoreTrailer) {
            withFrameNanos { }
            runCatching { initialActionFocus.requestFocus() }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // Always a wide backdrop when one exists, the item's own landscape first: an episode's/clip's
        // still before the show art Plex inherits into `art`; then other server backgrounds, TMDb's;
        // a poster only as last resort.
        val ownLandscape = item.thumbUrl?.takeIf { item.type in setOf("episode", "clip") }
        val backdrop = ownLandscape ?: item.wideBackdropUrl ?: fallbackBackdrop ?: item.thumbUrl
        val artworkAccent = rememberArtworkAccent(backdrop)
        val heroAccent = if (LocalAccentModeKey.current == "artwork") artworkAccent else MaterialTheme.colorScheme.primary
        if (!visibleBackgroundVideo) ArtworkImage(
            model = backdrop,
            // A backdrop that fails to load falls through to the next wide source, then the poster.
            fallbackModels = listOfNotNull(item.wideBackdropUrl, fallbackBackdrop, item.thumbUrl).distinct().filter { it != backdrop },
            title = item.title,
            contentScale = ContentScale.Crop,
            focusCrop = true,
            placeholder = false,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.12f),
                    0.42f to if (visibleBackgroundVideo) Color.Black.copy(alpha = 0.34f) else MaterialTheme.colorScheme.background.copy(alpha = 0.62f),
                    1f to if (visibleBackgroundVideo) Color.Black.copy(alpha = 0.54f) else MaterialTheme.colorScheme.background,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(
                        if (visibleBackgroundVideo) Color.Black.copy(alpha = 0.68f) else MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                        if (visibleBackgroundVideo) Color.Black.copy(alpha = 0.38f) else MaterialTheme.colorScheme.background.copy(alpha = 0.62f),
                        Color.Transparent,
                    ),
                ),
            ),
        )
        // A very subtle accent wash anchors the hero to the selected theme color without
        // tinting the artwork itself. It is strongest behind the title/actions and disappears
        // toward the media image.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to heroAccent.copy(alpha = 0.095f),
                    0.38f to Color.Transparent,
                    1f to Color.Transparent,
                ),
            ),
        )

        val listState = rememberLazyListState()
        val childrenSectionVisible = children.isNotEmpty() || detailShowsChildrenSection(item.type)
        val seasonStripVisible = item.type in setOf("season", "episode") && seasons.isNotEmpty() &&
            seriesKeyForSeasonStrip(item.type, item.parentRatingKey, item.grandparentRatingKey) != null
        // These are physical LazyColumn positions, including the optional strip above episodes.
        val sectionKeys = buildList {
            add("hero")
            if (seasonStripVisible) add("season-strip")
            if (childrenSectionVisible) add("children")
            if (item.cast.isNotEmpty()) add("cast")
            if (similar.isNotEmpty() || similarLoading) add("similar")
            actorRows.forEach { add("actor:${it.actorId}") }
            if (item.collections.isNotEmpty()) add("collections")
        }
        val castItems = item.cast.dedupeCastMembers().take(16)
        val rowDefaults = buildMap {
            preferredChild?.let { put("children", it) }
            castItems.firstOrNull { it.id != null }?.let { put("cast", "cast:${it.id ?: it.name}") }
            item.collections.firstOrNull { it.id != null }?.let { put("collections", "collection:${it.id ?: it.name}") }
        }
        // Decorative/disabled cards and loading placeholders are not navigation destinations.
        val rowOrder = buildList {
            if (heroHasFocusTarget) add("hero")
            if (seasonStripVisible) add("season-strip")
            if (childrenSectionVisible && (children.isNotEmpty() || (childrenLoadFailed && !childrenLoading))) add("children")
            if (castItems.any { it.id != null }) add("cast")
            if (similar.isNotEmpty()) add("similar")
            actorRows.filter { it.items.isNotEmpty() }.forEach { add("actor:${it.actorId}") }
            if (item.collections.any { it.id != null }) add("collections")
        }
        val scrollScope = rememberCoroutineScope()
        suspend fun focusRow(rowKey: String, stillRequested: () -> Boolean): Boolean {
            if (!stillRequested()) return false
            if (columnFocus.focusEntry(rowKey)) return true
            sectionKeys.indexOf(rowKey).takeIf { it >= 0 }?.let { listState.scrollToItem(it) }
            var frames = 0
            while (!columnFocus.canScrollToEntry(rowKey) && frames++ < 12) {
                withFrameNanos { }
                if (!stillRequested()) return false
            }
            if (!stillRequested()) return false
            if (columnFocus.canScrollToEntry(rowKey)) columnFocus.scrollToEntry(rowKey)
            repeat(12) {
                withFrameNanos { }
                if (!stillRequested()) return false
                if (columnFocus.focusEntry(rowKey)) return true
            }
            return false
        }
        LaunchedEffect(enterPrimaryPending, childrenLoading, childrenLoadFailed, rowOrder) {
            if (!enterPrimaryPending) return@LaunchedEffect
            // Down is an explicit request, so a not-yet-loaded primary row can finish before
            // focus moves. Any other navigation cancels this intent; refresh alone never arms it.
            if (detailShowsChildrenSection(item.type) && childrenLoading && children.isEmpty()) return@LaunchedEffect
            val destination = "children".takeIf { it in rowOrder }
                ?: rowOrder.firstOrNull { it != "hero" }
            if (destination != null) focusRow(destination) { enterPrimaryPending }
            enterPrimaryPending = false
        }
        // Resolve initial focus once per page, waiting for asynchronous rows when needed.
        // Later metadata updates must not steal focus from Back/Settings or another row.
        LaunchedEffect(focusScope, item.ratingKey, childFocusKey, childrenLoading, childrenLoadFailed, similarLoading, sectionKeys, rowOrder) {
            if (initialFocusHandled) return@LaunchedEffect
            if (childFocusPending && childFocusKey != null) {
                // Compose the child row before its tile requests focus (it may be below
                // a tall hero). The row starts with the destination already visible.
                if (focusRow("children") { !initialFocusHandled } || initialFocusHandled) {
                    initialFocusHandled = true
                    childFocusPending = false
                    return@LaunchedEffect
                }
            }
            if (childFocusPending && childrenLoading) return@LaunchedEffect
            if (restoreFocusPending) {
                val section = detailRowForFocusKey(restoreFocusKey)
                val sectionIndex = sectionKeys.indexOf(section)
                // Restore through the same row-entry policy as D-pad navigation. A deleted
                // saved card falls back to the row default rather than leaving focus nowhere.
                if (section !in rowOrder && (childrenLoading || similarLoading)) return@LaunchedEffect
                if (sectionIndex >= 0 && section != null && section in rowOrder) {
                    if (focusRow(section) { !initialFocusHandled } || initialFocusHandled) {
                        initialFocusHandled = true
                        restoreFocusPending = false
                        return@LaunchedEffect
                    }
                }
                restoreFocusPending = false
            }
            childFocusPending = false
            // Detail pages always open at their real beginning. Requesting focus can make the
            // LazyColumn reveal the action geometrically, so restore offset zero once after the
            // focus transaction as well.
            if (!restoreFocusPending) {
                listState.scrollToItem(0)
                withFrameNanos { }
                if (initialFocusHandled) return@LaunchedEffect
                if (heroHasFocusTarget) runCatching { initialActionFocus.requestFocus() }
                else requestUtilityFocus = true
                initialFocusHandled = true
                withFrameNanos { }
                listState.scrollToItem(0)
            }
        }
        val focusContentFromUtility = remember(item.ratingKey, listState, scrollScope, initialActionFocus) {
            var pendingFocusJob: Job? = null
            {
                pendingFocusJob?.cancel()
                if (listState.layoutInfo.visibleItemsInfo.any { it.index == 0 }) {
                    // Avoid an asynchronous focus round-trip while the hero is already present;
                    // otherwise Back visibly regains focus before Resume/Play is selected.
                    runCatching { initialActionFocus.requestFocus() }
                    pendingFocusJob = scrollScope.launch { listState.scrollToItem(0) }
                } else {
                    pendingFocusJob = scrollScope.launch {
                        // The destination may have been disposed while browsing lower sections.
                        // Snap it back into composition, then transfer focus on the next frame.
                        listState.scrollToItem(0)
                        withFrameNanos { }
                        runCatching { initialActionFocus.requestFocus() }
                        listState.scrollToItem(0)
                    }
                }
            }
        }
        // One vertical scroll policy for the whole page, instead of the app-wide comfort-zone
        // relocation nudging the list a little on every focus move:
        //  - Hero: exactly two resting positions, its top or (when taller than the screen) its
        //    bottom aligned with the screen. Focus picks whichever shows the focused action.
        //  - Sections: entering one glides its heading to the same spot below the utility bar,
        //    clamped at the page end so the trailing credits always come fully into view.
        //  - Moving within a row never moves the page vertically.
        val heroHasFocus = remember(item.ratingKey) { mutableStateOf(false) }
        val heroHeightPx = remember(item.ratingKey) { mutableIntStateOf(0) }
        val heroBottomMarginPx = with(LocalDensity.current) { 24.dp.roundToPx() }
        val baseBringIntoViewSpec = LocalBringIntoViewSpec.current
        val detailBringIntoViewSpec = remember(baseBringIntoViewSpec, heroHasFocus, listState) {
            DetailBringIntoViewSpec { offset, size, containerSize ->
                // How far the page is scrolled past its top, read from the hero's own position so
                // it also holds while the hero is still mostly above the screen.
                val heroInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                when {
                    !heroHasFocus.value -> 0f
                    heroInfo == null -> baseBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                    else -> {
                        val current = -heroInfo.offset.toFloat()
                        val margin = heroBottomMarginPx.toFloat()
                        // Where the requested area sits with the page at its very top.
                        val atTopY = offset + current
                        val bottomState = (listState.layoutInfo.beforeContentPadding + heroHeightPx.intValue - containerSize + margin)
                            .coerceAtLeast(0f)
                        val target = if (atTopY >= 0f && atTopY + size <= containerSize - margin) 0f else bottomState
                        val atTargetY = atTopY - target
                        if (atTargetY < 0f || atTargetY + size > containerSize) {
                            baseBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                        } else {
                            target - current
                        }
                    }
                }
            }
        }
        val sectionScrollJob = remember(item.ratingKey) { arrayOfNulls<Job>(1) }
        fun alignSection(key: String) {
            sectionScrollJob[0]?.cancel()
            sectionScrollJob[0] = scrollScope.launch {
                val info = listState.layoutInfo
                val target = info.visibleItemsInfo.firstOrNull { it.key == key }
                if (target == null) {
                    sectionKeys.indexOf(key).takeIf { it >= 0 }?.let { listState.animateTvScrollToItem(it) }
                    return@launch
                }
                var delta = target.offset.toFloat()
                val last = info.visibleItemsInfo.last()
                if (last.index == info.totalItemsCount - 1) {
                    val maxDelta = (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(0)
                    delta = delta.coerceAtMost(maxDelta.toFloat())
                }
                if (abs(delta) > 1f) listState.animateScrollBy(delta, TvScrollAnimationSpec)
            }
        }
        // Rows keep the app's horizontal relocation; only the page's own column uses the policy.
        @Composable
        fun Section(key: String, content: @Composable () -> Unit) {
            val hadFocus = remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalBringIntoViewSpec provides baseBringIntoViewSpec) {
                Box(
                    Modifier.onFocusChanged { focus ->
                        if (focus.hasFocus && !hadFocus.value) alignSection(key)
                        hadFocus.value = focus.hasFocus
                    },
                ) { content() }
            }
        }
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides detailBringIntoViewSpec,
            LocalDetailRowFocus provides DetailRowFocusContext(columnFocus, rowDefaults),
        ) {
        LazyColumn(
            wrapVerticalFocus = false,
            allowUpExit = true,
            focusActionsOnRight = false,
            state = listState,
            modifier = Modifier.fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key == Key.DirectionDown && heroHasFocus.value) {
                        enterPrimaryPending = true
                        true
                    } else {
                        enterPrimaryPending = false
                        false
                    }
                }
                .rowColumnFocusNavigation(
                    columnFocus,
                    scrollToRow = { rowKey ->
                        sectionKeys.indexOf(rowKey).takeIf { it >= 0 }?.let { listState.scrollToItem(it) }
                    },
                ) { rowOrder }
                .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange()
                }
            }.focusGroup(),
            contentPadding = PaddingValues(top = 72.dp, bottom = 36.dp),
            // Keep episode/similar/collection/cast groups on the same vertical rhythm as
            // grouped rows on YouTube Home.
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "hero") {
                Box(
                    Modifier
                        .rowColumnFocusTarget(columnFocus, "hero", "hero", soundsOnFocus = true)
                        .onSizeChanged { heroHeightPx.intValue = it.height }
                        .onFocusChanged { heroHasFocus.value = it.hasFocus },
                ) {
                DetailHero(
                    playbackAvailable = playbackAvailable,
                    availabilityMessage = availabilityMessage,                    onTrailer = onTrailer?.let { action -> { media -> restoreTrailer = true; action(media) } },
                    restoreTrailer = restoreTrailer && trailerAvailable,
                    trailerAvailable = trailerAvailable,
                    item = item,
                    nextEpisode = nextEpisode,
                    autoSelectDisplayVersion = autoSelectDisplayVersion,
                    onPlay = { media, version, restart -> restoreTrailer = false; onPlay(media, version, restart) },
                    onMore = {
                        moreOpened = true
                        showMore = true
                    },
                    canEdit = editSupport.any,
                    onHeroFocused = { rememberFocused(null) },
                    initialActionFocus = initialActionFocus,
                    moreActionFocus = moreActionFocus,
                )
                }
            }

            if ((item.type == "season" || item.type == "episode") && seasons.isNotEmpty()) {
                val seriesKey = seriesKeyForSeasonStrip(item.type, item.parentRatingKey, item.grandparentRatingKey)
                if (seriesKey != null) {
                    item(key = "season-strip") {
                        Section("season-strip") {
                            SeasonStrip(
                                seasons = seasons,
                                currentSeasonKey = currentSeasonKey(item.type, item.ratingKey, item.parentRatingKey),
                                onOpenSeries = { onOpenSeries(seriesKey) },
                                onOpenSeason = onOpenSeason,
                                restoreFocusKey = restoreFocusKey,
                                onFocused = { rememberFocused(it) },
                            )
                        }
                    }
                }
            }

            if (childrenSectionVisible) {
                item(key = "children") {
                    Section("children") {
                        if (item.type == "collection") {
                            CollectionItemsSection(
                                children, childrenLoading, childrenLoadFailed, onRetryChildren, onOpenChild,
                                restoreFocusKey,
                            ) { rememberFocused(it) }
                        } else {
                            ChildrenSection(
                                item, children, childrenLoading, childrenLoadFailed, onRetryChildren, onOpenChild,
                                onPlayEpisode = { onPlay(it, 0, false) },
                                onOpenEpisode = onOpenEpisode,
                                restoreFocusKey = restoreFocusKey,
                            ) { rememberFocused(it) }
                        }
                    }
                }
            }

            if (item.cast.isNotEmpty()) {
                item(key = "cast") {
                    Section("cast") {
                        CastSection(item, onBrowseActor, restoreFocusKey) { rememberFocused(it) }
                    }
                }
            }

            if (similar.isNotEmpty() || similarLoading) {
                item(key = "similar") {
                    Section("similar") {
                        SimilarSection(similar, similarLoading, onOpenChild, restoreFocusKey) { rememberFocused(it) }
                    }
                }
            }

            actorRows.forEach { row ->
                val key = "actor:${row.actorId}"
                item(key = key) {
                    Section(key) {
                        ActorRowSection(row, onOpenChild, restoreFocusKey) { rememberFocused(it) }
                    }
                }
            }

            if (item.collections.isNotEmpty()) {
                item(key = "collections") {
                    Section("collections") {
                        CollectionsSection(item, onBrowseCollection, restoreFocusKey) { rememberFocused(it) }
                    }
                }
            }

            if (item.directors.isNotEmpty() || item.writers.isNotEmpty() || item.studio != null || singleVersionFileName(item) != null) {
                item(key = "credits") { CreditsSection(item) }
            }

            item { Box(Modifier.height(36.dp)) }
        }
        }

        PageUtilityBar(
            onBack = onBack,
            onSettings = {
                rememberFocused(null)
                onOpenSettings()
            },
            onDown = {
                rememberFocused(null)
                if (heroHasFocusTarget) focusContentFromUtility() else enterPrimaryPending = true
            },
            requestInitialFocus = requestUtilityFocus,
            leftNavigatesBack = false,
            // Part of the page, not a sticky bar: it scrolls away with the hero above it.
            modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                translationY = if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset.toFloat()
                else -size.height
            },
        )

        if (showMore) {
            DetailContextMenu(
                playbackAvailable = playbackAvailable,
                item = item,
                busy = actionInProgress,
                onDismiss = { showMore = false },
                onPlayFromStart = {
                    showMore = false
                    onPlay(item, 0, true)
                },
                onSetWatched = { watched ->
                    showMore = false
                    onSetWatched(watched)
                },
                onRemoveFromContinue = {
                    showMore = false
                    onRemoveFromContinue()
                },
                editSupport = editSupport,
                onEdit = {
                    showMore = false
                    showEdit = true
                },
            )
        }
        if (showEdit && editSupport.any) {
            ItemEditDialog(
                item = item,
                support = editSupport,
                onDismiss = { showEdit = false },
                onEdited = onMetadataEdited,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class DetailBringIntoViewSpec(
    private val distance: (offset: Float, size: Float, containerSize: Float) -> Float,
) : BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = TvScrollAnimationSpec

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        distance(offset, size, containerSize)
}

private data class DetailRowFocusContext(val focus: RowColumnFocus, val defaults: Map<String, String>)
private val LocalDetailRowFocus = staticCompositionLocalOf<DetailRowFocusContext?> { null }

@Composable
private fun DetailHero(
    playbackAvailable: Boolean,
    availabilityMessage: Int?,
    onTrailer: ((MediaItem) -> Unit)?,
    restoreTrailer: Boolean,
    trailerAvailable: Boolean,
    item: MediaItem,
    nextEpisode: MediaItem?,
    autoSelectDisplayVersion: Boolean,
    onPlay: (MediaItem, Int, Boolean) -> Unit,
    onMore: (() -> Unit)?,
    canEdit: Boolean,
    onHeroFocused: () -> Unit,
    initialActionFocus: FocusRequester,
    moreActionFocus: FocusRequester,
) {
    val isPlayable = playbackAvailable && item.type !in setOf("show", "season")
    val maxDisplayPixels = rememberMaxDisplayPixels()
    val optimalIndex = remember(item.mediaVersions, maxDisplayPixels, autoSelectDisplayVersion) {
        if (item.mediaVersions.isEmpty()) 0
        else if (autoSelectDisplayVersion) pickOptimalVersionIndex(item.mediaVersions, maxDisplayPixels)
        else 0
    }
    val activeItem = com.ambient.tv.companion.playbackIndicator(item)
    val currentExact = activeItem?.takeIf { it.media.id in item.allRatingKeys || it.media.kind in setOf("file", "dlna", "device-local") }
    val openCurrent = com.ambient.tv.companion.LocalOpenActivePlayback.current
    val primaryPlayable = isPlayable && (currentExact != null || item.mediaVersions.getOrNull(optimalIndex)?.streamUrl != null)
    val playableNextEpisode = nextEpisode?.takeIf { playbackAvailable && it.mediaVersions.isNotEmpty() }
    val detailActions = detailActionCapabilities(
        type = item.type,
        playbackAvailable = playbackAvailable,
        hasMediaVersions = item.mediaVersions.isNotEmpty(),
        inProgress = (item.progressFraction ?: 0f) in 0.01f..0.94f,
        canEdit = canEdit,
    )

    DeviceActionRow(
        stack = handheldDetailStacks(LocalConfiguration.current.screenWidthDp),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.hasFocus) onHeroFocused() }
            .padding(start = screenGutter(), end = screenGutter(), top = com.ambient.tv.ui.common.pageHeaderTop(18.dp), bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Plex/Jellyfin detail pages use the wide artwork as the atmospheric background, but
        // the item poster/cover is still valuable visual identity. Keep it as a separate,
        // non-focusable element when the provider supplied one. When no thumb exists the text
        // column simply occupies the old position/width, so audio-only or sparse metadata items
        // retain the previous layout.
        val heroCoverUrl = if (item.type == "episode") item.posterArtUrl else item.thumbUrl
        heroCoverUrl?.takeIf { it.isNotBlank() }?.let { coverUrl ->
            DetailArtwork(
                item = item,
                model = coverUrl,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 860.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            // This is one semantic focus context: when focus returns to Play/Continue after the
            // user has browsed sections below, keep the synopsis and metadata visible with the
            // action instead of allowing Compose to expose only the button at the top edge.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // The initial programmatic Play focus is already visible at the top. Scrolling
                    // it into view again two frames later made every Detail opening visibly jump.
                    .bringFocusedContextIntoView(skipFirstFocus = true),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                val eyebrow = when (item.type) {
                    "episode" -> listOfNotNull(item.grandparentTitle, item.parentTitle, item.index?.let { stringResource(R.string.detail_episode_number, it) }).joinToString(" · ")
                    "season" -> item.parentTitle.orEmpty()
                    else -> item.studio.orEmpty()
                }
                if (eyebrow.isNotBlank()) {
                    Text(eyebrow, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Source mark and title/clear logo share one line, like the Home hero.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    com.ambient.tv.ui.common.SystemBadge(when {
                        com.ambient.tv.data.emby.EmbyBackend.owns(item.ratingKey) -> com.ambient.tv.ui.common.MediaSystem.EMBY
                        com.ambient.tv.data.jellyfin.JellyfinBackend.owns(item.ratingKey) -> com.ambient.tv.ui.common.MediaSystem.JELLYFIN
                        else -> com.ambient.tv.ui.common.MediaSystem.PLEX
                    })
                    TitleLogo(
                        title = item.title,
                        logoUrl = item.logoUrl,
                        textStyle = MaterialTheme.typography.headlineLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        maxLogoWidth = deviceDp(520.dp, 280.dp),
                        lockSlotToSingleLine = true,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                val primaryMeta = buildList {
                    item.year?.let { add(it.toString()) }
                    formatDuration(item.durationMs)?.let(::add)
                    if (item.type == "show") item.leafCount?.let { add(stringResource(R.string.detail_episode_count, it)) }
                }
                if (primaryMeta.isNotEmpty() || !item.contentRating.isNullOrBlank() || item.rating != null || item.audienceRating != null || item.criticRating != null) {
                    DeviceActionRow(
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (primaryMeta.isNotEmpty()) {
                            Text(
                                text = primaryMeta.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        item.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
                        if (item.rating != null || item.audienceRating != null || item.criticRating != null) {
                            PrimaryRatingScoreBadge(item)
                        }
                    }
                }

                // Technical information belongs on the detail page rather than being repeated as
                // permanent top-right icons on every library cover. Keep the most useful flags
                // immediately below the human metadata so they are easy to scan without
                // competing with the title or synopsis.
                StreamPropertyAdaptiveRows(item, maxItems = 5, itemsPerRow = 3)
                val activePlayback = com.ambient.tv.companion.playbackIndicator(item)
                com.ambient.tv.companion.PlaybackStatusSection(activePlayback)
                if (isPlayable && activePlayback == null) {
                    item.progressFraction?.takeIf { it > 0f && it < .94f }?.let { progress ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                stringResource(R.string.playback_resume_position, com.ambient.tv.companion.playbackTime(item.viewOffsetMs ?: 0L)),
                                color = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.text,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            com.ambient.tv.ui.common.PlaybackStatusBar(
                                progress = progress,
                                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .15f),
                            )
                        }
                    }
                }

                item.tagline?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic))
                }
                item.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 5)
                }
                if (item.genres.isNotEmpty()) {
                    Text(
                        item.genres.take(5).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                availabilityMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                DeviceActionRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isPlayable) {
                        PrimaryPlaybackActions(
                            item = item,
                            optimalIndex = optimalIndex,
                            onPlay = onPlay,
                            initialFocusRequester = if (primaryPlayable && !restoreTrailer) initialActionFocus else null,
                        )
                    }
                    if (playableNextEpisode != null) {
                        val nextPlaying = com.ambient.tv.companion.playbackIndicator(playableNextEpisode)
                        val hasProgress = (playableNextEpisode.progressFraction ?: 0f) in 0.01f..0.89f
                        TvActionButton(
                            text = stringResource(
                                if (nextPlaying != null) R.string.playback_open
                                else if (hasProgress) R.string.detail_continue_episode
                                else R.string.detail_next_episode,
                            ),
                            onClick = { if (nextPlaying != null) openCurrent(nextPlaying) else onPlay(playableNextEpisode, 0, false) },
                            modifier = if (!primaryPlayable && !restoreTrailer) Modifier.focusRequester(initialActionFocus) else Modifier,
                        )
                    }
                    if (onMore != null && detailActions.hasMenuActions) {
                        TvActionButton(
                            text = stringResource(R.string.detail_more_actions),
                            icon = Icons.Filled.MoreHoriz,
                            onClick = onMore,
                            modifier = Modifier
                                .focusRequester(moreActionFocus)
                                .then(
                                    if (!primaryPlayable && playableNextEpisode == null && !restoreTrailer) {
                                        Modifier.focusRequester(initialActionFocus)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    if (onTrailer != null && trailerAvailable) {
                        TvActionButton(
                            text = stringResource(R.string.trailers_play),
                            onClick = { onTrailer(item) },
                            modifier = if (restoreTrailer) Modifier.focusRequester(initialActionFocus) else Modifier,
                        )
                    }
                }
            }
            if (isPlayable && item.mediaVersions.size > 1) {
                MediaVersionPicker(item, optimalIndex, onPlay)
            }
        }
    }
}

@Composable
private fun DetailArtwork(
    item: MediaItem,
    model: Any,
) {
    val aspectRatio = when (item.type) {
        // Music artwork is usually square on both Plex and Jellyfin.
        "album", "artist", "track" -> 1f
        else -> 2f / 3f
    }
    val width = if (LocalHandheldUi.current) 144.dp else when (item.type) {
        "album", "artist", "track" -> 210.dp
        else -> 190.dp
    }
    val shape = RoundedCornerShape(14.dp)

    ArtworkImage(
        model = model,
        title = item.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(width)
            .aspectRatio(aspectRatio)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
    )
}

@Composable
private fun PrimaryPlaybackActions(
    item: MediaItem,
    optimalIndex: Int,
    onPlay: (MediaItem, Int, Boolean) -> Unit,
    initialFocusRequester: FocusRequester?,
) {
    val current = com.ambient.tv.companion.playbackIndicator(item)?.takeIf { it.media.id in item.allRatingKeys || it.media.kind in setOf("file", "dlna", "device-local") }
    val openCurrent = com.ambient.tv.companion.LocalOpenActivePlayback.current
    if (item.mediaVersions.isEmpty() && current == null) {
        if (detailShowsMissingPlaybackSource(item.type, hasMediaVersions = false, hasActivePlayback = false)) {
            Text(stringResource(R.string.detail_no_stream), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val hasProgress = (item.progressFraction ?: 0f) in 0.01f..0.94f
    val playLabel = if (current != null) stringResource(R.string.playback_open) else if (hasProgress) {
        val remaining = formatDuration(item.remainingMs)
        if (remaining != null) stringResource(R.string.detail_resume_remaining, remaining) else stringResource(R.string.detail_resume)
    } else stringResource(R.string.detail_play)
    TvActionButton(
        text = playLabel,
        icon = Icons.Filled.PlayArrow,
        onClick = { if (current != null) openCurrent(current) else onPlay(item, optimalIndex, false) },
        enabled = current != null || item.mediaVersions.getOrNull(optimalIndex)?.streamUrl != null,
        selected = true,
        modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
    )
    if (current != null) com.ambient.tv.companion.CompanionChooseDeviceAction { onPlay(item, optimalIndex, false) }
    if (hasProgress) {
        TvActionButton(text = stringResource(R.string.detail_restart), icon = Icons.Filled.RestartAlt, onClick = { onPlay(item, optimalIndex, true) })
    }
}

@Composable
private fun MediaVersionPicker(
    item: MediaItem,
    optimalIndex: Int,
    onPlay: (MediaItem, Int, Boolean) -> Unit,
) {
    val handheld = LocalHandheldUi.current
    Column(
        modifier = Modifier.bringFocusedContextIntoView(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.detail_versions), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        // Every version is listed; past four rows the list scrolls in place (D-pad focus scrolls it).
        Column(
            modifier = if (item.mediaVersions.size > VERSION_ROWS_VISIBLE)
                Modifier.heightIn(max = VERSION_ROW_HEIGHT * VERSION_ROWS_VISIBLE).verticalScroll(rememberScrollState())
            else Modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        item.mediaVersions.forEachIndexed { index, version ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = formatResolution(version.resolution) ?: "#${index + 1}"
                TvActionButton(
                    text = label,
                    onClick = { onPlay(item, index, false) },
                    enabled = version.streamUrl != null,
                    selected = index == optimalIndex,
                    modifier = Modifier.width(116.dp),
                    fillWidth = true,
                )
                Column(Modifier.weight(1f)) {
                    StreamPropertyRow(
                        version = version,
                        maxItems = 4,
                        // Narrow widths: badges scroll instead of being clipped.
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                    version.fileName?.takeIf { it.isNotBlank() }?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            maxLines = 1,
                            // Phones: long names scroll by swipe instead of being cut off.
                            overflow = if (handheld) TextOverflow.Clip else TextOverflow.Ellipsis,
                            modifier = if (handheld) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
                        )
                    }
                }
            }
        }
        }
    }
}

private const val VERSION_ROWS_VISIBLE = 4
private val VERSION_ROW_HEIGHT = 56.dp

@Composable
private fun RestoreDetailFocus(requested: Boolean, requester: FocusRequester) {
    val handheld = LocalHandheldUi.current
    LaunchedEffect(requested, handheld) {
        if (!requested || handheld) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}

/** Reveal a saved target before its tile/chip requests focus, including late-loaded rows. */
@Composable
private fun rememberDetailRowState(
    rowKey: String,
    focusKeys: List<String>,
    restoreFocusKey: String?,
): androidx.compose.foundation.lazy.LazyListState {
    val navigation = LocalDetailRowFocus.current
    val remembered = navigation?.focus?.rememberedItem(rowKey) as? String
    val defaultKey = navigation?.defaults?.get(rowKey)
    val entryKey = remembered?.takeIf { it in focusKeys }
        ?: restoreFocusKey?.takeIf { it in focusKeys }
        ?: defaultKey?.takeIf { it in focusKeys }
    val initialIndex = focusKeys.indexOf(entryKey).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    RegisterRowColumnFocus(navigation?.focus, rowKey, state, focusKeys, defaultItemKey = defaultKey)
    LaunchedEffect(restoreFocusKey, focusKeys) {
        val targetIndex = focusKeys.indexOf(restoreFocusKey)
        if (targetIndex >= 0) state.scrollToItem(targetIndex)
    }
    return state
}

private fun Modifier.detailRowTarget(row: String, key: String, focus: RowColumnFocus?, soundsOnFocus: Boolean = true): Modifier =
    rowColumnFocusTarget(focus, row, key, soundsOnFocus)

@Composable
private fun CollectionItemsSection(
    children: List<MediaItem>,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onOpenChild: (MediaItem) -> Unit,
    restoreFocusKey: String?,
    onFocused: (String) -> Unit,
) {
    val columnFocus = LocalDetailRowFocus.current?.focus
    Column(modifier = Modifier.fillMaxWidth().bringFocusedContextIntoView()) {
        Text(
            stringResource(R.string.collection_items_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = screenGutter(), vertical = 12.dp),
        )
        if (children.isNotEmpty()) {
            LazyRow(
                state = rememberDetailRowState("children", children.map { "children:${it.ratingKey}" }, restoreFocusKey),
                contentPadding = PaddingValues(horizontal = screenGutter()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.containRowFocusExit(),
            ) {
                items(children, key = { it.ratingKey }) { child ->
                    val focusKey = "children:${child.ratingKey}"
                    MediaTile(
                        modifier = Modifier.detailRowTarget("children", focusKey, columnFocus),
                        item = child,
                        style = MediaTileStyle.AUTO,
                        onClick = { onOpenChild(child) },
                        onFocused = { onFocused(focusKey) },
                        requestInitialFocus = restoreFocusKey == focusKey,
                    )
                }
            }
        }
        if (children.isEmpty() && loading) {
            DetailSkeletonTiles(MediaTileStyle.POSTER)
        } else if (failed || children.isEmpty()) {
            Column(
                modifier = Modifier.padding(horizontal = screenGutter()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(when {
                        loading -> R.string.player_loading
                        failed -> R.string.collection_items_failed
                        else -> R.string.collection_items_empty
                    }),
                    color = if (failed && !loading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (failed && !loading) TvActionButton(
                    stringResource(R.string.home_retry), onRetry,
                    modifier = Modifier.detailRowTarget("children", "children:retry", columnFocus)
                        .onFocusChanged { if (it.isFocused) onFocused("children:retry") },
                )
            }
        }
    }
}

@Composable
private fun SeasonStrip(
    seasons: List<MediaItem>,
    currentSeasonKey: String?,
    onOpenSeries: () -> Unit,
    onOpenSeason: (MediaItem) -> Unit,
    restoreFocusKey: String?,
    onFocused: (String) -> Unit,
) {
    val columnFocus = LocalDetailRowFocus.current?.focus
    val seriesFocus = "season-strip:series"
    Column(modifier = Modifier.fillMaxWidth().bringFocusedContextIntoView()) {
        LazyRow(
            state = rememberDetailRowState(
                "season-strip", listOf(seriesFocus) + seasons.map { "season-strip:${it.ratingKey}" }, restoreFocusKey,
            ),
            contentPadding = PaddingValues(horizontal = screenGutter()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.containRowFocusExit(),
        ) {
            item(key = "series") {
                val requester = remember { FocusRequester() }
                val restore = restoreFocusKey == seriesFocus
                TvChip(
                    text = stringResource(R.string.detail_series),
                    onClick = onOpenSeries,
                    modifier = Modifier.detailRowTarget("season-strip", seriesFocus, columnFocus)
                        .onFocusChanged { if (it.isFocused) onFocused(seriesFocus) }
                        .then(if (restore) Modifier.focusRequester(requester) else Modifier),
                )
                RestoreDetailFocus(restore, requester)
            }
            items(seasons, key = { it.ratingKey }) { season ->
                val focusKey = "season-strip:${season.ratingKey}"
                val requester = remember(focusKey) { FocusRequester() }
                val restore = restoreFocusKey == focusKey
                val selected = currentSeasonKey != null && currentSeasonKey in season.allRatingKeys
                TvChip(
                    text = season.title.ifBlank { season.index?.toString().orEmpty() },
                    selected = selected,
                    textMaxLines = 1,
                    onClick = { if (!selected) onOpenSeason(season) },
                    modifier = Modifier.detailRowTarget("season-strip", focusKey, columnFocus)
                        .onFocusChanged { if (it.isFocused) onFocused(focusKey) }
                        .then(if (restore) Modifier.focusRequester(requester) else Modifier),
                )
                RestoreDetailFocus(restore, requester)
            }
        }
    }
}

@Composable
private fun ChildrenSection(
    item: MediaItem,
    children: List<MediaItem>,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onOpenChild: (MediaItem) -> Unit,
    onPlayEpisode: (MediaItem) -> Unit,
    onOpenEpisode: (MediaItem) -> Unit,
    restoreFocusKey: String?,
    onFocused: (String) -> Unit,
) {
    val columnFocus = LocalDetailRowFocus.current?.focus
    Column(modifier = Modifier.fillMaxWidth().bringFocusedContextIntoView()) {
        if (item.type == "show" && children.isEmpty() && loading) {
            Box(Modifier.fillMaxWidth().height(160.dp).padding(horizontal = screenGutter()), contentAlignment = Alignment.CenterStart) {
                Text(stringResource(R.string.player_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        val seasons = children.firstOrNull()?.type == "season"
        if (children.isNotEmpty() || item.type != "show") {
            Text(
                stringResource(if (seasons) R.string.detail_seasons else R.string.detail_episodes),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = screenGutter(), end = screenGutter(), bottom = 12.dp),
            )
        }
        if (seasons && children.isNotEmpty()) {
            // contentPadding (not an outer Modifier.padding) so a focused item's scale-up can
            // render into that margin instead of being clipped by the row's own layout bounds.
            LazyRow(
                state = rememberDetailRowState("children", children.map { "children:${it.ratingKey}" }, restoreFocusKey),
                contentPadding = PaddingValues(horizontal = screenGutter()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.containRowFocusExit(),
            ) {
                items(children, key = { it.ratingKey }) { season ->
                    val focusKey = "children:${season.ratingKey}"
                    // The poster tile carries the season cover plus its own watched/unwatched badge.
                    MediaTile(
                        modifier = Modifier.detailRowTarget("children", focusKey, columnFocus),
                        item = season,
                        style = MediaTileStyle.POSTER,
                        onClick = { onOpenChild(season) },
                        onFocused = { onFocused(focusKey) },
                        requestInitialFocus = restoreFocusKey == focusKey,
                    )
                }
            }
        } else if (children.isNotEmpty()) {
            val currentEpisodeIndex = children.indexOfFirst {
                "children:${it.ratingKey}" == restoreFocusKey ||
                    (restoreFocusKey == null && item.type == "episode" && it.ratingKey == item.ratingKey)
            }.coerceAtLeast(0)
            val episodeListState = rememberDetailRowState(
                "children", children.map { "children:${it.ratingKey}" }, restoreFocusKey,
            )
            val scope = rememberCoroutineScope()
            val requesters = remember(children.map { it.ratingKey }) { children.map { FocusRequester() } }
            var focusedIndex by remember(children.map { it.ratingKey }) { mutableIntStateOf(currentEpisodeIndex) }
            var wrapping by remember { mutableStateOf(false) }
            LazyRow(
                state = episodeListState,
                contentPadding = PaddingValues(horizontal = screenGutter()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.containRowFocusExit().onPreviewKeyEvent { event ->
                    if (wrapping && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) return@onPreviewKeyEvent true
                    val target = when {
                        event.key == Key.DirectionLeft && focusedIndex == 0 -> children.lastIndex
                        event.key == Key.DirectionRight && focusedIndex == children.lastIndex -> 0
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown && !wrapping && target >= 0) {
                        wrapping = true
                        scope.launch {
                            try {
                                episodeListState.scrollToItem(target)
                                repeat(3) {
                                    withFrameNanos { }
                                    if (runCatching { requesters[target].requestFocus() }.getOrDefault(false)) return@launch
                                }
                            } finally { wrapping = false }
                        }
                    }
                    true
                },
            ) {
                items(children.size, key = { children[it].ratingKey }) { index ->
                    val child = children[index]
                    val focusKey = "children:${child.ratingKey}"
                    MediaTile(
                        modifier = Modifier.detailRowTarget("children", focusKey, columnFocus)
                            .focusRequester(requesters[index]).onFocusChanged { if (it.isFocused) focusedIndex = index },
                        item = child,
                        style = MediaTileStyle.EPISODE,
                        showDescription = false,
                        onClick = {
                            if (detailChildPlaysDirectly(child.type)) onPlayEpisode(child) else onOpenChild(child)
                        },
                        onInfo = if (detailChildPlaysDirectly(child.type)) ({ onOpenEpisode(child) }) else null,
                        onFocused = { onFocused(focusKey) },
                        requestInitialFocus = restoreFocusKey == focusKey,
                    )
                }
            }
        }
        if (children.isEmpty() && loading) {
            DetailSkeletonTiles(if (seasons) MediaTileStyle.POSTER else MediaTileStyle.EPISODE)
        } else if (children.isEmpty()) {
            Column(
                modifier = Modifier.padding(horizontal = screenGutter()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(when {
                        loading -> R.string.player_loading
                        failed -> R.string.detail_children_failed
                        else -> R.string.detail_children_empty
                    }),
                    color = if (failed && !loading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (failed && !loading) TvActionButton(
                    stringResource(R.string.home_retry), onRetry,
                    modifier = Modifier.detailRowTarget("children", "children:retry", columnFocus)
                        .onFocusChanged { if (it.isFocused) onFocused("children:retry") },
                )
            }
        }
        if (item.type == "season" && children.isNotEmpty()) {
            val watched = children.count { it.isWatched }
            Text(
                stringResource(R.string.detail_episode_progress, watched, children.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = screenGutter(), end = screenGutter(), top = 12.dp),
            )
        }
    }
}


/** Tile-sized placeholders, so a row keeps its final height while its content loads. */
@Composable
private fun DetailSkeletonTiles(style: MediaTileStyle) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = screenGutter()),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        userScrollEnabled = false,
    ) {
        // Static: a detail row loads briefly and must not keep the frame clock busy.
        items(8) { com.ambient.tv.ui.common.SkeletonTile(style = style, showDescription = style != MediaTileStyle.EPISODE, shimmer = false) }
    }
}

@Composable
private fun SimilarSection(
    items: List<MediaItem>,
    loading: Boolean,
    onOpenChild: (MediaItem) -> Unit,
    restoreFocusKey: String?,
    onFocused: (String) -> Unit,
) {
    val columnFocus = LocalDetailRowFocus.current?.focus
    Column(modifier = Modifier.fillMaxWidth().bringFocusedContextIntoView()) {
        Text(
            stringResource(R.string.detail_more_like_this),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = screenGutter(), end = screenGutter(), bottom = 12.dp),
        )
        if (items.isEmpty() && loading) {
            DetailSkeletonTiles(MediaTileStyle.POSTER)
            return@Column
        }
        LazyRow(
            state = rememberDetailRowState("similar", items.map { "similar:${it.ratingKey}" }, restoreFocusKey),
            contentPadding = PaddingValues(horizontal = screenGutter()),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.containRowFocusExit(),
        ) {
            items(items, key = { it.ratingKey }) { related ->
                val focusKey = "similar:${related.ratingKey}"
                MediaTile(
                    modifier = Modifier.detailRowTarget("similar", focusKey, columnFocus),
                    item = related,
                    style = MediaTileStyle.POSTER,
                    onClick = { onOpenChild(related) },
                    onFocused = { onFocused(focusKey) },
                    requestInitialFocus = restoreFocusKey == focusKey,
                )
            }
        }
    }
}

@Composable
private fun ActorRowSection(
    row: ActorRow,
    onOpenChild: (MediaItem) -> Unit,
    restoreFocusKey: String?,
    onFocused: (String) -> Unit,
) {
    val columnFocus = LocalDetailRowFocus.current?.focus
    Column(modifier = Modifier.fillMaxWidth().bringFocusedContextIntoView()) {
        Text(
            stringResource(R.string.detail_more_with, row.name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = screenGutter(), end = screenGutter(), bottom = 12.dp),
        )
        val prefix = "actor:${row.actorId}\u0000"
        LazyRow(
            state = rememberDetailRowState("actor:${row.actorId}", row.items.map { prefix + it.ratingKey }, restoreFocusKey),
            contentPadding = PaddingValues(horizontal = screenGutter()),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.containRowFocusExit(),
        ) {
            items(row.items, key = { it.ratingKey }) { media ->
                val focusKey = prefix + media.ratingKey
                MediaTile(
                    modifier = Modifier.detailRowTarget("actor:${row.actorId}", focusKey, columnFocus),
                    item = media,
                    style = MediaTileStyle.POSTER,
                    onClick = { onOpenChild(media) },
                    onFocused = { onFocused(focusKey) },
                    requestInitialFocus = restoreFocusKey == focusKey,
                )
            }
        }
    }
}

@Composable
private fun DetailContextMenu(
    playbackAvailable: Boolean,
    item: MediaItem,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPlayFromStart: () -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onRemoveFromContinue: () -> Unit,
    editSupport: com.ambient.tv.domain.model.MetadataEditSupport,
    onEdit: () -> Unit,
) {
    val firstFocus = remember(item.ratingKey) { FocusRequester() }
    val handheld = LocalHandheldUi.current
    // Portrait phones: the TV card fraction leaves only ~40dp beside the cover for the actions.
    val narrow = com.ambient.tv.ui.mobile.isNarrowHandheldWindow()
    val inProgress = (item.progressFraction ?: 0f) in 0.01f..0.94f
    val capabilities = detailActionCapabilities(
        type = item.type,
        playbackAvailable = playbackAvailable,
        hasMediaVersions = item.mediaVersions.isNotEmpty(),
        inProgress = inProgress,
        canEdit = editSupport.any,
    )
    val metadata = buildList {
        item.year?.let { add(it.toString()) }
        formatDuration(item.durationMs)?.let(::add)
    }
    LaunchedEffect(item.ratingKey) { delay(60); runCatching { firstFocus.requestFocus() } }
    // While an action runs the buttons are disabled: move focus to Back (which then owns firstFocus)
    // instead of leaving it on a disabled button.
    LaunchedEffect(busy) { if (busy) { delay(16); runCatching { firstFocus.requestFocus() } } }
    AppDialog(
        onDismiss = onDismiss,
        title = item.title,
        hint = stringResource(R.string.detail_more_actions_hint),
        captureInitialFocus = false,
        widthFraction = if (handheld) null else 0.64f,
        minWidth = deviceDp(680.dp, 0.dp),
        maxWidth = 800.dp,
        // Cover plus up to three actions are taller than the standard 72 % panel leaves for
        // content on a 1080p TV, which made the dialog scroll as focus moved between actions.
        maxHeightFraction = 0.9f,
        footer = {
            TvActionButton(
                text = stringResource(R.string.back),
                onClick = onDismiss,
                modifier = if (busy || !capabilities.hasMenuActions) Modifier.focusRequester(firstFocus) else Modifier,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (narrow) 12.dp else 24.dp), verticalAlignment = Alignment.Top) {
            ArtworkImage(
                model = item.thumbUrl ?: item.artUrl,
                title = item.title,
                modifier = Modifier
                    .width(if (narrow) 72.dp else 132.dp)
                    .height(if (narrow) 104.dp else 190.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (metadata.isNotEmpty() || !item.contentRating.isNullOrBlank() || item.rating != null || item.audienceRating != null || item.criticRating != null) {
                    MetaBadgeRow(
                        badges = metadata,
                        contentRating = item.contentRating,
                        rating = item.rating,
                        audienceRating = item.audienceRating,
                        criticRating = item.criticRating,
                    )
                }
                if (capabilities.canRestart) {
                    DetailContextAction(
                        text = stringResource(R.string.detail_restart),
                        icon = Icons.Filled.RestartAlt,
                        onClick = onPlayFromStart,
                        enabled = !busy,
                        modifier = if (!busy) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                if (capabilities.canSetWatched) {
                    DetailContextAction(
                        text = stringResource(if (item.isWatched) R.string.detail_mark_unwatched else R.string.detail_mark_watched),
                        icon = if (item.isWatched) Icons.Filled.VisibilityOff else Icons.Filled.CheckCircle,
                        onClick = { onSetWatched(!item.isWatched) },
                        enabled = !busy,
                        modifier = if (!busy && !capabilities.canRestart) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                if (capabilities.canRemoveFromContinue) {
                    DetailContextAction(
                        text = stringResource(R.string.detail_remove_continue_watching),
                        icon = Icons.Filled.PlaylistRemove,
                        onClick = onRemoveFromContinue,
                        enabled = !busy,
                        modifier = if (!busy && !capabilities.canRestart && !capabilities.canSetWatched) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                if (capabilities.canEdit) {
                    DetailContextAction(
                        text = stringResource(R.string.detail_edit),
                        icon = Icons.Filled.Edit,
                        onClick = onEdit,
                        enabled = !busy,
                        modifier = if (!busy && !capabilities.canRestart && !capabilities.canSetWatched && !capabilities.canRemoveFromContinue) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DetailContextAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subdued: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    androidx.tv.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        border = noSurfaceBorder(),
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .activateOnTvKey(enabled, onClick)
            .focusProperties { canFocus = enabled || focused }
            .onFocusChanged { focused = it.isFocused },
    ) {
        val container = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
            focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.19f)
            subdued -> Color.Transparent
            else -> Color.White.copy(alpha = 0.045f)
        }
        val foreground = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
            focused -> MaterialTheme.colorScheme.onSurface
            subdued -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(container, shape)
                .border(
                    1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = if (subdued) 0.06f else 0.10f),
                    shape,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = if (focused) MaterialTheme.colorScheme.primary else foreground,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CollectionsSection(
    item: MediaItem,
    onBrowseCollection: (String, String) -> Unit,
    restoreFocusKey: String?,
    onFocused: (String) -> Unit,
) {
    val columnFocus = LocalDetailRowFocus.current?.focus
    Column(modifier = Modifier.fillMaxWidth().bringFocusedContextIntoView()) {
        Text(stringResource(R.string.detail_collections), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = screenGutter(), end = screenGutter(), bottom = 10.dp))
        LazyRow(
            state = rememberDetailRowState("collections", item.collections.map { "collection:${it.id ?: it.name}" }, restoreFocusKey),
            contentPadding = PaddingValues(horizontal = screenGutter()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.containRowFocusExit(),
        ) {
            items(item.collections, key = { "${it.id}-${it.name}" }) { collection ->
                val focusKey = "collection:${collection.id ?: collection.name}"
                val requester = remember(collection.id, collection.name) { FocusRequester() }
                RestoreDetailFocus(restoreFocusKey == focusKey, requester)
                TvChip(
                    text = collection.name,
                    onClick = { collection.id?.let { onBrowseCollection(it, collection.name) } },
                    enabled = collection.id != null,
                    modifier = Modifier.detailRowTarget("collections", focusKey, columnFocus)
                        .focusRequester(requester).onFocusChanged { if (it.isFocused) onFocused(focusKey) },
                )
            }
        }
    }
}

@Composable
private fun CastSection(
    item: MediaItem,
    onBrowseActor: (String, String) -> Unit,
    restoreFocusKey: String?,
    onFocused: (String) -> Unit,
) {
    val columnFocus = LocalDetailRowFocus.current?.focus
    Column(modifier = Modifier.fillMaxWidth().bringFocusedContextIntoView()) {
        Text(stringResource(R.string.detail_cast), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = screenGutter(), end = screenGutter(), bottom = 12.dp))
        LazyRow(
            state = rememberDetailRowState("cast", item.cast.dedupeCastMembers().take(16).map { "cast:${it.id ?: it.name}" }, restoreFocusKey),
            contentPadding = PaddingValues(horizontal = screenGutter()),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.containRowFocusExit(),
        ) {
            items(item.cast.dedupeCastMembers().take(16), key = { it.id ?: it.name }) { member ->
                val focusKey = "cast:${member.id ?: member.name}"
                val requester = remember(member.id, member.name) { FocusRequester() }
                RestoreDetailFocus(restoreFocusKey == focusKey, requester)
                var isFocused by remember(member.id, member.name) { mutableStateOf(false) }
                // Fade the focus ring in/out instead of toggling it, so moving along the row glides.
                val ringWidth by animateDpAsState(if (isFocused) 2.dp else 0.dp, tween(180), label = "castRing")
                val ringColor by animateColorAsState(
                    if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                    tween(180),
                    label = "castRingColor",
                )
                androidx.tv.material3.Surface(
                    onClick = { member.id?.let { onBrowseActor(it, member.name) } },
                    enabled = member.id != null,
                    colors = transparentSurfaceColors(),
                    // No Modifier.scale() here, so also no reserved padding for one - rather
                    // than fight Surface's own default pop-and-grow (which had nothing to
                    // reserve space for and just overlapped the name below), focus is shown
                    // with a border around the portrait instead, like the rest of the app.
                    scale = noSurfaceScale(),
                    border = noSurfaceBorder(),
                    modifier = Modifier.width(112.dp).detailRowTarget("cast", focusKey, columnFocus, soundsOnFocus = false)
                        .focusRequester(requester)
                        .activateOnTvKey(enabled = member.id != null, onClick = { member.id?.let { onBrowseActor(it, member.name) } })
                        .onFocusChanged {
                            isFocused = it.isFocused
                            if (it.isFocused) onFocused(focusKey)
                        },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                        ArtworkImage(
                            model = member.thumbUrl,
                            title = member.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(92.dp).clip(CircleShape)
                                .border(ringWidth, ringColor, CircleShape),
                        )
                        Text(member.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        member.role?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditsSection(item: MediaItem) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = screenGutter()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.detail_more_info), style = MaterialTheme.typography.titleMedium)
        item.directors.takeIf { it.isNotEmpty() }?.let { Text(stringResource(R.string.detail_directors, it.joinToString(", ")), style = MaterialTheme.typography.bodySmall) }
        item.writers.takeIf { it.isNotEmpty() }?.let { Text(stringResource(R.string.detail_writers, it.joinToString(", ")), style = MaterialTheme.typography.bodySmall) }
        item.studio?.let { Text(stringResource(R.string.detail_studio, it), style = MaterialTheme.typography.bodySmall) }
        item.originallyAvailableAt?.let { Text(stringResource(R.string.detail_release_date, it), style = MaterialTheme.typography.bodySmall) }
        // With several versions the picker lists each file; a single file is named here instead.
        singleVersionFileName(item)?.let { name ->
            Text(
                stringResource(R.string.detail_file_name, name),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

private fun singleVersionFileName(item: MediaItem): String? =
    item.mediaVersions.singleOrNull()?.fileName?.takeIf { it.isNotBlank() }

@Composable
private fun rememberMaxDisplayPixels(): Long {
    val context = LocalContext.current
    return remember {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        // 4K TVs often render the UI at 1080p; the physical mode is what the video plays on.
        val mode = display?.mode
        maxDisplayPixels(mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0, metrics.widthPixels, metrics.heightPixels)
    }
}

internal fun maxDisplayPixels(modeWidth: Int, modeHeight: Int, uiWidth: Int, uiHeight: Int): Long {
    val pixels = maxOf(modeWidth.toLong() * modeHeight.toLong(), uiWidth.toLong() * uiHeight.toLong())
    return if (pixels > 0) pixels else Long.MAX_VALUE
}

private fun pickOptimalVersionIndex(versions: List<MediaVersion>, maxPixels: Long): Int {
    val indexed = versions.mapIndexed { index, version -> index to resolutionPixelCount(version.resolution) }
    return indexed.filter { (_, pixels) -> pixels != null && pixels <= maxPixels }
        .maxByOrNull { (_, pixels) -> pixels!! }
        ?.first
        ?: indexed.filter { (_, pixels) -> pixels != null }.minByOrNull { (_, pixels) -> pixels!! }?.first
        ?: 0
}

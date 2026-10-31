package com.ambient.tv.ui.photos

import com.ambient.tv.ui.common.leftKeyAction
import com.ambient.tv.ui.common.sortDirectionOptions
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.ambient.tv.ui.common.TvLazyVerticalGrid as LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.ambient.tv.R
import com.ambient.tv.data.photos.PhotoEntry
import com.ambient.tv.data.photos.PhotoKind
import com.ambient.tv.data.photos.PhotoSort
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.FilterBar
import com.ambient.tv.ui.common.FilterBarModel
import com.ambient.tv.ui.common.FilterOptionsPanel
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.common.LocalFilterFocusState
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.PanelOption
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.rememberFilterFocusState
import com.ambient.tv.ui.common.retryFocusFor
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.fillWindowHeight
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.BrowseFocusContentIdentity
import com.ambient.tv.ui.common.BrowseFocusInitialTarget
import com.ambient.tv.ui.common.BrowseFocusKey
import com.ambient.tv.ui.common.BrowseFocusSlot
import com.ambient.tv.ui.common.RegisterBrowseFocusSlots
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.LocalBrowseFocusReturnState
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Photo library/album browser shared by Plex, Jellyfin, Emby and Files. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PhotoBrowserScreen(
    rootKey: String,
    title: String,
    onOpenViewer: () -> Unit,
    onPlayVideo: (String) -> Unit,
    /** At library root: focus sidebar like Files instead of popping to Home. */
    onBack: (() -> Unit)? = null,
    viewModel: PhotoBrowserViewModel = hiltViewModel(key = "photos:$rootKey"),
) {
    // Resolve the viewer's return address before the shared owner can choose its first target.
    var viewerReturnApplied by remember(viewModel) { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.applyViewerReturn()
        viewerReturnApplied = true
    }
    val state by viewModel.state.collectAsState()
    val source = when {
        FileKeys.owns(rootKey) -> "files:${FileKeys.parse(rootKey).first}"
        com.ambient.tv.data.jellyfin.JellyfinBackend.owns(rootKey) -> "jellyfin"
        com.ambient.tv.data.emby.EmbyBackend.owns(rootKey) -> "emby"
        else -> "plex"
    }
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(setOf(source)) { viewModel.enter(rootKey, title) }
    ReportSidebarContentReady(!state.loading && viewerReturnApplied)
    val tokens = LocalUiTokens.current
    var showOptions by remember(rootKey) { mutableStateOf(false) }
    var restoreOptionsFocus by remember(rootKey) { mutableStateOf(false) }
    val optionsFocus = remember { FocusRequester() }
    val filterFocusState = rememberFilterFocusState(rootKey, state.current?.key, state.timeline)
    BackHandler(enabled = !showOptions && (!state.atRoot || state.timeline || onBack != null)) {
        if (!state.atRoot || state.timeline) viewModel.back() else onBack?.invoke()
    }
    fun closeOptions() {
        showOptions = false
        restoreOptionsFocus = true
        filterFocusState.keepControls()
    }
    LaunchedEffect(showOptions, restoreOptionsFocus) {
        if (!showOptions && restoreOptionsFocus) {
            withFrameNanos { }
            retryFocusFor { runCatching { optionsFocus.requestFocus() }.getOrDefault(false) }
            restoreOptionsFocus = false
        }
    }

    val gridState = rememberLazyGridState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val visible = if (state.timeline) state.timelineGroups.flatMap { it.entries } else state.entries
    val folder = rootKey to state.current?.key
    var focusFolder by remember { mutableStateOf(folder) }
    BrowseFocusContentIdentity(
        listOf(folder, state.timeline, state.sort),
        restoreInitialTarget = focusFolder != folder,
    )
    SideEffect { focusFolder = folder }
    val focusSlots = remember(state.timeline, state.timelineGroups, state.entries) {
        buildList {
            var index = 0
            if (state.timeline) {
                state.timelineGroups.forEach { group ->
                    index++ // The month header occupies a physical LazyGrid slot, but cannot receive focus.
                    group.entries.forEach { entry ->
                        add(BrowseFocusSlot(BrowseFocusKey(null, entry.id, true), index++))
                    }
                }
            } else {
                state.entries.forEach { entry ->
                    add(BrowseFocusSlot(BrowseFocusKey(null, entry.id, true), index++))
                }
            }
        }
    }
    RegisterBrowseFocusSlots(gridState, focusSlots)
    if (viewerReturnApplied && !state.loading && !state.failed) {
        BrowseFocusInitialTarget(state.focusId?.takeIf { id -> visible.any { it.id == id } } ?: visible.firstOrNull()?.id)
    }
    var initialFocusDone by remember(state.current?.key, state.timeline) { mutableStateOf(false) }
    val sharedFocusOwner = LocalBrowseFocusReturnState.current != null
    LaunchedEffect(state.current?.key, state.timeline, state.loading, viewerReturnApplied, sharedFocusOwner, showOptions, filterFocusState.allowContentAutoFocus) {
        if (!viewerReturnApplied || showOptions || !filterFocusState.allowContentAutoFocus || state.loading || initialFocusDone || visible.isEmpty()) return@LaunchedEffect
        if (sharedFocusOwner) {
            initialFocusDone = true
            return@LaunchedEffect
        }
        val target = viewModel.state.value.focusId?.takeIf { id -> visible.any { it.id == id } } ?: visible.first().id
        val index = if (state.timeline) {
            // Header rows occupy grid slots too.
            var slot = 0
            run {
                state.timelineGroups.forEach { group ->
                    slot++
                    val inGroup = group.entries.indexOfFirst { it.id == target }
                    if (inGroup >= 0) { slot += inGroup; return@run }
                    slot += group.entries.size
                }
            }
            slot
        } else visible.indexOfFirst { it.id == target }
        if (index >= 0) gridState.scrollToItem(index)
        repeat(6) {
            withFrameNanos { }
            if (showOptions || !filterFocusState.allowContentAutoFocus) return@LaunchedEffect
            if (runCatching { requester(target).requestFocus() }.getOrDefault(false)) { initialFocusDone = true; return@LaunchedEffect }
        }
    }

    fun open(entry: PhotoEntry) {
        when (entry.kind) {
            PhotoKind.FOLDER -> viewModel.openFolder(entry)
            PhotoKind.VIDEO -> { viewModel.rememberFocus(entry.id); entry.playKey?.let(onPlayVideo) }
            PhotoKind.PHOTO -> { viewModel.prepareViewer(entry); onOpenViewer() }
        }
    }

    val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
    val filterFocus = remember { FocusRequester() }
    val timelineAvailable = state.current?.key?.let(FileKeys::owns) == false
    val filterBarShown = !state.timeline && LocalFilterBarSetting.current.shown
    val sortModel = FilterBarModel(
        sorts = listOf(
            PanelOption("DATE", stringResource(R.string.photos_sort_date)),
            PanelOption("NAME", stringResource(R.string.photos_sort_name)),
        ),
        selectedSort = if (state.sort.byDate) "DATE" else "NAME",
        onSort = { viewModel.setSortField(it == "DATE") },
        sortOrders = photoSortOrderOptions(state.sort),
        selectedSortOrder = if (state.sort.descending) "DESCENDING" else "ASCENDING",
        onSortOrder = { viewModel.setSort(PhotoSort.of(state.sort.byDate, it == "DESCENDING")) },
    )
    CompositionLocalProvider(LocalFilterFocusState provides filterFocusState) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()
                .focusProperties { onEnter = { if (showOptions) cancelFocusChange() } }.focusGroup()
                .padding(start = tokens.screenPadding, end = tokens.screenPadding, bottom = tokens.screenPadding, top = com.ambient.tv.ui.common.pageHeaderTop(tokens.screenPadding)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PageHeader(focusChain = focusChain, actions = {
                    if (!state.atRoot || state.timeline) TvChip(stringResource(R.string.photos_back), modifier = Modifier.leftKeyAction { viewModel.back() }, icon = Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true) { viewModel.back() }
                    TvChip(stringResource(R.string.photos_slideshow), enabled = !state.loading, icon = Icons.Filled.Slideshow, iconOnly = true) {
                        if (viewModel.prepareSlideshow()) onOpenViewer()
                    }
                    TvChip(stringResource(R.string.action_reload), icon = Icons.Filled.Refresh,
                        iconOnly = true, loading = state.loading || state.refreshing,
                        onClick = { filterFocusState.keepControls(); viewModel.reload() })
                    // Timeline is fixed to chronological month groups; it has no sort/layout options.
                    if (!state.timeline) ViewOptionsChip(0, {
                        filterFocusState.keepControls()
                        showOptions = true
                    }, Modifier.focusRequester(optionsFocus))
                }) {
                    Text(
                        state.stack.joinToString(" › ") { it.title }.ifBlank { title },
                        style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.refreshFailed) Text(stringResource(R.string.photos_error), color = MaterialTheme.colorScheme.error)
                // The timeline needs a date index, which only the media servers provide.
                // Do not register an empty chip row for Files: its Up path goes straight to the header.
                if (timelineAvailable) {
                    com.ambient.tv.ui.common.PageChipRow(focusChain) {
                        item {
                            TvChip(
                                stringResource(if (state.timeline) R.string.photos_folders else R.string.photos_timeline),
                                selected = state.timeline, icon = Icons.Filled.CalendarMonth, onClick = viewModel::toggleTimeline,
                                modifier = Modifier.focusRequester(focusChain.selectedChip),
                            )
                        }
                    }
                }
                if (filterBarShown) {
                    FilterBar(sortModel, focusRequester = filterFocus,
                        up = if (timelineAvailable) focusChain.chips else focusChain.actions, focusChain = focusChain)
                }
                when {
                    (state.loading || state.refreshing) && visible.isEmpty() -> StatePanel(stringResource(R.string.photos_loading))
                    state.failed -> StatePanel(stringResource(R.string.photos_error), actionLabel = stringResource(R.string.photos_retry), onAction = viewModel::retry)
                    visible.isEmpty() -> StatePanel(stringResource(R.string.photos_empty))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(220.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().pageContentUp(focusChain) {
                            if (filterBarShown) filterFocus else focusChain.contentUp
                        }.onFocusChanged {
                            if (it.hasFocus) {
                                initialFocusDone = true
                                filterFocusState.enterContent()
                            }
                        }.focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (state.timeline) {
                            state.timelineGroups.forEach { group ->
                                item(key = "month:${group.month}", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        group.month?.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
                                            ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                                            ?: stringResource(R.string.photos_no_date),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                                items(group.entries, key = { "t:" + it.id }) { entry ->
                                    PhotoTile(entry, Modifier.focusRequester(requester(entry.id))) { open(entry) }
                                }
                            }
                        } else {
                            items(state.entries, key = { it.id }) { entry ->
                                PhotoTile(entry, Modifier.focusRequester(requester(entry.id))) { open(entry) }
                            }
                        }
                    }
                }
            }
            if (showOptions) {
                Box(Modifier.fillWindowHeight().fillMaxSize().background(Color.Black.copy(alpha = 0.38f))
                    .clickable { closeOptions() })
                FilterOptionsPanel(
                    title = stringResource(R.string.library_options),
                    model = sortModel,
                    onClose = ::closeOptions,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
private fun PhotoTile(entry: PhotoEntry, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalUiTokens.current.cardRadius)
    Box(
        modifier
            .browseFocusTarget(entry.id)
            .mediaFocusMotion(focused)
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .onFocusChanged { focused = it.isFocused }
            .activateOnTvKey(onClick = onClick)
            .clickable(onClick = onClick)
            .focusable()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .appControlSurface(focused, shape = shape),
    ) {
        val model = entry.thumbModel()
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = entry.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = com.ambient.tv.ui.display.uiHdrBitmapColorFilter(
                    com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f,
                ),
            )
        } else if (entry.kind == PhotoKind.FOLDER) {
            Icon(Icons.Filled.Folder, null, Modifier.size(56.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.kind == PhotoKind.VIDEO) {
            Icon(Icons.Filled.PlayCircle, null, Modifier.size(52.dp).align(Alignment.Center), tint = Color.White.copy(alpha = 0.9f))
        }
        // Folders and videos are named; photos let the picture speak (file names are noise).
        if (entry.kind != PhotoKind.PHOTO || focused) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (entry.kind == PhotoKind.FOLDER) Icon(Icons.Filled.Folder, null, Modifier.size(18.dp), tint = Color.White)
                MarqueeTitle(entry.title, focused = focused, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun photoSortOrderOptions(sort: PhotoSort) = sortDirectionOptions(
    ascendingTitle = stringResource(if (sort.byDate) R.string.library_sort_oldest else R.string.library_sort_az),
    descendingTitle = stringResource(if (sort.byDate) R.string.library_sort_newest else R.string.library_sort_za),
)

internal fun formatPhotoDate(epochMs: Long?): String? = epochMs?.let {
    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}

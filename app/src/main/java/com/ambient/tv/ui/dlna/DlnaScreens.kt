package com.ambient.tv.ui.dlna

import com.ambient.tv.ui.common.BrowseFocusContentIdentity
import com.ambient.tv.ui.common.BrowseFocusInitialTarget
import com.ambient.tv.ui.common.RegisterBrowseFocusGrid
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.LocalBrowseFocusReturnState

import com.ambient.tv.ui.common.leftKeyAction
import com.ambient.tv.ui.theme.screenGutter
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.alpha
import com.ambient.tv.data.dlna.toMediaItem
import com.ambient.tv.ui.common.*
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import com.ambient.tv.ui.common.TvLazyVerticalGrid as LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ambient.tv.ui.common.skeletonPulseAlpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.dlna.DlnaNode
import com.ambient.tv.data.dlna.DlnaServer
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.pageChips
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.SystemPageTitle
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.common.LocalNetworkPermissionSetting
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun DlnaManageScreen(onOpen: (String) -> Unit, onBack: () -> Unit, viewModel: DlnaManageViewModel = hiltViewModel()) {
    OperationFeedbackScope(viewModel.feedback, "discovery")
    val state by viewModel.state.collectAsState()
    com.ambient.tv.ui.common.RefreshOnReconnect(setOf("dlna"), viewModel::refreshOnReconnect)
    var removeServer by remember { mutableStateOf<DlnaServer?>(null) }
    val serverList = rememberLazyListState()
    val firstFoundFocus = remember { FocusRequester() }
    ReportSidebarContentReady(!state.searching)
    // Once per finished search: later updates of the found list (e.g. after adding a server)
    // must not pull focus back to the first server.
    LaunchedEffect(state.searchRevision) {
        if (state.searching || state.searchRevision == 0L || state.found.isEmpty()) return@LaunchedEffect
        val firstFoundIndex = (if (state.saved.isNotEmpty()) state.saved.size + 1 else 0) + 1
        serverList.scrollToItem(firstFoundIndex)
        delay(80)
        repeat(4) {
            if (runCatching { firstFoundFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(32)
        }
    }
    Column(Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(), bottom = screenGutter(), top = com.ambient.tv.ui.common.pageHeaderTop(screenGutter())), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeader(actions = { TvChip(stringResource(R.string.files_back), modifier = Modifier.leftKeyAction(onBack), icon = Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = onBack) }) {
            SystemPageTitle(MediaSystem.DLNA)
        }
        Text(stringResource(R.string.dlna_explanation), style = MaterialTheme.typography.bodyMedium)
        LocalNetworkPermissionSetting(onGranted = viewModel::search)
        TvActionButton(
            text = stringResource(R.string.dlna_search),
            onClick = viewModel::search,
            loading = state.searching,
            modifier = Modifier.fillMaxWidth(),
            fillWidth = true,
        )
        state.error?.let { OperationFeedbackContent(it, Modifier.fillMaxWidth()) }
        LazyColumn(
            allowUpExit = true,
            state = serverList,
            modifier = Modifier.fillMaxWidth().weight(1f).overlayLazyScrollbar(serverList),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.saved.isNotEmpty()) {
                item { Text(stringResource(R.string.dlna_saved_servers), style = MaterialTheme.typography.titleMedium) }
                items(state.saved, key = { it.id }) { server -> SavedDlnaServer(server, { onOpen(server.id) }, { removeServer = server }) }
            }
            if (state.found.isNotEmpty()) {
                item { Text(stringResource(R.string.dlna_found_servers), style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(state.found, key = { _, server -> server.udn }) { index, server ->
                    TvActionButton(
                        server.name,
                        { viewModel.add(server); onOpen(server.id) },
                        modifier = (if (index == 0) Modifier.focusRequester(firstFoundFocus) else Modifier).fillMaxWidth(),
                        fillWidth = true,
                    )
                }
            }
            if (state.searched && state.error == null && state.found.isEmpty()) {
                item { Text(stringResource(R.string.dlna_no_servers_found), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    removeServer?.let { server ->
        AppDialog(
            onDismiss = { removeServer = null },
            title = server.name,
            hint = stringResource(R.string.dlna_remove_confirm, server.name),
            footer = {
                TvActionButton(stringResource(R.string.files_cancel), { removeServer = null })
                TvActionButton(stringResource(R.string.dlna_remove, server.name), {
                    removeServer = null
                    viewModel.remove(server)
                })
            },
        )
    }
}

@Composable
private fun SavedDlnaServer(server: DlnaServer, onOpen: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvActionButton(server.name, onOpen, modifier = Modifier.fillMaxWidth(), fillWidth = true)
        TvActionButton(stringResource(R.string.dlna_remove, server.name), onRemove, modifier = Modifier.fillMaxWidth(), fillWidth = true)
    }
}

@Composable
fun DlnaBrowserScreen(onPlay: (String) -> Unit, onManage: () -> Unit, onBack: () -> Unit, viewModel: DlnaBrowserViewModel = hiltViewModel()) {
    OperationFeedbackScope(viewModel.feedback, "browse")
    val state by viewModel.state.collectAsState()
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(
        setOf(state.server?.id?.let { "dlna:$it" } ?: "dlna"), viewModel::refreshOnReconnect,
    )
    val nodes = remember(state.page) { state.page?.nodes.orEmpty().sortedBy { !it.container } }
    val context = LocalContext.current
    val locationKey = com.ambient.tv.data.dlna.DlnaKeys.key(state.server?.id.orEmpty(), state.path.lastOrNull()?.objectId ?: "0")
    var keepHeaderFocus by remember(locationKey) { mutableStateOf(false) }
    val contentGrid = remember(locationKey) {
        val (index, offset) = viewModel.scroll(locationKey)
        LazyGridState(index, offset)
    }
    var focusLocation by remember { mutableStateOf(locationKey) }
    BrowseFocusContentIdentity(
        listOf(locationKey, state.viewMode),
        restoreInitialTarget = focusLocation != locationKey,
    )
    SideEffect { focusLocation = locationKey }
    RegisterBrowseFocusGrid(contentGrid, remember(nodes) { nodes.map { it.key } })
    androidx.compose.runtime.DisposableEffect(contentGrid) {
        onDispose { viewModel.rememberScroll(locationKey, contentGrid.firstVisibleItemIndex, contentGrid.firstVisibleItemScrollOffset) }
    }
    var showOptions by remember { mutableStateOf(false) }
    val optionsFocus = remember { FocusRequester() }
    var restoreOptionsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(restoreOptionsFocus) {
        if (restoreOptionsFocus) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { optionsFocus.requestFocus() }
            restoreOptionsFocus = false
        }
    }
    var focusedKey by remember(locationKey) { mutableStateOf(viewModel.focus(locationKey)) }
    var restoreRevision by remember(locationKey) { mutableStateOf(0) }
    var restoreAfterRetry by remember(locationKey) { mutableStateOf(false) }
    LaunchedEffect(state.pageError, state.loadingMore) {
        if (restoreAfterRetry && state.pageError == null && !state.loadingMore) {
            restoreAfterRetry = false
            restoreRevision++
        }
    }
    val focusKey = "$locationKey:${state.contentRevision}"
    val firstItemFocus = remember(focusKey, state.viewMode, restoreRevision) { FocusRequester() }
    var initialContentFocusPending by remember(focusKey, state.viewMode, restoreRevision) { mutableStateOf(true) }
    fun closeOptions() {
        showOptions = false
        keepHeaderFocus = true
        initialContentFocusPending = false
        restoreOptionsFocus = true
    }
    ReportSidebarContentReady(!state.loading)
    BackHandler(enabled = !showOptions) { restoreOptionsFocus = false; if (!viewModel.up()) onBack() }
    val targetKey = focusedKey?.takeIf { key -> nodes.any { it.key == key } } ?: nodes.firstOrNull()?.key
    if (!state.loading) BrowseFocusInitialTarget(targetKey)
    val sharedFocusOwner = LocalBrowseFocusReturnState.current != null
    LaunchedEffect(state.loading, initialContentFocusPending, focusKey, showOptions, state.viewMode, restoreRevision, sharedFocusOwner, keepHeaderFocus) {
        if (showOptions || keepHeaderFocus || state.loading || nodes.isEmpty() || !initialContentFocusPending) return@LaunchedEffect
        if (sharedFocusOwner) {
            initialContentFocusPending = false
            return@LaunchedEffect
        }
        val index = nodes.indexOfFirst { it.key == targetKey }.coerceAtLeast(0)
        if (contentGrid.layoutInfo.visibleItemsInfo.none { it.index == index }) contentGrid.scrollToItem(index)
        delay(100)
        repeat(4) {
            if (runCatching { firstItemFocus.requestFocus() }.getOrDefault(false)) {
                initialContentFocusPending = false
                return@LaunchedEffect
            }
            delay(32)
        }
    }
    LaunchedEffect(contentGrid, locationKey) {
        snapshotFlow {
            val layout = contentGrid.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.hasMore && !state.loading && !state.refreshing && !state.loadingMore &&
                layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - maxOf(9, layout.visibleItemsInfo.size)
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) viewModel.more()
        }
    }
    Box(Modifier.fillMaxSize()) {
        val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
        Column(Modifier.fillMaxSize().focusProperties { onEnter = { if (showOptions) cancelFocusChange() } }.focusGroup().padding(start = screenGutter(), end = screenGutter(), bottom = screenGutter(), top = com.ambient.tv.ui.common.pageHeaderTop(screenGutter())), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageHeader(focusChain = focusChain, actions = {
                TvChip(stringResource(R.string.files_back), modifier = Modifier.leftKeyAction { if (!viewModel.up()) onBack() }, icon = Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = { if (!viewModel.up()) onBack() })
                TvChip(stringResource(R.string.dlna_servers), icon = Icons.Default.Storage, iconOnly = true, onClick = onManage)
                TvChip(stringResource(R.string.action_reload), icon = Icons.Default.Refresh,
                    loading = state.loading || state.refreshing, iconOnly = true,
                    onClick = { keepHeaderFocus = true; viewModel.refresh() })
                com.ambient.tv.ui.common.ViewOptionsChip(0, { keepHeaderFocus = true; showOptions = true }, Modifier.focusRequester(optionsFocus))
            }) {
                SystemPageTitle(MediaSystem.DLNA, title = state.server?.name)
            }
            val failedOrEmptyFolder = !state.loading && !state.refreshing && state.path.isNotEmpty() &&
                nodes.isEmpty()
            DlnaBreadcrumb(
                root = stringResource(R.string.dlna_root),
                path = state.path,
                requestedFocusIndex = if (failedOrEmptyFolder && state.error == null && !showOptions && !keepHeaderFocus) dlnaParentBreadcrumbIndex(state.path.size) else null,
                focusChain = focusChain,
                onSelect = viewModel::breadcrumb,
            )
            if (nodes.isNotEmpty()) state.error?.let {
                OperationFeedbackContent(it, Modifier.fillMaxWidth(), actionLabel = stringResource(R.string.home_retry), onAction = viewModel::refresh)
            }
            state.pageError?.let {
                OperationFeedbackContent(if (state.loadingMore) it.copy(message = R.string.dlna_loading_more,
                    phase = com.ambient.tv.notifications.FeedbackPhase.INFO, detail = null) else it,
                    Modifier.fillMaxWidth(), actionLabel = stringResource(R.string.home_retry),
                    onAction = { restoreAfterRetry = true; viewModel.retryMore() }, actionLoading = state.loadingMore)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).pageContentUp(focusChain)
                .onFocusChanged { if (it.hasFocus) { keepHeaderFocus = false; initialContentFocusPending = false } }
                .focusGroup()) {
                val style = if (state.viewMode == DlnaViewMode.POSTER) MediaTileStyle.POSTER else MediaTileStyle.LANDSCAPE
                val scale = rememberUhdScale()
                val handheld = LocalHandheldUi.current
                val cellWidth = (if (state.viewMode == DlnaViewMode.POSTER) {
                    if (handheld) 148.dp else 176.dp
                } else { if (handheld) 256.dp else 316.dp }) * scale
                val columnCount = when {
                    state.viewMode == DlnaViewMode.LIST -> 1
                    handheld -> ((maxWidth - 12.dp) / cellWidth).toInt().coerceAtLeast(1)
                    state.viewMode == DlnaViewMode.POSTER -> state.gridColumns
                    else -> landscapeGridColumns(state.gridColumns)
                }
                val rowScale = if (handheld) 1f else (7f / state.listRows.coerceIn(4, 10)).coerceIn(0.72f, 1.75f)
                val columns = GridCells.Fixed(columnCount)
                val pulse = if ((state.loading || state.refreshing) && nodes.isEmpty()) com.ambient.tv.ui.common.rememberSkeletonPulseOrNull() else null
                SoftViewChange(state.viewMode, Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = columns,
                    state = contentGrid,
                    modifier = Modifier.fillMaxSize().overlayLazyGridScrollbar(contentGrid),
                    contentPadding = PaddingValues(end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if ((state.loading || state.refreshing) && nodes.isEmpty()) {
                        if (state.viewMode == DlnaViewMode.LIST) {
                            items(state.listRows.coerceIn(4, 10)) { DlnaLoadingRow(pulse, rowScale) }
                        } else {
                            items(columnCount * 3) { SkeletonTile(style = style, showDescription = false, fillCell = true) }
                        }
                    } else {
                        if (nodes.isEmpty() && !state.loading && !state.refreshing && state.error == null) {
                            item("empty", span = { GridItemSpan(maxLineSpan) }) {
                                Text(stringResource(R.string.dlna_empty), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        gridItemsIndexed(nodes, key = { _, node -> node.key }, span = { _, node ->
                            GridItemSpan(if (state.viewMode == DlnaViewMode.LIST) maxLineSpan else 1)
                        }) { _, node ->
                            val focusModifier = (if (node.key == targetKey) Modifier.focusRequester(firstItemFocus) else Modifier)
                                .onFocusChanged { if (it.hasFocus) { focusedKey = node.key; viewModel.rememberFocus(locationKey, node.key) } }
                            if (state.viewMode == DlnaViewMode.LIST) {
                                DlnaNodeRow(node, { viewModel.open(node) }, { onPlay(node.key) }, focusModifier,
                                    rowScale = rowScale,
                                    formatSize = { size -> Formatter.formatShortFileSize(context, size) })
                            } else {
                                val media = remember(node) { node.toMediaItem() }
                                MediaTile(media, { if (node.container) viewModel.open(node) else onPlay(node.key) }, modifier = focusModifier,
                                    style = style, showDescription = false, fillCell = true)
                            }
                        }
                        if (state.loadingMore) {
                            if (state.viewMode == DlnaViewMode.LIST) {
                                item("loading-more", span = { GridItemSpan(maxLineSpan) }) { DlnaLoadingRows(count = 3, rowScale = rowScale) }
                            } else {
                                // Full rows, including an incomplete last content row.
                                items(columnCount * 3 - nodes.size % columnCount, span = { GridItemSpan(1) }) {
                                    SkeletonTile(style = style, showDescription = false, fillCell = true)
                                }
                            }
                        }
                    }
                }
                }
                if (nodes.isEmpty()) state.error?.let {
                    OperationFeedbackContent(it, Modifier.align(Alignment.Center).widthIn(max = 680.dp), FeedbackLayout.PAGE,
                        actionLabel = stringResource(if (state.server == null) R.string.dlna_servers else R.string.home_retry),
                        onAction = if (state.server == null) onManage else viewModel::refresh,
                        requestActionFocus = !showOptions && !keepHeaderFocus)
                }
                if (state.loadingMore && state.pageError == null) {
                    Row(
                        Modifier.align(Alignment.BottomCenter)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                                Modifier.size(20.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        Text(stringResource(R.string.dlna_loading_more), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (showOptions) {
            BackHandler { closeOptions() }
            Box(Modifier.fillWindowHeight().fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f))
                .clickable { closeOptions() })
            ViewOptionsPanel(
                views = DlnaViewMode.entries.map { mode -> ViewOption(mode.name, stringResource(when (mode) {
                    DlnaViewMode.LIST -> R.string.library_view_list
                    DlnaViewMode.POSTER -> R.string.library_view_poster
                    DlnaViewMode.LANDSCAPE -> R.string.library_view_landscape
                }), ViewPreview.valueOf(mode.name)) },
                selectedView = state.viewMode.name,
                onView = { viewModel.setViewMode(DlnaViewMode.valueOf(it)) },
                gridColumns = state.gridColumns, listRows = state.listRows,
                onGridColumns = viewModel::setGridColumns, onListRows = viewModel::setListRows,
                hint = stringResource(R.string.dlna_view_hint),
                onClose = ::closeOptions,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

internal fun dlnaParentBreadcrumbIndex(pathSize: Int): Int? =
    if (pathSize > 0) pathSize - 2 else null

@Composable
private fun DlnaBreadcrumb(
    root: String,
    path: List<DlnaBrowserLocation>,
    requestedFocusIndex: Int?,
    focusChain: com.ambient.tv.ui.common.PageFocusChain,
    onSelect: (Int) -> Unit,
) {
    val crumbs = listOf(-1 to root) + path.mapIndexed { index, location -> index to location.title }
    val row = rememberLazyListState()
    val requestedFocus = remember(path, requestedFocusIndex) { FocusRequester() }
    LaunchedEffect(crumbs.size, requestedFocusIndex) {
        if (crumbs.isEmpty()) return@LaunchedEffect
        val targetPosition = requestedFocusIndex?.let { target -> crumbs.indexOfFirst { it.first == target } }
            ?.takeIf { it >= 0 }
        row.scrollToItem(targetPosition ?: crumbs.lastIndex)
        if (targetPosition != null) {
            delay(60)
            repeat(4) {
                if (runCatching { requestedFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                delay(32)
            }
        }
    }
    LazyRow(
        state = row,
        // The current folder is plain text; the parent crumb is where Up from the grid lands.
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).then(if (crumbs.size > 1) Modifier.pageChips(focusChain) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs) { position, (index, title) ->
            Row(if (position == crumbs.lastIndex - 1) Modifier.focusRequester(focusChain.selectedChip) else Modifier, verticalAlignment = Alignment.CenterVertically) {
                if (position > 0) androidx.compose.material3.Icon(
                    Icons.Default.ChevronRight,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                DlnaCrumb(
                    title = title,
                    current = position == crumbs.lastIndex,
                    modifier = if (index == requestedFocusIndex) Modifier.focusRequester(requestedFocus) else Modifier,
                ) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun DlnaCrumb(title: String, current: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    val style = MaterialTheme.typography.labelLarge
    if (current) {
        Text(
            title,
            Modifier.padding(2.dp).padding(padding),
            color = MaterialTheme.colorScheme.onSurface,
            style = style,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 1,
        )
        return
    }
    if (LocalHandheldUi.current) {
        Text(
            title,
            Modifier.padding(2.dp).clickable(onClick = onClick).padding(padding),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = style,
            maxLines = 1,
        )
        return
    }
    var focused by remember { mutableStateOf(false) }
    var keyPressed by remember { mutableStateOf(false) }
    val currentClick = rememberUpdatedState(onClick)
    val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
    Box(
        modifier = Modifier.padding(2.dp).then(modifier)
            .focusOnTvPointer()
            .onPreviewKeyEvent { event ->
                val activation = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                    event.key == Key.DirectionCenter || event.key == Key.Spacebar
                if (!activation) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> if (!keyPressed && event.nativeKeyEvent.repeatCount == 0) keyPressed = true
                    KeyEventType.KeyUp -> {
                        val activate = keyPressed
                        keyPressed = false
                        if (activate) currentClick.value()
                    }
                    else -> Unit
                }
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) keyPressed = false
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures(onTap = { currentClick.value() }) },
    ) {
        Text(
            title,
            (if (focused) Modifier.appControlSurface(true, shape = shape) else Modifier).padding(padding),
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = style,
            maxLines = 1,
        )
    }
}

@Composable
private fun DlnaNodeRow(
    node: DlnaNode,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    focusModifier: Modifier = Modifier,
    rowScale: Float = 1f,
    formatSize: (Long) -> String,
) {
    val action = if (node.container) onOpen else onPlay
    val resource = node.resources.firstOrNull()
    val duration = resource?.durationMs?.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it / 1_000) }.orEmpty()
    val size = resource?.size?.takeIf { it > 0 }?.let(formatSize).orEmpty()
    if (LocalHandheldUi.current) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(node.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = duration.takeIf { it.isNotEmpty() }?.let { value -> { Text(value) } },
            leadingContent = { DlnaRowArtwork(node, Modifier.size(56.dp, 36.dp)) },
            trailingContent = size.takeIf { it.isNotEmpty() }?.let { value -> { Text(value, style = MaterialTheme.typography.bodySmall) } },
            modifier = focusModifier.clickable(onClick = action),
        )
        return
    }
    var focused by remember { mutableStateOf(false) }
    var keyPressed by remember { mutableStateOf(false) }
    val currentAction = rememberUpdatedState(action)
    val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
    Box(
        modifier = Modifier.fillMaxWidth().padding(2.dp).then(focusModifier)
            .browseFocusTarget(node.key)
            .focusOnTvPointer()
            .onPreviewKeyEvent { event ->
                val activation = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                    event.key == Key.DirectionCenter || event.key == Key.Spacebar
                if (!activation) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> if (!keyPressed && event.nativeKeyEvent.repeatCount == 0) keyPressed = true
                    KeyEventType.KeyUp -> {
                        val activate = keyPressed
                        keyPressed = false
                        if (activate) currentAction.value()
                    }
                    else -> Unit
                }
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) keyPressed = false
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures(onTap = { currentAction.value() }) },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp * rowScale).appControlSurface(focused, shape = shape)
                .padding(horizontal = 16.dp * rowScale, vertical = 8.dp * rowScale),
            horizontalArrangement = Arrangement.spacedBy(12.dp * rowScale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DlnaRowArtwork(node, Modifier.size(
                if (node.container) 20.dp * rowScale else 64.dp * rowScale,
                if (node.container) 20.dp * rowScale else 40.dp * rowScale,
            ))
            MarqueeTitle(
                node.title,
                focused = focused,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            if (duration.isNotEmpty()) Text(duration, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (size.isNotEmpty()) Text(
                size,
                Modifier.widthIn(min = 64.dp * rowScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DlnaLoadingRows(count: Int = 7, rowScale: Float = 1f) {
    val pulse = com.ambient.tv.ui.common.rememberSkeletonPulseOrNull()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { DlnaLoadingRow(pulse, rowScale) }
    }
}

@Composable
private fun DlnaLoadingRow(pulse: androidx.compose.runtime.State<Float>?, rowScale: Float = 1f) {
    // The shared pulse is the row's layer alpha; the parts keep their relative strengths.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(2.dp)
            .heightIn(min = 44.dp * rowScale)
            .skeletonPulseAlpha(pulse)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(LocalUiTokens.current.controlRadius))
            .padding(horizontal = 16.dp * rowScale, vertical = 10.dp * rowScale),
        horizontalArrangement = Arrangement.spacedBy(12.dp * rowScale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp * rowScale).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f), RoundedCornerShape(4.dp)))
        Box(Modifier.height(12.dp).weight(1f).fillMaxWidth(0.58f).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f), RoundedCornerShape(6.dp)))
        Box(Modifier.width(58.dp * rowScale).height(10.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.44f), RoundedCornerShape(5.dp)))
    }
}

@Composable
private fun DlnaRowArtwork(node: DlnaNode, modifier: Modifier) {
    if (node.container) {
        androidx.compose.material3.Icon(Icons.Default.Folder, null, modifier)
    } else {
        val item = remember(node) { node.toMediaItem() }
        MediaArtworkWithStreamIcons(item, item.thumbUrl, modifier = modifier)
    }
}

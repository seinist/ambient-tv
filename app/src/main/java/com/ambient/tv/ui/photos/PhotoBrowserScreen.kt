package com.ambient.tv.ui.photos

import com.ambient.tv.ui.common.SortOrderToggle
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
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
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Photo library/album browser shared by Plex, Jellyfin, Emby and Files. */
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
    LaunchedEffect(rootKey) { viewModel.start(rootKey, title) }
    // Runs before the focus effect below reads the focus target.
    LaunchedEffect(viewModel) { viewModel.applyViewerReturn() }
    val state by viewModel.state.collectAsState()
    ReportSidebarContentReady(!state.loading)
    val tokens = LocalUiTokens.current
    BackHandler(enabled = !state.atRoot || state.timeline || onBack != null) {
        if (!state.atRoot || state.timeline) viewModel.back() else onBack?.invoke()
    }

    val gridState = rememberLazyGridState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val visible = if (state.timeline) state.timelineGroups.flatMap { it.entries } else state.entries
    var initialFocusDone by remember(state.current?.key, state.timeline) { mutableStateOf(false) }
    LaunchedEffect(state.current?.key, state.timeline, state.loading) {
        if (state.loading || initialFocusDone || visible.isEmpty()) return@LaunchedEffect
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
    val orderFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(start = tokens.screenPadding, end = tokens.screenPadding, bottom = tokens.screenPadding, top = com.ambient.tv.ui.common.pageHeaderTop(tokens.screenPadding)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(focusChain = focusChain, actions = {
            if (!state.atRoot || state.timeline) TvChip(stringResource(R.string.photos_back), modifier = Modifier.leftKeyAction { viewModel.back() }, icon = Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true) { viewModel.back() }
            TvChip(stringResource(R.string.photos_slideshow), enabled = !state.loading, icon = Icons.Filled.Slideshow, iconOnly = true) {
                if (viewModel.prepareSlideshow()) onOpenViewer()
            }
        }) {
            Text(
                state.stack.joinToString(" › ") { it.title }.ifBlank { title },
                style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        com.ambient.tv.ui.common.PageChipRow(focusChain) {
            // The timeline needs a date index, which only the media servers provide.
            if (state.current?.key?.let(FileKeys::owns) == false) item {
                TvChip(
                    stringResource(if (state.timeline) R.string.photos_folders else R.string.photos_timeline),
                    selected = state.timeline, icon = Icons.Filled.CalendarMonth, onClick = viewModel::toggleTimeline,
                    modifier = if (state.timeline) Modifier.focusRequester(focusChain.selectedChip) else Modifier,
                )
            }
            if (!state.timeline) {
                item {
                    TvChip(
                        stringResource(R.string.photos_sort_date),
                        selected = state.sort.byDate,
                        modifier = if (state.sort.byDate) Modifier.focusRequester(focusChain.selectedChip) else Modifier,
                        onClick = { viewModel.setSortField(true) },
                    )
                }
                item {
                    TvChip(
                        stringResource(R.string.photos_sort_name),
                        selected = !state.sort.byDate,
                        modifier = if (!state.sort.byDate) Modifier.focusRequester(focusChain.selectedChip) else Modifier,
                        onClick = { viewModel.setSortField(false) },
                    )
                }
            }
        }
        if (!state.timeline) {
            SortOrderToggle(
                orders = photoSortOrderOptions(state.sort),
                selectedKey = if (state.sort.descending) "DESCENDING" else "ASCENDING",
                onSelect = { viewModel.toggleSortOrder() },
                showTitle = true,
                up = focusChain.chips,
                focusRequester = orderFocus,
            )
        }
        when {
            state.loading && visible.isEmpty() -> StatePanel(stringResource(R.string.photos_loading))
            state.failed -> StatePanel(stringResource(R.string.photos_error), actionLabel = stringResource(R.string.photos_retry), onAction = viewModel::retry)
            visible.isEmpty() -> StatePanel(stringResource(R.string.photos_empty))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().pageContentUp(focusChain) {
                    if (state.timeline) focusChain.contentUp else orderFocus
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
}

@Composable
private fun PhotoTile(entry: PhotoEntry, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalUiTokens.current.cardRadius)
    Box(
        modifier
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

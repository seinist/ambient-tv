package com.ambient.tv.ui.tvheadend

import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.RegisterBrowseFocusList
import com.ambient.tv.ui.common.RegisterBrowseFocusRows

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.livetv.*
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Programme guide and channel list share filtering, alphabet navigation and focus handling. */
@Composable
internal fun Guide(
    browseState: LiveTvBrowseState,
    channels: List<LiveTvChannel>,
    events: List<LiveTvProgramme>,
    imageHeaders: Map<String, String>,
    onEvent: (LiveTvProgramme) -> Unit,
    firstContentFocusRequester: FocusRequester,
    focusTarget: TvhFocusTarget,
    onFocused: (String, String?) -> Unit,
    options: GuideOptions,
    onOptions: (GuideOptions) -> Unit,
    onWatch: (String) -> Unit,
    canWatch: Boolean,
    controls: LiveTvBrowseControls,
    refreshing: Boolean = false,
    restoreFocus: Boolean = false,
    onFocusRestored: () -> Unit = {},
    pageFocusChain: PageFocusChain? = null,
    listing: LiveTvChannelListing = rememberLiveTvChannelListing(channels, events, options),
    titleArt: Map<String, String> = emptyMap(),
    onArtworkNeeded: (String, kotlinx.coroutines.CoroutineScope) -> Unit = { _, _ -> },
) = LiveTvChannelBrowser(browseState, channels, events, imageHeaders, onEvent,
    firstContentFocusRequester, focusTarget, onFocused, options, onOptions, onWatch, canWatch, controls,
    refreshing = refreshing, restoreFocus = restoreFocus, onFocusRestored = onFocusRestored,
    pageFocusChain = pageFocusChain, listing = listing, titleArt = titleArt, onArtworkNeeded = onArtworkNeeded)

/** The same filtered rows drive rendering, alphabet jumps and restored focus. */
@Composable
internal fun LiveTvChannelBrowser(
    browseState: LiveTvBrowseState,
    channels: List<LiveTvChannel>,
    events: List<LiveTvProgramme>,
    imageHeaders: Map<String, String>,
    onEvent: (LiveTvProgramme) -> Unit,
    firstContentFocusRequester: FocusRequester,
    focusTarget: TvhFocusTarget,
    onFocused: (String, String?) -> Unit,
    options: GuideOptions,
    onOptions: (GuideOptions) -> Unit,
    onWatch: (String) -> Unit,
    canWatch: Boolean,
    controls: LiveTvBrowseControls,
    channelList: Boolean = false,
    upcoming: List<LiveTvRecording> = emptyList(),
    refreshing: Boolean = false,
    /** Entering the tab (also back from the player): scroll to [focusTarget], then focus it. */
    restoreFocus: Boolean = false,
    onFocusRestored: () -> Unit = {},
    pageFocusChain: PageFocusChain? = null,
    listing: LiveTvChannelListing = rememberLiveTvChannelListing(channels, events, options),
    titleArt: Map<String, String> = emptyMap(),
    onArtworkNeeded: (String, kotlinx.coroutines.CoroutineScope) -> Unit = { _, _ -> },
) {
    val now = listing.now
    val eventsByChannel = listing.eventsByChannel
    val visibleChannels = listing.visibleChannels
    val tags = remember(channels) { channels.flatMap { it.tags }.distinct().sorted() }
    val hasHbbtvCategory = remember(channels) { channels.any { !it.hbbtvUrl.isNullOrBlank() } }
    val positionKey = if (channelList) "channels" else "guide"
    val filterFocusState = controls.filterFocusState
    val savedPosition = remember(browseState, positionKey) { browseState.position(positionKey) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = visibleChannels.indexOfFirst { it.uuid == (savedPosition?.itemId ?: if (channelList) focusTarget.contentId else focusTarget.rowId) }.coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = savedPosition?.offset ?: 0,
    )
    if (channelList) RegisterBrowseFocusList(listState, visibleChannels.map { it.uuid })
    else RegisterBrowseFocusRows(listState, visibleChannels.map { channel ->
        channel.uuid to (listOf("channel:${channel.uuid}") +
            eventsByChannel[channel.uuid].orEmpty().map { "event:${it.eventId}" })
    })
    val currentChannels by rememberUpdatedState(visibleChannels)
    LaunchedEffect(listState, browseState, positionKey) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            currentChannels.getOrNull(index)?.let { browseState.savePosition(positionKey, it.uuid, offset) }
        }
    }
    val rowFocus = remember(visibleChannels) { visibleChannels.associate { it.uuid to FocusRequester() } }
    val alphabetFocus = remember { FocusRequester() }
    val alphabetActionFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val handheld = LocalHandheldUi.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var jumpJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pendingAlphabet by remember { mutableStateOf(false) }
    var alphabetTarget by remember(options, visibleChannels) { mutableStateOf<String?>(null) }
    val letters = remember(visibleChannels) { visibleChannels.map { guideIndexLetter(it.name) }.distinct() }
    val showAlphabet = options.sort == GuideSort.NAME && letters.isNotEmpty()
    val selectedLetter = (visibleChannels.firstOrNull { it.uuid == alphabetTarget }
        ?: visibleChannels.getOrNull(listState.firstVisibleItemIndex))?.let { guideIndexLetter(it.name) }
    fun jumpTo(index: Int, focus: Boolean) {
        val channel = visibleChannels.getOrNull(index) ?: return
        if (focus) filterFocusState.enterContent()
        jumpJob?.cancel()
        jumpJob = scope.launch {
            listState.scrollToItem(index)
            withTimeoutOrNull(GUIDE_ROW_LAYOUT_WAIT_MS) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == channel.uuid } }.first { it }
            }
            if (focus) {
                withFrameNanos { }
                repeat(4) {
                    if (!filterFocusState.allowContentAutoFocus) return@launch
                    if (runCatching { rowFocus[channel.uuid]?.requestFocus() == true }.getOrDefault(false)) return@launch
                    withFrameNanos { }
                }
            }
        }
    }
    LaunchedEffect(options, visibleChannels) { jumpJob?.cancel() }
    val currentOnFocusRestored by rememberUpdatedState(onFocusRestored)
    fun contentFocused(id: String, rowId: String?) {
        filterFocusState.enterContent()
        currentOnFocusRestored()
        onFocused(id, rowId)
    }
    // The list keeps its old scroll position across the player, so the target row (for example a
    // channel reached by zapping) may not be composed yet: bring it into view before focusing.
    val sharedInitialFocus = com.ambient.tv.ui.common.LocalBrowseFocusReturnState.current != null
    LaunchedEffect(restoreFocus, focusTarget, visibleChannels, filterFocusState) {
        if (sharedInitialFocus) return@LaunchedEffect
        if (!restoreFocus) return@LaunchedEffect
        if (!filterFocusState.allowContentAutoFocus) {
            currentOnFocusRestored()
            return@LaunchedEffect
        }
        val rowId = if (channelList) focusTarget.contentId else focusTarget.rowId
        val index = visibleChannels.indexOfFirst { it.uuid == rowId }
        if (index >= 0) {
            jumpJob?.cancel()
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowId }
            val viewportEnd = listState.layoutInfo.viewportEndOffset
            if (visible == null || visible.offset < 0 || visible.offset + visible.size > viewportEnd) {
                listState.scrollToItem((index - 1).coerceAtLeast(0))
            }
            // Bounded: a list that never lays the row out (hidden, detached) must not wait forever;
            // the focus attempts below then fall back to the channel row.
            withTimeoutOrNull(GUIDE_ROW_LAYOUT_WAIT_MS) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == rowId } }.first { it }
            }
        }
        withFrameNanos { }
        repeat(8) {
            if (!filterFocusState.allowContentAutoFocus) {
                currentOnFocusRestored()
                return@LaunchedEffect
            }
            if (runCatching { firstContentFocusRequester.requestFocus() }.getOrDefault(false)) {
                currentOnFocusRestored()
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
        // A programme card outside its row's view: the channel itself is the next best target.
        if (index >= 0 && filterFocusState.allowContentAutoFocus) runCatching { rowFocus[visibleChannels[index].uuid]?.requestFocus() }
        // Done either way: the screen holds the shell's content entry while this is pending.
        currentOnFocusRestored()
    }
    LaunchedEffect(pendingAlphabet, showAlphabet) {
        if (pendingAlphabet && showAlphabet) {
            if (!handheld) {
                inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                withFrameNanos { }
                repeat(4) {
                    if (runCatching { alphabetFocus.requestFocus() }.getOrDefault(false)) {
                        pendingAlphabet = false
                        return@LaunchedEffect
                    }
                    withFrameNanos { }
                }
            }
            pendingAlphabet = false
        }
    }
    val filterModel = guideFilterModel(options, tags, hasHbbtvCategory, onOptions)
    val filterBarShown = LocalFilterBarSetting.current.shown
    CompositionLocalProvider(LocalFilterFocusState provides filterFocusState) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().focusProperties { onEnter = { if (controls.showOptions) cancelFocusChange() } }.focusGroup()) {
            // The view action lives in the page header. A-Z stays beside the sender navigation,
            // outside the scrolling filters, and never moves the header action off screen.
            if (filterBarShown || visibleChannels.isNotEmpty()) Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (filterBarShown) FilterBar(
                    filterModel, Modifier.weight(1f)
                        .then(if (visibleChannels.isEmpty()) Modifier.focusRequester(firstContentFocusRequester) else Modifier),
                    focusRequester = controls.filterBarFocus,
                    up = pageFocusChain?.contentUp,
                    focusChain = pageFocusChain,
                ) else Spacer(Modifier.weight(1f))
                if (visibleChannels.isNotEmpty()) TvChip(stringResource(R.string.guide_az), selected = showAlphabet,
                    modifier = Modifier.focusRequester(alphabetActionFocus)
                        .focusProperties { up = pageFocusChain?.contentUp ?: FocusRequester.Default }, onClick = {
                    filterFocusState.keepControls()
                    pendingAlphabet = true
                    if (options.sort != GuideSort.NAME) onOptions(options.copy(sort = GuideSort.NAME, descending = false))
                })
            }
            Box(Modifier.weight(1f).fillMaxWidth().then(
                if (pageFocusChain == null) Modifier else Modifier.pageContentUp(pageFocusChain) {
                    when {
                        filterBarShown -> controls.filterBarFocus
                        visibleChannels.isNotEmpty() -> alphabetActionFocus
                        else -> pageFocusChain.contentUp
                    }
                },
            ).focusGroup()) {
            if (visibleChannels.isEmpty()) {
                if (refreshing) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                } else StatePanel(title = stringResource(R.string.guide_no_matches), actionLabel = stringResource(R.string.library_filter_reset),
                    modifier = if (!filterBarShown) Modifier.focusRequester(firstContentFocusRequester) else Modifier,
                    onAction = {
                        filterFocusState.keepControls()
                        if (!filterBarShown) controls.openOptions()
                        onOptions(GuideOptions(sort = options.sort, descending = options.descending))
                        filterFocusState.restoreFilter(if (filterBarShown) FilterFocusSurface.BAR else FilterFocusSurface.PANEL)
                    })
            } else Row(Modifier.fillMaxSize()) {
                LazyColumn(allowUpExit = true, state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(contentFocus).focusGroup()
                        .overlayLazyScrollbar(listState, enabled = !showAlphabet),
                    verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp)) {
                    items(visibleChannels, key = { it.uuid }) { channel ->
                        val rowEvents = eventsByChannel[channel.uuid].orEmpty()
                        if (channelList) {
                            val current = rowEvents.firstOrNull { it.isLive(now) }
                            val next = rowEvents.firstOrNull { it.startEpochSeconds >= now }
                            val recording = current?.let { event -> findProgrammeRecording(event, upcoming) != null } == true
                            // Only laid-out rows trigger TMDB work. Art updates never change row keys or size.
                            LaunchedEffect(current?.eventId, current?.title) {
                                current?.let { onArtworkNeeded(it.title, this) }
                            }
                            val currentArt = current?.let { liveTvProgrammeArt(it, listOf(channel), titleArt) }
                            ChannelRow(channel, current, next, recording, imageHeaders,
                                onWatch = { if (canWatch) onWatch(it.uuid) }, onEvent = onEvent,
                                onFocused = { alphabetTarget = channel.uuid; contentFocused(channel.uuid, null) },
                                art = currentArt,
                                modifier = Modifier.focusRequester(rowFocus.getValue(channel.uuid))
                                    .then(if (channel.uuid == focusTarget.contentId) Modifier.focusRequester(firstContentFocusRequester) else Modifier))
                        } else {
                            // A channel target (back from the player) starts at the programme running now.
                            val channelTarget = channel.uuid == focusTarget.rowId && focusTarget.contentId == "channel:${channel.uuid}"
                            val rowPosition = remember(browseState, channel.uuid) { browseState.position("guide:${channel.uuid}") }
                                ?.takeUnless { channelTarget && restoreFocus }
                            val rowState = rememberLazyListState(
                                initialFirstVisibleItemIndex = if (rowPosition == null && channelTarget) guideLiveIndex(rowEvents, now)
                                else rowEvents.indexOfFirst {
                                    if (rowPosition != null) it.eventId == rowPosition.itemId
                                    else channel.uuid == focusTarget.rowId && "event:${it.eventId}" == focusTarget.contentId
                                }.coerceAtLeast(0), initialFirstVisibleItemScrollOffset = rowPosition?.offset ?: 0)
                            RegisterBrowseFocusList(rowState, rowEvents.map { "event:${it.eventId}" }, channel.uuid)
                            // The row may keep an old saved position: show the current programme again.
                            if (channelTarget && restoreFocus) LaunchedEffect(filterFocusState) {
                                if (filterFocusState.allowContentAutoFocus && rowEvents.isNotEmpty()) rowState.scrollToItem(guideLiveIndex(rowEvents, now))
                            }
                            DisposableEffect(rowState, rowEvents) {
                                onDispose { rowEvents.getOrNull(rowState.firstVisibleItemIndex)?.let {
                                    browseState.savePosition("guide:${channel.uuid}", it.eventId, rowState.firstVisibleItemScrollOffset)
                                } }
                            }
                            DeviceActionRow(Modifier.fillMaxWidth(), stack = true, verticalAlignment = Alignment.CenterVertically) {
                                var focused by remember { mutableStateOf(false) }
                                Surface(onClick = { if (canWatch) onWatch(channel.uuid) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(),
                                    modifier = Modifier.width(if (LocalHandheldUi.current) 260.dp else 212.dp)
                                        .focusRequester(rowFocus.getValue(channel.uuid))
                                        .browseFocusTarget("channel:${channel.uuid}", rowKey = channel.uuid)
                                        .then(if ("channel:${channel.uuid}" == focusTarget.contentId) Modifier.focusRequester(firstContentFocusRequester) else Modifier)
                                        .onFocusChanged { focused = it.isFocused; if (it.isFocused) { alphabetTarget = channel.uuid; contentFocused("channel:${channel.uuid}", channel.uuid) } }
                                        .activateOnTvKey { if (canWatch) onWatch(channel.uuid) }) {
                                    Row(Modifier.fillMaxWidth().background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .22f) else Color.Transparent)
                                        .padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ArtworkImage(model = channel.iconUrl, title = channel.name, modifier = Modifier.size(64.dp, 44.dp),
                                            contentScale = ContentScale.Fit, headers = imageHeaders, fallbackText = channel.numberLabel ?: channel.name.take(3))
                                        Column(Modifier.weight(1f)) {
                                            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            channel.numberLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                                if (rowEvents.isEmpty() && refreshing) Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
                                } else if (rowEvents.isEmpty()) Text(stringResource(R.string.guide_no_programme), modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    items(rowEvents, key = { it.eventId }) { event ->
                                        GuideEventCard(event, now, onEvent, onFocused = { alphabetTarget = channel.uuid; contentFocused("event:${event.eventId}", channel.uuid) },
                                            art = liveTvProgrammeArt(event, listOf(channel), titleArt),
                                            imageHeaders = imageHeaders,
                                            onArtworkNeeded = { onArtworkNeeded(event.title, it) },
                                            modifier = if (channel.uuid == focusTarget.rowId && "event:${event.eventId}" == focusTarget.contentId)
                                                Modifier.focusRequester(firstContentFocusRequester) else Modifier)
                                    }
                                }
                            }
                        }
                    }
                }
                if (showAlphabet) AlphabetBar(letters, selectedLetter, onSelect = { letter ->
                    val index = visibleChannels.indexOfFirst { guideIndexLetter(it.name) == letter }
                    alphabetTarget = visibleChannels.getOrNull(index)?.uuid
                    jumpTo(index, false)
                }, gridFocus = contentFocus, modifier = Modifier.fillMaxHeight(), firstEntryFocusRequester = alphabetFocus,
                    onReturnToContent = {
                        val target = visibleChannels.indexOfFirst { it.uuid == alphabetTarget }.takeIf { it >= 0 } ?: listState.firstVisibleItemIndex
                        jumpTo(target, true)
                    })
            }
            }
        }
    }
    }
}

/** How long a guide jump waits for the target row to be laid out before it focuses anyway. */
internal const val GUIDE_ROW_LAYOUT_WAIT_MS = 1_000L

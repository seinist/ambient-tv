package com.ambient.tv.ui.tvheadend

import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.common.pageFocusStage
import com.ambient.tv.ui.common.PageFocusLevel
import com.ambient.tv.ui.common.RegisterBrowseFocusList

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.animatedFocusSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    artworkUpdates: StateFlow<Map<String, String>>? = null,
) = LiveTvChannelBrowser(browseState, channels, events, imageHeaders, onEvent,
    firstContentFocusRequester, focusTarget, onFocused, options, onOptions, onWatch, canWatch, controls,
    refreshing = refreshing, restoreFocus = restoreFocus, onFocusRestored = onFocusRestored,
    pageFocusChain = pageFocusChain, listing = listing, titleArt = titleArt, onArtworkNeeded = onArtworkNeeded,
    artworkUpdates = artworkUpdates)

/** The same filtered rows drive rendering, alphabet jumps and restored focus. */
@OptIn(ExperimentalFoundationApi::class)
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
    artworkUpdates: StateFlow<Map<String, String>>? = null,
) {
    val now = listing.now
    val timelineOrigin = remember(now) { guideTimelineOrigin(now) }
    val eventsByChannel = listing.eventsByChannel
    val visibleChannels = listing.visibleChannels
    val tags = listing.tags
    val hasHbbtvCategory = listing.hasHbbtvCategory
    val browsePending = refreshing || listing.building
    val positionKey = if (channelList) "channels" else "guide"
    val filterFocusState = controls.filterFocusState
    val savedPosition = remember(browseState, positionKey) { browseState.position(positionKey) }
    val initialChannelIndex = remember(visibleChannels, savedPosition, channelList, focusTarget) {
        visibleChannels.indexOfFirst {
            it.uuid == (savedPosition?.itemId ?: if (channelList) focusTarget.contentId else focusTarget.rowId)
        }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialChannelIndex,
        initialFirstVisibleItemScrollOffset = savedPosition?.offset ?: 0,
    )
    if (channelList) RegisterBrowseFocusList(listState, listing.visibleChannelIds)
    else RegisterBrowseFocusSlots(listState, listing.guideFocusSlots)
    val currentChannels by rememberUpdatedState(visibleChannels)
    LaunchedEffect(listState, browseState, positionKey) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            currentChannels.getOrNull(index)?.let { browseState.savePosition(positionKey, it.uuid, offset) }
        }
    }
    // A large EPG previously allocated a FocusRequester for every channel on the UI thread.
    // LazyColumn only composes visible rows; keep requesters for visited rows stable instead.
    val rowFocus = remember(browseState, positionKey) { mutableMapOf<String, FocusRequester>() }
    val epgRowFocus = rememberRowColumnFocus()
    val alphabetFocus = remember { FocusRequester() }
    val alphabetActionFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val handheld = LocalHandheldUi.current
    val centerGuideRows = !handheld && !channelList
    val rowCentering = rememberRowCenteringState(listState)
    val focusedGuideRow = remember(listState) { MutableStateFlow<String?>(null) }
    val channelIndices = remember(listing.visibleChannelIds) {
        listing.visibleChannelIds.withIndex().associate { it.value to it.index }
    }
    LaunchedEffect(centerGuideRows, channelIndices, listState) {
        if (!centerGuideRows) {
            focusedGuideRow.value = null
            return@LaunchedEffect
        }
        // Center the whole sender row once on entry. Retargeting preserves the current glide;
        // lateral moves between its header and programmes must not start another vertical move.
        focusedGuideRow.collectLatest { id ->
            val index = id?.let(channelIndices::get)
            if (index != null) rowCentering.centerItem(index)
        }
    }
    val inheritedScrollSpec = LocalBringIntoViewSpec.current
    val verticalScrollSpec = if (centerGuideRows) ExplicitRowScrollSpec else inheritedScrollSpec
    val horizontalScrollSpec = if (centerGuideRows) TvBringIntoViewSpec else inheritedScrollSpec
    val tokens = LocalUiTokens.current
    val channelShape = RoundedCornerShape(tokens.controlRadius)
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var jumpJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pendingAlphabet by remember { mutableStateOf(false) }
    val alphabetTarget = remember(options, visibleChannels) { mutableStateOf<String?>(null) }
    val letters = remember(visibleChannels) { visibleChannels.map { guideIndexLetter(it.name) }.distinct() }
    val showAlphabet = options.sort == GuideSort.NAME && letters.isNotEmpty()
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
    LaunchedEffect(restoreFocus, focusTarget, visibleChannels, filterFocusState, listing.building) {
        if (sharedInitialFocus || listing.building) return@LaunchedEffect
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
    val auxiliaryBarFocus = remember { FocusRequester() }
    CompositionLocalProvider(LocalFilterFocusState provides filterFocusState,
        LocalBrowseRightIndexFocus provides alphabetFocus.takeIf { showAlphabet }) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().focusProperties { onEnter = { if (controls.showOptions) cancelFocusChange() } }.focusGroup()) {
            // The view action lives in the page header. A-Z stays beside the sender navigation,
            // outside the scrolling filters, and never moves the header action off screen.
            if (filterBarShown || visibleChannels.isNotEmpty()) Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .then(if (pageFocusChain != null && !filterBarShown)
                        Modifier.pageFocusStage(pageFocusChain, PageFocusLevel.FILTER, auxiliaryBarFocus) else Modifier),
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
                if (browsePending) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FullscreenSpinner()
                } else BrowseStatusPanel(title = stringResource(R.string.guide_no_matches), actionLabel = stringResource(R.string.library_filter_reset),
                    modifier = Modifier.fillMaxSize().then(if (!filterBarShown) Modifier.focusRequester(firstContentFocusRequester) else Modifier),
                    onAction = {
                        filterFocusState.keepControls()
                        if (!filterBarShown) controls.openOptions()
                        onOptions(GuideOptions(sort = options.sort, descending = options.descending))
                        filterFocusState.restoreFilter(if (filterBarShown) FilterFocusSurface.BAR else FilterFocusSurface.PANEL)
                    })
            } else Row(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides verticalScrollSpec) {
                LazyColumn(allowUpExit = true, state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(contentFocus).focusGroup()
                        .rowColumnFocusWrapRight(epgRowFocus)
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
                                onFocused = {
                                    if (showAlphabet) alphabetTarget.value = channel.uuid
                                    contentFocused(channel.uuid, null)
                                },
                                art = currentArt,
                                modifier = Modifier.focusRequester(rowFocus.getOrPut(channel.uuid) { FocusRequester() })
                                    .then(if (channel.uuid == focusTarget.contentId) Modifier.focusRequester(firstContentFocusRequester) else Modifier))
                        } else {
                            // All channels share the same timeline origin, including gaps with no EPG.
                            // Keep one LazyRow item per original event so saved focus indices stay valid.
                            val timelineSlots = remember(rowEvents, timelineOrigin) {
                                guideTimelineSlots(rowEvents, timelineOrigin)
                            }
                            // A channel target (back from the player) starts at the programme running now.
                            val channelTarget = channel.uuid == focusTarget.rowId && focusTarget.contentId == "channel:${channel.uuid}"
                            val rowPosition = remember(browseState, channel.uuid) { browseState.position("guide:${channel.uuid}") }
                                ?.takeUnless { channelTarget && restoreFocus }
                            val initialEventIndex = remember(rowEvents, rowPosition, channelTarget, focusTarget, now) {
                                if (rowPosition == null && channelTarget) guideLiveIndex(rowEvents, now)
                                else rowEvents.indexOfFirst {
                                    if (rowPosition != null) it.eventId == rowPosition.itemId
                                    else channel.uuid == focusTarget.rowId && "event:${it.eventId}" == focusTarget.contentId
                                }.coerceAtLeast(0)
                            }
                            val rowState = rememberLazyListState(
                                initialFirstVisibleItemIndex = initialEventIndex,
                                initialFirstVisibleItemScrollOffset = rowPosition?.offset ?: 0,
                            )
                            val rowEventIds = remember(rowEvents) { rowEvents.map { "event:${it.eventId}" } }
                            RegisterRowColumnFocus(epgRowFocus, channel.uuid, rowState, rowEventIds)
                            // The row may keep an old saved position: show the current programme again.
                            if (channelTarget && restoreFocus) LaunchedEffect(filterFocusState) {
                                if (filterFocusState.allowContentAutoFocus && rowEvents.isNotEmpty()) rowState.scrollToItem(guideLiveIndex(rowEvents, now))
                            }
                            DisposableEffect(rowState, rowEvents) {
                                onDispose { rowEvents.getOrNull(rowState.firstVisibleItemIndex)?.let {
                                    browseState.savePosition("guide:${channel.uuid}", it.eventId, rowState.firstVisibleItemScrollOffset)
                                } }
                            }
                            DeviceActionRow(Modifier.fillMaxWidth().then(if (centerGuideRows) Modifier
                                .heightIn(min = GuideRowHeight)
                                .onFocusChanged { state ->
                                    if (state.hasFocus) focusedGuideRow.value = channel.uuid
                                    else if (focusedGuideRow.value == channel.uuid) focusedGuideRow.value = null
                                }.focusGroup() else Modifier),
                                stack = true, verticalAlignment = Alignment.CenterVertically) {
                                var focused by remember { mutableStateOf(false) }
                                Surface(onClick = { if (canWatch) onWatch(channel.uuid) }, colors = transparentSurfaceColors(), scale = noSurfaceScale(),
                                    border = noSurfaceBorder(),
                                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = channelShape),
                                    modifier = Modifier.width(if (handheld) 260.dp else 212.dp)
                                        .focusRequester(rowFocus.getOrPut(channel.uuid) { FocusRequester() })
                                        .browseFocusTarget("channel:${channel.uuid}", rowKey = channel.uuid)
                                        .then(if ("channel:${channel.uuid}" == focusTarget.contentId) Modifier.focusRequester(firstContentFocusRequester) else Modifier)
                                        .onFocusChanged {
                                            focused = it.isFocused
                                            if (it.isFocused) {
                                                if (showAlphabet) alphabetTarget.value = channel.uuid
                                                contentFocused("channel:${channel.uuid}", channel.uuid)
                                            }
                                        }
                                        .activateOnTvKey { if (canWatch) onWatch(channel.uuid) }) {
                                    Row(Modifier.fillMaxWidth()
                                        .then(if (centerGuideRows) Modifier.height(GuideRowHeight) else Modifier)
                                        .animatedFocusSurface(focused, channelShape,
                                            focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = .22f),
                                            unfocusedBackground = Color.Transparent,
                                            focusedBorder = MaterialTheme.colorScheme.primary,
                                            borderWidth = tokens.focusBorder)
                                        .padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ArtworkImage(model = channel.iconUrl, title = channel.name, modifier = Modifier.size(64.dp, 44.dp),
                                            contentScale = ContentScale.Fit, headers = imageHeaders, fallbackText = channel.numberLabel ?: channel.name.take(3))
                                        Column(Modifier.weight(1f)) {
                                            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            channel.numberLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                                if (rowEvents.isEmpty() && browsePending) Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
                                } else if (rowEvents.isEmpty()) Text(stringResource(R.string.guide_no_programme), modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalScrollSpec) {
                                LazyRow(state = rowState, modifier = Modifier.containRowFocusExit(),
                                    horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    items(timelineSlots, key = { it.programme.eventId }) { slot ->
                                        val event = slot.programme
                                        GuideEventCard(event, now, onEvent, onFocused = {
                                            if (showAlphabet) alphabetTarget.value = channel.uuid
                                            contentFocused("event:${event.eventId}", channel.uuid)
                                        },
                                            art = liveTvProgrammeArt(event, listOf(channel), titleArt),
                                            imageHeaders = imageHeaders,
                                            artworkUpdates = artworkUpdates,
                                            gapBeforeSeconds = slot.gapBeforeSeconds,
                                            visibleSeconds = slot.visibleSeconds,
                                            onArtworkNeeded = { onArtworkNeeded(event.title, it) },
                                            modifier = (if (channel.uuid == focusTarget.rowId && "event:${event.eventId}" == focusTarget.contentId)
                                                Modifier.focusRequester(firstContentFocusRequester) else Modifier)
                                                .rowColumnFocusTarget(epgRowFocus, channel.uuid, "event:${event.eventId}"))
                                    }
                                }
                                }
                            }
                        }
                    }
                }
                }
                if (showAlphabet) GuideAlphabetBar(visibleChannels, letters, alphabetTarget, listState, onSelect = { letter ->
                    val index = visibleChannels.indexOfFirst { guideIndexLetter(it.name) == letter }
                    alphabetTarget.value = visibleChannels.getOrNull(index)?.uuid
                    jumpTo(index, false)
                }, gridFocus = contentFocus, modifier = Modifier.fillMaxHeight(), firstEntryFocusRequester = alphabetFocus,
                    actionsFocus = pageFocusChain?.actions,
                    onReturnToContent = {
                        val target = visibleChannels.indexOfFirst { it.uuid == alphabetTarget.value }.takeIf { it >= 0 } ?: listState.firstVisibleItemIndex
                        jumpTo(target, true)
                    })
            }
            }
        }
    }
    }
}

/** The alphabet alone observes D-pad selection and viewport changes. */
@Composable
private fun GuideAlphabetBar(
    channels: List<LiveTvChannel>,
    letters: List<Char>,
    target: State<String?>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (Char?) -> Unit,
    gridFocus: FocusRequester,
    modifier: Modifier,
    firstEntryFocusRequester: FocusRequester,
    actionsFocus: FocusRequester?,
    onReturnToContent: () -> Unit,
) {
    val lettersById = remember(channels) { channels.associate { it.uuid to guideIndexLetter(it.name) } }
    val selectedLetter = target.value?.let(lettersById::get)
        ?: channels.getOrNull(listState.firstVisibleItemIndex)?.let { guideIndexLetter(it.name) }
    AlphabetBar(
        letters, selectedLetter, onSelect, gridFocus, modifier, onReturnToContent,
        firstEntryFocusRequester, actionsFocus,
    )
}

/** How long a guide jump waits for the target row to be laid out before it focuses anyway. */
internal const val GUIDE_ROW_LAYOUT_WAIT_MS = 1_000L

/** Match sender targets, programme cells and empty EPG rows on TV. */
internal val GuideRowHeight = 86.dp

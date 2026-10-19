package com.ambient.tv.ui.tvheadend

import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    refreshing: Boolean = false,
) = LiveTvChannelBrowser(browseState, channels, events, imageHeaders, onEvent,
    firstContentFocusRequester, focusTarget, onFocused, options, onOptions, onWatch, canWatch, refreshing = refreshing)

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
    channelList: Boolean = false,
    upcoming: List<LiveTvRecording> = emptyList(),
    refreshing: Boolean = false,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() / 1000L } }
    val eventsByChannel = remember(events, now) { events.groupBy { it.channelUuid }.mapValues { guideEvents(it.value, now) } }
    val visibleChannels = remember(channels, eventsByChannel, options) { guideChannels(channels, eventsByChannel, now, options) }
    val tags = remember(channels) { channels.flatMap { it.tags }.distinct().sorted() }
    val positionKey = if (channelList) "channels" else "guide"
    val optionsLabel = stringResource(if (channelList) R.string.channel_options else R.string.guide_options)
    val activeFilters = guideActiveFilterLabels(options)
    val savedPosition = remember(browseState, positionKey) { browseState.position(positionKey) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = visibleChannels.indexOfFirst { it.uuid == (savedPosition?.itemId ?: if (channelList) focusTarget.contentId else focusTarget.rowId) }.coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = savedPosition?.offset ?: 0,
    )
    val currentChannels by rememberUpdatedState(visibleChannels)
    LaunchedEffect(listState, browseState, positionKey) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            currentChannels.getOrNull(index)?.let { browseState.savePosition(positionKey, it.uuid, offset) }
        }
    }
    val rowFocus = remember(visibleChannels) { visibleChannels.associate { it.uuid to FocusRequester() } }
    val alphabetFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val handheld = LocalHandheldUi.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var jumpJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var optionsOpened by remember { mutableStateOf(false) }
    var pendingAlphabet by remember { mutableStateOf(false) }
    var alphabetTarget by remember(options, visibleChannels) { mutableStateOf<String?>(null) }
    val letters = remember(visibleChannels) { visibleChannels.map { guideIndexLetter(it.name) }.distinct() }
    val showAlphabet = options.sort == GuideSort.NAME && letters.isNotEmpty()
    val selectedLetter = (visibleChannels.firstOrNull { it.uuid == alphabetTarget }
        ?: visibleChannels.getOrNull(listState.firstVisibleItemIndex))?.let { guideIndexLetter(it.name) }
    fun jumpTo(index: Int, focus: Boolean) {
        val channel = visibleChannels.getOrNull(index) ?: return
        jumpJob?.cancel()
        jumpJob = scope.launch {
            listState.scrollToItem(index)
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == channel.uuid } }.first { it }
            if (focus) {
                withFrameNanos { }
                repeat(4) {
                    if (runCatching { rowFocus[channel.uuid]?.requestFocus() == true }.getOrDefault(false)) return@launch
                    withFrameNanos { }
                }
            }
        }
    }
    LaunchedEffect(options, visibleChannels) { jumpJob?.cancel() }
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
    LaunchedEffect(showOptions) {
        if (!showOptions && optionsOpened) {
            withFrameNanos { }
            runCatching { optionsFocus.requestFocus() }
            optionsOpened = false
        }
    }
    BackHandler(showOptions) { showOptions = false }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().focusProperties { onEnter = { if (showOptions) cancelFocusChange() } }.focusGroup()) {
            DeviceActionRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvChip(if (activeFilters.isEmpty()) optionsLabel else stringResource(R.string.guide_options_filtered, optionsLabel, activeFilters.size), selected = options.filtersActive,
                    modifier = Modifier.focusRequester(optionsFocus)
                        .then(if (visibleChannels.isEmpty()) Modifier.focusRequester(firstContentFocusRequester) else Modifier), onClick = { optionsOpened = true; showOptions = true })
                if (visibleChannels.isNotEmpty()) TvChip(stringResource(R.string.guide_az), selected = showAlphabet, onClick = {
                    pendingAlphabet = true
                    if (options.sort != GuideSort.NAME) onOptions(options.copy(sort = GuideSort.NAME))
                })
                Text(stringResource(R.string.guide_count, visibleChannels.size), style = MaterialTheme.typography.bodySmall)
            }
            GuideActiveFilters(activeFilters)
            if (visibleChannels.isEmpty()) {
                if (refreshing) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                } else StatePanel(title = stringResource(R.string.guide_no_matches), actionLabel = stringResource(R.string.library_filter_reset),
                    onAction = { onOptions(GuideOptions(sort = options.sort)) })
            } else Row(Modifier.weight(1f).fillMaxWidth()) {
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
                            ChannelRow(channel, current, next, recording, imageHeaders,
                                onWatch = { if (canWatch) onWatch(it.uuid) }, onEvent = onEvent,
                                onFocused = { alphabetTarget = channel.uuid; onFocused(channel.uuid, null) },
                                modifier = Modifier.focusRequester(rowFocus.getValue(channel.uuid))
                                    .then(if (channel.uuid == focusTarget.contentId) Modifier.focusRequester(firstContentFocusRequester) else Modifier))
                        } else {
                            val rowPosition = remember(browseState, channel.uuid) { browseState.position("guide:${channel.uuid}") }
                            val rowState = rememberLazyListState(
                                initialFirstVisibleItemIndex = rowEvents.indexOfFirst {
                                    if (rowPosition != null) it.eventId == rowPosition.itemId
                                    else channel.uuid == focusTarget.rowId && "event:${it.eventId}" == focusTarget.contentId
                                }.coerceAtLeast(0), initialFirstVisibleItemScrollOffset = rowPosition?.offset ?: 0)
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
                                        .then(if ("channel:${channel.uuid}" == focusTarget.contentId) Modifier.focusRequester(firstContentFocusRequester) else Modifier)
                                        .onFocusChanged { focused = it.isFocused; if (it.isFocused) { alphabetTarget = channel.uuid; onFocused("channel:${channel.uuid}", channel.uuid) } }
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
                                        GuideEventCard(event, now, onEvent, onFocused = { alphabetTarget = channel.uuid; onFocused("event:${event.eventId}", channel.uuid) },
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
        if (showOptions) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)))
            Box(Modifier.align(Alignment.TopEnd)) { GuideOptionsPanel(options, tags, onOptions, title = optionsLabel) { showOptions = false } }
        }
    }
}

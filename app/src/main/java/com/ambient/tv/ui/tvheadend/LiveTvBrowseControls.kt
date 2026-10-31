package com.ambient.tv.ui.tvheadend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.LiveTvBrowseState
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.guideChannelsPrepared
import com.ambient.tv.livetv.guideEvents
import com.ambient.tv.ui.common.BrowseFocusKey
import com.ambient.tv.ui.common.BrowseFocusSlot
import com.ambient.tv.ui.common.FilterFocusState
import com.ambient.tv.ui.common.FilterBarModel
import com.ambient.tv.ui.common.FilterOptionsPanel
import com.ambient.tv.ui.common.LocalFilterFocusState
import com.ambient.tv.ui.common.rememberFilterFocusState
import com.ambient.tv.ui.common.retryFocusFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The header, filter bar and panel share one focus owner, including while content reloads. */
@Stable
internal class LiveTvBrowseControls(val filterFocusState: FilterFocusState) {
    val optionsFocus = FocusRequester()
    val filterBarFocus = FocusRequester()
    var showOptions by mutableStateOf(false)
        private set
    internal var restoreOptionsFocus by mutableStateOf(false)
        private set

    fun openOptions() {
        filterFocusState.keepControls()
        restoreOptionsFocus = false
        showOptions = true
    }

    fun closeOptions() {
        if (!showOptions) return
        showOptions = false
        restoreOptionsFocus = true
    }

    fun leavePage() {
        showOptions = false
        restoreOptionsFocus = false
    }

    internal fun focusRestored() { restoreOptionsFocus = false }
}

@Composable
internal fun rememberLiveTvBrowseControls(browseState: LiveTvBrowseState, positionKey: String): LiveTvBrowseControls {
    val filterFocus = rememberFilterFocusState(browseState, positionKey)
    val controls = remember(filterFocus) { LiveTvBrowseControls(filterFocus) }
    LaunchedEffect(controls, controls.showOptions, controls.restoreOptionsFocus) {
        if (!controls.showOptions && controls.restoreOptionsFocus) {
            withFrameNanos { }
            retryFocusFor { runCatching { controls.optionsFocus.requestFocus() }.getOrDefault(false) }
            controls.focusRestored()
        }
    }
    return controls
}

/** The panel overlays the whole page, so loading/empty content never removes its controls. */
@Composable
internal fun LiveTvBrowseOptionsPanel(controls: LiveTvBrowseControls, model: FilterBarModel) {
    if (!controls.showOptions) return
    CompositionLocalProvider(LocalFilterFocusState provides controls.filterFocusState) {
        com.ambient.tv.ui.common.RightPanelOverlay(onOutsideTap = controls::closeOptions) {
            FilterOptionsPanel(
                title = stringResource(R.string.library_options),
                model = model,
                onClose = controls::closeOptions,
                modifier = Modifier.align(Alignment.TopEnd),
                hint = stringResource(R.string.guide_options_hint),
            )
        }
    }
}

/**
 * Prepared data for channel lists and guide grids. No sorting, EPG grouping or construction of
 * programme focus IDs should be done in a Compose frame. [building] keeps the last snapshot
 * browseable while a newer source response, filter or clock tick is being processed.
 */
internal data class LiveTvChannelListing(
    val now: Long,
    val eventsByChannel: Map<String, List<LiveTvProgramme>>,
    val visibleChannels: List<LiveTvChannel>,
    val visibleChannelIds: List<String> = visibleChannels.map { it.uuid },
    val guideFocusRows: List<Pair<String, List<String>>> = visibleChannels.map { channel ->
        channel.uuid to (listOf("channel:${channel.uuid}") +
            eventsByChannel[channel.uuid].orEmpty().map { "event:${it.eventId}" })
    },
    // Flattening all programme focus slots in RegisterBrowseFocusRows used to run in Compose.
    val guideFocusSlots: List<BrowseFocusSlot> = guideFocusRows.flatMapIndexed { index, (row, items) ->
        items.map { BrowseFocusSlot(BrowseFocusKey(row, it, true), index) }
    },
    val tags: List<String> = emptyList(),
    val hasHbbtvCategory: Boolean = false,
    val building: Boolean = false,
)

/** Pure worker function; expensive EPG normalization and focus-index building happen together. */
internal fun buildLiveTvChannelListing(
    channels: List<LiveTvChannel>,
    events: List<LiveTvProgramme>,
    options: GuideOptions,
    now: Long,
    checkActive: () -> Unit = {},
): LiveTvChannelListing {
    checkActive()
    val programmes = events.groupBy { it.channelUuid }.mapValues { (_, group) -> guideEvents(group, now) }
    checkActive()
    val visible = guideChannelsPrepared(channels, programmes, options)
    checkActive()
    return LiveTvChannelListing(
        now = now,
        eventsByChannel = programmes,
        visibleChannels = visible,
        tags = channels.asSequence().flatMap { it.tags.asSequence() }.distinct().sorted().toList(),
        hasHbbtvCategory = channels.any { !it.hbbtvUrl.isNullOrBlank() },
    )
}

/** Inputs are held only until their corresponding worker result has been committed on Main. */
private data class CompletedLiveTvListing(
    val channels: List<LiveTvChannel>,
    val events: List<LiveTvProgramme>,
    val options: GuideOptions,
    val now: Long,
    val listing: LiveTvChannelListing,
)

/**
 * Compose effect keys normally compare two List instances structurally. A fresh 20,000-entry
 * EPG with unchanged values would perform that deep equality check on the main thread.
 * Compare the incoming immutable list instances by reference instead.
 */
private class LiveTvDataIdentityKey(
    private val channels: List<LiveTvChannel>,
    private val events: List<LiveTvProgramme>,
) {
    override fun equals(other: Any?): Boolean =
        other is LiveTvDataIdentityKey && other.channels === channels && other.events === events

    override fun hashCode(): Int =
        31 * System.identityHashCode(channels) + System.identityHashCode(events)
}

/**
 * An independent, cancelable worker builds the EPG snapshot. Keep previous rows and focus IDs
 * while the replacement loads. Switching providers starts with no old source's channels.
 */
@Composable
internal fun rememberLiveTvChannelListing(
    channels: List<LiveTvChannel>,
    events: List<LiveTvProgramme>,
    options: GuideOptions,
    sourceId: String = "",
    enabled: Boolean = true,
): LiveTvChannelListing {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis() / 1000L
        }
    }
    // Avoid deep equality comparisons of channel/EPG lists in both effects and snapshot writes.
    var completed by remember(sourceId) {
        mutableStateOf<CompletedLiveTvListing?>(null, referentialEqualityPolicy())
    }
    val sourceData = LiveTvDataIdentityKey(channels, events)
    LaunchedEffect(sourceId, sourceData, options, now, enabled) {
        if (!enabled) return@LaunchedEffect
        // A newer effect cancels its predecessor. No partial background result touches Compose.
        val built = withContext(Dispatchers.Default) {
            val worker = currentCoroutineContext()
            buildLiveTvChannelListing(channels, events, options, now) { worker.ensureActive() }
        }
        completed = CompletedLiveTvListing(channels, events, options, now, built)
    }
    val answer = completed
    val current = answer?.listing ?: LiveTvChannelListing(
        now = now, eventsByChannel = emptyMap(), visibleChannels = emptyList(), building = true,
    )
    val upToDate = answer != null &&
        answer.channels === channels && answer.events === events &&
        answer.options == options && answer.now == now
    return if (enabled && !upToDate) current.copy(building = true) else current
}

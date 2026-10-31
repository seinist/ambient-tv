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
import com.ambient.tv.livetv.guideChannels
import com.ambient.tv.livetv.guideEvents
import com.ambient.tv.ui.common.FilterFocusState
import com.ambient.tv.ui.common.FilterBarModel
import com.ambient.tv.ui.common.FilterOptionsPanel
import com.ambient.tv.ui.common.LocalFilterFocusState
import com.ambient.tv.ui.common.rememberFilterFocusState
import com.ambient.tv.ui.common.retryFocusFor
import kotlinx.coroutines.delay

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

/** One minute clock and one filtered result for the header count, channel rows and guide. */
internal data class LiveTvChannelListing(
    val now: Long,
    val eventsByChannel: Map<String, List<LiveTvProgramme>>,
    val visibleChannels: List<LiveTvChannel>,
)

@Composable
internal fun rememberLiveTvChannelListing(
    channels: List<LiveTvChannel>,
    events: List<LiveTvProgramme>,
    options: GuideOptions,
): LiveTvChannelListing {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis() / 1000L
        }
    }
    val programmes = remember(events, now) {
        events.groupBy { it.channelUuid }.mapValues { guideEvents(it.value, now) }
    }
    return remember(channels, programmes, now, options) {
        LiveTvChannelListing(now, programmes, guideChannels(channels, programmes, now, options))
    }
}

package com.ambient.tv.ui.tvheadend

import androidx.compose.runtime.staticCompositionLocalOf
import com.ambient.tv.livetv.LiveTvCapabilities
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvRecording

internal data class LiveRecordingControl(
    val eventId: String? = null,
    val available: Boolean = false,
    val scheduled: Boolean = false,
    val busy: Boolean = false,
    val record: () -> Unit = {},
)
internal val LocalLiveRecordingControl = staticCompositionLocalOf { LiveRecordingControl() }

internal fun canRecordProgramme(capabilities: LiveTvCapabilities, event: LiveTvProgramme?, now: Long): Boolean =
    capabilities.available && capabilities.manageTimers && event != null &&
        event.eventId.isNotBlank() && event.isLive(now)

internal fun hasProgrammeRecording(event: LiveTvProgramme, timers: List<LiveTvRecording>): Boolean =
    !event.dvrUuid.isNullOrBlank() || timers.any {
        it.broadcastEventId == event.eventId ||
            (it.channelUuid == event.channelUuid &&
                it.startEpochSeconds <= event.startEpochSeconds && it.stopEpochSeconds >= event.stopEpochSeconds)
    }

internal val LocalLiveTimeshiftChanged = staticCompositionLocalOf<(() -> Unit)?> { null }

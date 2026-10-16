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
    !event.dvrUuid.isNullOrBlank() || findProgrammeRecording(event, timers) != null

internal fun findProgrammeRecording(event: LiveTvProgramme, timers: List<LiveTvRecording>): LiveTvRecording? =
    timers.firstOrNull { it.uuid == event.dvrUuid } ?: timers.firstOrNull {
        it.broadcastEventId == event.eventId ||
            (it.channelUuid == event.channelUuid &&
                it.startEpochSeconds < event.stopEpochSeconds && it.stopEpochSeconds >= event.stopEpochSeconds)
    }

internal val LocalLiveTimeshiftChanged = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Use backend confirmation; an elapsed timer start alone does not prove recording. */
internal fun LiveTvRecording.isActivelyRecording(): Boolean =
    scheduleStatus.equals("recording", ignoreCase = true) ||
        scheduleStatus.equals("recordingError", ignoreCase = true) ||
        status.equals("recording", ignoreCase = true) ||
        status.equals("inprogress", ignoreCase = true)

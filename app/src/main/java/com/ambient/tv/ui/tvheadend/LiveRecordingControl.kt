package com.ambient.tv.ui.tvheadend

import androidx.compose.runtime.staticCompositionLocalOf
import com.ambient.tv.livetv.LiveTvCapabilities
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvRecording
import com.ambient.tv.livetv.LiveTvRepository

internal data class LiveRecordingControl(
    val eventId: String? = null,
    val available: Boolean = false,
    val scheduled: Boolean = false,
    val busy: Boolean = false,
    val record: () -> Unit = {},
    /** Backend permission, independent of the feature switch and whether EPG is available. */
    val canManageRecordings: Boolean = false,
    /** A matching recording is currently reported as active by the backend. */
    val isRecordingConfirmed: Boolean = false,
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
internal val LocalLiveTimeshiftConfigurable = staticCompositionLocalOf { false }

/** Use backend confirmation; an elapsed timer start alone does not prove recording. */
internal fun LiveTvRecording.isActivelyRecording(): Boolean =
    scheduleStatus.equals("recording", ignoreCase = true) ||
        scheduleStatus.equals("recordingError", ignoreCase = true) ||
        status.equals("recording", ignoreCase = true) ||
        status.equals("inprogress", ignoreCase = true)

/**
 * Guide recording request. A running programme records from now: an EPG-linked timer would
 * start at the programme's original (already elapsed) start time.
 */
internal suspend fun scheduleProgrammeRecording(
    repository: LiveTvRepository,
    event: LiveTvProgramme,
    series: Boolean,
    nowEpochSeconds: Long,
): Result<Unit> = when {
    series -> repository.scheduleSeries(event.eventId)
    event.isLive(nowEpochSeconds) -> repository.recordCurrentProgramme(event)
    else -> repository.scheduleRecording(event.eventId)
}

/** Player record button: records the running programme from now, or stops its timer. */
internal suspend fun toggleCurrentProgrammeRecording(repository: LiveTvRepository, event: LiveTvProgramme, stopping: Boolean) {
    val capabilities = repository.capabilities(true)
    check(capabilities.available && capabilities.manageTimers)
    val entry = findProgrammeRecording(event, repository.upcomingRecordings(true))
    if (stopping) {
        // Only cancel a timer still reported by the backend; EPG DVR IDs can be stale.
        if (entry != null) repository.cancelRecording(entry.uuid).getOrThrow()
    } else if (entry == null) {
        repository.recordCurrentProgramme(event).getOrThrow()
    }
}

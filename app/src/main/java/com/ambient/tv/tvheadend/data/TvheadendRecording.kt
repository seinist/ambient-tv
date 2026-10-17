package com.ambient.tv.tvheadend.data

import com.ambient.tv.livetv.LiveTvProgramme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun currentProgrammeRecordingConfig(
    event: LiveTvProgramme,
    nowEpochSeconds: Long,
    configUuid: String,
): JsonObject {
    require(event.isLive(nowEpochSeconds)) { "Programme is not currently running. Refresh the guide." }
    require(event.channelUuid.isNotBlank()) { "Missing recording channel." }
    return buildJsonObject {
        put("enabled", true)
        put("channel", event.channelUuid)
        put("start", nowEpochSeconds)
        put("stop", event.stopEpochSeconds)
        // Keep this an explicit timer: an EPG association can restore the original start time.
        put("title", buildJsonObject { put("und", event.title) })
        event.subtitle?.let { put("subtitle", buildJsonObject { put("und", it) }) }
        (event.description ?: event.summary)?.let { put("description", buildJsonObject { put("und", it) }) }
        if (configUuid.isNotBlank()) put("config_name", configUuid)
    }
}

/** Cancelling a running TVHeadend recording marks it failed; stop preserves normal completion. */
internal fun recordingTerminationPath(scheduleStatus: String?): String =
    when (scheduleStatus?.lowercase()) {
        "recording", "recordingerror" -> "api/dvr/entry/stop"
        "scheduled" -> "api/dvr/entry/cancel"
        else -> error("Recording is no longer scheduled or running. Refresh the recordings.")
    }

/** TVHeadend reports user aborts in its failed-recordings list. */
internal fun recordingWasAborted(status: String?): Boolean =
    status?.trim().equals("Aborted by user", ignoreCase = true)

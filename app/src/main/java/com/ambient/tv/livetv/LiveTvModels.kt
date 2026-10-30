package com.ambient.tv.livetv

@kotlinx.serialization.Serializable
data class LiveTvServerInfo(
    val name: String,
    val version: String?,
    val apiVersion: Int?,
)

@kotlinx.serialization.Serializable
enum class LiveTvChannelKind { UNKNOWN, TV, RADIO }

@kotlinx.serialization.Serializable
enum class LiveTvChannelAccess { UNKNOWN, FTA, ENCRYPTED }

@kotlinx.serialization.Serializable
data class LiveTvChannel(
    val uuid: String,
    val name: String,
    val number: Int?,
    val iconUrl: String?,
    val tags: List<String> = emptyList(),
    val sourceId: String = "",
    val numberLabel: String? = number?.toString(),
    val kind: LiveTvChannelKind = LiveTvChannelKind.UNKNOWN,
    val access: LiveTvChannelAccess = LiveTvChannelAccess.UNKNOWN,
    /** Server-side HbbTV discovery fallback; the live stream's AIT remains authoritative. */
    val hbbtvUrl: String? = null,
    val hbbtvName: String? = null,
)

@kotlinx.serialization.Serializable
data class LiveTvProgramme(
    val eventId: String,
    val channelUuid: String,
    val channelName: String,
    val title: String,
    val subtitle: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val startEpochSeconds: Long,
    val stopEpochSeconds: Long,
    val imageUrl: String? = null,
    val seriesLink: String? = null,
    val dvrUuid: String? = null,
    val sourceId: String = "",
) {
    val durationSeconds: Long get() = (stopEpochSeconds - startEpochSeconds).coerceAtLeast(0L)
    fun isLive(nowEpochSeconds: Long): Boolean = nowEpochSeconds in startEpochSeconds until stopEpochSeconds
}

@kotlinx.serialization.Serializable
data class LiveTvRecording(
    val uuid: String,
    val channelUuid: String?,
    val channelName: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val startEpochSeconds: Long,
    val stopEpochSeconds: Long,
    val status: String?,
    val scheduleStatus: String?,
    val url: String?,
    val imageUrl: String?,
    val fanartUrl: String?,
    val fileSizeBytes: Long?,
    val playPositionSeconds: Long? = null,
    val broadcastEventId: String? = null,
    val sourceId: String = "",
    val canDelete: Boolean = true,
    /** Timer padding and priority where the server reports them (null: not editable there). */
    val paddingBeforeMinutes: Int? = null,
    val paddingAfterMinutes: Int? = null,
    val priority: Int? = null,
)

@kotlinx.serialization.Serializable
data class LiveTvDvrConfig(
    val uuid: String,
    val name: String,
)

@kotlinx.serialization.Serializable
data class LiveTvSeriesTimer(
    val uuid: String,
    val name: String,
    val title: String?,
    val channelName: String?,
    val enabled: Boolean = true,
    val imageUrl: String? = null,
    val paddingBeforeMinutes: Int? = null,
    val paddingAfterMinutes: Int? = null,
    val priority: Int? = null,
    /** Whether the server can switch the rule off without deleting it (TVHeadend autorecs). */
    val canDisable: Boolean = false,
)

/** New settings for a timer or series timer; null fields stay as they are on the server. */
data class LiveTvTimerEdit(
    val paddingBeforeMinutes: Int? = null,
    val paddingAfterMinutes: Int? = null,
    val priority: Int? = null,
    val enabled: Boolean? = null,
) {
    val isEmpty: Boolean get() = paddingBeforeMinutes == null && paddingAfterMinutes == null && priority == null && enabled == null
}

@kotlinx.serialization.Serializable
data class LiveTvDashboard(
    val serverInfo: LiveTvServerInfo,
    val channels: List<LiveTvChannel>,
    val events: List<LiveTvProgramme>,
    val upcoming: List<LiveTvRecording>,
    val recordings: List<LiveTvRecording>,
    val failedRecordings: List<LiveTvRecording> = emptyList(),
    val autoRecs: List<LiveTvSeriesTimer> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class LiveTvSearchResults(
    val channels: List<LiveTvChannel> = emptyList(),
    val programs: List<LiveTvProgramme> = emptyList(),
    val recordings: List<LiveTvRecording> = emptyList(),
    val incomplete: Boolean = false,
) {
    val size: Int get() = channels.size + programs.size + recordings.size
}

data class LiveTvPlaybackSource(
    val url: String,
    val headers: Map<String, String>,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val durationMs: Long? = null,
    val startPositionMs: Long = 0L,
    val isLive: Boolean = false,
    val streamProfile: String? = null,
    val session: LiveTvPlaybackSession? = null,
)

data class LiveTvStreamProfile(
    val uuid: String?,
    val name: String,
    val profileClass: String? = null,
    val enabled: Boolean = true,
)

/** Stable server identity; multiple servers of the same provider can coexist. */
data class LiveTvSource(val id: String, val system: String, val name: String)

@kotlinx.serialization.Serializable
data class LiveTvCapabilities(
    val available: Boolean = false,
    val guide: Boolean = false,
    val recordings: Boolean = false,
    val timers: Boolean = false,
    val manageTimers: Boolean = false,
    val seriesTimers: Boolean = false,
    val deleteRecordings: Boolean = false,
    /** Timers and series timers can be edited (padding, priority, enabled). */
    val editTimers: Boolean = false,
)

/** Owned by PlaybackService, including while the user browses another screen. */
interface LiveTvPlaybackSession {
    suspend fun report(positionMs: Long, playing: Boolean, stopped: Boolean)
}

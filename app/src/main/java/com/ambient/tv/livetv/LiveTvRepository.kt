package com.ambient.tv.livetv

import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** One dashboard load may obtain its capabilities and channel list from the same response. */
data class LiveTvDashboardPrefetch(
    val capabilities: LiveTvCapabilities,
    val channels: List<LiveTvChannel>? = null,
)

interface LiveTvRepository {
    val source: LiveTvSource
    /** A recent read needed stored fallback data; this alone is not evidence of an offline host. */
    val servingCachedData: Boolean get() = false
    /** Marker taken before a load; [servedCacheSince] answers for that load only. */
    val cacheFallbackMarker: Long get() = 0L
    /**
     * Whether a read since [marker] fell back to stored data. Sources without per-load tracking
     * use [servingCachedData]; TVHeadend counts its own fallbacks, so a flag left over from
     * another screen's read (or one success racing another fallback) cannot decide this load.
     */
    fun servedCacheSince(marker: Long): Boolean = servingCachedData
    fun authHeaders(): Map<String, String> = emptyMap()
    suspend fun cachedChannels(): List<LiveTvChannel>? = null
    suspend fun cachedGuideEvents(start: Long, hours: Int): List<LiveTvProgramme>? = null
    suspend fun cachedChannelGuideEvents(channelId: String, start: Long, hours: Int): List<LiveTvProgramme>? =
        cachedGuideEvents(start, hours)?.filter { it.channelUuid == channelId }
    suspend fun capabilities(force: Boolean = false): LiveTvCapabilities
    suspend fun channels(force: Boolean = false): List<LiveTvChannel>
    /** Fresh protected API read. Cached dashboard data or server identity cannot grant play access. */
    suspend fun verifyPlayback(kind: String, id: String): Boolean = when (kind) {
        "channel" -> channels(force = true).let { id.isBlank() || it.any { channel -> channel.uuid == id } }
        "recording" -> finishedRecordings(force = true).let { id.isBlank() || it.any { recording -> recording.uuid == id } } ||
            failedRecordings(force = true).any { recording -> recording.uuid == id }
        else -> false
    }
    suspend fun epgEvents(startEpochSeconds: Long = System.currentTimeMillis() / 1000 - 3600, windowHours: Int = 24, force: Boolean = false): List<LiveTvProgramme>
    suspend fun channelEpgEvents(channelId: String, startEpochSeconds: Long, windowHours: Int = 8): List<LiveTvProgramme> =
        epgEvents(startEpochSeconds, windowHours).filter { it.channelUuid == channelId }
    suspend fun nowEvents(force: Boolean = false) = epgEvents(windowHours = 2, force = force).filter { it.isLive(System.currentTimeMillis() / 1000) }
    suspend fun finishedRecordings(force: Boolean = false): List<LiveTvRecording> = emptyList()
    suspend fun failedRecordings(force: Boolean = false): List<LiveTvRecording> = emptyList()
    suspend fun upcomingRecordings(force: Boolean = false): List<LiveTvRecording> = emptyList()
    suspend fun autoRecordings(force: Boolean = false): List<LiveTvSeriesTimer> = emptyList()
    suspend fun scheduleRecording(eventId: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun recordCurrentProgramme(event: LiveTvProgramme): Result<Unit> = suspendResult {
        check(event.isLive(System.currentTimeMillis() / 1000)) { "Programme has ended. Refresh the guide." }
        scheduleRecording(event.eventId).getOrThrow()
    }
    suspend fun scheduleSeries(eventId: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun cancelRecording(uuid: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun deleteRecording(uuid: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun deleteAutoRecording(uuid: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun updateTimer(uuid: String, edit: LiveTvTimerEdit): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun updateSeriesTimer(uuid: String, edit: LiveTvTimerEdit): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun liveStream(channel: LiveTvChannel, profileOverride: String? = null): LiveTvPlaybackSource
    suspend fun recordingStream(entry: LiveTvRecording): LiveTvPlaybackSource
    suspend fun streamProfiles(force: Boolean = false): List<LiveTvStreamProfile> = emptyList()
    suspend fun invalidateAll() = Unit
    suspend fun prefetchDashboard(force: Boolean = false): LiveTvDashboardPrefetch = LiveTvDashboardPrefetch(capabilities(force))
    suspend fun dashboard(force: Boolean = false, prefetch: LiveTvDashboardPrefetch? = null): LiveTvDashboard = coroutineScope {
        val prepared = prefetch ?: prefetchDashboard(force)
        val capabilities = prepared.capabilities
        check(capabilities.available) { "Live TV is not available for this user or server." }
        val warnings = java.util.concurrent.ConcurrentLinkedQueue<String>()
        suspend fun <T> optional(label: String, block: suspend () -> List<T>): List<T> = suspendResult { block() }.getOrElse {
            warnings.add("$label: ${it.message ?: "Unavailable"}"); emptyList()
        }
        val channels = async { prepared.channels ?: channels(force) }
        val events = async { if (capabilities.guide) optional("EPG") { epgEvents(windowHours = 12, force = force) } else emptyList() }
        val recordings = async { if (capabilities.recordings) optional("Recordings") { finishedRecordings(force) } else emptyList() }
        val timers = async { if (capabilities.timers) optional("Timers") { upcomingRecordings(force) } else emptyList() }
        val series = async { if (capabilities.timers && capabilities.seriesTimers) optional("Series timers") { autoRecordings(force) } else emptyList() }
        LiveTvDashboard(LiveTvServerInfo(source.name, null, null), channels.await(), events.await(), timers.await(), recordings.await(), autoRecs = series.await(), warnings = warnings.toList())
    }
    suspend fun search(query: String): LiveTvSearchResults = coroutineScope {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return@coroutineScope LiveTvSearchResults()
        fun matches(value: String) = terms.all { it in value.lowercase() }
        val channels = async { channels().filter { matches("${it.name} ${it.numberLabel.orEmpty()}") }.take(24) }
        val capabilities = capabilities()
        val events = async {
            if (capabilities.guide) suspendResult { epgEvents(windowHours = 72) }
            else Result.success(emptyList())
        }
        val recordings = async {
            if (capabilities.recordings) suspendResult { finishedRecordings() }
            else Result.success(emptyList())
        }
        val eventsResult = events.await()
        val recordingsResult = recordings.await()
        LiveTvSearchResults(
            channels.await(),
            eventsResult.getOrDefault(emptyList()).filter { matches("${it.title} ${it.subtitle.orEmpty()} ${it.channelName}") }.take(24),
            recordingsResult.getOrDefault(emptyList()).filter { matches(it.title) }.take(24),
            incomplete = eventsResult.isFailure || recordingsResult.isFailure,
        )
    }
}

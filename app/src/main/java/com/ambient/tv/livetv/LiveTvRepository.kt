package com.ambient.tv.livetv

import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

interface LiveTvRepository {
    val source: LiveTvSource
    fun authHeaders(): Map<String, String> = emptyMap()
    suspend fun capabilities(force: Boolean = false): LiveTvCapabilities
    suspend fun channels(force: Boolean = false): List<LiveTvChannel>
    suspend fun epgEvents(startEpochSeconds: Long = System.currentTimeMillis() / 1000 - 3600, windowHours: Int = 24, force: Boolean = false): List<LiveTvProgramme>
    suspend fun channelEpgEvents(channelId: String, startEpochSeconds: Long, windowHours: Int = 8): List<LiveTvProgramme> =
        epgEvents(startEpochSeconds, windowHours).filter { it.channelUuid == channelId }
    suspend fun nowEvents(force: Boolean = false) = epgEvents(windowHours = 2, force = force).filter { it.isLive(System.currentTimeMillis() / 1000) }
    suspend fun finishedRecordings(force: Boolean = false): List<LiveTvRecording> = emptyList()
    suspend fun failedRecordings(force: Boolean = false): List<LiveTvRecording> = emptyList()
    suspend fun upcomingRecordings(force: Boolean = false): List<LiveTvRecording> = emptyList()
    suspend fun autoRecordings(force: Boolean = false): List<LiveTvSeriesTimer> = emptyList()
    suspend fun scheduleRecording(eventId: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun scheduleSeries(eventId: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun cancelRecording(uuid: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun deleteRecording(uuid: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun deleteAutoRecording(uuid: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun liveStream(channel: LiveTvChannel, profileOverride: String? = null): LiveTvPlaybackSource
    suspend fun recordingStream(entry: LiveTvRecording): LiveTvPlaybackSource
    suspend fun streamProfiles(force: Boolean = false): List<LiveTvStreamProfile> = emptyList()
    suspend fun invalidateAll() = Unit
    suspend fun dashboard(): LiveTvDashboard = coroutineScope {
        val capabilities = capabilities()
        check(capabilities.available) { "Live TV is not available for this user or server." }
        val warnings = java.util.concurrent.ConcurrentLinkedQueue<String>()
        suspend fun <T> optional(label: String, block: suspend () -> List<T>): List<T> = suspendResult { block() }.getOrElse {
            warnings.add("$label: ${it.message ?: "Unavailable"}"); emptyList()
        }
        val channels = async { channels() }
        val events = async { if (capabilities.guide) optional("EPG") { epgEvents(windowHours = 12) } else emptyList() }
        val recordings = async { if (capabilities.recordings) optional("Recordings") { finishedRecordings() } else emptyList() }
        val timers = async { if (capabilities.timers) optional("Timers") { upcomingRecordings() } else emptyList() }
        val series = async { if (capabilities.timers && capabilities.seriesTimers) optional("Series timers") { autoRecordings() } else emptyList() }
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

package com.ambient.tv.livetv

import com.ambient.tv.util.suspendResult
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Jellyfin and Emby share the LiveTv protocol; their library repositories remain independent. */
internal class MediaBrowserLiveTvRepository(
    override val source: LiveTvSource,
    private val http: LiveTvHttp,
    private val userId: String,
    private val deviceId: String,
    private val audioProfile: () -> LiveTvAudioProfile,
) : LiveTvRepository {
    private val lock = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, Any>>()
    private suspend fun <T : Any> cached(key: String, ttl: Long, force: Boolean, block: suspend () -> T): T = lock.withLock {
        @Suppress("UNCHECKED_CAST")
        cache[key]?.takeIf { !force && System.currentTimeMillis() - it.first < ttl }?.let { return@withLock it.second as T }
        block().also {
            cache[key] = System.currentTimeMillis() to it
            if (cache.size > 128) cache.entries.minByOrNull { entry -> entry.value.first }?.let { oldest -> cache.remove(oldest.key) }
        }
    }
    override fun authHeaders() = http.headers
    override suspend fun invalidateAll() = lock.withLock { cache.clear() }
    private val userQuery get() = mapOf("UserId" to userId)

    override suspend fun capabilities(force: Boolean): LiveTvCapabilities = cached("capabilities", 60_000, force) {
        try {
            val info = http.request("LiveTv/Info")
            val policy = suspendResult { http.request("Users/${segment(userId)}").obj("Policy") }.getOrNull()
            if (info.flag("IsEnabled") == false || policy?.flag("EnableLiveTvAccess") == false) return@cached LiveTvCapabilities()
            val channels = http.request("LiveTv/Channels", userQuery + mapOf("Limit" to "1", "EnableTotalRecordCount" to "true"))
            val available = channels.number("TotalRecordCount")?.let { it > 0 } ?: channels.objects("Items").isNotEmpty()
            val management = policy?.flag("EnableLiveTvManagement") == true
            LiveTvCapabilities(available, available, available, available, management, available, management)
        } catch (e: LiveTvHttpException) {
            if (e.status in setOf(403, 404)) LiveTvCapabilities() else throw e
        }
    }

    private suspend fun items(path: String, query: Map<String, String?> = emptyMap()): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var start = 0
        do {
            val page = http.request(path, userQuery + query + mapOf("StartIndex" to start.toString(), "Limit" to "250", "EnableTotalRecordCount" to "true"))
            val next = page.objects("Items")
            if (next.isEmpty()) break
            if (start > 0 && next.first().text("Id") == result.firstOrNull()?.text("Id")) break
            result += next
            start += next.size
        } while (start < (page.number("TotalRecordCount") ?: start.toLong()) && start < 30_000)
        return result.distinctBy { it.text("Id") }
    }
    private fun image(item: JsonObject, type: String = "Primary"): String? {
        val id = item.text("Id") ?: return null
        val tag = if (type == "Backdrop") (item["BackdropImageTags"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull else item.obj("ImageTags").text(type)
        if (tag == null) return null
        return http.url("Items/${segment(id)}/Images/$type", mapOf("tag" to tag, "maxWidth" to "1280")).toString()
    }
    override suspend fun channels(force: Boolean): List<LiveTvChannel> = cached("channels", 300_000, force) {
        items("LiveTv/Channels", mapOf("AddCurrentProgram" to "true", "Fields" to "CurrentProgram,ChannelInfo", "SortBy" to "ChannelNumber,SortName")).mapNotNull { item ->
            val id = item.text("Id") ?: return@mapNotNull null
            val number = item.text("ChannelNumber") ?: item.text("Number")
            LiveTvChannel(id, item.text("Name").orEmpty(), number?.toIntOrNull(), image(item), sourceId = source.id, numberLabel = number)
        }
    }
    private fun programme(item: JsonObject): LiveTvProgramme? {
        val id = item.text("Id") ?: return null
        val channel = item.text("ChannelId") ?: return null
        return LiveTvProgramme(id, channel, item.text("ChannelName").orEmpty(), item.text("Name").orEmpty(),
            subtitle = item.text("EpisodeTitle"), description = item.text("Overview"),
            startEpochSeconds = item.epoch("StartDate") ?: return null, stopEpochSeconds = item.epoch("EndDate") ?: return null,
            imageUrl = image(item), seriesLink = item.text("SeriesId") ?: id.takeIf { item.flag("IsSeries") == true },
            dvrUuid = item.text("TimerId"), sourceId = source.id)
    }
    override suspend fun epgEvents(startEpochSeconds: Long, windowHours: Int, force: Boolean) = guide(null, startEpochSeconds, windowHours, force)
    override suspend fun channelEpgEvents(channelId: String, startEpochSeconds: Long, windowHours: Int) = guide(channelId, startEpochSeconds, windowHours, false)
    private suspend fun guide(channelId: String?, startEpochSeconds: Long, windowHours: Int, force: Boolean): List<LiveTvProgramme> =
        cached("epg:$channelId:${startEpochSeconds / 300}:$windowHours", 45_000, force) {
            items("LiveTv/Programs", mapOf(
                "ChannelIds" to channelId,
                "MinEndDate" to Instant.ofEpochSecond(startEpochSeconds).toString(),
                "MaxStartDate" to Instant.ofEpochSecond(startEpochSeconds + windowHours.coerceIn(1, 72) * 3600).toString(),
                "Fields" to "Overview,ChannelInfo", "SortBy" to "StartDate",
            )).mapNotNull(::programme)
        }
    private fun recording(item: JsonObject, timer: Boolean): LiveTvRecording? {
        val id = item.text("Id") ?: return null
        val info = item.obj("ProgramInfo").takeIf { it.isNotEmpty() } ?: item
        val start = item.epoch("StartDate") ?: info.epoch("StartDate") ?: item.epoch("DateCreated") ?: 0L
        return LiveTvRecording(id, item.text("ChannelId"), item.text("ChannelName").orEmpty(), item.text("Name") ?: info.text("Name").orEmpty(),
            subtitle = info.text("EpisodeTitle"), description = item.text("Overview") ?: info.text("Overview"),
            startEpochSeconds = start, stopEpochSeconds = item.epoch("EndDate") ?: start + (item.number("RunTimeTicks") ?: 0L) / 10_000_000,
            status = item.text("Status"), scheduleStatus = if (timer) "scheduled" else "completed",
            url = null, imageUrl = image(info), fanartUrl = image(info, "Backdrop"), fileSizeBytes = item.number("Size"),
            playPositionSeconds = item.obj("UserData").number("PlaybackPositionTicks")?.div(10_000_000),
            broadcastEventId = item.text("ProgramId"), sourceId = source.id, canDelete = item.flag("CanDelete") != false)
    }
    override suspend fun finishedRecordings(force: Boolean): List<LiveTvRecording> = cached("recordings", 20_000, force) {
        items("LiveTv/Recordings", mapOf("Fields" to "Overview,CanDelete", "IsInProgress" to "false")).mapNotNull { recording(it, false) }
    }
    override suspend fun upcomingRecordings(force: Boolean): List<LiveTvRecording> = cached("timers", 15_000, force) {
        http.request("LiveTv/Timers", userQuery).objects("Items").mapNotNull { recording(it, true) }.sortedBy { it.startEpochSeconds }
    }
    override suspend fun autoRecordings(force: Boolean): List<LiveTvSeriesTimer> = cached("series", 30_000, force) {
        http.request("LiveTv/SeriesTimers", userQuery).objects("Items").mapNotNull {
            val id = it.text("Id") ?: return@mapNotNull null
            LiveTvSeriesTimer(id, it.text("Name").orEmpty(), it.text("Name"), it.text("ChannelName"))
        }
    }
    private suspend fun schedule(id: String, series: Boolean): Result<Unit> = suspendResult {
        check(capabilities().manageTimers) { "Recording is not permitted for this user." }
        val defaults = http.request("LiveTv/Timers/Defaults", mapOf("ProgramId" to id))
        val program = http.request("LiveTv/Programs/${segment(id)}", userQuery)
        val body = JsonObject(defaults + buildJsonObject {
            put("ProgramId", id)
            for (key in listOf("Name", "Overview", "StartDate", "EndDate", "ChannelId", "SeriesId")) program[key]?.let { put(key, it) }
        })
        http.request(if (series) "LiveTv/SeriesTimers" else "LiveTv/Timers", method = "POST", body = body)
        invalidateAll()
    }
    override suspend fun scheduleRecording(eventId: String) = schedule(eventId, false)
    override suspend fun scheduleSeries(eventId: String) = schedule(eventId, true)
    private suspend fun delete(path: String): Result<Unit> = suspendResult {
        check(capabilities().manageTimers) { "Recording management is not permitted for this user." }
        http.request(path, method = "DELETE")
        invalidateAll()
    }
    override suspend fun cancelRecording(uuid: String) = delete("LiveTv/Timers/${segment(uuid)}")
    override suspend fun deleteRecording(uuid: String) = delete("LiveTv/Recordings/${segment(uuid)}")
    override suspend fun deleteAutoRecording(uuid: String) = delete("LiveTv/SeriesTimers/${segment(uuid)}")
    override suspend fun liveStream(channel: LiveTvChannel, profileOverride: String?) = playback(channel.uuid, channel.name, true, 0)
    override suspend fun recordingStream(entry: LiveTvRecording) = playback(entry.uuid, entry.title, false, (entry.playPositionSeconds ?: 0) * 1000)

    private suspend fun playback(id: String, title: String, live: Boolean, startMs: Long): LiveTvPlaybackSource {
        val profile = audioProfile().browserDeviceProfile()
        val request = buildJsonObject {
            put("UserId", userId); put("IsPlayback", true); put("AutoOpenLiveStream", true)
            put("EnableDirectPlay", true); put("EnableDirectStream", true); put("EnableTranscoding", true)
            put("AllowVideoStreamCopy", true); put("AllowAudioStreamCopy", true)
            put("StartTimeTicks", startMs * 10_000); put("DeviceProfile", profile)
        }
        val info = http.request("Items/${segment(id)}/PlaybackInfo", method = "POST", body = request)
        check(info.text("ErrorCode") == null) { "Server could not open the TV stream: ${info.text("ErrorCode")}" }
        var media = info.objects("MediaSources").firstOrNull() ?: error("Server returned no TV stream.")
        val playSession = info.text("PlaySessionId")
        if (media.flag("RequiresOpening") == true && media.text("LiveStreamId").isNullOrBlank()) {
            media = http.request("LiveStreams/Open", method = "POST", body = JsonObject(request + buildJsonObject {
                put("ItemId", id); media.text("OpenToken")?.let { put("OpenToken", it) }
                playSession?.let { put("PlaySessionId", it) }
            })).obj("MediaSource")
        }
        val directStream = media.text("DirectStreamUrl")?.takeIf { media.flag("SupportsDirectStream") != false }
        val directPlay = directStream == null && media.flag("SupportsDirectPlay") == true
        val method = if (directStream != null) "DirectStream" else if (directPlay) "DirectPlay" else "Transcode"
        val session = BrowserSession(http, id, media.text("Id"), playSession, media.text("LiveStreamId"), deviceId, method)
        try {
            val direct = directStream ?: if (directPlay) http.url("Videos/${segment(id)}/stream", mapOf(
                    "Static" to "true", "MediaSourceId" to media.text("Id"), "LiveStreamId" to media.text("LiveStreamId"),
                    "PlaySessionId" to playSession,
                )).toString() else null
            val path = direct ?: media.text("TranscodingUrl") ?: error("Server returned no playable TV URL.")
            val url = http.url(path).toString()
            return LiveTvPlaybackSource(url, authHeaders(), title, startPositionMs = startMs, isLive = live,
                durationMs = media.number("RunTimeTicks")?.div(10_000), session = session)
        } catch (e: Exception) {
            withContext(NonCancellable) { suspendResult { session.report(0, false, true) } }
            throw e
        }
    }
}

internal class BrowserSession(
    private val http: LiveTvHttp, private val item: String, private val media: String?,
    private val session: String?, private val live: String?, private val device: String,
    private val playMethod: String = "Transcode",
) : LiveTvPlaybackSession {
    private val mutex = Mutex()
    private var started = false
    private var closed = false
    override suspend fun report(positionMs: Long, playing: Boolean, stopped: Boolean) = mutex.withLock {
        if (closed) return@withLock
        val body = buildJsonObject {
            put("ItemId", item); media?.let { put("MediaSourceId", it) }; session?.let { put("PlaySessionId", it) }
            live?.let { put("LiveStreamId", it) }; put("PositionTicks", positionMs.coerceAtLeast(0) * 10_000)
            put("IsPaused", !playing); put("CanSeek", live == null); put("PlayMethod", playMethod)
        }
        if (stopped) {
            // Cleanup still runs if playback reporting fails. It is idempotent per lease.
            try { http.request("Sessions/Playing/Stopped", method = "POST", body = body) }
            finally {
                closed = releaseLiveTvResources(
                    { if (live != null) http.request("LiveStreams/Close", mapOf("LiveStreamId" to live), "POST") },
                    { if (session != null) http.request("Videos/ActiveEncodings", mapOf("DeviceId" to device, "PlaySessionId" to session), "DELETE") },
                )
            }
        } else {
            if (!started) { http.request("Sessions/Playing", method = "POST", body = body); started = true }
            http.request("Sessions/Playing/Progress", method = "POST", body = body)
        }
        Unit
    }
}

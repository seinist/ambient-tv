package com.ambient.tv.livetv

import com.ambient.tv.util.suspendResult
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** PMS DVR, not Plex's separately authenticated, ad-supported cloud channel catalogue. */
internal class PlexLiveTvRepository(override val source: LiveTvSource, private val http: LiveTvHttp, private val audioProfile: () -> LiveTvAudioProfile) : LiveTvRepository {
    private data class Lineup(val prefix: String, val dvr: String)
    private data class TuneChannel(val lineup: Lineup, val channel: LiveTvChannel, val rawId: String, val grid: String, val vcn: String)
    private data class Airing(val programme: LiveTvProgramme, val guid: String)
    private val lock = Mutex()
    private var channelCache: List<TuneChannel> = emptyList()
    private var loadedAt = 0L
    private val airings = ConcurrentHashMap<String, Airing>()
    private val grids = ConcurrentHashMap<String, Pair<Long, List<JsonObject>>>()
    private val permits = Semaphore(4)
    override fun authHeaders() = http.headers
    private suspend fun container(path: String, query: Map<String, String?> = emptyMap(), method: String = "GET") = http.request(path, query, method).obj("MediaContainer")
    override suspend fun invalidateAll() { lock.withLock { loadedAt = 0 }; grids.clear() }

    override suspend fun capabilities(force: Boolean): LiveTvCapabilities = try {
        val available = channels(force).isNotEmpty()
        val timers = available && suspendResult { container("media/subscriptions/scheduled") }.isSuccess
        // PMS stores finished DVR recordings in regular movie/series libraries. Do not relabel
        // the user's entire library as recordings or offer library deletion through a TV timer.
        LiveTvCapabilities(available, available, recordings = false, timers = timers, manageTimers = timers, seriesTimers = timers)
    } catch (e: LiveTvHttpException) {
        if (e.status in setOf(403, 404)) LiveTvCapabilities() else throw e
    }
    override suspend fun channels(force: Boolean): List<LiveTvChannel> = lock.withLock {
        if (!force && loadedAt > System.currentTimeMillis() - 300_000) return@withLock channelCache.map { it.channel }
        val providers = container("media/providers").objects("MediaProvider")
        val dvrs = container("livetv/dvrs").let { it.objects("DVR") + it.objects("Dvr") }
        val lineups = providers.mapNotNull { p ->
            val prefix = p.text("identifier")?.takeIf { it.startsWith("tv.plex.providers.epg.") } ?: return@mapNotNull null
            val dvr = dvrs.firstOrNull { it.text("key") == p.text("parentID") }
                ?: dvrs.singleOrNull() ?: return@mapNotNull null
            Lineup(prefix, dvr.text("key") ?: return@mapNotNull null)
        }.distinct()
        channelCache = lineups.flatMap { lineup ->
            container("${lineup.prefix}/lineups/dvr/channels").objects("Channel").mapNotNull { c ->
                val id = c.text("id") ?: return@mapNotNull null
                val vcn = c.text("vcn") ?: return@mapNotNull null
                val channel = LiveTvChannel("${lineup.dvr}:$id", c.text("title") ?: c.text("callSign") ?: vcn,
                    vcn.toIntOrNull(), artwork(c.text("thumb")), sourceId = source.id, numberLabel = vcn)
                TuneChannel(lineup, channel, id, c.text("gridKey") ?: id, vcn)
            }
        }
        loadedAt = System.currentTimeMillis()
        channelCache.map { it.channel }
    }
    private fun artwork(path: String?): String? = path?.takeIf(String::isNotBlank)?.let {
        // The PMS image proxy keeps account credentials away from external artwork hosts.
        http.url("photo/:/transcode", mapOf("url" to it, "width" to "1280", "height" to "720", "minSize" to "1")).toString()
    }
    override suspend fun epgEvents(startEpochSeconds: Long, windowHours: Int, force: Boolean) = guide(null, startEpochSeconds, windowHours, force)
    override suspend fun channelEpgEvents(channelId: String, startEpochSeconds: Long, windowHours: Int) = guide(channelId, startEpochSeconds, windowHours, false)
    private suspend fun guide(channelId: String?, startEpochSeconds: Long, windowHours: Int, force: Boolean): List<LiveTvProgramme> = coroutineScope {
        channels()
        val end = startEpochSeconds + windowHours.coerceIn(1, 72) * 3600L
        val startDate = Instant.ofEpochSecond(startEpochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
        val endDate = Instant.ofEpochSecond(end).atZone(ZoneId.systemDefault()).toLocalDate()
        val days = generateSequence(startDate) { it.plusDays(1).takeUnless { day -> day > endDate } }.toList()
        val tuning = lock.withLock { channelCache.filter { channelId == null || it.channel.uuid == channelId } }
        tuning.flatMap { channel -> days.map { date -> async { permits.withPermit {
            val cacheKey = "${channel.channel.uuid}:$date"
            val cached = grids[cacheKey]?.takeIf { !force && it.first > System.currentTimeMillis() - 120_000 }?.second
            val rows = cached ?: container("${channel.lineup.prefix}/grid", mapOf("channelGridKey" to channel.grid, "date" to date.toString()))
                .objects("Metadata").also { grids[cacheKey] = System.currentTimeMillis() to it }
            rows.flatMap { item -> item.objects("Media").mapNotNull { media ->
                val begins = media.number("beginsAt") ?: return@mapNotNull null
                val ends = media.number("endsAt") ?: return@mapNotNull null
                if (begins >= end || ends <= startEpochSeconds || media.text("channelIdentifier")?.let { it != channel.rawId && it != channel.vcn } == true) return@mapNotNull null
                val guid = item.text("guid") ?: item.text("ratingKey") ?: return@mapNotNull null
                val id = "${channel.channel.uuid}:$begins:$guid"
                LiveTvProgramme(id, channel.channel.uuid, channel.channel.name, item.text("grandparentTitle") ?: item.text("title").orEmpty(),
                    subtitle = item.text("title").takeIf { item.text("grandparentTitle") != null }, description = item.text("summary"),
                    startEpochSeconds = begins, stopEpochSeconds = ends, imageUrl = artwork(item.text("thumb")),
                    seriesLink = item.text("grandparentGuid") ?: item.text("grandparentTitle"), sourceId = source.id)
                    .also { airings[id] = Airing(it, guid) }
            } }
        } } } }.flatMap { it.await() }.distinctBy { it.eventId }.sortedBy { it.startEpochSeconds }.also {
            grids.entries.removeAll { it.value.first < System.currentTimeMillis() - 300_000 }
            airings.entries.removeAll { it.value.programme.stopEpochSeconds < startEpochSeconds - 86400 }
        }
    }
    override suspend fun upcomingRecordings(force: Boolean): List<LiveTvRecording> = container("media/subscriptions/scheduled").objects("MediaGrabOperation").mapNotNull { grab ->
        val item = grab.obj("Metadata")
        val media = item.objects("Media").firstOrNull() ?: return@mapNotNull null
        val start = media.number("beginsAt") ?: return@mapNotNull null
        // An operation is one airing. Never use its subscription ID for cancelling a single timer.
        val operation = grab.text("key") ?: grab.text("id")
        LiveTvRecording(operation ?: "scheduled:${grab.text("mediaSubscriptionID")}:$start", null, media.text("channelTitle").orEmpty(),
            item.text("grandparentTitle") ?: item.text("title").orEmpty(), description = item.text("summary"), startEpochSeconds = start,
            stopEpochSeconds = media.number("endsAt") ?: start, status = grab.text("status"), scheduleStatus = "scheduled",
            url = null, imageUrl = artwork(item.text("thumb")), fanartUrl = null, fileSizeBytes = null, sourceId = source.id, canDelete = operation != null)
    }
    override suspend fun autoRecordings(force: Boolean): List<LiveTvSeriesTimer> = container("media/subscriptions").objects("MediaSubscription").mapNotNull {
        val id = it.text("key") ?: return@mapNotNull null
        LiveTvSeriesTimer(id, it.text("title").orEmpty(), it.text("title"), null)
    }
    override suspend fun scheduleRecording(eventId: String) = schedule(eventId, false)
    override suspend fun scheduleSeries(eventId: String) = schedule(eventId, true)
    private suspend fun schedule(id: String, series: Boolean): Result<Unit> = suspendResult {
        val airing = airings[id] ?: error("Programme expired. Refresh the guide.")
        val templates = container("media/subscriptions/template", mapOf("guid" to airing.guid)).objects("SubscriptionTemplate").flatMap { it.objects("MediaSubscription") }
        val candidates = templates.filter { if (series) it.number("type") == 2L else it.number("type") in setOf(1L, 4L) }
        val template = candidates.singleOrNull() ?: candidates.singleOrNull { it.flag("selected") == true }
            ?: error("Configure a recording library in Plex first.")
        val library = template.text("targetLibrarySectionID") ?: error("Configure a recording library in Plex first.")
        val params = ("http://localhost/?" + template.text("parameters").orEmpty()).toHttpUrl()
        val query = params.queryParameterNames.associateWith { params.queryParameter(it) }.toMutableMap()
        query["type"] = template.text("type"); query["targetLibrarySectionID"] = library
        val locations = container("library/sections").objects("Directory").firstOrNull { it.text("key") == library }?.objects("Location").orEmpty()
        val location = locations.singleOrNull() ?: locations.singleOrNull { it.text("path") == template.text("locationPath") }
            ?: error("Choose the recording folder in Plex first.")
        query["targetSectionLocationID"] = location.text("id") ?: error("Missing recording folder.")
        template.objects("Setting").forEach { setting -> setting.text("id")?.let { query["prefs[$it]"] = setting.text("value") ?: setting.text("default") } }
        query["prefs[replaceLowerQuality]"] = "false"
        query["prefs[autoDeletionItemPolicyUnwatchedLibrary]"] = "0"
        query["prefs[autoDeletionItemPolicyWatchedLibrary]"] = "0"
        query["prefs[oneShot]"] = (!series).toString()
        if (!series) {
            query["prefs[lineupChannel]"] = airing.programme.channelUuid.substringAfter(':')
            query["prefs[startTimeslot]"] = airing.programme.startEpochSeconds.toString()
        }
        container("media/subscriptions", query, "POST")
        invalidateAll()
    }
    override suspend fun cancelRecording(uuid: String): Result<Unit> = suspendResult {
        check(!uuid.startsWith("scheduled:")) { "Cancel this airing in Plex, or remove its recording rule." }
        http.request("media/grabbers/operations/${segment(uuid.substringAfterLast('/'))}", method = "DELETE"); invalidateAll()
    }
    override suspend fun deleteAutoRecording(uuid: String): Result<Unit> = suspendResult {
        http.request("media/subscriptions/${segment(uuid.substringAfterLast('/'))}", method = "DELETE"); invalidateAll()
    }
    override suspend fun liveStream(channel: LiveTvChannel, profileOverride: String?): LiveTvPlaybackSource {
        channels()
        val tune = lock.withLock { channelCache.firstOrNull { it.channel.uuid == channel.uuid } } ?: error("Channel not found.")
        val result = container("livetv/dvrs/${segment(tune.lineup.dvr)}/channels/${segment(tune.vcn)}/tune", method = "POST")
        val item = result.objects("Metadata").firstOrNull() ?: error("Plex could not tune this channel.")
        val id = item.objects("Media").firstOrNull()?.text("uuid") ?: item.text("key")?.substringAfterLast('/') ?: error("Plex returned no Live TV session.")
        val session = UUID.randomUUID().toString()
        val path = "/livetv/sessions/${segment(id)}"
        val url = http.url("video/:/transcode/universal/start.m3u8", mapOf("path" to path, "session" to session,
            "protocol" to "hls", "mediaIndex" to "0", "partIndex" to "0",
            "videoCodec" to "h264", "X-Plex-Session-Identifier" to session) + audioProfile().plexQuery())
        return LiveTvPlaybackSource(url.toString(), authHeaders(), channel.name, isLive = true, session = PlexTvSession(http, path, session))
    }
    override suspend fun recordingStream(entry: LiveTvRecording): LiveTvPlaybackSource = error("Open Plex DVR recordings from their movie or series library.")
}

internal class PlexTvSession(private val http: LiveTvHttp, private val path: String, private val id: String) : LiveTvPlaybackSession {
    private val lock = Mutex()
    private var closed = false
    override suspend fun report(positionMs: Long, playing: Boolean, stopped: Boolean) = lock.withLock {
        if (closed) return@withLock
        try {
            http.request(":/timeline", mapOf("key" to path, "state" to if (stopped) "stopped" else if (playing) "playing" else "paused",
                "time" to positionMs.toString(), "session" to id, "X-Plex-Session-Identifier" to id))
        } finally {
            if (stopped) {
                closed = releaseLiveTvResources({ http.request("video/:/transcode/universal/stop", mapOf("session" to id)) })
            }
        }
        Unit
    }
}

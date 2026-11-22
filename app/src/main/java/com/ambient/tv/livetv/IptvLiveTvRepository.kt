package com.ambient.tv.livetv

import com.ambient.tv.data.iptv.IptvChannel
import com.ambient.tv.data.iptv.IptvGuideMatcher
import com.ambient.tv.data.iptv.IptvPlaylist
import com.ambient.tv.data.iptv.IptvSource
import com.ambient.tv.data.iptv.M3uParser
import com.ambient.tv.data.iptv.XmltvParser
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Cosmetic labels and unrelated preferences do not invalidate a parsed guide. */
internal fun sameIptvGuideSource(a: IptvSource, b: IptvSource): Boolean =
    a.id == b.id && a.playlistUrl == b.playlistUrl && a.epgUrl == b.epgUrl && a.userAgent == b.userAgent && a.enabled == b.enabled

/**
 * IPTV from an M3U playlist with an optional XMLTV guide, behind the same interface as the
 * media servers, so the Live TV screens, guide and player work unchanged. No recordings or timers.
 */
class IptvLiveTvRepository(
    override val source: LiveTvSource,
    private val config: IptvSource,
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val guideIndex: ProgrammeGuideIndex,
    private val isCurrent: () -> Boolean = { true },
    private val accessFailed: () -> Unit = {},
) : LiveTvRepository {
    private val lock = Mutex()
    private val cacheGuard = Any()
    private val refreshPolicy = IptvRefreshPolicy()
    private var playlist: Pair<Long, IptvPlaylist>? = null
    private var indexedPlaylist: IptvPlaylist? = null
    private var indexedScope: String? = null
    private var failedGuideAt = 0L
    private var failedGuideRevision = -1L
    private var playlistFromFallback = false
    private var guideFromFallback = false

    override val servingCachedData: Boolean
        get() = synchronized(cacheGuard) { playlistFromFallback || guideFromFallback }

    override suspend fun capabilities(force: Boolean): LiveTvCapabilities {
        return capabilities(playlist(force))
    }

    override suspend fun prefetchDashboard(force: Boolean): LiveTvDashboardPrefetch {
        val loaded = playlist(force)
        return LiveTvDashboardPrefetch(capabilities(loaded), channels(loaded))
    }

    private fun capabilities(playlist: IptvPlaylist) = LiveTvCapabilities(
        available = playlist.channels.isNotEmpty(), guide = config.epgUrl.isNotBlank() || playlist.epgUrls.isNotEmpty(),
    )

    override suspend fun channels(force: Boolean): List<LiveTvChannel> = channels(playlist(force))

    /** A disk fallback keeps channels browsable but must never authorize a new playback. */
    override suspend fun verifyPlayback(kind: String, id: String): Boolean {
        if (kind != "channel") return false
        val fresh = playlist(force = true, allowFallback = false)
        return id.isBlank() || fresh.channels.any { it.uuid == id }
    }

    private fun channels(playlist: IptvPlaylist): List<LiveTvChannel> = playlist.channels.map { channel ->
        LiveTvChannel(
            uuid = channel.uuid, name = channel.name, number = channel.number, iconUrl = channel.logo,
            tags = listOfNotNull(channel.group), sourceId = source.id,
            kind = if (channel.radio) LiveTvChannelKind.RADIO else LiveTvChannelKind.TV,
        )
    }

    override suspend fun epgEvents(startEpochSeconds: Long, windowHours: Int, force: Boolean): List<LiveTvProgramme> {
        val end = startEpochSeconds + windowHours.coerceIn(1, 72) * 3600L
        val scope = guide(force, startEpochSeconds, end) ?: return emptyList()
        check(isCurrent()) { "IPTV source changed" }
        return guideIndex.query(source.id, scope, startEpochSeconds, end).orEmpty().also { check(isCurrent()) { "IPTV source changed" } }
    }

    override suspend fun channelEpgEvents(channelId: String, startEpochSeconds: Long, windowHours: Int): List<LiveTvProgramme> {
        val end = startEpochSeconds + windowHours.coerceIn(1, 72) * 3600L
        val scope = guide(false, startEpochSeconds, end) ?: return emptyList()
        check(isCurrent()) { "IPTV source changed" }
        return guideIndex.query(source.id, scope, startEpochSeconds, end, channelId).orEmpty().also { check(isCurrent()) { "IPTV source changed" } }
    }

    override suspend fun liveStream(channel: LiveTvChannel, profileOverride: String?): LiveTvPlaybackSource {
        val entry = playlist(false).channels.firstOrNull { it.uuid == channel.uuid } ?: throw IOException("Channel is no longer in the playlist")
        return LiveTvPlaybackSource(url = entry.url, headers = headers(entry), title = entry.name, isLive = true)
    }

    override suspend fun recordingStream(entry: LiveTvRecording): LiveTvPlaybackSource =
        throw UnsupportedOperationException("IPTV has no recordings")

    override suspend fun invalidateAll() {
        // Do not wait for a failed connection's request lock before publishing recovery.
        synchronized(cacheGuard) {
            refreshPolicy.invalidate()
            playlist = null
            indexedPlaylist = null
            indexedScope = null
            failedGuideAt = 0L
            failedGuideRevision = -1L
        }
    }

    private fun headers(channel: IptvChannel?): Map<String, String> = buildMap {
        channel?.headers?.let(::putAll)
        config.userAgent.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
    }

    private suspend fun playlist(force: Boolean, allowFallback: Boolean = true): IptvPlaylist = withContext(Dispatchers.IO) { lock.withLock {
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "IPTV source changed" }
        val now = System.currentTimeMillis()
        val revision = synchronized(cacheGuard) {
            playlist?.takeIf { !force && now - it.first in 0 until PLAYLIST_TTL_MS }?.let { return@withLock it.second }
            refreshPolicy.revision
        }
        val cache = File(cacheDir, "${cacheKey(config.playlistUrl)}.m3u")
        var fromFallback = false
        fun parse(text: String): IptvPlaylist = M3uParser.parse(text).also { parsed ->
            // A provider's HTML/login error must not replace the playlist that scopes a good guide.
            if (parsed.channels.isEmpty() && !text.removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U", ignoreCase = true))
                throw IOException("Not an M3U playlist")
        }
        val parsed = com.ambient.tv.util.suspendResult {
            val text = download(config.playlistUrl, MAX_PLAYLIST_BYTES).toString(Charsets.UTF_8)
            val loaded = parse(text)
            currentCoroutineContext().ensureActive()
            check(isCurrent()) { "IPTV source changed" }
            runCatching { cacheDir.mkdirs(); cache.writeText(text) }
            loaded
        }
            // Offline or provider hiccup: the last good playlist keeps the channel list usable.
            .getOrElse { error ->
                if (isCurrent()) accessFailed()
                synchronized(cacheGuard) { if (revision == refreshPolicy.revision) playlistFromFallback = true }
                if (!allowFallback || !cache.isFile) throw error
                fromFallback = true
                parse(cache.readText())
            }
        parsed.also {
            currentCoroutineContext().ensureActive()
            check(isCurrent()) { "IPTV source changed" }
            synchronized(cacheGuard) {
                if (revision == refreshPolicy.revision) {
                    playlist = now to parsed
                    playlistFromFallback = fromFallback
                    if (config.epgUrl.isBlank() && parsed.epgUrls.isEmpty()) guideFromFallback = false
                }
            }
        }
    } }

    private suspend fun guide(force: Boolean, requestedFrom: Long, until: Long): String? = withContext(Dispatchers.IO) {
        check(isCurrent()) { "IPTV source changed" }
        val revision = synchronized(cacheGuard) { refreshPolicy.revision }
        val loadedPlaylist = playlist(false)
        val channels = loadedPlaylist.channels
        val urls = config.epgUrl.takeIf(String::isNotBlank)?.let(::listOf) ?: loadedPlaylist.epgUrls.take(3)
        if (urls.isEmpty()) return@withContext null
        val scope = synchronized(cacheGuard) {
            indexedScope?.takeIf { indexedPlaylist === loadedPlaylist } ?: programmeGuideScope(sequence {
                yield("xmltv-v1"); yield(config.id); yield(config.playlistUrl); yield(config.epgUrl); yield(config.userAgent)
                urls.forEach { yield(it) }
                channels.sortedBy { it.uuid }.forEach { channel ->
                    yield(channel.uuid); yield(channel.name); yield(channel.tvgId.orEmpty()); yield(channel.tvgName.orEmpty())
                }
            }.asIterable()).also { indexedPlaylist = loadedPlaylist; indexedScope = it }
        }
        lock.withLock {
            currentCoroutineContext().ensureActive()
            fun valid(): Boolean = isCurrent() && synchronized(cacheGuard) { revision == refreshPolicy.revision }
            check(valid()) { "IPTV source changed" }
            val now = System.currentTimeMillis()
            val existing = guideIndex.metadata(source.id, scope)
            val download = synchronized(cacheGuard) { refreshPolicy.downloadGuide(force) } || existing?.fromFallback == true
            if (existing != null && !download && existing.loadedAt <= now && now - existing.loadedAt < GUIDE_TTL_MS &&
                existing.fromEpochSeconds <= requestedFrom && existing.toEpochSeconds >= until) {
                synchronized(cacheGuard) { if (valid()) guideFromFallback = existing.fromFallback }
                return@withLock scope
            }
            // Repeated channel/now queries during an outage reuse the last guide for one minute.
            // Manual refresh and a new reconnect revision still force an immediate attempt.
            if (existing != null && !force && synchronized(cacheGuard) {
                    failedGuideRevision == revision && now - failedGuideAt in 0 until 60_000L
                }) return@withLock scope
            val from = minOf(requestedFrom, now / 1000 - 6 * 3600)
            val to = maxOf(until, now / 1000 + GUIDE_HOURS * 3600)
            val matcher = IptvGuideMatcher(channels)
            val byUuid = channels.associateBy { it.uuid }
            var fromFallback = false
            try {
                guideIndex.replace(source.id, scope, from, to, fromFallback = { fromFallback }, valid = ::valid) { emit ->
                    val operation = currentCoroutineContext()
                    urls.forEachIndexed { index, url ->
                        operation.ensureActive()
                        val cached = guideFile(url, index, download)
                        fromFallback = fromFallback || cached.fromFallback
                        // Only matched channels are retained; unknown guide channels do not grow a map.
                        val resolved = HashMap<String, String>()
                        fun resolve(id: String): String? = resolved[id] ?: matcher.match(null, id)?.uuid
                        var parsedEvents = 0
                        openGuide(cached.file) { operation.ensureActive() }.use { reader ->
                            XmltvParser.stream(reader, from, to, accept = { resolve(it) != null },
                                onChannel = { channel -> matcher.match(channel, channel.id)?.uuid?.let { uuid ->
                                    if (resolved.size >= 20_000 && channel.id !in resolved) throw IOException("Too many guide channel aliases")
                                    resolved[channel.id] = uuid
                                } }, checkActive = {
                                    operation.ensureActive()
                                    // The registry check reads encrypted source settings. Check
                                    // it periodically even for unmatched feeds, not on every IO read.
                                    if (++parsedEvents % 1024 == 0) check(valid()) { "IPTV source changed" }
                                }, onProgramme = { p ->
                                    resolve(p.channelId)?.let { uuid -> emit(LiveTvProgramme(
                                        eventId = "$uuid:${p.startEpochSeconds}", channelUuid = uuid, channelName = byUuid[uuid]?.name.orEmpty(),
                                        title = p.title, subtitle = p.subtitle, description = p.description,
                                        startEpochSeconds = p.startEpochSeconds, stopEpochSeconds = p.stopEpochSeconds, imageUrl = p.icon, sourceId = source.id,
                                    )) }
                                })
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                check(valid()) { "IPTV source changed" }
                if (existing == null) throw error
                synchronized(cacheGuard) {
                    guideFromFallback = true
                    failedGuideAt = now; failedGuideRevision = revision
                    refreshPolicy.guideLoaded(revision, fromFallback = true)
                }
                return@withLock scope
            }
            currentCoroutineContext().ensureActive()
            synchronized(cacheGuard) {
                if (valid()) {
                    guideFromFallback = fromFallback
                    failedGuideAt = if (fromFallback) now else 0L
                    failedGuideRevision = if (fromFallback) revision else -1L
                    refreshPolicy.guideLoaded(revision, fromFallback)
                }
            }
            scope
        }
    }

    /** Guides are large: downloaded to the cache once per TTL and parsed from there. */
    private data class GuideFile(val file: File, val fromFallback: Boolean)

    private suspend fun guideFile(url: String, index: Int, force: Boolean): GuideFile = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val file = File(cacheDir, "${cacheKey(url)}-epg$index.xml")
        if (!force && file.isFile && System.currentTimeMillis() - file.lastModified() in 0 until GUIDE_TTL_MS) return@withContext GuideFile(file, false)
        val temp = File.createTempFile("guide-", ".part", cacheDir)
        val operation = currentCoroutineContext()
        var fromFallback = false
        try {
            com.ambient.tv.util.suspendResult {
                readResponse(url) { response ->
                    try {
                        operation.ensureActive()
                        if (!response.isSuccessful) throw IOException("Guide HTTP ${response.code}")
                        response.body.byteStream().use { input -> temp.outputStream().use { out -> copyLimited(input, out, MAX_GUIDE_BYTES) } }
                    } finally {
                        // Cancellation may run the outer cleanup before OkHttp finishes unwinding.
                        if (!operation.isActive) temp.delete()
                    }
                }
                operation.ensureActive()
                if (!temp.renameTo(file)) { file.delete(); check(temp.renameTo(file)) }
            }.getOrElse {
                if (!file.isFile) throw it
                fromFallback = true
            }
            GuideFile(file, fromFallback)
        } finally { temp.delete() }
    }

    private fun openGuide(file: File, ensureActive: () -> Unit): java.io.Reader {
        val input = java.io.BufferedInputStream(file.inputStream())
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        val stream = try { if (gzip) java.util.zip.GZIPInputStream(input, 64 * 1024) else input }
            catch (error: Exception) { input.close(); throw error }
        val limited = object : java.io.FilterInputStream(stream) {
            private var bytes = 0L
            override fun read(): Int {
                ensureActive()
                val value = `in`.read()
                if (value >= 0 && ++bytes > MAX_GUIDE_BYTES) throw IOException("Expanded guide is too large")
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                ensureActive()
                val count = `in`.read(buffer, offset, length)
                if (count > 0) bytes += count
                if (bytes > MAX_GUIDE_BYTES) throw IOException("Expanded guide is too large")
                return count
            }
        }
        return java.io.InputStreamReader(limited, Charsets.UTF_8)
    }

    private suspend fun download(url: String, limit: Long): ByteArray = withContext(Dispatchers.IO) {
        readResponse(url) { response ->
            if (response.code == 401 || response.code == 403) throw SecurityException("HTTP ${response.code}")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val out = java.io.ByteArrayOutputStream()
            response.body.byteStream().use { copyLimited(it, out, limit) }
            val bytes = out.toByteArray()
            if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                java.util.zip.GZIPInputStream(bytes.inputStream()).use { gz -> java.io.ByteArrayOutputStream().also { copyLimited(gz, it, limit) }.toByteArray() }
            else bytes
        }
    }

    /** Cancelling an obsolete refresh releases the request lock without waiting for its timeout. */
    private suspend fun <T> readResponse(url: String, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request(url))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) { response.close(); return }
                continuation.resumeWith(runCatching { response.use(consume) })
            }
        })
    }

    private fun cacheKey(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${config.id}\u0000$url\u0000${config.userAgent}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun request(url: String): Request = Request.Builder().url(url)
        .header("User-Agent", config.userAgent.ifBlank { DEFAULT_USER_AGENT }).build()

    private fun copyLimited(input: java.io.InputStream, out: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) return
            total += n
            if (total > limit) throw IOException("Response too large")
            out.write(buffer, 0, n)
        }
    }

    private companion object {
        const val PLAYLIST_TTL_MS = 6L * 3600 * 1000
        const val GUIDE_TTL_MS = 12L * 3600 * 1000
        const val GUIDE_HOURS = 48L
        const val MAX_PLAYLIST_BYTES = 20L * 1024 * 1024
        const val MAX_GUIDE_BYTES = 400L * 1024 * 1024
        const val DEFAULT_USER_AGENT = "AmbientTV"
    }
}

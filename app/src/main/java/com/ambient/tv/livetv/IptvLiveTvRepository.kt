package com.ambient.tv.livetv

import com.ambient.tv.data.iptv.IptvChannel
import com.ambient.tv.data.iptv.IptvGuideMatcher
import com.ambient.tv.data.iptv.IptvPlaylist
import com.ambient.tv.data.iptv.IptvSource
import com.ambient.tv.data.iptv.M3uParser
import com.ambient.tv.data.iptv.XmltvChannel
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

/**
 * IPTV from an M3U playlist with an optional XMLTV guide, behind the same interface as the
 * media servers, so the Live TV screens, guide and player work unchanged. No recordings or timers.
 */
class IptvLiveTvRepository(
    override val source: LiveTvSource,
    private val config: IptvSource,
    private val client: OkHttpClient,
    private val cacheDir: File,
) : LiveTvRepository {
    private val lock = Mutex()
    private val cacheGuard = Any()
    private val refreshPolicy = IptvRefreshPolicy()
    private var playlist: Pair<Long, IptvPlaylist>? = null
    /** Loaded at, covered window end, programmes. */
    private var guide: Triple<Long, Long, List<LiveTvProgramme>>? = null
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

    private fun channels(playlist: IptvPlaylist): List<LiveTvChannel> = playlist.channels.map { channel ->
        LiveTvChannel(
            uuid = channel.uuid, name = channel.name, number = channel.number, iconUrl = channel.logo,
            tags = listOfNotNull(channel.group), sourceId = source.id,
            kind = if (channel.radio) LiveTvChannelKind.RADIO else LiveTvChannelKind.TV,
        )
    }

    override suspend fun epgEvents(startEpochSeconds: Long, windowHours: Int, force: Boolean): List<LiveTvProgramme> {
        if (guideUrls().isEmpty()) return emptyList()
        val end = startEpochSeconds + windowHours * 3600L
        return guide(force, end).filter { it.stopEpochSeconds > startEpochSeconds && it.startEpochSeconds < end }
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
            guide = null
        }
    }

    private fun headers(channel: IptvChannel?): Map<String, String> = buildMap {
        channel?.headers?.let(::putAll)
        config.userAgent.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
    }

    private suspend fun playlist(force: Boolean): IptvPlaylist = withContext(Dispatchers.IO) { lock.withLock {
        currentCoroutineContext().ensureActive()
        val now = System.currentTimeMillis()
        val revision = synchronized(cacheGuard) {
            playlist?.takeIf { !force && now - it.first < PLAYLIST_TTL_MS }?.let { return@withLock it.second }
            refreshPolicy.revision
        }
        val cache = File(cacheDir, "${cacheKey(config.playlistUrl)}.m3u")
        var fromFallback = false
        val text = com.ambient.tv.util.suspendResult { download(config.playlistUrl, MAX_PLAYLIST_BYTES).toString(Charsets.UTF_8) }
            .onSuccess { runCatching { cacheDir.mkdirs(); cache.writeText(it) } }
            // Offline or provider hiccup: the last good playlist keeps the channel list usable.
            .getOrElse { error ->
                if (!cache.isFile) throw error
                fromFallback = true
                cache.readText()
            }
        M3uParser.parse(text).also { parsed ->
            currentCoroutineContext().ensureActive()
            synchronized(cacheGuard) {
                if (revision == refreshPolicy.revision) {
                    playlist = now to parsed
                    playlistFromFallback = fromFallback
                    if (config.epgUrl.isBlank() && parsed.epgUrls.isEmpty()) guideFromFallback = false
                }
            }
        }
    } }

    private suspend fun guideUrls(): List<String> =
        config.epgUrl.takeIf(String::isNotBlank)?.let(::listOf) ?: com.ambient.tv.util.suspendResult { playlist(false).epgUrls }.getOrDefault(emptyList()).take(3)

    private suspend fun guide(force: Boolean, until: Long): List<LiveTvProgramme> {
        val revision = synchronized(cacheGuard) { refreshPolicy.revision }
        val channels = playlist(false).channels
        val urls = guideUrls()
        return lock.withLock {
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis()
            val download = synchronized(cacheGuard) {
                val required = refreshPolicy.downloadGuide(force)
                guide?.takeIf { !required && now - it.first < GUIDE_TTL_MS && it.second >= until }?.let { return@withLock it.third }
                required
            }
            val from = now / 1000 - 6 * 3600
            val to = maxOf(until, now / 1000 + GUIDE_HOURS * 3600)
            val matcher = IptvGuideMatcher(channels)
            val byUuid = channels.associateBy { it.uuid }
            var fromFallback = false
            val programmes = urls.flatMapIndexed { index, url ->
                val cached = guideFile(url, index, download)
                fromFallback = fromFallback || cached.fromFallback
                val known = HashMap<String, XmltvChannel>()
                val resolved = HashMap<String, String?>()
                fun resolve(id: String): String? = resolved.getOrPut(id) { matcher.match(known[id], id)?.uuid }
                val parsed = withContext(Dispatchers.IO) {
                    openGuide(cached.file).use { reader ->
                        XmltvParser.parse(reader, from, to, accept = { resolve(it) != null }, onChannel = { known[it.id] = it })
                    }
                }
                parsed.programmes.mapNotNull { p ->
                    val uuid = resolve(p.channelId) ?: return@mapNotNull null
                    LiveTvProgramme(
                        eventId = "$uuid:${p.startEpochSeconds}", channelUuid = uuid, channelName = byUuid[uuid]?.name.orEmpty(),
                        title = p.title, subtitle = p.subtitle, description = p.description,
                        startEpochSeconds = p.startEpochSeconds, stopEpochSeconds = p.stopEpochSeconds, imageUrl = p.icon, sourceId = source.id,
                    )
                }
            }.distinctBy { it.eventId }.sortedBy { it.startEpochSeconds }
            currentCoroutineContext().ensureActive()
            synchronized(cacheGuard) {
                if (revision == refreshPolicy.revision) {
                    guide = Triple(now, to, programmes)
                    guideFromFallback = fromFallback
                    refreshPolicy.guideLoaded(revision, fromFallback)
                }
            }
            programmes
        }
    }

    /** Guides are large: downloaded to the cache once per TTL and parsed from there. */
    private data class GuideFile(val file: File, val fromFallback: Boolean)

    private suspend fun guideFile(url: String, index: Int, force: Boolean): GuideFile = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val file = File(cacheDir, "${cacheKey(url)}-epg$index.xml")
        if (!force && file.isFile && System.currentTimeMillis() - file.lastModified() < GUIDE_TTL_MS) return@withContext GuideFile(file, false)
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

    private fun openGuide(file: File): java.io.Reader {
        val input = java.io.BufferedInputStream(file.inputStream())
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        val stream = if (gzip) java.util.zip.GZIPInputStream(input, 64 * 1024) else input
        return java.io.InputStreamReader(stream, Charsets.UTF_8)
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

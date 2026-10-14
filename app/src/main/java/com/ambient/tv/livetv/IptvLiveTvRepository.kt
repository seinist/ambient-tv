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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private var playlist: Pair<Long, IptvPlaylist>? = null
    /** Loaded at, covered window end, programmes. */
    private var guide: Triple<Long, Long, List<LiveTvProgramme>>? = null

    override suspend fun capabilities(force: Boolean): LiveTvCapabilities {
        val channels = playlist(force).channels
        return LiveTvCapabilities(available = channels.isNotEmpty(), guide = guideUrls().isNotEmpty())
    }

    override suspend fun channels(force: Boolean): List<LiveTvChannel> = playlist(force).channels.map { channel ->
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

    override suspend fun invalidateAll() = lock.withLock { playlist = null; guide = null }

    private fun headers(channel: IptvChannel?): Map<String, String> = buildMap {
        channel?.headers?.let(::putAll)
        config.userAgent.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
    }

    private suspend fun playlist(force: Boolean): IptvPlaylist = withContext(Dispatchers.IO) { lock.withLock {
        val now = System.currentTimeMillis()
        playlist?.takeIf { !force && now - it.first < PLAYLIST_TTL_MS }?.let { return@withLock it.second }
        val cache = File(cacheDir, "${cacheKey(config.playlistUrl)}.m3u")
        val text = com.ambient.tv.util.suspendResult { download(config.playlistUrl, MAX_PLAYLIST_BYTES).toString(Charsets.UTF_8) }
            .onSuccess { runCatching { cacheDir.mkdirs(); cache.writeText(it) } }
            // Offline or provider hiccup: the last good playlist keeps the channel list usable.
            .getOrElse { error -> if (cache.isFile) cache.readText() else throw error }
        M3uParser.parse(text).also { playlist = now to it }
    } }

    private suspend fun guideUrls(): List<String> =
        config.epgUrl.takeIf(String::isNotBlank)?.let(::listOf) ?: com.ambient.tv.util.suspendResult { playlist(false).epgUrls }.getOrDefault(emptyList()).take(3)

    private suspend fun guide(force: Boolean, until: Long): List<LiveTvProgramme> {
        val channels = playlist(false).channels
        val urls = guideUrls()
        return lock.withLock {
            val now = System.currentTimeMillis()
            guide?.takeIf { !force && now - it.first < GUIDE_TTL_MS && it.second >= until }?.let { return@withLock it.third }
            val from = now / 1000 - 6 * 3600
            val to = maxOf(until, now / 1000 + GUIDE_HOURS * 3600)
            val matcher = IptvGuideMatcher(channels)
            val byUuid = channels.associateBy { it.uuid }
            val programmes = urls.flatMapIndexed { index, url ->
                val file = guideFile(url, index, force)
                val known = HashMap<String, XmltvChannel>()
                val resolved = HashMap<String, String?>()
                fun resolve(id: String): String? = resolved.getOrPut(id) { matcher.match(known[id], id)?.uuid }
                val parsed = withContext(Dispatchers.IO) {
                    openGuide(file).use { reader ->
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
            guide = Triple(now, to, programmes)
            programmes
        }
    }

    /** Guides are large: downloaded to the cache once per TTL and parsed from there. */
    private suspend fun guideFile(url: String, index: Int, force: Boolean): File = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val file = File(cacheDir, "${cacheKey(url)}-epg$index.xml")
        if (!force && file.isFile && System.currentTimeMillis() - file.lastModified() < GUIDE_TTL_MS) return@withContext file
        val temp = File.createTempFile("guide-", ".part", cacheDir)
        runCatching {
            client.newCall(request(url)).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Guide HTTP ${response.code}")
                response.body.byteStream().use { input -> temp.outputStream().use { out -> copyLimited(input, out, MAX_GUIDE_BYTES) } }
            }
            if (!temp.renameTo(file)) { file.delete(); check(temp.renameTo(file)) }
        }.onFailure { temp.delete(); if (!file.isFile) throw it }
        file
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
        client.newCall(request(url)).execute().use { response ->
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

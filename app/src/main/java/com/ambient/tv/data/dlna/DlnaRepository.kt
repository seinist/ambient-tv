package com.ambient.tv.data.dlna

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.LibraryRecommendation
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.domain.repository.MediaRepository
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element

/** ContentDirectory adapter. It deliberately exposes only capabilities DLNA actually guarantees. */
@Singleton
class DlnaRepository @Inject constructor(
    private val store: DlnaStore,
    private val preferences: AppPreferences,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val wake: DlnaWake,
) : MediaRepository {
    private val client = dlnaHttpClient(preferences).newBuilder().addInterceptor(hosts.interceptor()).build()
    private val cache = LinkedHashMap<String, MediaItem>()

    suspend fun browse(
        serverId: String,
        objectId: String = ROOT_ID,
        start: Int = 0,
        count: Int = PAGE_SIZE,
    ): Result<DlnaBrowsePage> = withContext(Dispatchers.IO) {
        runCatching {
            require(start >= 0)
            require(count in 1..PAGE_SIZE)
            val server = store.server(serverId) ?: error("DLNA server was removed")
            wake.ensureAwake(server)
            val page = browseDlnaPageWithFallback(count) { requestedCount ->
                currentCoroutineContext().ensureActive()
                val result = soap(
                    server,
                    "Browse",
                    mapOf(
                        "ObjectID" to objectId,
                        "BrowseFlag" to "BrowseDirectChildren",
                        "Filter" to "*",
                        "StartingIndex" to start.toString(),
                        "RequestedCount" to requestedCount.toString(),
                        "SortCriteria" to "",
                    ),
                )
                currentCoroutineContext().ensureActive()
                parseDlnaBrowseResponse(result, server, serverId, memoizedDlnaUrlGate(preferences))
            }
            synchronized(cache) {
                page.nodes.forEach { node -> cache[node.key] = node.toMediaItem(store.progressFor(node.key)) }
            }
            page
        }.onFailure { if (it is CancellationException) throw it }
    }

    override fun cachedLibrarySections(): List<LibrarySection>? = null
    override fun cachedOnDeck(): List<MediaItem>? = null
    override fun cachedSectionItems(sectionKey: String): List<MediaItem>? = null
    override fun cachedMediaItem(ratingKey: String): MediaItem? = synchronized(cache) { cache[ratingKey] }
    override fun invalidateCaches() = synchronized(cache) { cache.clear() }
    override fun invalidateSectionCache(sectionKey: String) = Unit
    override suspend fun getIdentity(): Result<PlexServerIdentity> = Result.failure(UnsupportedOperationException("DLNA has no Plex identity"))
    override suspend fun getLibrarySections(): Result<List<LibrarySection>> = Result.success(emptyList())
    override suspend fun getOnDeck(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getMetadata(ratingKey: String): Result<MediaItem?> = withContext(Dispatchers.IO) {
        runCatching {
            val (serverId, objectId) = DlnaKeys.parse(ratingKey)
            val server = store.server(serverId) ?: error("DLNA server was removed")
            wake.ensureAwake(server)
            val xml = soap(
                server,
                "Browse",
                mapOf(
                    "ObjectID" to objectId,
                    "BrowseFlag" to "BrowseMetadata",
                    "Filter" to "*",
                    "StartingIndex" to "0",
                    "RequestedCount" to "1",
                    "SortCriteria" to "",
                ),
            )
            parseDlnaBrowseResponse(xml, server, serverId, memoizedDlnaUrlGate(preferences))
                .nodes
                .firstOrNull()
                ?.toMediaItem(store.progressFor(ratingKey))
                ?.also { synchronized(cache) { cache[ratingKey] = it } }
                .also { if (it == null) synchronized(cache) { cache.remove(ratingKey) } }
        }
    }

    override suspend fun getMetadataFast(ratingKey: String): Result<MediaItem?> =
        getMetadata(ratingKey)

    override suspend fun getSectionItems(sectionKey: String, onPage: suspend (List<MediaItem>) -> Unit): Result<List<MediaItem>> {
        val result = getChildren(sectionKey)
        result.getOrNull()?.let { onPage(it) }
        return result
    }

    override suspend fun getSectionPreview(sectionKey: String, sort: String, limit: Int): Result<List<MediaItem>> =
        getChildren(sectionKey).map { it.take(limit.coerceAtLeast(0)) }
    override suspend fun getRecommendation(sectionKey: String, recommendation: LibraryRecommendation, limit: Int) = Result.success(emptyList<MediaItem>())

    override suspend fun getChildren(ratingKey: String): Result<List<MediaItem>> = runCatching {
        val (serverId, objectId) = DlnaKeys.parse(ratingKey)
        browse(serverId, objectId).getOrThrow().nodes.map { node ->
            node.toMediaItem(store.progressFor(node.key)).also { item -> synchronized(cache) { cache[item.ratingKey] = item } }
        }
    }

    override suspend fun getSimilar(ratingKey: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getNextEpisode(ratingKey: String) = Result.success<MediaItem?>(null)
    override suspend fun getNextTrack(ratingKey: String) = Result.success<MediaItem?>(null)
    override suspend fun getNextUnwatchedEpisode(ratingKey: String) = Result.success<MediaItem?>(null)
    override suspend fun search(sectionKeys: List<String>, query: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getItemsByActor(actorId: String) = Result.success(emptyList<MediaItem>())
    override suspend fun getItemsByCollection(collectionId: String) = Result.success(emptyList<MediaItem>())

    override suspend fun reportProgress(ratingKey: String, positionMs: Long, state: String): Result<Unit> = runCatching {
        store.recordProgress(ratingKey, positionMs)
    }

    override suspend fun markWatched(ratingKey: String): Result<Unit> = runCatching { store.recordProgress(ratingKey, 0, completed = true) }
    override suspend fun markUnwatched(ratingKey: String): Result<Unit> = runCatching { store.recordProgress(ratingKey, 0, completed = false) }
    override suspend fun removeFromContinueWatching(ratingKey: String): Result<Unit> = runCatching { store.removeProgress(ratingKey) }
    override suspend fun getAudioCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, audioCodec: String, audioStreamId: Long?, audioChannels: Int) = Result.failure<String>(UnsupportedOperationException("DLNA has no common transcoder API"))
    override suspend fun getVideoCompatibleStreamUrl(ratingKey: String, mediaIndex: Int, startPositionMs: Long, audioChannels: Int) = Result.failure<String>(UnsupportedOperationException("DLNA has no common transcoder API"))

    private fun soap(server: DlnaServer, action: String, values: Map<String, String>): String {
        val body = dlnaSoapEnvelope(action, server.contentDirectoryType, values)
        val request = Request.Builder().url(server.contentDirectoryUrl)
            .header("SOAPACTION", "\"${server.contentDirectoryType}#$action\"")
            .header("USER-AGENT", "Android UPnP/1.1 ${com.ambient.tv.branding.AppBrand.name}/${BuildConfig.VERSION_NAME}")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val payload = response.limitedUtf8(MAX_SOAP_BYTES)
            if (!response.isSuccessful) {
                val fault = dlnaSoapFaultMessage(payload)
                throw DlnaHttpException(response.code, fault ?: "DLNA $action failed (${response.code})")
            }
            payload
        }
    }

    internal companion object {
        const val ROOT_ID = "0"
        // Plex can take ~27 s to build a 150-item DIDL page. Keep the first page below the
        // network timeout and let the browser append subsequent pages explicitly.
        const val PAGE_SIZE = 30
        const val MAX_SOAP_BYTES = 8 * 1024 * 1024
    }
}

internal fun dlnaSoapFaultMessage(soap: String): String? = runCatching {
    val root = parseXmlDocument(soap).documentElement
    val description = root.firstDescendantText("errordescription")?.takeIf { it.isNotBlank() }
        ?: root.firstDescendantText("faultstring")?.takeIf { it.isNotBlank() }
    val code = root.firstDescendantText("errorcode")?.takeIf { it.isNotBlank() }
    if (description == null && code == null) null
    else listOfNotNull(code?.let { "DLNA error $it" }, description).joinToString(": ")
}.getOrNull()

internal fun dlnaSoapEnvelope(action: String, serviceType: String, values: Map<String, String>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
    append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
    append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
    append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
    values.forEach { (name, value) ->
        append('<').append(name).append('>').append(value.xmlEscape()).append("</").append(name).append('>')
    }
    append("</u:").append(action).append("></s:Body></s:Envelope>")
}

internal fun parseDlnaBrowseResponse(
    soap: String,
    server: DlnaServer,
    serverId: String,
    allowUrl: (String) -> Boolean = { true },
): DlnaBrowsePage {
    val root = parseXmlDocument(soap).documentElement
    val didl = root.firstDescendantText("result").orEmpty()
    val number = root.firstDescendantText("numberreturned")?.toIntOrNull()
    val total = root.firstDescendantText("totalmatches")?.toIntOrNull()
    val fault = dlnaSoapFaultMessage(soap)
    check(fault == null) { checkNotNull(fault) }
    val nodes = parseDlnaDidl(didl, server, serverId, allowUrl)
    return DlnaBrowsePage(nodes, total ?: -1, number ?: nodes.size)
}

internal fun parseDlnaDidl(
    didl: String,
    server: DlnaServer,
    serverId: String,
    allowUrl: (String) -> Boolean = { true },
): List<DlnaNode> {
    if (didl.isBlank()) return emptyList()
    return parseXmlDocument(didl).documentElement.childElements()
        .filter { it.localTag() == "container" || it.localTag() == "item" }
        .mapNotNull { element ->
            val objectId = element.attribute("id").orEmpty()
            if (objectId.isBlank() || objectId.length > MAX_OBJECT_ID_LENGTH) return@mapNotNull null
            val artwork = element.directChildText("albumarturi").orEmpty().resolveAgainst(server.descriptionUrl)
                .takeIf { it.isNotBlank() && allowUrl(it) }
            val subtitles = mutableListOf<DlnaSubtitle>()
            // Samsung's sec:CaptionInfo(Ex) and PacketVideo's pv:subtitleFileUri name a subtitle file per item.
            element.childElements().filter { it.localTag() in setOf("captioninfoex", "captioninfo", "subtitlefileuri") }.forEach { caption ->
                val url = caption.textContent.orEmpty().resolveAgainst(server.descriptionUrl)
                if (url.isBlank() || !allowUrl(url)) return@forEach
                dlnaSubtitleCodec(caption.attribute("type"), null, url)?.let { subtitles += DlnaSubtitle(url, it) }
            }
            val resources = element.childElements("res").mapNotNull { resource ->
                val url = resource.textContent.orEmpty().resolveAgainst(server.descriptionUrl)
                if (url.isBlank() || !allowUrl(url)) return@mapNotNull null
                val subtitleMime = resource.attribute("protocolInfo")?.split(':', limit = 4)?.getOrNull(2)
                dlnaSubtitleCodec(null, subtitleMime, url)?.let { codec ->
                    // A subtitle is a track of the video, not another version of it.
                    subtitles += DlnaSubtitle(url, codec)
                    return@mapNotNull null
                }
                // Unsupported subtitle formats (SAMI, …) are no playable version either.
                if (subtitleMime?.lowercase()?.let { it.startsWith("smi/") || it.startsWith("text/") } == true) return@mapNotNull null
                val dimensions = resource.attribute("resolution").parseResolution()
                DlnaResource(
                    url = url,
                    protocolInfo = resource.attribute("protocolInfo"),
                    durationMs = resource.attribute("duration").parseDlnaDurationMs(),
                    size = resource.attribute("size")?.toLongOrNull()?.takeIf { it >= 0 },
                    width = dimensions?.first,
                    height = dimensions?.second,
                )
            }
            NodeBuilder(
                objectId = objectId,
                parentObjectId = element.attribute("parentID"),
                container = element.localTag() == "container",
                title = element.directChildText("title").orEmpty().take(MAX_TITLE_LENGTH),
                upnpClass = element.directChildText("class"),
                artworkUrl = artwork,
                summary = (element.directChildText("longdescription") ?: element.directChildText("description"))?.take(MAX_SUMMARY_LENGTH),
                date = element.directChildText("date"),
                resources = resources.toMutableList(),
                subtitles = subtitles.distinctBy { it.url }.take(12),
            ).build(serverId)
        }
}

private data class NodeBuilder(
    val objectId: String,
    val parentObjectId: String?,
    val container: Boolean,
    var title: String = "",
    var upnpClass: String? = null,
    var artworkUrl: String? = null,
    var summary: String? = null,
    var date: String? = null,
    val resources: MutableList<DlnaResource> = mutableListOf(),
    val subtitles: List<DlnaSubtitle> = emptyList(),
) {
    fun build(serverId: String) = DlnaNode(
        serverId,
        objectId,
        parentObjectId?.take(MAX_OBJECT_ID_LENGTH),
        title.ifBlank { objectId }.take(MAX_TITLE_LENGTH),
        upnpClass,
        container,
        artworkUrl,
        summary,
        date,
        resources,
        subtitles,
    )
}

/** Codec of a DLNA subtitle by caption type, MIME type or URL extension; null when it is no subtitle. */
internal fun dlnaSubtitleCodec(type: String?, mime: String?, url: String): String? {
    val byType = type?.lowercase()?.trim()
    val byMime = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
        "text/srt", "application/x-subrip", "text/x-srt", "application/x-srt" -> "srt"
        "text/vtt" -> "vtt"
        "text/x-ssa", "text/x-ass", "application/x-ass", "application/x-ssa" -> "ass"
        else -> null
    }
    val byUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
    return when {
        byType in setOf("srt", "vtt", "ass", "ssa") -> byType
        byMime != null -> byMime
        // Samsung's "smi/caption" is used for any subtitle file; caption elements may carry no type.
        (mime == null || mime.lowercase().startsWith("smi/caption")) && byUrl in setOf("srt", "vtt", "ass", "ssa") -> byUrl
        else -> null
    }
}

internal fun DlnaNode.toMediaItem(progress: DlnaProgress? = null): MediaItem {
    val subtitleTracks = subtitles.map { subtitle ->
        val name = subtitle.url.substringBefore('?').substringAfterLast('/')
        val parsed = com.ambient.tv.data.files.SidecarSubtitles.languageFrom(name.substringBeforeLast('.'),
            name.substringBeforeLast('.').split('.', '_', ' ', '-').map { it.lowercase() }.filter(String::isNotEmpty))
        MediaTrack(id = null, type = MediaTrackType.SUBTITLE, codec = subtitle.codec,
            language = parsed?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { null } }, languageCode = parsed,
            title = name.ifBlank { subtitle.codec.uppercase() }, channels = null, selected = false, forced = false, externalUrl = subtitle.url)
    }
    val versions = resources.filter { it.url.isNotBlank() }.mapIndexed { index, resource ->
        val mime = resource.protocolInfo?.split(':', limit = 4)?.getOrNull(2)?.substringBefore(';')?.lowercase()
        MediaVersion(
            streamUrl = resource.url,
            resolution = if (resource.width != null && resource.height != null) "${resource.width}x${resource.height}" else null,
            // protocolInfo describes the transport MIME/container, not the encoded tracks.
            videoCodec = null,
            audioCodec = null,
            container = mime?.substringAfter('/')?.removePrefix("x-")?.takeIf { it.isNotBlank() },
            bitrateKbps = null,
            width = resource.width,
            height = resource.height,
            tracks = subtitleTracks,
            sourceRatingKey = key,
            sourceMediaIndex = index,
        )
    }
    val mimeTypes = resources.mapNotNull { it.protocolInfo?.split(':', limit = 4)?.getOrNull(2)?.lowercase() }
    val mediaType = when {
        container -> "container"
        upnpClass?.contains("audioItem", true) == true || mimeTypes.any { it.startsWith("audio/") } -> "track"
        upnpClass?.contains("imageItem", true) == true || mimeTypes.any { it.startsWith("image/") } -> "photo"
        else -> "movie"
    }
    val artwork = artworkUrl ?: resources.firstOrNull()?.url?.takeIf { mediaType == "photo" }
    return MediaItem(
        ratingKey = key,
        title = title,
        type = mediaType,
        index = null,
        thumbUrl = artwork,
        artUrl = artwork,
        year = date?.take(4)?.toIntOrNull(),
        summary = summary,
        tagline = null,
        durationMs = resources.firstNotNullOfOrNull { it.durationMs },
        viewOffsetMs = progress?.takeUnless { it.completed }?.positionMs,
        parentTitle = null,
        parentRatingKey = parentObjectId?.let { DlnaKeys.key(serverId, it) },
        grandparentTitle = null,
        grandparentRatingKey = null,
        contentRating = null,
        rating = null,
        studio = null,
        viewCount = if (progress?.completed == true) 1 else 0,
        mediaVersions = versions,
    )
}

private fun Element.attribute(name: String): String? = (0 until attributes.length).firstNotNullOfOrNull { index ->
    attributes.item(index)?.nodeValue?.takeIf { attributes.item(index)?.localName?.equals(name, ignoreCase = true) == true || attributes.item(index)?.nodeName?.equals(name, ignoreCase = true) == true }
}

private fun String.xmlEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
private fun String.resolveAgainst(base: String): String {
    val value = trim()
    if (value.isBlank()) return ""
    return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
}

internal fun String?.parseDlnaDurationMs(): Long? {
    val value = this?.trim()?.substringBefore('.') ?: return null
    val fields = value.split(':').map { it.toLongOrNull() ?: return null }
    if (fields.size != 3 || fields.any { it < 0 } || fields[1] > 59 || fields[2] > 59) return null
    return runCatching {
        Math.multiplyExact(
            Math.addExact(Math.multiplyExact(Math.addExact(Math.multiplyExact(fields[0], 60), fields[1]), 60), fields[2]),
            1000,
        )
    }.getOrNull()
}

private fun String?.parseResolution(): Pair<Int, Int>? {
    val fields = this?.lowercase()?.split('x', limit = 2) ?: return null
    if (fields.size != 2) return null
    val width = fields[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val height = fields[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return width to height
}

private const val MAX_OBJECT_ID_LENGTH = 4096
private const val MAX_TITLE_LENGTH = 500
private const val MAX_SUMMARY_LENGTH = 20_000

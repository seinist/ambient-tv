package com.ambient.tv.companion

import android.net.Uri
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FilePaths
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.navigation.Destinations
import javax.inject.Inject
import javax.inject.Singleton

/** Resolve device-local source IDs on each side. Never switch a TV's active server implicitly. */
@Singleton
class CompanionMediaMapper @Inject constructor(
    private val preferences: AppPreferences,
    private val dlna: com.ambient.tv.data.dlna.DlnaStore,
    private val shared: com.ambient.tv.data.shared.SharedMediaRepository,
) {
    private fun scope(vararg parts: Any?) = fingerprint(parts.joinToString("\u0000").toByteArray())
    private fun server(system: String, id: String?, host: String, port: Int, path: String = "") =
        if (!id.isNullOrBlank()) scope(system, id) else scope(system, host.lowercase(), port, path.trim('/'))
    private fun librarySource(id: String): String = when {
        id.startsWith("j:") -> requireNotNull(preferences.activeJellyfinServerProfile()).let { server("jellyfin", it.serverId, it.host, it.port, it.basePath) }
        id.startsWith("e:") -> requireNotNull(preferences.activeEmbyServerProfile()).let { server("emby", it.serverId, it.host, it.port, it.basePath) }
        else -> requireNotNull(preferences.activeServerProfile()).let { server("plex", it.machineIdentifier, it.host, it.port) }
    }
    private fun fileSource(source: FileSource): String {
        require(source.network)
        return scope(source.kind, source.host.lowercase(), source.port, source.share, source.rootPath)
    }
    fun liveSources(): Map<String, String> = buildMap {
        preferences.serverProfiles.forEach { put("plex:${it.id}", server("plex", it.machineIdentifier, it.host, it.port)) }
        preferences.jellyfinServerProfiles.forEach { put("jellyfin:${it.id}:${it.userId}", server("jellyfin", it.serverId, it.host, it.port, it.basePath)) }
        preferences.embyServerProfiles.forEach { put("emby:${it.id}:${it.userId}", server("emby", it.serverId, it.host, it.port, it.basePath)) }
        if (preferences.isTvheadendConfigured) put("tvheadend:${preferences.tvheadendHistoryScope}", server("tvheadend", null, preferences.tvheadendHost, preferences.tvheadendPort))
    }

    internal fun browseSources() = PlaybackBrowseSources(
        library = buildMap {
            runCatching { librarySource("1") }.getOrNull()?.let { put("", it) }
            runCatching { librarySource("j:1") }.getOrNull()?.let { put("j:", it) }
            runCatching { librarySource("e:1") }.getOrNull()?.let { put("e:", it) }
        },
        files = preferences.fileSources.filter { it.network }.associate { it.id to fileSource(it) },
        dlna = dlna.servers().associate { it.id to scope(it.udn) },
        live = liveSources(),
    )

    /** A library item from another saved server: the TV asks before switching (never implicitly). */
    data class ServerSwitch(val system: String, val profileId: String, val name: String)

    fun serverSwitchFor(media: CompanionMedia): ServerSwitch? {
        validateBounds(media)
        if (media.kind != "library" || media.source.isEmpty()) return null
        if (runCatching { librarySource(media.id) }.getOrNull() == media.source) return null
        return when {
            media.id.startsWith("j:") -> preferences.jellyfinServerProfiles
                .firstOrNull { server("jellyfin", it.serverId, it.host, it.port, it.basePath) == media.source }
                ?.let { ServerSwitch("jellyfin", it.id, it.name) }
            media.id.startsWith("e:") -> preferences.embyServerProfiles
                .firstOrNull { server("emby", it.serverId, it.host, it.port, it.basePath) == media.source }
                ?.let { ServerSwitch("emby", it.id, it.name) }
            else -> preferences.serverProfiles
                .firstOrNull { server("plex", it.machineIdentifier, it.host, it.port) == media.source }
                ?.let { ServerSwitch("plex", it.id, it.name) }
        }
    }

    /** Makes [switch] the active server of its system; caches are invalidated by the caller. */
    fun activate(switch: ServerSwitch): Boolean = when (switch.system) {
        "jellyfin" -> preferences.activateJellyfinServer(switch.profileId)
        "emby" -> preferences.activateEmbyServer(switch.profileId)
        else -> preferences.activateServer(switch.profileId)
    }

    internal fun nextUpMedia(next: com.ambient.tv.ui.player.NextUpCandidate, current: CompanionMedia?): CompanionMedia? = runCatching {
        if (current?.kind == "library" && next.source in setOf("plex", "jellyfin", "emby"))
            current.copy(id = next.mediaId, restart = false, mediaIndex = null)
        else fromRoute(when (next.source) {
            "youtube" -> Destinations.youtubePlayer(next.mediaId)
            // Monogram's next post is a "telegram:<chat>:<message>" key, not a library id.
            "telegram" -> requireNotNull(telegramPlayerRouteFor(next.mediaId))
            else -> Destinations.player(next.mediaId)
        })
            .also { require(current == null || it.kind == "youtube" || it.source == current.source) }
    }.getOrNull()

    fun fromRoute(route: String): CompanionMedia {
        val uri = Uri.parse("ambient://play/$route")
        val path = uri.pathSegments
        val restart = uri.getQueryParameter("restart") == "true"
        return when (path.firstOrNull()) {
            "spotify_player" -> {
                val parts = requireNotNull(uri.getQueryParameter("uri")).split(':'); require(parts.size == 3 && parts[0] == "spotify")
                CompanionMedia("spotify:${parts[1]}", parts[2])
            }
            "youtube_player" -> CompanionMedia("youtube", path[1], trailer = uri.getQueryParameter("trailer") == "true")
            // The browse queue token is local to one device; the post itself is portable.
            "telegram_player" -> CompanionMedia("telegram",
                telegramCompanionId(path[1].toLong(), path[2].toInt()))
            "player" -> {
                val key = path[1]
                when {
                    com.ambient.tv.data.shared.SharedMediaRepository.owns(key) ->
                        CompanionMedia("url", requireNotNull(shared.link(key)).url)
                    FileKeys.owns(key) -> {
                        val (sourceId, file) = FileKeys.parse(key)
                        CompanionMedia("file", FilePaths.relative(file), fileSource(requireNotNull(preferences.fileSources.find { it.id == sourceId })), restart)
                    }
                    key.startsWith("dlna:") -> {
                        val (serverId, objectId) = com.ambient.tv.data.dlna.DlnaKeys.parse(key)
                        CompanionMedia("dlna", objectId, scope(requireNotNull(dlna.server(serverId)).udn), restart)
                    }
                    else -> CompanionMedia("library", key, librarySource(key), restart, mediaIndex = uri.getQueryParameter("mediaIndex")?.toIntOrNull(), collectionId = uri.getQueryParameter("collectionId").orEmpty(), collectionSort = uri.getQueryParameter("collectionSort").orEmpty())
                }
            }
            "tvh_player" -> {
                val sourceId = uri.getQueryParameter("sourceId")?.takeIf { it.isNotBlank() }
                    ?: liveSources().keys.first { it.startsWith("tvheadend:") }
                CompanionMedia("live:${path[1]}", path[2], liveSources().getValue(sourceId))
            }
            else -> error("This source does not support companion playback")
        }
    }

    private fun validateBounds(media: CompanionMedia) {
        require(media.id.length in 1..4096 && media.source.length <= 64)
        require(media.mediaIndex == null || media.mediaIndex in 0..10_000)
        require(media.collectionId.isEmpty() || media.collectionId.matches(Regex("[A-Za-z0-9_:-]{1,128}")))
        require(media.collectionSort.length <= 128 && media.collectionSort.none { it.isISOControl() })
        if (media.kind == "library") require(media.id.matches(Regex("(?:[je]:)?[A-Za-z0-9_-]{1,128}")))
    }

    fun toRoute(media: CompanionMedia): String {
        validateBounds(media)
        return when (media.kind) {
            "url" -> {
                require(media.source.isEmpty())
                Destinations.player(shared.register(media.id), restart = true)
            }
            "youtube" -> {
                require(media.id.matches(Regex("[A-Za-z0-9_-]{11}")))
                if (media.trailer) Destinations.trailerPlayer(media.id) else Destinations.youtubePlayer(media.id)
            }
            "telegram" -> {
                require(media.source.isEmpty())
                val (chatId, messageId) = requireNotNull(parseTelegramCompanionId(media.id))
                Destinations.telegramPlayer(chatId, messageId)
            }
            "library" -> {
                require(media.id.matches(Regex("(?:[je]:)?[A-Za-z0-9_-]{1,128}")))
                require(media.source.isNotEmpty() && media.source == librarySource(media.id))
                Destinations.player(media.id, mediaIndex = media.mediaIndex ?: 0, restart = media.restart, collectionId = media.collectionId.takeIf { it.isNotEmpty() }, collectionSort = media.collectionSort.takeIf { it.isNotEmpty() })
            }
            "file" -> {
                val source = requireNotNull(preferences.fileSources.firstOrNull { it.network && fileSource(it) == media.source })
                Destinations.player(FileKeys.key(source.id, FilePaths.relative(media.id)), restart = media.restart)
            }
            "dlna" -> {
                val server = requireNotNull(dlna.servers().firstOrNull { scope(it.udn) == media.source })
                Destinations.player(com.ambient.tv.data.dlna.DlnaKeys.key(server.id, media.id), restart = media.restart)
            }
            "live:channel", "live:recording" -> {
                require(media.id.length <= 256 && media.id.none { it.isISOControl() })
                val source = requireNotNull(liveSources().entries.firstOrNull { it.value == media.source })
                Destinations.tvhPlayer(media.kind.substringAfter(':'), media.id, source.key)
            }
            else -> error("Unsupported media")
        }
    }
}

/** Companion id of a Monogram post: "chatId/messageId". */
internal fun telegramCompanionId(chatId: Long, messageId: Int): String {
    require(chatId != 0L && messageId > 0)
    return "$chatId/$messageId"
}

internal fun parseTelegramCompanionId(id: String): Pair<Long, Int>? {
    val match = Regex("(-?[1-9][0-9]{0,18})/([1-9][0-9]{0,9})").matchEntire(id) ?: return null
    val chatId = match.groupValues[1].toLongOrNull() ?: return null
    val messageId = match.groupValues[2].toIntOrNull() ?: return null
    return chatId to messageId
}

/** Player route of a Monogram media key ("telegram:<chat>:<message>"); null for other keys. */
internal fun telegramPlayerRouteFor(mediaKey: String): String? {
    if (!mediaKey.startsWith(com.ambient.tv.data.telegram.TELEGRAM_MEDIA_PREFIX)) return null
    val (chatId, messageId) = parseTelegramCompanionId(
        mediaKey.removePrefix(com.ambient.tv.data.telegram.TELEGRAM_MEDIA_PREFIX).replace(':', '/')) ?: return null
    return Destinations.telegramPlayer(chatId, messageId)
}

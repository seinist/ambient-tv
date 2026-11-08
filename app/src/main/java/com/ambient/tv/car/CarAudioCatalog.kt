package com.ambient.tv.car

import android.content.Context
import android.os.SystemClock
import com.ambient.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.data.radio.radioLanguageDisplayName
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvPlaybackSource
import java.util.Locale
import com.ambient.tv.data.repository.RoutingMediaRepository
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.data.telegram.MonogramLoginStep
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramRepository
import com.ambient.tv.spotify.SpotifyBrowseItem
import com.ambient.tv.spotify.SpotifyEntityType
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.spotify.SpotifyTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

internal data class CarAudioEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val playable: Boolean = false,
    val radioStation: RadioStation? = null,
    val libraryItem: MediaItem? = null,
    val telegramMedia: TelegramMedia? = null,
)

internal sealed interface CarAudioSelection {
    data class Radio(val station: RadioStation, val queue: List<RadioStation>) : CarAudioSelection
    data class Spotify(val uri: String) : CarAudioSelection
    data class Library(val item: MediaItem, val queue: List<String>) : CarAudioSelection
    data class Live(val channel: LiveTvChannel, val stream: LiveTvPlaybackSource,
        val queue: List<String>, val system: String,
        val artworkHeaders: Map<String, String> = emptyMap()) : CarAudioSelection
    /** [queue] holds car item IDs of the browsed Telegram audio posts. */
    data class Telegram(val media: TelegramMedia, val url: String, val queue: List<String>) : CarAudioSelection
}

/** Read-only, driver-safe browse facade. Existing repositories and playback engines own the data. */
@Singleton
internal class CarAudioCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val radio: RadioBrowserRepository,
    private val spotify: SpotifyRepository,
    private val media: RoutingMediaRepository,
    private val auxiliary: CarAuxiliaryAudio,
    /** Lazy: a no-Monogram build or a disabled system never touches the native client. */
    private val telegram: javax.inject.Provider<TelegramRepository>,
) {
    private data class Page(val entries: List<CarAudioEntry>, val loadedAt: Long)
    private val pages = LinkedHashMap<String, Page>()
    private val entriesById = LinkedHashMap<String, CarAudioEntry>()
    private val parents = LinkedHashMap<String, String>()
    private val maxItems = 500
    private val maxPages = 90

    fun invalidate(parentId: String) {
        pages.remove(parentId)
    }

    suspend fun children(parentId: String): List<CarAudioEntry> {
        if (!CarAudioIds.isFolder(parentId)) return emptyList()
        if (parentId == CarAudioIds.ROOT) return root()
        val now = SystemClock.elapsedRealtime()
        pages[parentId]?.takeIf { now - it.loadedAt < 45_000 }?.let { return it.entries }
        val fresh = try {
            withTimeoutOrNull(15_000) { loadCarPage(parentId) } ?: emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Connectivity must not make the Auto UI jump to a temporary empty page.
            return pages[parentId]?.entries.orEmpty()
        }
        if (fresh.isEmpty() && pages[parentId]?.entries?.isNotEmpty() == true) {
            return pages.getValue(parentId).entries
        }
        val result = fresh.distinctBy { it.id }.take(PAGE_SIZE + 1)
        pages[parentId] = Page(result, now)
        while (pages.size > maxPages) pages.remove(pages.keys.first())
        result.forEach { entry ->
            entriesById[entry.id] = entry
            parents[entry.id] = parentId
        }
        while (entriesById.size > maxItems) {
            val oldest = entriesById.keys.first()
            entriesById.remove(oldest)
            parents.remove(oldest)
        }
        return result
    }

    private fun hierarchy() = carAudioHierarchy(
        radioEnabled = preferences.radioEnabled,
        iptvAvailable = auxiliary.hasLive("iptv"),
        tvheadendAvailable = auxiliary.hasLive("tvheadend"),
        spotifyAvailable = spotifyEnabled(),
        storageAvailable = auxiliary.hasStorage(),
    )

    private fun root(): List<CarAudioEntry> = hierarchy().roots.map(::navigationFolder)

    private fun navigationFolder(id: String): CarAudioEntry = folder(id, context.getString(when (id) {
        CarAudioIds.RADIO_ROOT -> R.string.car_audio_radio
        CarAudioIds.LIVE_ROOT -> R.string.tvh_title
        CarAudioIds.SPOTIFY_ROOT -> R.string.car_audio_spotify
        CarAudioIds.LIBRARY_ROOT -> R.string.library_default_title
        CarAudioIds.MUSIC_LIBRARIES -> R.string.car_audio_music_libraries
        CarAudioIds.STORAGE_ROOT -> R.string.car_audio_storage
        CarAudioIds.RADIO_FAVORITES -> R.string.car_audio_favorites
        CarAudioIds.RADIO_RECENT -> R.string.car_audio_recent
        CarAudioIds.RADIO_POPULAR -> R.string.car_audio_popular
        CarAudioIds.RADIO_ALL -> R.string.car_audio_all
        CarAudioIds.RADIO_GENRES -> R.string.car_audio_genres
        CarAudioIds.RADIO_COUNTRIES -> R.string.car_audio_countries
        CarAudioIds.RADIO_LANGUAGES -> R.string.car_audio_languages
        CarAudioIds.LIVE_RADIO_IPTV -> R.string.car_audio_iptv_radio
        CarAudioIds.LIVE_RADIO_TVH -> R.string.car_audio_tvh_radio
        else -> error("Unknown car navigation folder")
    }))

    private fun folder(id: String, title: String, subtitle: String? = null) =
        CarAudioEntry(id, title, subtitle)

    /** Signed-in Monogram account; Telegram restores its session itself when enabled. */
    private fun telegramReady(): Boolean = preferences.telegramEnabled &&
        telegram.get().let { it.nativeAvailable && it.login.value.step == MonogramLoginStep.AUTHORIZED }

    /** Only audio files Telegram lets us stream; protected posts and links stay hidden. */
    private fun TelegramMedia.asCarEntry(): CarAudioEntry? =
        if (kind != TelegramMediaKind.AUDIO || !playableOnTelegram || fileSize <= 0L) null
        else CarAudioEntry(
            id = CarAudioIds.telegramItem(chatId, messageId),
            title = title.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: channel,
            subtitle = channel,
            artworkUrl = thumbnailUrl,
            playable = true,
            telegramMedia = this,
        )

    private suspend fun telegramAudio(section: String): List<CarAudioEntry> =
        telegram.get().browsePage(section, limit = 100).items.mapNotNull { it.asCarEntry() }

    private fun RadioStation.asEntry() = CarAudioEntry(
        id = CarAudioIds.station(id),
        title = name,
        subtitle = country,
        artworkUrl = artworkUrl,
        playable = true,
        radioStation = this,
    )

    private fun MediaItem.asEntry(): CarAudioEntry? {
        val playable = carAudioPlayableType(type)
        if (!playable && !carAudioBrowseType(type)) return null
        return CarAudioEntry(
            id = CarAudioIds.musicItem(ratingKey),
            title = title,
            subtitle = parentTitle ?: grandparentTitle,
            playable = playable,
            libraryItem = this,
        )
    }

    private fun SpotifyBrowseItem.asEntry(): CarAudioEntry? = when (type) {
        SpotifyEntityType.TRACK -> if (uri.startsWith("spotify:track:")) CarAudioEntry(
            CarAudioIds.spotifyTrack(id), title, subtitle, artworkUrl, playable = true,
        ) else null
        SpotifyEntityType.ALBUM -> folder(CarAudioIds.spotifyCollection("album", id), title, subtitle)
        SpotifyEntityType.PLAYLIST -> folder(CarAudioIds.spotifyCollection("playlist", id), title, subtitle)
        SpotifyEntityType.ARTIST -> folder(CarAudioIds.spotifyCollection("artist", id), title, subtitle)
    }

    private fun SpotifyTrack.asEntry() = CarAudioEntry(
        CarAudioIds.spotifyTrack(id), name, artists.joinToString(", ") { it.name },
        album?.images?.firstOrNull()?.url, playable = uri.startsWith("spotify:track:"),
    )

    /** Bounded pages and explicit "More" folders work with the stock Android Auto UI. */
    private suspend fun loadCarPage(requestId: String): List<CarAudioEntry> {
        val (parent, offset) = CarAudioIds.moreSpec(requestId) ?: (requestId to 0)
        val special = auxiliary.children(parent, offset, PAGE_SIZE)
            ?: radioPage(parent, offset)
        if (special != null) {
            val nextOffset = special.nextOffset ?: (offset + PAGE_SIZE)
            return special.items + if (special.hasMore && nextOffset > offset && nextOffset <= MAX_CURSOR)
                listOf(folder(CarAudioIds.more(parent, nextOffset), context.getString(R.string.car_audio_more)))
            else emptyList()
        }
        val base = loadPage(parent)
        val end = (offset + PAGE_SIZE).coerceAtMost(base.size)
        val selected = base.drop(offset).take(PAGE_SIZE)
        return if (end < base.size && end <= MAX_CURSOR) selected +
            folder(CarAudioIds.more(parent, end), context.getString(R.string.car_audio_more))
        else selected
    }

    private suspend fun radioPage(parent: String, offset: Int): CarAudioPage? {
        fun slice(items: List<CarAudioEntry>) =
            CarAudioPage(items.drop(offset).take(PAGE_SIZE), items.size > offset + PAGE_SIZE)
        if (!preferences.radioEnabled) return when {
            parent in setOf(CarAudioIds.RADIO_ALL, CarAudioIds.RADIO_GENRES,
                CarAudioIds.RADIO_COUNTRIES, CarAudioIds.RADIO_LANGUAGES) ||
                CarAudioIds.radioFacetId(parent) != null -> CarAudioPage(emptyList())
            else -> null
        }
        return when (parent) {
            CarAudioIds.RADIO_ALL -> radio.search(offset = offset, limit = PAGE_SIZE,
                order = "name", reverse = false).let { CarAudioPage(it.items.map { station -> station.asEntry() }, it.hasMore) }
            CarAudioIds.RADIO_GENRES -> slice(radio.tags(limit = 150)
                .sortedBy { it.name.lowercase() }
                .map { folder(CarAudioIds.radioFacet("tag", it.id), it.name) })
            CarAudioIds.RADIO_COUNTRIES -> slice(radio.countries(limit = 200)
                .sortedBy { it.name }
                .map { facet ->
                    val label = runCatching {
                        Locale.Builder().setRegion(facet.id).build()
                            .getDisplayCountry(context.resources.configuration.locales[0])
                    }
                        .getOrDefault(facet.name).takeIf { it.isNotBlank() } ?: facet.name
                    folder(CarAudioIds.radioFacet("country", facet.id), label)
                })
            CarAudioIds.RADIO_LANGUAGES -> slice(radio.languages(limit = 200)
                .map { facet ->
                    val label = radioLanguageDisplayName(facet.languageCode, facet.name,
                        context.resources.configuration.locales[0]) ?: facet.name
                    folder(CarAudioIds.radioFacet("language", facet.id), label)
                }.sortedBy { it.title.lowercase() })
            else -> CarAudioIds.radioFacetId(parent)?.let { (kind, value) ->
                val page = radio.search(
                    tag = value.takeIf { kind == "tag" },
                    countryCode = value.takeIf { kind == "country" },
                    language = value.takeIf { kind == "language" },
                    offset = offset, limit = PAGE_SIZE, order = "name", reverse = false,
                )
                CarAudioPage(page.items.map { it.asEntry() }, page.hasMore)
            }
        }
    }

    private suspend fun loadPage(parentId: String): List<CarAudioEntry> = when (parentId) {
        CarAudioIds.RADIO_ROOT -> hierarchy().radioFolders.map(::navigationFolder)
        CarAudioIds.LIVE_ROOT -> hierarchy().liveFolders.map(::navigationFolder)
        // This container is intentionally local: unavailable media servers cannot prevent
        // opening DLNA/files when both library sources share the fourth navigation tab.
        CarAudioIds.LIBRARY_ROOT -> hierarchy().libraryFolders.map(::navigationFolder)
        CarAudioIds.SPOTIFY_ROOT -> if (spotifyEnabled()) listOf(
            folder(CarAudioIds.SPOTIFY_HOME, context.getString(R.string.car_audio_home)),
            folder(CarAudioIds.SPOTIFY_LIKED, context.getString(R.string.car_audio_liked)),
            folder(CarAudioIds.SPOTIFY_PLAYLISTS, context.getString(R.string.car_audio_playlists)),
            folder(CarAudioIds.SPOTIFY_ALBUMS, context.getString(R.string.car_audio_albums)),
            folder(CarAudioIds.SPOTIFY_ARTISTS, context.getString(R.string.car_audio_artists)),
        ) else emptyList()
        CarAudioIds.RADIO_FAVORITES ->
            if (preferences.radioEnabled) preferences.radioFavorites.map { it.asEntry() } else emptyList()
        CarAudioIds.RADIO_RECENT ->
            if (preferences.radioEnabled) preferences.recentRadioStations.map { it.asEntry() } else emptyList()
        CarAudioIds.RADIO_POPULAR ->
            if (preferences.radioEnabled) radio.popular(limit = 60).map { it.asEntry() } else emptyList()
        CarAudioIds.SPOTIFY_HOME ->
            if (spotifyEnabled()) spotify.home().mapNotNull { it.asEntry() } else emptyList()
        CarAudioIds.SPOTIFY_LIKED, CarAudioIds.SPOTIFY_PLAYLISTS,
        CarAudioIds.SPOTIFY_ALBUMS, CarAudioIds.SPOTIFY_ARTISTS -> {
            if (!spotifyEnabled()) emptyList() else {
                val section = when (parentId) {
                    CarAudioIds.SPOTIFY_LIKED -> "liked"
                    CarAudioIds.SPOTIFY_PLAYLISTS -> "playlists"
                    CarAudioIds.SPOTIFY_ALBUMS -> "albums"
                    else -> "artists"
                }
                spotify.librarySection(section).mapNotNull { it.asEntry() }
            }
        }
        CarAudioIds.MUSIC_LIBRARIES -> {
            // Root holds at most four tabs; Telegram audio lives with the other music sources.
            val telegramFolder = if (telegramReady())
                listOf(folder(CarAudioIds.TELEGRAM_ROOT, context.getString(R.string.car_audio_telegram)))
            else emptyList()
            // Include all configured backend instances; provider-owned IDs stay opaque.
            telegramFolder + listOf(media.plex, media.jellyfin, media.emby)
                .flatMap { provider -> provider.getLibrarySections().getOrDefault(emptyList()) }
                .filter { carAudioBrowseType(it.type) }
                .distinctBy { it.key }
                .map { folder(CarAudioIds.musicSection(it.key), it.title) }
        }
        CarAudioIds.TELEGRAM_ROOT -> if (telegramReady()) listOf(
            folder(CarAudioIds.TELEGRAM_AUDIO, context.getString(R.string.car_audio_telegram_audio)),
            folder(CarAudioIds.TELEGRAM_CHANNELS, context.getString(R.string.car_audio_telegram_channels)),
        ) else emptyList()
        CarAudioIds.TELEGRAM_AUDIO -> if (telegramReady()) telegramAudio("audio") else emptyList()
        CarAudioIds.TELEGRAM_CHANNELS -> if (telegramReady())
            telegram.get().channelsPage(limit = 100).items.map {
                folder(CarAudioIds.telegramChannel(it.chatId), it.title)
            } else emptyList()
        else -> {
            CarAudioIds.telegramChannelId(parentId)?.let { chatId ->
                return if (telegramReady()) telegramAudio("channel:$chatId") else emptyList()
            }
            val spotifyCollection = CarAudioIds.spotifyCollectionId(parentId)
            val musicSection = CarAudioIds.musicSectionId(parentId)
            val musicItem = CarAudioIds.musicItemId(parentId)
            when {
                spotifyCollection != null && spotifyEnabled() -> {
                    val (kind, id) = spotifyCollection
                    when (kind) {
                        "album" -> spotify.album(id).tracks.filter { it.uri.startsWith("spotify:track:") }.map { it.asEntry() }
                        "playlist" -> spotify.playlist(id).tracks.filter { it.uri.startsWith("spotify:track:") }.map { it.asEntry() }
                        "artist" -> spotify.artist(id).topTracks.filter { it.uri.startsWith("spotify:track:") }.map { it.asEntry() }
                        else -> emptyList()
                    }
                }
                musicSection != null -> {
                    val preview = media.getSectionPreview(musicSection, "titleSort", 80).getOrDefault(emptyList())
                    val content = preview.ifEmpty { media.getSectionItems(musicSection).getOrDefault(emptyList()).take(80) }
                    content.mapNotNull { it.asEntry() }
                }
                musicItem != null -> {
                    val selected = entriesById[parentId]?.libraryItem
                        ?: media.getMetadata(musicItem).getOrNull()
                    if (selected != null && carAudioBrowseType(selected.type)) {
                        media.getChildren(selected.ratingKey).getOrDefault(emptyList())
                            .mapNotNull { it.asEntry() }
                    } else emptyList()
                }
                else -> emptyList()
            }
        }
    }

    private fun spotifyEnabled() = preferences.spotifyEnabled && spotify.webAuthorized

    /** A selection is resolved through the authenticated repository, never a URI provided by a car client. */
    suspend fun resolve(id: String): CarAudioSelection? {
        CarAudioIds.liveAudioSpec(id)?.let { (sourceId, _) ->
            val parent = parents[id]
            val folder = parent?.let { CarAudioIds.moreSpec(it)?.first ?: it }
            val group = folder?.let(CarAudioIds::liveGroupSpec)
                ?.takeIf { it.first == sourceId }?.second
            return auxiliary.resolveLive(id, group)
        }
        CarAudioIds.stationId(id)?.let { stationId ->
            if (!preferences.radioEnabled) return null
            val station = entriesById[id]?.radioStation
                ?: preferences.radioFavorites.firstOrNull { it.id == stationId }
                ?: preferences.recentRadioStations.firstOrNull { it.id == stationId }
                ?: radio.station(stationId) ?: return null
            val siblings = parents[id]?.let(pages::get)?.entries.orEmpty()
                .mapNotNull { it.radioStation }
            return CarAudioSelection.Radio(station, siblings.ifEmpty { listOf(station) })
        }
        CarAudioIds.spotifyTrackId(id)?.let { trackId ->
            if (!spotifyEnabled()) return null
            return CarAudioSelection.Spotify("spotify:track:$trackId")
        }
        CarAudioIds.telegramItemId(id)?.let { (chatId, messageId) ->
            if (!telegramReady()) return null
            val repository = telegram.get()
            val media = entriesById[id]?.telegramMedia
                ?: repository.media(chatId, messageId)?.takeIf { it.asCarEntry() != null }
                ?: return null
            val queue = parents[id]?.let(pages::get)?.entries.orEmpty()
                .filter { it.telegramMedia != null }.map { it.id }
            return CarAudioSelection.Telegram(media, repository.playerHttpUrl(media), queue.ifEmpty { listOf(id) })
        }
        CarAudioIds.musicItemId(id)?.let { key ->
            // Browse previews often omit stream URLs; resolve the authoritative track
            // metadata before claiming the item is unavailable to the head unit.
            val preview = entriesById[id]?.libraryItem
            if (preview != null && !carAudioPlayableType(preview.type)) return null
            val item = media.getMetadata(key).getOrNull()
                ?.takeIf { carAudioPlayableType(it.type) && !it.streamUrl.isNullOrBlank() }
                ?: preview?.takeIf { !it.streamUrl.isNullOrBlank() }
                ?: return null
            if (!carAudioPlayableType(item.type)) return null
            val queue = parents[id]?.let(pages::get)?.entries.orEmpty()
                .filter { it.playable }
                .mapNotNull { CarAudioIds.musicItemId(it.id) }
            return CarAudioSelection.Library(item, queue.ifEmpty { listOf(key) })
        }
        return null
    }

    /** Optional programme metadata is loaded after the service has started the audio. */
    suspend fun liveProgrammes(id: String) = auxiliary.liveProgrammes(id)

    suspend fun entry(id: String): CarAudioEntry? =
        entriesById[id] ?: when (val selection = resolve(id)) {
            is CarAudioSelection.Radio -> selection.station.asEntry()
            is CarAudioSelection.Library -> selection.item.asEntry()
            is CarAudioSelection.Live -> CarAudioEntry(id, selection.channel.name,
                selection.channel.numberLabel, selection.channel.iconUrl?.takeIf(String::isNotBlank)
                    ?.let { CarAudioArtwork.uri(context, id).toString() }, playable = true)
            is CarAudioSelection.Telegram -> selection.media.asCarEntry()
            else -> null
        }

    /** Multiple browse results for official Android Auto search UIs and voice search. */
    suspend fun search(query: String): List<CarAudioEntry> {
        val q = query.trim().take(120)
        if (q.isBlank()) return emptyList()
        val found = mutableListOf<CarAudioEntry>()
        if (preferences.radioEnabled) {
            runCatching { radio.search(query = q, limit = 30).items }
                .getOrDefault(emptyList()).mapTo(found) { it.asEntry() }
        }
        found += auxiliary.searchLive(q, limit = 20)
        found += auxiliary.searchStoredAudio(q, limit = 15)
        // UPnP/SMB have no shared remote full-text API. Search the currently browsed
        // audio folders in addition to saved file favorites/history, never video nodes.
        found += entriesById.values.filter { entry ->
            entry.playable && entry.title.contains(q, ignoreCase = true) &&
                CarAudioIds.musicItemId(entry.id)?.let { DlnaKeys.owns(it) || FileKeys.owns(it) } == true
        }.take(20)
        if (telegramReady()) {
            runCatching { telegram.get().search(q).mapNotNull { it.asCarEntry() } }
                .getOrDefault(emptyList()).take(15).let(found::addAll)
        }
        if (spotifyEnabled()) {
            runCatching { spotify.search(q).mapNotNull { it.asEntry() } }
                .getOrDefault(emptyList()).take(15).let(found::addAll)
        }
        val unique = found.distinctBy { it.id }.take(60)
        unique.forEach { entriesById[it.id] = it }
        while (entriesById.size > maxItems) entriesById.remove(entriesById.keys.first())
        return unique
    }

    suspend fun firstForVoiceSearch(query: String): CarAudioSelection? {
        val safeQuery = query.trim().take(120)
        if (safeQuery.isEmpty()) return null
        return search(safeQuery).firstNotNullOfOrNull { resolve(it.id) }
    }
    private companion object { const val PAGE_SIZE = 40; const val MAX_CURSOR = 1_000_000 }
}

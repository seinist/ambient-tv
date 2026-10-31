package com.ambient.tv.car

import android.content.Context
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.dlna.DlnaRepository
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.dlna.toMediaItem
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.files.FileKinds
import com.ambient.tv.data.files.FilesRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.livetv.LiveTvPlaybackSource
import com.ambient.tv.livetv.LiveTvRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class CarAudioPage(
    val items: List<CarAudioEntry>,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
)

/** Source-specific Android Auto audio adapter.
 * Never creates its own player, duplicates credentials, or returns video browse items.
 */
@Singleton
internal class CarAuxiliaryAudio @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val liveTv: LiveTvRegistry,
    private val dlnaStore: DlnaStore,
    private val dlna: DlnaRepository,
    private val files: FilesRepository,
    private val fileStore: FileStore,
    private val access: FileAccess,
) {
    fun hasStorage(): Boolean =
        (preferences.filesEnabled && fileStore.sources().isNotEmpty()) || dlnaStore.servers().isNotEmpty()

    fun hasLive(system: String): Boolean = liveTv.configured().any { it.source.system == system }

    private fun liveRepository(id: String) = liveTv.configured()
        .firstOrNull { it.source.id == id && it.source.system in setOf("iptv", "tvheadend") }

    fun handles(folder: String): Boolean = when {
        folder in setOf(CarAudioIds.LIVE_RADIO_IPTV, CarAudioIds.LIVE_RADIO_TVH,
            CarAudioIds.STORAGE_ROOT, CarAudioIds.DLNA_ROOT, CarAudioIds.FILES_ROOT,
            CarAudioIds.FILE_FAVORITES, CarAudioIds.FILE_RECENT) -> true
        CarAudioIds.liveSourceId(folder) != null -> true
        CarAudioIds.liveGroupsId(folder) != null -> true
        CarAudioIds.liveGroupSpec(folder) != null -> true
        CarAudioIds.dlnaServerId(folder) != null -> true
        CarAudioIds.dlnaFolderId(folder) != null -> true
        CarAudioIds.fileSourceId(folder) != null -> true
        CarAudioIds.fileFolderId(folder) != null -> true
        else -> false
    }

    suspend fun children(parent: String, offset: Int, limit: Int): CarAudioPage? {
        if (!handles(parent)) return null
        fun slice(all: List<CarAudioEntry>) =
            CarAudioPage(all.drop(offset).take(limit), all.size > offset + limit)
        return when (parent) {
            CarAudioIds.STORAGE_ROOT -> slice(buildList {
                if (dlnaStore.servers().isNotEmpty()) add(folder(CarAudioIds.DLNA_ROOT, "DLNA"))
                if (preferences.filesEnabled && fileStore.sources().isNotEmpty()) {
                    add(folder(CarAudioIds.FILES_ROOT, "SMB / NAS / Dateien"))
                    add(folder(CarAudioIds.FILE_FAVORITES, context.getString(com.ambient.tv.R.string.car_audio_favorites)))
                    add(folder(CarAudioIds.FILE_RECENT, context.getString(com.ambient.tv.R.string.car_audio_recent)))
                }
            })
            CarAudioIds.DLNA_ROOT -> slice(dlnaStore.servers().map {
                folder(CarAudioIds.dlnaServer(it.id), it.name)
            })
            CarAudioIds.FILES_ROOT -> slice(if (preferences.filesEnabled) fileStore.sources().map {
                folder(CarAudioIds.fileSource(it.id), it.name)
            } else emptyList())
            CarAudioIds.FILE_FAVORITES -> slice(if (preferences.filesEnabled)
                fileStore.favorites().filter(FileEntry::audio).map(::fileEntry) else emptyList())
            CarAudioIds.FILE_RECENT -> slice(if (preferences.filesEnabled)
                fileStore.history().map { it.entry }.filter(FileEntry::audio).distinctBy { it.id }.map(::fileEntry)
                else emptyList())
            CarAudioIds.LIVE_RADIO_IPTV, CarAudioIds.LIVE_RADIO_TVH -> {
                val system = if (parent == CarAudioIds.LIVE_RADIO_IPTV) "iptv" else "tvheadend"
                slice(liveTv.configured().filter { it.source.system == system }.map {
                    folder(CarAudioIds.liveSource(it.source.id), it.source.name)
                })
            }
            else -> when {
                CarAudioIds.liveSourceId(parent) != null -> {
                    val id = requireNotNull(CarAudioIds.liveSourceId(parent))
                    if (liveRepository(id) == null) CarAudioPage(emptyList()) else slice(listOf(
                        folder(CarAudioIds.liveGroup(id, ""), "Alle Radios"),
                        folder(CarAudioIds.liveGroups(id), "Gruppen"),
                    ))
                }
                CarAudioIds.liveGroupsId(parent) != null -> {
                    val repo = liveRepository(requireNotNull(CarAudioIds.liveGroupsId(parent)))
                        ?: return CarAudioPage(emptyList())
                    val tags = repo.channels().asSequence()
                        .filter { it.kind == LiveTvChannelKind.RADIO }
                        .flatMap { it.tags.asSequence() }
                        .map(String::trim).filter(String::isNotBlank).distinct()
                        .sortedWith(String.CASE_INSENSITIVE_ORDER).toList()
                    slice(tags.map { folder(CarAudioIds.liveGroup(repo.source.id, it), it) })
                }
                CarAudioIds.liveGroupSpec(parent) != null -> {
                    val (id, tag) = requireNotNull(CarAudioIds.liveGroupSpec(parent))
                    val repo = liveRepository(id) ?: return CarAudioPage(emptyList())
                    val channels = repo.channels().asSequence()
                        .filter { it.kind == LiveTvChannelKind.RADIO && (tag.isEmpty() || tag in it.tags) }
                        .sortedWith(compareBy<LiveTvChannel> { it.number ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
                        .toList()
                    slice(channels.map { ch ->
                        CarAudioEntry(CarAudioIds.liveAudio(id, ch.uuid), ch.name,
                            ch.numberLabel, ch.iconUrl, playable = true)
                    })
                }
                CarAudioIds.dlnaServerId(parent) != null || CarAudioIds.dlnaFolderId(parent) != null -> {
                    val serverRoot = CarAudioIds.dlnaServerId(parent)
                    val location = if (serverRoot != null) serverRoot to DlnaRepository.ROOT_ID
                        else DlnaKeys.parse(requireNotNull(CarAudioIds.dlnaFolderId(parent)))
                    if (dlnaStore.server(location.first) == null) return CarAudioPage(emptyList())
                    val browse = dlna.browse(location.first, location.second,
                        start = offset, count = limit.coerceAtMost(DlnaRepository.PAGE_SIZE)).getOrThrow()
                    val entries = browse.nodes.mapNotNull { node ->
                        if (node.container) folder(CarAudioIds.dlnaFolder(node.key), node.title)
                        else {
                            val item = node.toMediaItem()
                            if (!carAudioPlayableType(item.type) || item.streamUrl.isNullOrBlank()) null
                            else CarAudioEntry(CarAudioIds.musicItem(item.ratingKey), item.title, null,
                                node.artworkUrl, playable = true, libraryItem = item)
                        }
                    }
                    CarAudioPage(
                        entries,
                        offset + browse.numberReturned < browse.totalMatches ||
                            (browse.totalMatches <= 0 && browse.numberReturned >= browse.requestedCount),
                        nextOffset = offset + browse.numberReturned,
                    )
                }
                CarAudioIds.fileSourceId(parent) != null || CarAudioIds.fileFolderId(parent) != null -> {
                    if (!preferences.filesEnabled) return CarAudioPage(emptyList())
                    val fileSource = CarAudioIds.fileSourceId(parent)
                    val location = if (fileSource != null) fileSource to null
                        else FileKeys.parse(requireNotNull(CarAudioIds.fileFolderId(parent))).let { it.first to it.second }
                    val source = fileStore.sources().firstOrNull { it.id == location.first }
                        ?: return CarAudioPage(emptyList())
                    val path = location.second ?: access.root(source)
                    // FileAccess already takes care of SMB credentials, WireGuard, WOL and sorting.
                    // Its listing has a limit rather than an offset, so request a bounded prefix.
                    val wanted = (offset + limit + 1).coerceAtMost(2000)
                    val listing = access.list(source, path, wanted, FileEntry::audio)
                    CarAudioPage(listing.entries.drop(offset).take(limit).map { entry ->
                        if (entry.directory) folder(CarAudioIds.fileFolder(entry.id), entry.name)
                        else fileEntry(entry)
                    }, listing.more || listing.entries.size > offset + limit)
                }
                else -> null
            }
        }
    }

    private fun folder(id: String, label: String) = CarAudioEntry(id, label)

    private fun fileEntry(entry: FileEntry) =
        CarAudioEntry(CarAudioIds.musicItem(entry.id), entry.name, null, playable = entry.audio)

    /** Only explicit radio-kind channels qualify; TV or unknown streams never enter Auto. */
    suspend fun resolveLive(id: String): CarAudioSelection.Live? {
        val (sourceId, uuid) = CarAudioIds.liveAudioSpec(id) ?: return null
        val repo = liveRepository(sourceId) ?: return null
        val radioChannels = repo.channels().filter { it.kind == LiveTvChannelKind.RADIO }
        val channel = radioChannels.firstOrNull { it.uuid == uuid } ?: return null
        val siblings = radioChannels.map { CarAudioIds.liveAudio(sourceId, it.uuid) }
        return CarAudioSelection.Live(channel, repo.liveStream(channel), siblings, repo.source.system)
    }

    /** Search only explicit audio channel kinds, never general live TV programme titles. */
    suspend fun searchLive(query: String, limit: Int = 25): List<CarAudioEntry> {
        val results = mutableListOf<CarAudioEntry>()
        for (repo in liveTv.configured().filter { it.source.system in setOf("iptv", "tvheadend") }) {
            if (results.size >= limit) break
            val channels = runCatching { repo.channels() }.getOrDefault(emptyList())
            results += channels.filter { it.kind == LiveTvChannelKind.RADIO &&
                it.name.contains(query, ignoreCase = true) }
                .take(limit - results.size)
                .map { CarAudioEntry(CarAudioIds.liveAudio(repo.source.id, it.uuid), it.name,
                    repo.source.name, it.iconUrl, playable = true) }
        }
        return results
    }

    suspend fun searchStoredAudio(query: String, limit: Int = 25): List<CarAudioEntry> {
        if (!preferences.filesEnabled) return emptyList()
        return (fileStore.favorites() + fileStore.history().map { it.entry })
            .filter { it.audio && it.name.contains(query, ignoreCase = true) }
            .distinctBy { it.id }.take(limit).map(::fileEntry)
    }
}

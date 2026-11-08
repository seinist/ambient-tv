package com.ambient.tv.car

import android.content.Context
import android.os.SystemClock
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.dlna.DlnaNode
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
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvRegistry
import com.ambient.tv.livetv.LiveTvRepository
import com.ambient.tv.util.suspendResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Conservative classification: never turn video-only or ambiguous DLNA resources into audio. */
internal fun carDlnaAudioEligible(node: DlnaNode): Boolean {
    if (node.container) return false
    val type = node.upnpClass.orEmpty()
    if (type.contains("videoItem", ignoreCase = true) ||
        type.contains("imageItem", ignoreCase = true)) return false
    val mimes = node.resources.mapNotNull {
        it.protocolInfo?.split(':', limit = 4)?.getOrNull(2)?.substringBefore(';')
    }
    if (mimes.any { it.startsWith("video/", ignoreCase = true) }) return false
    return type.contains("audioItem", ignoreCase = true) ||
        mimes.any { it.startsWith("audio/", ignoreCase = true) }
}

internal fun carAudioFileEligible(entry: FileEntry) = !entry.directory && entry.audio
internal fun carLiveAudioEligible(channel: LiveTvChannel) = channel.kind == LiveTvChannelKind.RADIO

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
    private data class LiveRadioSnapshot(
        val loadedAt: Long,
        val channels: CarLiveRadioChannels,
        val programmes: List<LiveTvProgramme>,
    )
    private class LiveRadioState(val repository: LiveTvRepository) {
        val lock = Mutex()
        @Volatile var snapshot: LiveRadioSnapshot? = null
        val guideJob = SupervisorJob()
        val guideScope = CoroutineScope(guideJob + Dispatchers.IO)
        @Volatile var epg: Deferred<List<LiveTvProgramme>?>? = null
        var epgStartedAt = 0L
    }
    private val liveRadioStates = mutableMapOf<String, LiveRadioState>()

    fun hasStorage(): Boolean =
        (preferences.filesEnabled && fileStore.sources().isNotEmpty()) || dlnaStore.servers().isNotEmpty()

    fun hasLive(system: String): Boolean = liveRepositories().any { it.source.system == system }

    private fun liveRepository(id: String) = liveRepositories().firstOrNull { it.source.id == id }

    private fun liveRepositories(): List<LiveTvRepository> {
        val repositories = liveTv.configured().filter { it.source.system in setOf("iptv", "tvheadend") }
        val current = repositories.associateBy { it.source.id }
        synchronized(liveRadioStates) {
            liveRadioStates.entries.removeAll { (id, state) ->
                (current[id] !== state.repository).also { stale -> if (stale) state.guideJob.cancel() }
            }
        }
        return repositories
    }

    private suspend fun liveRadioChannels(repo: LiveTvRepository, waitForGuide: Boolean = true): CarLiveRadioChannels {
        val state = synchronized(liveRadioStates) {
            liveRadioStates[repo.source.id]?.takeIf { it.repository === repo }
                ?: LiveRadioState(repo).also { liveRadioStates[repo.source.id] = it }
        }
        fun fresh() = state.snapshot?.takeIf {
            SystemClock.elapsedRealtime() - it.loadedAt < LIVE_RADIO_TTL_MS
        }?.channels
        if (!waitForGuide) {
            // A simultaneous browse may be awaiting EPG under the cache lock. Playback
            // uses available evidence immediately and never queues behind that optional work.
            return fresh() ?: loadLiveRadioSnapshot(state, waitForGuide = false).channels
        }
        return state.lock.withLock {
            fresh() ?: loadLiveRadioSnapshot(state, waitForGuide = true).also { state.snapshot = it }.channels
        }
    }

    private suspend fun loadLiveRadioSnapshot(state: LiveRadioState, waitForGuide: Boolean): LiveRadioSnapshot {
        val repo = state.repository
        val channels = repo.channels()
        val now = System.currentTimeMillis() / 1000L
        val withoutGuide = withContext(Dispatchers.Default) { CarLiveRadioChannels(channels, emptyList(), now) }
        val programmes = if (withoutGuide.needsProgrammeEvidence) {
            // The phone/TV browser already has the exact grouping evidence in most cases.
            // Radio browsing remains available when the guide is missing or slow.
            liveTv.browseState(repo).dashboard?.events
                ?: optionalRadioGuide(state, waitForGuide)
                ?: state.snapshot?.programmes.orEmpty()
        } else emptyList()
        return withContext(Dispatchers.Default) {
            val radioIds = channels.filter(::carLiveAudioEligible).mapTo(HashSet()) { it.uuid }
            val radioProgrammes = programmes.filter { it.channelUuid in radioIds }
            val grouped = if (radioProgrammes.isEmpty()) withoutGuide else CarLiveRadioChannels(channels, radioProgrammes, now)
            LiveRadioSnapshot(SystemClock.elapsedRealtime(), grouped, radioProgrammes)
        }
    }

    private suspend fun optionalRadioGuide(state: LiveRadioState, waitForGuide: Boolean): List<LiveTvProgramme>? {
        if (!waitForGuide) {
            // Direct/voice playback must not wait for guide grouping on a cold catalog.
            return state.epg?.takeIf { it.isCompleted && !it.isCancelled }?.await()
        }
        val now = SystemClock.elapsedRealtime()
        // Demand-only work scoped to this repository fingerprint. Removing/reconfiguring
        // a source cancels its job, including a load started after an older caller resumed.
        val pending = state.epg?.takeUnless {
            it.isCompleted && now - state.epgStartedAt >= RADIO_GUIDE_TTL_MS
        } ?: state.guideScope.async {
            suspendResult { state.repository.epgEvents(windowHours = 12) }.getOrNull()
        }.also {
            state.epg = it
            state.epgStartedAt = now
        }
        // Only await is a child of this timeout: legacy repositories can use blocking HTTP.
        // Cancellation of the caller still propagates, while the shared lookup remains usable.
        val programmes = withTimeoutOrNull(RADIO_GUIDE_WAIT_MS) { pending.await() }
        if (programmes == null && pending.isCompleted) state.epg = null
        return programmes
    }

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
                fileStore.favorites().filter(::carAudioFileEligible).map(::fileEntry) else emptyList())
            CarAudioIds.FILE_RECENT -> slice(if (preferences.filesEnabled)
                fileStore.history().map { it.entry }.filter(::carAudioFileEligible).distinctBy { it.id }.map(::fileEntry)
                else emptyList())
            CarAudioIds.LIVE_RADIO_IPTV, CarAudioIds.LIVE_RADIO_TVH -> {
                val system = if (parent == CarAudioIds.LIVE_RADIO_IPTV) "iptv" else "tvheadend"
                slice(liveRepositories().filter { it.source.system == system }.map {
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
                    val tags = liveRadioChannels(repo).tags
                    slice(tags.map { folder(CarAudioIds.liveGroup(repo.source.id, it), it) })
                }
                CarAudioIds.liveGroupSpec(parent) != null -> {
                    val (id, tag) = requireNotNull(CarAudioIds.liveGroupSpec(parent))
                    val repo = liveRepository(id) ?: return CarAudioPage(emptyList())
                    val radios = liveRadioChannels(repo)
                    val channels = withContext(Dispatchers.Default) { radios.inGroup(tag) }
                    CarAudioPage(channels.drop(offset).take(limit).map { channel -> liveEntry(id, channel) },
                        channels.size > offset + limit)
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
                            if (!carDlnaAudioEligible(node) || !carAudioPlayableType(item.type) ||
                                item.streamUrl.isNullOrBlank()) null
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
                    if (offset >= 2000) return CarAudioPage(emptyList())
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

    private fun liveEntry(sourceId: String, channel: LiveTvChannel, subtitle: String? = channel.numberLabel): CarAudioEntry {
        val id = CarAudioIds.liveAudio(sourceId, channel.uuid)
        return CarAudioEntry(id, channel.name, subtitle,
            channel.iconUrl?.takeIf(String::isNotBlank)?.let { CarAudioArtwork.uri(context, id).toString() },
            playable = true)
    }

    private fun fileEntry(entry: FileEntry) =
        CarAudioEntry(CarAudioIds.musicItem(entry.id), entry.name, null,
            playable = carAudioFileEligible(entry))

    /** Only explicit radio-kind channels qualify; TV or unknown streams never enter Auto. */
    suspend fun resolveLive(id: String, group: String? = null): CarAudioSelection.Live? {
        val (sourceId, uuid) = CarAudioIds.liveAudioSpec(id) ?: return null
        val repo = liveRepository(sourceId) ?: return null
        val radios = liveRadioChannels(repo, waitForGuide = false)
        val selection = withContext(Dispatchers.Default) { radios.selection(uuid, group) } ?: return null
        val siblings = selection.queue.map { CarAudioIds.liveAudio(sourceId, it.uuid) }
        return CarAudioSelection.Live(selection.channel, repo.liveStream(selection.channel), siblings,
            repo.source.system, artworkHeaders = repo.authHeaders())
    }

    /** Called after playback starts; fetching programme details never gates stream selection. */
    suspend fun liveProgrammes(id: String): List<LiveTvProgramme> {
        val (sourceId, uuid) = CarAudioIds.liveAudioSpec(id) ?: return emptyList()
        val repo = liveRepository(sourceId) ?: return emptyList()
        if (repo.channels().none { it.uuid == uuid && carLiveAudioEligible(it) }) return emptyList()
        val now = System.currentTimeMillis() / 1000L
        val state = synchronized(liveRadioStates) {
            liveRadioStates[sourceId]?.takeIf { it.repository === repo }
        }
        suspend fun matching(events: List<LiveTvProgramme>) = withContext(Dispatchers.Default) {
            carLiveRadioProgrammes(events, sourceId, uuid, now)
        }
        var fallback = emptyList<LiveTvProgramme>()
        val cached = listOfNotNull(liveTv.browseState(repo).dashboard?.events, state?.snapshot?.programmes)
        for (events in cached) {
            val matches = matching(events)
            if (matches.any { it.isLive(now) }) return matches
            if (fallback.isEmpty()) fallback = matches
        }
        // A full guide started for station grouping may already have completed meanwhile.
        state?.epg?.takeIf { it.isCompleted && !it.isCancelled }?.await()?.let { events ->
            val matches = matching(events)
            if (matches.any { it.isLive(now) }) return matches
            if (fallback.isEmpty()) fallback = matches
        }
        val fetched = suspendResult {
            repo.channelEpgEvents(uuid, startEpochSeconds = now - 1800L, windowHours = 8)
        }.getOrNull() ?: return fallback
        return matching(fetched)
    }

    /** Search only explicit audio channel kinds, never general live TV programme titles. */
    suspend fun searchLive(query: String, limit: Int = 25): List<CarAudioEntry> {
        val results = mutableListOf<CarAudioEntry>()
        for (repo in liveRepositories()) {
            if (results.size >= limit) break
            val channels = suspendResult { liveRadioChannels(repo).all }.getOrDefault(emptyList())
            results += channels.filter { it.name.contains(query, ignoreCase = true) }
                .take(limit - results.size)
                .map { liveEntry(repo.source.id, it, repo.source.name) }
        }
        return results
    }

    suspend fun searchStoredAudio(query: String, limit: Int = 25): List<CarAudioEntry> {
        if (!preferences.filesEnabled) return emptyList()
        return (fileStore.favorites() + fileStore.history().map { it.entry })
            .filter { carAudioFileEligible(it) && it.name.contains(query, ignoreCase = true) }
            .distinctBy { it.id }.take(limit).map(::fileEntry)
    }

    private companion object {
        const val LIVE_RADIO_TTL_MS = 45_000L
        const val RADIO_GUIDE_TTL_MS = 5 * 60_000L
        const val RADIO_GUIDE_WAIT_MS = 2_000L
    }
}

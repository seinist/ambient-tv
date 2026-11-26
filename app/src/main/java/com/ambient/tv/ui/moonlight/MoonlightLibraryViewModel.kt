package com.ambient.tv.ui.moonlight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.browse.BrowseLoadPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import com.ambient.tv.moonlight.MoonlightApp
import com.ambient.tv.moonlight.MoonlightHost
import com.ambient.tv.moonlight.MoonlightLibraryStore
import com.ambient.tv.moonlight.MoonlightSystemTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MoonlightBrowseGame(
    val host: MoonlightHost,
    val app: MoonlightApp,
    override val favorite: Boolean = false,
    val recentPosition: Int = Int.MAX_VALUE,
    val artworkPath: String? = null,
    val artworkRatio: Float = 2f / 3f,
) : com.ambient.tv.domain.model.GameBrowseItem {
    val key: String get() = host.id + ":" + app.id
    override val fileId: String get() = key
    override val title: String get() = app.title
    override val systemId: String get() = host.id
    override val systemLabel: String get() = host.name.ifBlank { host.host }
    override val metadataYear: Int? get() = null
    override val coverAspectRatio: Float get() = artworkRatio
    override val coverModel: Any? get() = artworkPath?.let(::File)
    override val artworkIcon: Int get() = com.ambient.tv.R.drawable.system_moonlight
}

data class MoonlightLibraryUiState(
    val hosts: List<MoonlightHost> = emptyList(),
    val catalogs: Map<String, List<MoonlightApp>> = emptyMap(),
    val onlineHosts: Set<String> = emptySet(),
    val runningApps: Map<String, Int> = emptyMap(),
    val pairedHosts: Set<String> = emptySet(),
    val offlineHosts: Set<String> = emptySet(),
    val hostIdentities: Map<String, String?> = emptyMap(),
    val loadingHosts: Set<String> = emptySet(),
    val sections: String = "all",
    val query: String = "",
    val view: String = "grid",
    val gridColumns: Int = 8,
    val listRows: Int = 7,
    val sortKey: String = "name",
    val sortDescending: Boolean = false,
    val artwork: Map<String, com.ambient.tv.moonlight.MoonlightArtwork> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val recent: List<String> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val artworkVersion: Long = 0,
    val artworkRevisions: Map<String, Long> = emptyMap(),
    val wakingHosts: Set<String> = emptySet(),
    val quittingHosts: Set<String> = emptySet(),
    val localLoaded: Boolean = false,
    val initialRefreshComplete: Boolean = false,
) {
    val ready: Boolean get() = localLoaded && (catalogs.values.any { it.isNotEmpty() } || initialRefreshComplete)

    /** Host selection, global search and favorite/recent filtering happen in memory, not on DPAD focus. */
    val games: List<MoonlightBrowseGame> get() {
        val recentIndex = recent.withIndex().associate { it.value to it.index }
        val all = hosts.flatMap { host ->
            catalogs[host.id].orEmpty().map { app ->
                val id = host.id + ":" + app.id
                MoonlightBrowseGame(host, app, id in favorites, recentIndex[id] ?: Int.MAX_VALUE)
            }
        }
        val chosen = all.filter { game ->
            (sections == "all" ||
                sections == "favorites" && game.favorite ||
                sections == "recent" && game.recentPosition != Int.MAX_VALUE ||
                sections == "host:" + game.host.id) &&
            (query.isBlank() || game.app.title.contains(query, ignoreCase = true) ||
                game.systemLabel.contains(query, ignoreCase = true))
        }
        val comparator = when {
            sections == "recent" || sortKey == "recent" -> compareBy<MoonlightBrowseGame> { it.recentPosition }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            sortKey == "system" -> compareBy<MoonlightBrowseGame, String>(String.CASE_INSENSITIVE_ORDER) { it.systemLabel }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            else -> compareBy<MoonlightBrowseGame, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        }.thenBy { it.key }
        return chosen.sortedWith(if (sortDescending && sections != "recent") comparator.reversed() else comparator)
    }
}

/**
 * One lazy load coordinator for Sunshine applications, artwork and local metadata.
 * No host network request occurs until this ViewModel's browse screen opens.
 * Cached contents stay visible offline, but the player is not allowed to start while offline.
 */
@HiltViewModel
class MoonlightLibraryViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val transport: MoonlightSystemTransport,
    private val store: MoonlightLibraryStore,
    private val machineWake: com.ambient.tv.wol.MachineWake,
    private val reconnects: com.ambient.tv.data.browse.BrowseReconnects,
    private val connectivity: com.ambient.tv.connectivity.HostConnectivity,
    private val catalogRepository: com.ambient.tv.moonlight.MoonlightCatalogRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(MoonlightLibraryUiState(view = prefs.moonlightLibraryView))
    val state: StateFlow<MoonlightLibraryUiState> = mutable.asStateFlow()
    // Filter/sort off the UI thread; artwork arrivals only replace the corresponding card data.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sortedGames = mutable.map { it.copy(onlineHosts = emptySet(), runningApps = emptyMap(), pairedHosts = emptySet(),
        loadingHosts = emptySet(), offlineHosts = emptySet(), errors = emptyMap(), artwork = emptyMap(), artworkVersion = 0,
        artworkRevisions = emptyMap(), wakingHosts = emptySet(), quittingHosts = emptySet(), localLoaded = false, initialRefreshComplete = false) }
        .distinctUntilChanged().mapLatest { withContext(Dispatchers.Default) { it.games } }
    val games = combine(sortedGames, mutable.map { it.artwork }.distinctUntilChanged()) { sorted, artwork ->
        withContext(Dispatchers.Default) {
            sorted.map { game -> artwork[game.key]?.let { game.copy(artworkPath = it.file.absolutePath, artworkRatio = it.aspectRatio) } ?: game }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val inflightArtwork = HashSet<Pair<String, Boolean>>()
    private val hostSlots = Semaphore(2)
    private var fetch: Job? = null
    private var visible = false
    private var localRevision = 0L
    private val artworkJobs = mutableSetOf<Job>()

    init {
        viewModelScope.launch {
            combine(store.metadataRevisions, store.availabilityRevisions,
                prefs.changes().onStart { emit(Unit) }, store.trustRevisions) { metadata, online, _, trust ->
                listOf(metadata, online, prefs.moonlightEnabled, prefs.moonlightHosts, prefs.moonlightLibraryView,
                    prefs.moonlightGridColumns, prefs.moonlightListRows, prefs.moonlightSortKey, prefs.moonlightSortDescending, trust)
            }.distinctUntilChanged().collectLatest { reloadLocal() }
        }
        viewModelScope.launch { store.artworkChanges.collect { revisions ->
            val previous = mutable.value.artworkRevisions
            mutable.update { it.copy(artworkVersion = store.artworkRevisions.value,
                artworkRevisions = revisions) }
            // Only refresh image descriptors that the visible cards have already requested.
            val keys = mutable.value.artwork.keys.intersect(revisions.filter { previous[it.key] != it.value }.keys)
            for (key in keys) {
                val game = item(key.substringBefore(':'), key.substringAfter(':').toIntOrNull() ?: continue) ?: continue
                refreshArtworkDescriptor(game)
            }
        } }
    }

    private suspend fun reloadLocal() {
        val revision = ++localRevision
        val snapshot = withContext(Dispatchers.IO) {
            val hosts = if (prefs.moonlightEnabled && transport.available) prefs.moonlightHosts else emptyList()
            MoonlightLibraryUiState(
                hosts = hosts,
                catalogs = hosts.associate { it.id to store.catalog(it.id)?.apps.orEmpty() },
                onlineHosts = hosts.filter { store.isRecentlyOnline(it.id) }.map { it.id }.toSet(),
                runningApps = hosts.mapNotNull { host -> store.runningApp(host.id)?.let { host.id to it } }.toMap(),
                pairedHosts = hosts.filter { transport.locallyPaired(it.id) }.map { it.id }.toSet(),
                offlineHosts = hosts.filter { store.isKnownOffline(it.id) }.map { it.id }.toSet(),
                hostIdentities = hosts.associate { it.id to store.peerIdentity(it.id) },
                favorites = store.favorites(), recent = store.recent(),
            )
        }
        if (revision != localRevision) return
        mutable.update { current ->
            current.copy(hosts = snapshot.hosts, catalogs = snapshot.catalogs,
                onlineHosts = snapshot.onlineHosts, runningApps = snapshot.runningApps, pairedHosts = snapshot.pairedHosts,
                offlineHosts = snapshot.offlineHosts, hostIdentities = snapshot.hostIdentities,
                favorites = snapshot.favorites, recent = snapshot.recent,
                view = prefs.moonlightLibraryView, localLoaded = true,
                gridColumns = prefs.moonlightGridColumns, listRows = prefs.moonlightListRows,
                sortKey = prefs.moonlightSortKey, sortDescending = prefs.moonlightSortDescending,
                artwork = current.artwork.filterKeys { key ->
                    val id = key.substringBefore(':')
                    snapshot.hostIdentities[id] != null && snapshot.hostIdentities[id] == current.hostIdentities[id]
                },
                sections = current.sections.takeIf { !it.startsWith("host:") || snapshot.hosts.any { host -> "host:" + host.id == it } } ?: "all")
        }
    }

    fun section(value: String) {
        if (mutable.value.sections == value) return
        mutable.update { it.copy(sections = value) }
        if (visible) load(restart = true)
    }

    fun search(value: String) {
        mutable.update { it.copy(query = value.take(150)) }
    }

    fun view(value: String) {
        if (value !in setOf("grid", "list", "rows")) return
        prefs.moonlightLibraryView = value
        mutable.update { it.copy(view = value) }
    }

    fun gridColumns(value: Int) { prefs.moonlightGridColumns = value; mutable.update { it.copy(gridColumns = prefs.moonlightGridColumns) } }
    fun listRows(value: Int) { prefs.moonlightListRows = value; mutable.update { it.copy(listRows = prefs.moonlightListRows) } }
    fun sort(value: String) { prefs.moonlightSortKey = value; mutable.update { it.copy(sortKey = prefs.moonlightSortKey) } }
    fun sortDescending(value: Boolean) { prefs.moonlightSortDescending = value; mutable.update { it.copy(sortDescending = prefs.moonlightSortDescending) } }

    fun favorite(game: MoonlightBrowseGame) {
        store.toggleFavorite(game.host.id, game.app.id)
        mutable.update { it.copy(favorites = store.favorites()) }
    }

    fun item(hostId: String, appId: Int): MoonlightBrowseGame? {
        val snapshot = mutable.value
        val host = snapshot.hosts.firstOrNull { it.id == hostId } ?: return null
        val app = snapshot.catalogs[hostId]?.firstOrNull { it.id == appId } ?: return null
        return MoonlightBrowseGame(host, app, "$hostId:$appId" in snapshot.favorites,
            snapshot.recent.indexOf("$hostId:$appId").takeIf { it >= 0 } ?: Int.MAX_VALUE)
    }

    fun artwork(game: MoonlightBrowseGame): File? = store.artworkFile(game.host.id, game.app.id)
    fun artworkInfo(game: MoonlightBrowseGame) = store.artworkInfo(game.host.id, game.app.id)

    fun enter() {
        visible = true
        if (transport.available) load() else mutable.update { it.copy(initialRefreshComplete = true) }
    }
    fun leave() {
        visible = false
        fetch?.cancel()
        fetch = null
        artworkJobs.toList().forEach { it.cancel() }
    }

    suspend fun observeRecovery() {
        var seen = com.ambient.tv.data.browse.browseReconnectVersion(reconnects.revisions.value, setOf("moonlight"))
        reconnects.revisions.collect {
            val next = com.ambient.tv.data.browse.browseReconnectVersion(it, setOf("moonlight"))
            if (next > seen) { seen = next; load(force = true) }
        }
    }

    fun load(force: Boolean = false, onlyHost: String? = null, restart: Boolean = false) {
        if (!visible || !transport.available) return
        val previous = fetch
        if (previous?.isActive == true && !force && !restart) return
        if (force || restart) previous?.cancel()
        fetch = viewModelScope.launch {
            previous?.join()
            reloadLocal()
            val snapshot = mutable.value
            val selectedHost = onlyHost ?: snapshot.sections.takeIf { it.startsWith("host:") }?.removePrefix("host:")
            val paired = snapshot.hosts.filter { (selectedHost == null || it.id == selectedHost) &&
                it.id in snapshot.pairedHosts && (force || it.id !in snapshot.onlineHosts || !store.isCatalogFresh(it.id)) }
            coroutineScope {
                paired.map { host ->
                    async {
                        hostSlots.withPermit {
                            mutable.update { it.copy(loadingHosts = it.loadingHosts + host.id) }
                            val identity = store.peerIdentity(host.id)
                            try {
                                if (identity == null) throw SecurityException("Moonlight host not paired")
                                // Single-flight owns one request token, even with two browse consumers.
                                catalogRepository.refresh(host, BrowseLoadPriority.VISIBLE, forceCatalog = force)
                                if (store.peerIdentity(host.id) == identity && prefs.moonlightHosts.any { it == host }) {
                                    reloadLocal()
                                    mutable.update { it.copy(errors = it.errors - host.id) }
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: SecurityException) {
                                if (store.peerIdentity(host.id) == identity && prefs.moonlightHosts.any { it == host }) {
                                    reloadLocal()
                                    mutable.update { it.copy(errors = it.errors + (host.id to "identity")) }
                                }
                            } catch (_: Exception) {
                                // Previously authenticated cached titles remain browseable, but not playable.
                                if (store.peerIdentity(host.id) == identity && prefs.moonlightHosts.any { it == host }) {
                                    reloadLocal()
                                    mutable.update { it.copy(errors = it.errors + (host.id to "offline")) }
                                }
                            } finally {
                                mutable.update { it.copy(loadingHosts = it.loadingHosts - host.id) }
                            }
                        }
                    }
                }.awaitAll()
            }
            mutable.update { it.copy(initialRefreshComplete = true) }
        }
    }

    private suspend fun refreshArtworkDescriptor(game: MoonlightBrowseGame) {
        val identity = store.peerIdentity(game.host.id) ?: return
        val descriptor = withContext(Dispatchers.IO) { store.artworkInfo(game.host.id, game.app.id) }
        mutable.update { state ->
            if (state.hosts.none { it == game.host } || state.hostIdentities[game.host.id] != identity || store.peerIdentity(game.host.id) != identity) state
            else state.copy(artwork = if (descriptor == null) state.artwork - game.key else
                (state.artwork - game.key + (game.key to descriptor)).entries.toList().takeLast(512).associate { it.toPair() })
        }
    }

    fun prioritizeArtwork(game: MoonlightBrowseGame, visible: Boolean = true) {
        if (!this.visible) return
        val key = game.key to visible
        synchronized(inflightArtwork) {
            if ((!visible && (game.key to true) in inflightArtwork) || !inflightArtwork.add(key)) return
        }
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                refreshArtworkDescriptor(game)
                if (game.host.id !in mutable.value.onlineHosts) return@launch
                catalogRepository.artwork(game.host, game.app.id,
                    if (visible) BrowseLoadPriority.VISIBLE else BrowseLoadPriority.BACKGROUND)
                refreshArtworkDescriptor(game)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* No cover is a valid Sunshine catalog case. */ }
            finally { synchronized(inflightArtwork) { inflightArtwork.remove(key) } }
        }
        artworkJobs.add(job)
        job.invokeOnCompletion { viewModelScope.launch { artworkJobs.remove(job) } }
        job.start()
    }

    /**
     * Explicit WOL only. A cached offline card never starts this without a button press.
     * Shared MachineWake coalesces a Moonlight PC with Plex/Jellyfin on the same machine.
     */
    fun wakeHost(hostId: String) {
        val host = prefs.moonlightHosts.firstOrNull { it.id == hostId } ?: return
        if (host.macAddress.isBlank() || host.id in mutable.value.onlineHosts ||
            host.id in mutable.value.wakingHosts) return
        val request = com.ambient.tv.wol.ManualWakeRequest(
            system = "moonlight:" + host.id,
            member = "moonlight:" + host.id,
            subject = host.name.ifBlank { host.host },
            host = host.host,
            mac = host.macAddress,
            probePort = host.port,
        )
        mutable.update { it.copy(wakingHosts = it.wakingHosts + host.id) }
        viewModelScope.launch {
            try {
                val result = machineWake.wakeAndAwait(request,
                    stillCurrent = { prefs.moonlightHosts.any { it == host } },
                    alreadyAwake = { connectivity.verifySystem("moonlight:" + host.id) })
                if (result == com.ambient.tv.wol.ManualWakeCoordinator.Outcome.AWAKE) {
                    fetch?.join()
                    load(force = true, onlyHost = host.id)
                }
            } finally {
                mutable.update { it.copy(wakingHosts = it.wakingHosts - host.id) }
            }
        }
    }

    fun quitRemote(hostId: String, appId: Int) {
        if (hostId !in mutable.value.onlineHosts || hostId !in mutable.value.pairedHosts ||
            hostId in mutable.value.quittingHosts) return
        mutable.update { it.copy(quittingHosts = it.quittingHosts + hostId, errors = it.errors - hostId) }
        viewModelScope.launch {
            try {
                if (transport.quitRemoteApp(hostId, appId)) load(force = true, onlyHost = hostId)
                else mutable.update { it.copy(errors = it.errors + (hostId to "quit_failed")) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutable.update { it.copy(errors = it.errors + (hostId to "quit_failed")) }
            } finally {
                mutable.update { it.copy(quittingHosts = it.quittingHosts - hostId) }
            }
        }
    }

    fun recordLaunch(hostId: String, appId: Int) {
        store.recordLaunch(hostId, appId)
        mutable.update { it.copy(recent = store.recent()) }
    }
}

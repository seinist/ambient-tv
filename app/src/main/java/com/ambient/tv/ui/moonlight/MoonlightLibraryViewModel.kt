package com.ambient.tv.ui.moonlight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.browse.BrowseLoadCoordinator
import com.ambient.tv.data.browse.BrowseLoadPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MoonlightBrowseGame(
    val host: MoonlightHost,
    val app: MoonlightApp,
    val favorite: Boolean = false,
    val recentPosition: Int = Int.MAX_VALUE,
    val artworkPath: String? = null,
) {
    val key: String get() = host.id + ":" + app.id
}

data class MoonlightLibraryUiState(
    val hosts: List<MoonlightHost> = emptyList(),
    val catalogs: Map<String, List<MoonlightApp>> = emptyMap(),
    val onlineHosts: Set<String> = emptySet(),
    val pairedHosts: Set<String> = emptySet(),
    val loadingHosts: Set<String> = emptySet(),
    val sections: String = "all",
    val query: String = "",
    val view: String = "grid",
    val favorites: Set<String> = emptySet(),
    val recent: List<String> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val artworkVersion: Long = 0,
    val wakingHosts: Set<String> = emptySet(),
    val quittingHosts: Set<String> = emptySet(),
) {
    val ready: Boolean get() = loadingHosts.isEmpty()

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
                game.host.name.contains(query, ignoreCase = true))
        }
        return if (sections == "recent") chosen.sortedWith(compareBy({ it.recentPosition }, { it.app.title }))
            else chosen.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.app.title })
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
    private val browse: BrowseLoadCoordinator,
) : ViewModel() {
    private val mutable = MutableStateFlow(MoonlightLibraryUiState(view = prefs.moonlightLibraryView))
    val state: StateFlow<MoonlightLibraryUiState> = mutable.asStateFlow()
    private val inflightArtwork = HashSet<Pair<String, Boolean>>()
    private val hostSlots = Semaphore(2)
    private var fetch: Job? = null

    init {
        viewModelScope.launch { store.metadataRevisions.collectLatest { reloadLocal() } }
        viewModelScope.launch { store.artworkRevisions.collect { version ->
            mutable.update { it.copy(artworkVersion = version) }
        } }
        viewModelScope.launch { prefs.changes().collectLatest { reloadLocal() } }
    }

    private suspend fun reloadLocal() {
        val snapshot = withContext(Dispatchers.IO) {
            val hosts = if (prefs.moonlightEnabled && transport.available) prefs.moonlightHosts else emptyList()
            MoonlightLibraryUiState(
                hosts = hosts,
                catalogs = hosts.associate { it.id to store.catalog(it.id)?.apps.orEmpty() },
                onlineHosts = hosts.filter { store.isRecentlyOnline(it.id) }.map { it.id }.toSet(),
                pairedHosts = hosts.filter { transport.locallyPaired(it.id) }.map { it.id }.toSet(),
                favorites = store.favorites(), recent = store.recent(),
            )
        }
        mutable.update { current ->
            current.copy(hosts = snapshot.hosts, catalogs = snapshot.catalogs,
                onlineHosts = snapshot.onlineHosts, pairedHosts = snapshot.pairedHosts,
                favorites = snapshot.favorites, recent = snapshot.recent)
        }
    }

    fun section(value: String) {
        mutable.update { it.copy(sections = value) }
    }

    fun search(value: String) {
        mutable.update { it.copy(query = value.take(150)) }
    }

    fun view(value: String) {
        if (value !in setOf("grid", "list")) return
        prefs.moonlightLibraryView = value
        mutable.update { it.copy(view = value) }
    }

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

    fun load(force: Boolean = false) {
        if (!transport.available || fetch?.isActive == true) return
        fetch = viewModelScope.launch {
            reloadLocal()
            val snapshot = mutable.value
            val paired = snapshot.hosts.filter { it.id in snapshot.pairedHosts &&
                (force || it.id !in snapshot.onlineHosts) }
            coroutineScope {
                paired.map { host ->
                    async {
                        hostSlots.withPermit {
                            mutable.update { it.copy(loadingHosts = it.loadingHosts + host.id) }
                            try {
                                val identity = store.peerIdentity(host.id)
                                    ?: throw SecurityException("Moonlight host not paired")
                                val catalog = browse.request("moonlight", "catalog:${host.id}:$identity",
                                    BrowseLoadPriority.VISIBLE) {
                                    transport.applications(host.id).also { store.putCatalog(host.id, it) }
                                }
                                if (store.peerIdentity(host.id) != identity ||
                                    prefs.moonlightHosts.none { it == host }) return@withPermit
                                mutable.update { state ->
                                    if (state.hosts.none { it.id == host.id }) state
                                    else state.copy(
                                        catalogs = state.catalogs + (host.id to catalog.apps),
                                        onlineHosts = state.onlineHosts + host.id,
                                        errors = state.errors - host.id,
                                    )
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: SecurityException) {
                                withContext(Dispatchers.IO) { store.invalidateCatalog(host.id) }
                                mutable.update { it.copy(
                                    catalogs = it.catalogs - host.id, onlineHosts = it.onlineHosts - host.id,
                                    errors = it.errors + (host.id to "identity"),
                                ) }
                            } catch (_: Exception) {
                                // Previously authenticated cached titles remain browseable, but not playable.
                                store.markOffline(host.id)
                                mutable.update { it.copy(
                                    onlineHosts = it.onlineHosts - host.id,
                                    errors = it.errors + (host.id to "offline"),
                                ) }
                            } finally {
                                mutable.update { it.copy(loadingHosts = it.loadingHosts - host.id) }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun prioritizeArtwork(game: MoonlightBrowseGame, visible: Boolean = true) {
        if (game.host.id !in mutable.value.onlineHosts) return
        val key = game.key to visible
        synchronized(inflightArtwork) {
            if ((!visible && (game.key to true) in inflightArtwork) || !inflightArtwork.add(key)) return
        }
        viewModelScope.launch {
            try {
                val identity = withContext(Dispatchers.IO) {
                    if (store.artworkFile(game.host.id, game.app.id) != null) null
                    else store.peerIdentity(game.host.id)
                } ?: return@launch
                // The same key allows a new focus request to promote queued background work.
                browse.request("moonlight", "art:${game.key}:$identity",
                    if (visible) BrowseLoadPriority.VISIBLE else BrowseLoadPriority.BACKGROUND) {
                    if (store.artworkFile(game.host.id, game.app.id) == null) {
                        val data = transport.artwork(game.host.id, game.app.id)
                        store.saveArtwork(game.host.id, game.app.id, data, identity)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* No cover is a valid Sunshine catalog case. */ }
            finally { synchronized(inflightArtwork) { inflightArtwork.remove(key) } }
        }
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
                    alreadyAwake = { false })
                if (result == com.ambient.tv.wol.ManualWakeCoordinator.Outcome.AWAKE) {
                    fetch?.join()
                    load(force = true)
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
                if (transport.quitRemoteApp(hostId, appId)) load(force = true)
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

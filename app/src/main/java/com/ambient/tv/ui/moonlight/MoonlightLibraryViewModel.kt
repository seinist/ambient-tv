package com.ambient.tv.ui.moonlight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.browse.BrowseLoadCoordinator
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
    val artworkRevisions: Map<String, Long> = emptyMap(),
    val wakingHosts: Set<String> = emptySet(),
    val quittingHosts: Set<String> = emptySet(),
    val localLoaded: Boolean = false,
) {
    val ready: Boolean get() = localLoaded && (catalogs.values.any { it.isNotEmpty() } || loadingHosts.isEmpty())

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
    private val reconnects: com.ambient.tv.data.browse.BrowseReconnects,
    private val connectivity: com.ambient.tv.connectivity.HostConnectivity,
) : ViewModel() {
    private val mutable = MutableStateFlow(MoonlightLibraryUiState(view = prefs.moonlightLibraryView))
    val state: StateFlow<MoonlightLibraryUiState> = mutable.asStateFlow()
    private val inflightArtwork = HashSet<Pair<String, Boolean>>()
    private val hostSlots = Semaphore(2)
    private var fetch: Job? = null
    private var visible = false
    private var localRevision = 0L
    private val artworkJobs = mutableSetOf<Job>()

    init {
        viewModelScope.launch {
            combine(store.metadataRevisions, store.availabilityRevisions,
                prefs.changes().onStart { emit(Unit) }) { metadata, online, _ ->
                Triple(metadata, online, Triple(prefs.moonlightEnabled, prefs.moonlightHosts, prefs.moonlightLibraryView))
            }.distinctUntilChanged().collectLatest { reloadLocal() }
        }
        viewModelScope.launch { store.artworkChanges.collect { revisions ->
            mutable.update { it.copy(artworkVersion = store.artworkRevisions.value,
                artworkRevisions = revisions) }
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
                pairedHosts = hosts.filter { transport.locallyPaired(it.id) }.map { it.id }.toSet(),
                favorites = store.favorites(), recent = store.recent(),
            )
        }
        if (revision != localRevision) return
        mutable.update { current ->
            current.copy(hosts = snapshot.hosts, catalogs = snapshot.catalogs,
                onlineHosts = snapshot.onlineHosts, pairedHosts = snapshot.pairedHosts,
                favorites = snapshot.favorites, recent = snapshot.recent,
                view = prefs.moonlightLibraryView, localLoaded = true,
                sections = current.sections.takeIf { !it.startsWith("host:") || snapshot.hosts.any { host -> "host:" + host.id == it } } ?: "all")
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

    fun enter() { visible = true; load() }
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

    fun load(force: Boolean = false, onlyHost: String? = null) {
        if (!visible || !transport.available) return
        val previous = fetch
        if (previous?.isActive == true && !force) return
        if (force) previous?.cancel()
        fetch = viewModelScope.launch {
            previous?.join()
            reloadLocal()
            val snapshot = mutable.value
            val paired = snapshot.hosts.filter { (onlyHost == null || it.id == onlyHost) &&
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
                                browse.request("moonlight", "catalog:${host.id}:$identity:${host.host}:${host.port}",
                                    BrowseLoadPriority.VISIBLE) {
                                    val token = store.beginRequest(host.id) ?: throw SecurityException("Moonlight host not paired")
                                    val check = connectivity.begin("moonlight:" + host.id)
                                    try {
                                        val catalog = transport.applications(host.id)
                                        if (prefs.moonlightHosts.any { it == host } && store.putCatalog(host.id, catalog, token))
                                            connectivity.online(check)
                                    } catch (error: SecurityException) {
                                        store.invalidateCatalog(host.id, token)
                                        throw error
                                    } catch (cancel: CancellationException) { throw cancel }
                                    catch (error: Exception) {
                                        store.markOffline(host.id, token)
                                        throw error
                                    }
                                }
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
        }
    }

    fun prioritizeArtwork(game: MoonlightBrowseGame, visible: Boolean = true) {
        if (!this.visible || game.host.id !in mutable.value.onlineHosts) return
        val key = game.key to visible
        synchronized(inflightArtwork) {
            if ((!visible && (game.key to true) in inflightArtwork) || !inflightArtwork.add(key)) return
        }
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val identity = withContext(Dispatchers.IO) {
                    store.peerIdentity(game.host.id)?.takeIf {
                        store.shouldLoadArtwork(game.host.id, game.app.id, it)
                    }
                } ?: return@launch
                // The same key allows a new focus request to promote queued background work.
                browse.request("moonlight", "art:${game.key}:$identity",
                    if (visible) BrowseLoadPriority.VISIBLE else BrowseLoadPriority.BACKGROUND) {
                    if (store.shouldLoadArtwork(game.host.id, game.app.id, identity)) {
                        try {
                            val data = transport.artwork(game.host.id, game.app.id)
                            if (store.saveArtwork(game.host.id, game.app.id, data, identity) == null)
                                store.artworkUnavailable(game.host.id, game.app.id, identity)
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) {
                            store.artworkUnavailable(game.host.id, game.app.id, identity)
                            throw error
                        }
                    }
                }
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

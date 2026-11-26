package com.ambient.tv.ui.moonlight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
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
) : ViewModel() {
    private val mutable = MutableStateFlow(MoonlightLibraryUiState())
    val state: StateFlow<MoonlightLibraryUiState> = mutable.asStateFlow()
    private val inflightArtwork = HashSet<String>()
    private val artworkSlots = Semaphore(2)
    private val hostSlots = Semaphore(2)
    private var fetch: Job? = null

    init { reloadLocal() }

    fun reloadLocal() {
        val hosts = if (prefs.moonlightEnabled && transport.available) prefs.moonlightHosts else emptyList()
        mutable.update { current ->
            current.copy(hosts = hosts,
                catalogs = hosts.associate { it.id to store.catalog(it.id)?.apps.orEmpty() },
                pairedHosts = hosts.filter { transport.locallyPaired(it.id) }.map { it.id }.toSet(),
                favorites = store.favorites(), recent = store.recent())
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
        mutable.update { it.copy(view = value) }
    }

    fun favorite(game: MoonlightBrowseGame) {
        store.toggleFavorite(game.host.id, game.app.id)
        mutable.update { it.copy(favorites = store.favorites()) }
    }

    fun item(hostId: String, appId: Int): MoonlightBrowseGame? {
        val host = prefs.moonlightHosts.firstOrNull { it.id == hostId } ?: return null
        val app = store.catalog(hostId)?.apps?.firstOrNull { it.id == appId } ?: return null
        return MoonlightBrowseGame(host, app, store.favorite(hostId, appId),
            store.recent().indexOf("$hostId:$appId").takeIf { it >= 0 } ?: Int.MAX_VALUE)
    }

    fun artwork(game: MoonlightBrowseGame): File? = store.artworkFile(game.host.id, game.app.id)

    fun load() {
        if (!transport.available || fetch?.isActive == true) return
        reloadLocal()
        val paired = mutable.value.hosts.filter { it.id in mutable.value.pairedHosts }
        fetch = viewModelScope.launch {
            coroutineScope {
                paired.map { host ->
                    async {
                        hostSlots.withPermit {
                            mutable.update { it.copy(loadingHosts = it.loadingHosts + host.id) }
                            try {
                                val catalog = transport.applications(host.id)
                                store.putCatalog(host.id, catalog)
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
                                mutable.update { it.copy(
                                    catalogs = it.catalogs - host.id, onlineHosts = it.onlineHosts - host.id,
                                    errors = it.errors + (host.id to "identity"),
                                ) }
                            } catch (_: Exception) {
                                // Previously authenticated cached titles remain browseable, but not playable.
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

    fun prioritizeArtwork(game: MoonlightBrowseGame) {
        if (game.host.id !in mutable.value.onlineHosts || artwork(game) != null) return
        val key = game.key
        synchronized(inflightArtwork) { if (!inflightArtwork.add(key)) return }
        viewModelScope.launch {
            try {
                artworkSlots.withPermit {
                    if (store.artworkFile(game.host.id, game.app.id) == null) {
                        val identity = store.peerIdentity(game.host.id) ?: return@withPermit
                        val data = transport.artwork(game.host.id, game.app.id)
                        if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            store.saveArtwork(game.host.id, game.app.id, data, identity)
                        } != null) {
                            mutable.update { it.copy(artworkVersion = it.artworkVersion + 1) }
                        }
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
        if (host.macAddress.isBlank() || host.id in mutable.value.onlineHosts) return
        machineWake.wake(com.ambient.tv.wol.ManualWakeRequest(
            system = "moonlight:" + host.id,
            member = "moonlight:" + host.id,
            subject = host.name.ifBlank { host.host },
            host = host.host,
            mac = host.macAddress,
            probePort = host.port,
        ))
    }

    fun quitRemote(hostId: String, appId: Int) {
        if (hostId !in mutable.value.onlineHosts || hostId !in mutable.value.pairedHosts) return
        viewModelScope.launch {
            try {
                if (transport.quitRemoteApp(hostId, appId)) load()
                else mutable.update { it.copy(errors = it.errors + (hostId to "quit_failed")) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutable.update { it.copy(errors = it.errors + (hostId to "quit_failed")) }
            }
        }
    }

    fun recordLaunch(hostId: String, appId: Int) {
        store.recordLaunch(hostId, appId)
        mutable.update { it.copy(recent = store.recent()) }
    }
}

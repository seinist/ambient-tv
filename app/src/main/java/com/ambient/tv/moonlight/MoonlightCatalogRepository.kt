package com.ambient.tv.moonlight

import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.data.browse.BrowseLoadCoordinator
import com.ambient.tv.data.browse.BrowseLoadPriority
import com.ambient.tv.data.local.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

/** Single authenticated catalog path for browse, setup, Home and playback authorization.
 * A lightweight pinned server-info probe renews availability without downloading unchanged apps.
 * Never wakes a host or establishes VPN merely because a browse consumer is present.
 */
@Singleton
class MoonlightCatalogRepository @Inject constructor(
    private val prefs: AppPreferences,
    private val transport: MoonlightSystemTransport,
    private val store: MoonlightLibraryStore,
    private val browse: BrowseLoadCoordinator,
    private val connectivity: HostConnectivity,
) {
    private val slots = Semaphore(2)
    private val hostLocks = Array(32) { kotlinx.coroutines.sync.Mutex() }

    suspend fun artwork(host: MoonlightHost, appId: Int, priority: BrowseLoadPriority): MoonlightArtwork? = withContext(Dispatchers.IO) {
        val identity = store.peerIdentity(host.id) ?: return@withContext null
        fun current() = prefs.moonlightEnabled && prefs.moonlightHosts.any { it == host } &&
            store.peerIdentity(host.id) == identity
        if (!current()) return@withContext null
        if (store.isRecentlyOnline(host.id) && store.shouldLoadArtwork(host.id, appId, identity)) {
            browse.request("moonlight", "art:${host.id}:$appId:$identity", priority) {
                if (current() && store.isRecentlyOnline(host.id) && store.shouldLoadArtwork(host.id, appId, identity)) {
                    try {
                        val bytes = transport.artwork(host.id, appId)
                        if (current() && store.saveArtwork(host.id, appId, bytes, identity) == null)
                            store.artworkUnavailable(host.id, appId, identity)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { if (current()) store.artworkUnavailable(host.id, appId, identity) }
                }
            }
        }
        if (current()) store.artworkInfo(host.id, appId) else null
    }

    suspend fun refresh(host: MoonlightHost, priority: BrowseLoadPriority, forceCatalog: Boolean = false): MoonlightHostCatalog {
        val identity = withContext(Dispatchers.IO) { store.peerIdentity(host.id) }
            ?: throw SecurityException("Moonlight host not paired")
        fun current() = prefs.moonlightEnabled && prefs.moonlightHosts.any { it == host } &&
            store.peerIdentity(host.id) == identity
        if (!transport.available || !current()) throw SecurityException("Moonlight host changed")
        val download = forceCatalog || !store.isCatalogFresh(host.id)
        if (!download && store.isRecentlyOnline(host.id))
            store.catalog(host.id)?.let { return it }
        // Separate lightweight proof from catalog downloads, but serialize the two per host
        // so a late proof never invalidates a newer catalog request token.
        return browse.request("moonlight", "catalog:${host.id}:$identity:${host.host}:${host.port}:$download", priority) {
            hostLocks[(host.id.hashCode() and Int.MAX_VALUE) % hostLocks.size].withLock {
            slots.withPermit {
                if (!current()) throw SecurityException("Moonlight host changed")
                val token = store.beginRequest(host.id) ?: throw SecurityException("Moonlight host not paired")
                val check = connectivity.begin("moonlight:${host.id}")
                try {
                    // Forced refreshes and lightweight probes serialize per endpoint.
                    if (download || !store.isCatalogFresh(host.id)) {
                        val catalog = transport.applications(host.id)
                        if (!current() || !store.putCatalog(host.id, catalog, token))
                            throw SecurityException("Moonlight host changed")
                    } else {
                        val proof = transport.verify(host.id)
                        if (!current() || proof != identity || !store.markOnline(token))
                            throw SecurityException("Moonlight host changed")
                    }
                    connectivity.online(check)
                    store.catalog(host.id) ?: throw SecurityException("Moonlight catalog unavailable")
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: SecurityException) {
                    store.invalidateCatalog(host.id, token)
                    throw error
                } catch (error: Exception) {
                    store.markOffline(host.id, token)
                    connectivity.failure(check)
                    throw error
                }
            }
            }
        }
    }
}

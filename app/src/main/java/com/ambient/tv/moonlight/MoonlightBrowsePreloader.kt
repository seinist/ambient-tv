package com.ambient.tv.moonlight

import com.ambient.tv.data.browse.BrowseLoadPriority
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.games.metadata.SharedGameArtworkPriority
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/** Only configured, pinned peers are prefetched. No wake or untrusted discovery. */
@Singleton
class MoonlightBrowsePreloader @Inject constructor(
    private val prefs: AppPreferences,
    private val store: MoonlightLibraryStore,
    private val repository: MoonlightCatalogRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            combine(prefs.changes().onStart { emit(Unit) }, store.trustRevisions) { _, _ ->
                if (prefs.moonlightEnabled) prefs.moonlightHosts.mapNotNull { host ->
                    store.peerIdentity(host.id)?.let { host to it }
                } else emptyList()
            }.distinctUntilChanged().collectLatest { hosts ->
                if (hosts.isEmpty()) return@collectLatest
                // Keep paired hosts fresh without discovery, WOL or VPN side effects.
                while (true) {
                    coroutineScope {
                        hosts.forEach { (host, _) ->
                            launch {
                                try { repository.refresh(host, BrowseLoadPriority.BACKGROUND) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { /* Keep any identity-bound local cache. */ }
                            }
                        }
                    }
                    kotlinx.coroutines.delay(45_000L)
                }
            }
        }
        scope.launch {
            combine(SharedGameArtworkPriority.demands, store.availabilityRevisions) { demands, revision -> demands to revision }
                .collectLatest { (demands, _) ->
                    coroutineScope {
                        demands.forEachIndexed { index, demand ->
                            val key = when {
                                demand.fileId.startsWith("moonlight:") -> demand.fileId.removePrefix("moonlight:")
                                demand.fileId.startsWith("search:moonlight:") -> demand.fileId.removePrefix("search:moonlight:")
                                else -> return@forEachIndexed
                            }
                            val host = prefs.moonlightHosts.firstOrNull { it.id == key.substringBefore(':') } ?: return@forEachIndexed
                            val appId = key.substringAfter(':').toIntOrNull()?.takeIf { it > 0 } ?: return@forEachIndexed
                            launch {
                                try { repository.artwork(host, appId, if (index == 0) BrowseLoadPriority.VISIBLE else BrowseLoadPriority.BACKGROUND) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { /* Missing cover is normal. */ }
                            }
                        }
                    }
                }
        }
    }
}

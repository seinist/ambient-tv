package com.ambient.tv.data.browse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.repository.PlexRepository
import com.ambient.tv.livetv.LiveTvRegistry
import com.ambient.tv.trailers.TmdbTrailerRepository
import com.ambient.tv.tvheadend.data.TvheadendRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/** Recovery expires source caches once, then notifies only the visible views of those sources. */
@Singleton
class BrowseReconnects @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: AppPreferences,
    private val hosts: HostConnectivity,
    private val plex: Provider<PlexRepository>,
    private val jellyfin: Provider<JellyfinRepository>,
    private val emby: Provider<EmbyRepository>,
    private val liveTv: Provider<LiveTvRegistry>,
    private val tvheadend: Provider<TvheadendRepository>,
    private val trailers: Provider<TmdbTrailerRepository>,
    private val resumeConnectivity: Provider<ResumeConnectivityMonitor>,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = BrowseReconnectRevisions()
    /** Saved view cursors must not collide with revision numbers from a previous process. */
    val epoch: String = java.util.UUID.randomUUID().toString()
    private val mutable = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions = mutable.asStateFlow()
    private val pending = mutableSetOf<String>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val networkRecovery = BrowseNetworkRecovery()
    private var started = false

    init {
        scope.launch {
            for (signal in signals) {
                // A machine may host several services. Combine their simultaneous recovery.
                delay(200)
                val sources = synchronized(pending) { pending.toSet().also { pending.clear() } }
                if (sources.isEmpty()) continue
                expire(sources)
                mutable.value = clock.publish(sources)
            }
        }
    }

    fun request(source: String) = request(setOf(source))

    fun request(sources: Set<String>) {
        if (sources.isEmpty()) return
        synchronized(pending) { pending.addAll(sources) }
        signals.trySend(Unit)
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        networkChanged() // Establish the initial network without announcing a reconnect.
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = networkChanged()
                override fun onLost(network: Network) = networkChanged()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkChanged()
            })
        }.onFailure { Timber.w(it, "Could not observe network recovery for browsing") }
        scope.launch {
            var seen = hosts.states.value.mapValues { it.value.recoveries }
            hosts.states.map { states -> states.mapValues { it.value.recoveries } }.distinctUntilChanged().collect { current ->
                val restored = current.filter { (machine, revision) -> revision > (seen[machine] ?: 0L) }.keys
                seen = current
                // Server managers confirm their services separately. NAS/DLNA have no such manager.
                val sources = hosts.endpoints().filter {
                    it.machine in restored && (it.system.startsWith("files:") || it.system.startsWith("dlna:"))
                }.map { it.system }.toSet()
                request(sources)
            }
        }
        scope.launch { tvheadend.get().revalidated.collect { request("tvheadend") } }
        scope.launch {
            val monitor = resumeConnectivity.get()
            var seen = monitor.recheckGeneration.value
            monitor.recheckGeneration.collect { revision ->
                if (revision == seen) return@collect
                seen = revision
                if (connectivity.activeNetwork != null) {
                    request(setOf("youtube", "spotify", "trailers", "files", "dlna", "iptv"))
                }
            }
        }
    }

    private fun networkChanged() {
        val network = connectivity.activeNetwork
        val internetReady = network?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val sources = networkRecovery.update(network?.toString(), internetReady)
        if (sources.isNotEmpty()) {
            hosts.recheckOffline()
            request(sources)
        }
    }

    private suspend fun expire(sources: Set<String>) {
        fun includes(provider: String) = sources.any { browseSourceMatches(it, setOf(provider)) }
        suspend fun safely(block: suspend () -> Unit) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Timber.w(error, "Could not expire browse data after reconnect") }
        }
        if (includes("plex") && preferences.plexEnabled) safely { plex.get().refreshCaches() }
        if (includes("jellyfin") && preferences.jellyfinEnabled) safely { jellyfin.get().refreshCaches() }
        if (includes("emby") && preferences.embyEnabled) safely { emby.get().refreshCaches() }
        if (includes("trailers")) safely { trailers.get().refreshCaches() }
        if (listOf("plex", "jellyfin", "emby", "tvheadend", "iptv").any(::includes)) {
            safely {
                liveTv.get().configured().filter { repo ->
                    sources.any { browseSourceMatches(it, setOf(repo.source.id, repo.source.system)) }
                }.forEach { repo -> safely { repo.invalidateAll() } }
            }
        }
    }
}

package com.ambient.tv.data.browse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.connectivity.mediaServerProfileSourceId
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.repository.PlexRepository
import com.ambient.tv.livetv.LiveTvRegistry
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.vpn.WireGuardRouteState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/** Entry and recovery expire source caches before visible destinations start fresh reads. */
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
    private val wireGuard: Provider<WireGuardRouteController>,
    private val moonlightStore: Provider<com.ambient.tv.moonlight.MoonlightLibraryStore>,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = BrowseReconnectRevisions()
    /** Saved view cursors must not collide with revision numbers from a previous process. */
    val epoch: String = java.util.UUID.randomUUID().toString()
    private val mutable = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions = mutable.asStateFlow()
    private val refreshQueue = BrowseRefreshQueue()
    private val entryRefreshPolicy = BrowseEntryRefreshPolicy()
    private val sourceLocks = BrowseSourceLocks()
    private val networkRecovery = BrowseNetworkRecovery()
    private var started = false

    init {
        scope.launch {
            for (signal in refreshQueue.signals) {
                // Coalesce simultaneous entries and recovery before any of their fresh reads start.
                delay(200)
                try {
                    refreshQueue.preparePending(sourceLocks, ::expire) { recovered ->
                        if (recovered.isNotEmpty()) mutable.value = clock.publish(recovered)
                        mutable.value
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Timber.w(error, "Could not prepare fresh browse content")
                }
            }
        }
    }

    fun request(source: String) = request(setOf(source))

    fun request(sources: Set<String>) = refreshQueue.recover(sources)

    /** Expire once for entering destinations, without making sidebar/auxiliary observers reload. */
    suspend fun prepareEntry(sources: Set<String>): Map<String, Long> {
        // On a cold start, repository TTLs decide freshness. Repeated navigation within
        // the same minute must not expire caches or refetch identical content.
        val expired = entryRefreshPolicy.sourcesToExpire(sources)
        if (expired.isEmpty()) return revisions.value
        val ready = refreshQueue.enter(expired)
        return try { ready.await() } finally { ready.cancel() }
    }

    /** Watched/progress changes may span linked editions and must finish before automatic expiry. */
    suspend fun <T> withMutation(source: String, action: suspend () -> T): T =
        sourceLocks.withSources(setOf(source), action)

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
                // Active server aliases are confirmed by their managers. Concrete saved profiles,
                // NAS and DLNA instead re-read their own endpoints after host recovery.
                val activeProfiles = setOfNotNull(
                    preferences.activeServerId?.takeIf { preferences.matchesActivePlexEndpoint }
                        ?.let { mediaServerProfileSourceId("plex", it) },
                    preferences.activeJellyfinServerId?.takeIf { preferences.matchesActiveJellyfinEndpoint }
                        ?.let { mediaServerProfileSourceId("jellyfin", it, preferences.jellyfinUserId) },
                    preferences.activeEmbyServerId?.takeIf { preferences.matchesActiveEmbyEndpoint }
                        ?.let { mediaServerProfileSourceId("emby", it, preferences.embyUserId) },
                )
                val sources = hosts.endpoints().filter {
                    it.machine in restored && it.system !in activeProfiles && ':' in it.system &&
                        it.system.substringBefore(':') in setOf("files", "dlna", "plex", "jellyfin", "emby", "moonlight")
                }.map { it.system }.toSet()
                request(sources)
            }
        }
        scope.launch { tvheadend.get().revalidated.collect { request("tvheadend") } }
        scope.launch {
            // An already-configured tunnel can recover its handshake without Android announcing
            // a new network. Revalidate only the systems routed through this VPN, not LAN siblings.
            val route = wireGuard.get()
            var previousUp = route.status.value is WireGuardRouteState.Up
            route.status.collect { state ->
                val up = state is WireGuardRouteState.Up
                if (up && !previousUp) {
                    val selected = buildSet {
                        if (preferences.isConfigured && preferences.wireGuardPlexEnabled) add("plex")
                        if (preferences.isJellyfinConfigured && preferences.wireGuardJellyfinEnabled) add("jellyfin")
                        if (preferences.isEmbyConfigured && preferences.wireGuardEmbyEnabled) add("emby")
                        if (preferences.hasTvheadendEndpoint && preferences.wireGuardTvheadendEnabled) add("tvheadend")
                        preferences.fileSources.filter { it.wireGuard && it.kind != "local" }
                            .forEach { add("files:${it.id}") }
                        val routedHosts = route.tunnelHosts().mapTo(hashSetOf()) { it.trim().lowercase() }
                        hosts.endpoints().filter { it.system.startsWith("dlna:") &&
                            it.host.trim().lowercase() in routedHosts }
                            .forEach { add(it.system) }
                    }
                    if (selected.isNotEmpty()) {
                        hosts.recheckOffline()
                        request(selected)
                    }
                }
                previousUp = up
            }
        }
        scope.launch {
            val monitor = resumeConnectivity.get()
            var seen = monitor.recheckGeneration.value
            monitor.recheckGeneration.collect { revision ->
                if (revision == seen) return@collect
                seen = revision
                if (connectivity.activeNetwork != null) {
                    request(setOf("youtube", "telegram", "spotify", "trailers", "files", "dlna", "iptv"))
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
        // Restore snapshots first: a later disk restore must not revive their old freshness TTLs.
        if (includes("plex") && preferences.plexEnabled) safely { plex.get().let { it.restoreCache(); it.refreshCaches() } }
        if (includes("jellyfin") && preferences.jellyfinEnabled) safely { jellyfin.get().let { it.restoreCache(); it.refreshCaches() } }
        if (includes("emby") && preferences.embyEnabled) safely { emby.get().let { it.restoreCache(); it.refreshCaches() } }
        if (includes("trailers")) safely { trailers.get().refreshCaches() }
        if (includes("moonlight") && com.ambient.tv.BuildConfig.MOONLIGHT_NATIVE && preferences.hasMoonlightHosts)
            preferences.moonlightHosts.filter { host -> sources.any { browseSourceMatches(it, setOf("moonlight:" + host.id)) } }
                .forEach { moonlightStore.get().markOffline(it.id) }
        if (listOf("plex", "jellyfin", "emby", "tvheadend", "iptv").any(::includes)) {
            safely {
                liveTv.get().configured().filter { repo ->
                    sources.any { browseSourceMatches(it, setOf(repo.source.id, repo.source.system)) }
                }.forEach { repo -> safely { repo.invalidateAll() } }
            }
        }
    }
}

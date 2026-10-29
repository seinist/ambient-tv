package com.ambient.tv.livetv

import com.ambient.tv.BuildConfig
import com.ambient.tv.branding.AppBrand
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.connectivity.mediaServerProfileSourceId
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.di.SharedOkHttp
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.tvheadend.data.TvheadendRepository
import com.ambient.tv.util.suspendResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class AvailableLiveTvSource(val source: LiveTvSource, val capabilities: LiveTvCapabilities)

/** Does not change the active VOD server. A repository captures one endpoint and one login. */
@Singleton
class LiveTvRegistry @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val preferences: AppPreferences,
    private val tokens: TokenStore,
    private val tvheadend: TvheadendRepository,
    private val iptv: com.ambient.tv.data.iptv.IptvStore,
    @SharedOkHttp pool: ConnectionPool,
    @SharedOkHttp dispatcher: Dispatcher,
    private val browseDisk: com.ambient.tv.data.browse.BrowseDiskStore,
    hosts: HostConnectivity,
) {
    private val client = OkHttpClient.Builder().connectionPool(pool).dispatcher(dispatcher)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .addInterceptor(hosts.interceptor())
        .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor).build()
    /** IPTV providers redirect (load balancers, tokenised CDNs) and guides are slow to download. */
    // No diagnostic logging: Xtream-style providers put user and password into the URL path.
    private val iptvClient = client.newBuilder().apply { interceptors().clear(); networkInterceptors().clear() }
        .followRedirects(true).followSslRedirects(true)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
    private val instances = mutableMapOf<String, Pair<List<Any?>, LiveTvRepository>>()
    private val browseStates = mutableMapOf<String, Pair<LiveTvRepository, LiveTvBrowseState>>()
    private val known = LiveTvKnownSources()
    private val knownJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val knownSerializer = kotlinx.serialization.builtins.ListSerializer(KnownLiveTvSource.serializer())
    @Volatile private var knownRestored = false
    private val restoreMutex = kotlinx.coroutines.sync.Mutex()

    /** Restored once per process: sources stay in the sidebar after a restart while servers sleep. */
    private suspend fun restoreKnown() {
        if (knownRestored) return
        // A parallel discover waits for the restore instead of answering without the stored sources.
        restoreMutex.withLock {
            if (knownRestored) return
            browseDisk.read(KNOWN_SCOPE, KNOWN_KEY)[KNOWN_KEY]
                ?.let { runCatching { knownJson.decodeFromString(knownSerializer, it) }.getOrNull() }
                ?.let { records ->
                    val ids = configured().map { it.source.id }.toSet()
                    known.restore(records.filter { it.id in ids })
                }
            knownRestored = true
        }
    }

    private fun persistKnown() {
        val snapshot = known.snapshot()
        browseDisk.put(KNOWN_SCOPE, KNOWN_KEY, { true }) { knownJson.encodeToString(knownSerializer, snapshot) }
    }

    @Synchronized fun configured(): List<LiveTvRepository> = buildList {
        fun addServer(system: String, id: String, name: String, host: String, port: Int, https: Boolean, path: String, token: String?, user: String? = null) {
            if (host.isBlank() || token.isNullOrBlank() || (system != "plex" && user.isNullOrBlank())) return
            val sourceId = mediaServerProfileSourceId(system, id, user) ?: return
            val source = LiveTvSource(sourceId, system, name)
            val fingerprint = listOf(source, host, port, https, path, token, user, preferences.clientIdentifier)
            val existing = instances[source.id]?.takeIf { it.first == fingerprint }?.second
            val repository = existing ?: run {
                val base = HttpUrl.Builder().scheme(if (https) "https" else "http")
                    .host(NetworkEndpoint.normalizeHost(host)).port(port)
                    .encodedPath("/" + path.trim('/') + if (path.trim('/').isEmpty()) "" else "/").build()
                val headers = if (system == "plex") mapOf(
                    "X-Plex-Token" to token, "X-Plex-Client-Identifier" to preferences.clientIdentifier,
                    "X-Plex-Product" to AppBrand.name, "X-Plex-Version" to BuildConfig.VERSION_NAME,
                    "X-Plex-Platform" to "Android", "X-Plex-Device" to "Android TV",
                ) else {
                    val auth = "MediaBrowser Client=\"${AppBrand.name}\", Device=\"Android TV\", DeviceId=\"${preferences.clientIdentifier}\", Version=\"${BuildConfig.VERSION_NAME}\", Token=\"$token\""
                    mapOf("Authorization" to auth, "X-Emby-Authorization" to auth, "X-Emby-Token" to token)
                }
                val http = LiveTvHttp(base, headers, client) { preferences.localNetworkOnly }
                if (system == "plex") PlexLiveTvRepository(source, http) { liveTvAudioProfile(context, preferences) }
                else MediaBrowserLiveTvRepository(source, http, requireNotNull(user), preferences.clientIdentifier) { liveTvAudioProfile(context, preferences) }
            }
            instances[source.id] = fingerprint to repository
            add(repository)
        }
        if (preferences.plexEnabled) preferences.serverProfiles.forEach { p ->
            addServer("plex", p.id, p.name, p.host, p.port, p.useHttps, "", tokens.tokenForServer(p.id))
        }
        if (preferences.jellyfinEnabled) preferences.jellyfinServerProfiles.filter { it.authenticated }.forEach { p ->
            addServer("jellyfin", p.id, p.name, p.host, p.port, p.useHttps, p.basePath, tokens.jellyfinTokenForServer(p.id), p.userId)
        }
        if (preferences.embyEnabled) preferences.embyServerProfiles.filter { it.authenticated }.forEach { p ->
            addServer("emby", p.id, p.name, p.host, p.port, p.useHttps, p.basePath, tokens.embyTokenForServer(p.id), p.userId)
        }
        if (preferences.isTvheadendConfigured) {
            val scope = preferences.tvheadendHistoryScope
            val source = LiveTvSource("tvheadend:$scope", "tvheadend", "TVHeadend")
            val fingerprint = listOf(scope, preferences.tvheadendUseHttps, tokens.tvheadendPassword)
            val repository = instances[source.id]?.takeIf { it.first == fingerprint }?.second
                ?: TvheadendLiveTvRepository(source, tvheadend, preferences, scope)
            instances[source.id] = fingerprint to repository
            add(repository)
        }
        iptv.sources().filter { it.enabled }.forEach { config ->
            val source = LiveTvSource("iptv:${config.id}", "iptv", config.name)
            val fingerprint = listOf(config)
            val repository = instances[source.id]?.takeIf { it.first == fingerprint }?.second
                ?: IptvLiveTvRepository(source, config, iptvClient, java.io.File(context.cacheDir, "iptv"))
            instances[source.id] = fingerprint to repository
            add(repository)
        }
    }.also { repos ->
        val ids = repos.map { it.source.id }.toSet()
        instances.keys.retainAll(ids)
        known.retain(ids)
        browseStates.entries.removeAll { (id, state) -> repos.none { it.source.id == id && it === state.first } }
    }

    fun repository(id: String?): LiveTvRepository = configured().firstOrNull {
        if (id.isNullOrBlank()) it.source.system == "tvheadend" else it.source.id == id
    } ?: MissingLiveTvRepository(LiveTvSource(id ?: "tvheadend", id?.substringBefore(':') ?: "tvheadend", "Live TV"))

    /** Settings "Test": loads the playlist with this exact configuration; channel count and whether a guide is known. */
    suspend fun testIptv(config: com.ambient.tv.data.iptv.IptvSource): Pair<Int, Boolean> {
        val repository = IptvLiveTvRepository(LiveTvSource("iptv:test", "iptv", config.name), config, iptvClient,
            java.io.File(context.cacheDir, "iptv-test"))
        val capabilities = repository.capabilities(force = true)
        return repository.channels().size to capabilities.guide
    }

    @Synchronized fun browseState(repository: LiveTvRepository): LiveTvBrowseState {
        return browseStates[repository.source.id]?.takeIf { it.first === repository }?.second
            ?: LiveTvBrowseState().also { browseStates[repository.source.id] = repository to it }
    }

    suspend fun discover(force: Boolean = false): List<AvailableLiveTvSource> = coroutineScope {
        restoreKnown()
        val before = known.snapshot()
        configured().map { repo -> async {
            suspendResult { repo.capabilities(force) }.fold(
                onSuccess = { caps ->
                    if (caps.available) known.available(AvailableLiveTvSource(repo.source, caps))
                    else known.unavailable(repo.source.id)
                },
                onFailure = { known.failed(repo.source.id) }, // An offline source must not jump out of the sidebar.
            )
        } }.mapNotNull { it.await() }.also { if (known.snapshot() != before) persistKnown() }
    }

    private companion object {
        const val KNOWN_SCOPE = "livetv:known"
        const val KNOWN_KEY = "sources"
    }
}

private class MissingLiveTvRepository(override val source: LiveTvSource) : LiveTvRepository {
    override suspend fun capabilities(force: Boolean) = LiveTvCapabilities()
    override suspend fun channels(force: Boolean) = emptyList<LiveTvChannel>()
    override suspend fun epgEvents(startEpochSeconds: Long, windowHours: Int, force: Boolean) = emptyList<LiveTvProgramme>()
    override suspend fun liveStream(channel: LiveTvChannel, profileOverride: String?): LiveTvPlaybackSource = error("TV source is no longer configured.")
    override suspend fun recordingStream(entry: LiveTvRecording): LiveTvPlaybackSource = error("TV source is no longer configured.")
}

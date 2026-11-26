package com.ambient.tv.tvheadend.data

import com.ambient.tv.notifications.*

import com.ambient.tv.util.suspendResult
import com.ambient.tv.data.browse.BrowseWorkBudget
import com.ambient.tv.livetv.LiveTvHbbtvApp
import com.ambient.tv.connectivity.HostHealth
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.tvheadend.domain.TvhAutoRec
import com.ambient.tv.tvheadend.domain.TvhChannel
import com.ambient.tv.tvheadend.domain.TvhDashboard
import com.ambient.tv.tvheadend.domain.TvhDvrConfig
import com.ambient.tv.tvheadend.domain.TvhDvrEntry
import com.ambient.tv.tvheadend.domain.TvhEpgEvent
import com.ambient.tv.tvheadend.domain.TvhPlaybackSource
import com.ambient.tv.tvheadend.domain.TvhServerInfo
import com.ambient.tv.tvheadend.domain.TvhSearchResults
import com.ambient.tv.tvheadend.domain.TvhStreamProfile
import com.ambient.tv.vpn.WireGuardRouteController
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@Singleton
class TvheadendRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val tokenStore: TokenStore,
    private val wireGuardRoute: WireGuardRouteController,
    private val browseDisk: com.ambient.tv.data.browse.BrowseDiskStore,
    private val browseScopes: com.ambient.tv.data.browse.BrowseScopes,
    private val availability: com.ambient.tv.data.browse.SourceAvailability,
    @com.ambient.tv.di.SharedOkHttp private val sharedConnectionPool: okhttp3.ConnectionPool,
    @com.ambient.tv.di.SharedOkHttp private val sharedDispatcher: okhttp3.Dispatcher,
    /** Runs the one TVHeadend wake (state, card, gate); a Provider because it uses this repository. */
    private val connection: javax.inject.Provider<com.ambient.tv.server.TvheadendConnectionManager>,
    private val browseReconnects: javax.inject.Provider<com.ambient.tv.data.browse.BrowseReconnects>,
    private val guideIndex: com.ambient.tv.livetv.ProgrammeGuideIndex,
) {
    /** Last answers per server/account on disk: channels, guide and recordings stay visible offline. */
    private val diskCache = TvheadendDiskCache(BrowseTvheadendCacheStore(browseDisk))
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var revalidateJob: kotlinx.coroutines.Job? = null
    @Volatile private var lastRevalidateAtMs = 0L
    private val _servingCachedData = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Retry hint after a fallback; host reachability is reported independently by HostConnectivity. */
    val servingCachedData: kotlinx.coroutines.flow.StateFlow<Boolean> = _servingCachedData
    private val fallbackCounter = java.util.concurrent.atomic.AtomicLong()
    /** Increases on every read answered with stored data; screens compare it per load. */
    val cacheFallbacks: Long get() = fallbackCounter.get()
    private val _revalidated = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The server answered again after cached data was served; screens reload to get fresh data. */
    val revalidated: kotlinx.coroutines.flow.SharedFlow<Unit> = _revalidated

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cacheMutex = Mutex()
    /** Serialize legacy migration and fresh-window merges from parallel per-channel loads. */
    private val epgPersistMutex = Mutex()
    private val guideFetchMutex = Mutex()
    private data class HbbtvKey(val scope: String, val htspPort: Int)
    private class HbbtvState(val key: HbbtvKey) {
        var directory: TvheadendHbbtvDirectory? = null
        var job: kotlinx.coroutines.Job? = null
        var lastHttpAttempt: Long? = null
        var lastHtspAttempt: Long? = null
    }
    private val hbbtvLock = Any()
    private val hbbtvRestoreMutex = Mutex()
    private var hbbtvState: HbbtvState? = null
    private val _hbbtvChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    internal val hbbtvChanges: kotlinx.coroutines.flow.StateFlow<Long> = _hbbtvChanges
    private val guideEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var guideFreshnessBlocked = false

    private suspend fun freshGuideWindow(scope: String, from: Long, to: Long, ageMs: Long): Boolean =
        !guideFreshnessBlocked && currentGuideScope(scope) &&
            suspendResult { guideIndex.coversFreshWindow(EPG_INDEX_OWNER, scope, from, to, ageMs) }.getOrDefault(false)
    private var guideSyncJob: kotlinx.coroutines.Job? = null
    private val cache = mutableMapOf<String, CacheValue<*>>()
    private var cacheScope: List<String>? = null
    private var cacheGeneration = 0L
    private val requestMutexes = Array(16) { Mutex() }
    @Volatile private var lastReachableAtMs: Long = 0L
    /** Last time the port was seen closed; a server that just came up counts as waking. */
    @Volatile private var lastUnreachableAtMs: Long = 0L
    private val loginRejection = TvheadendLoginRejection()
    /** Shared socket check (2 s, below the 2.5 s wake poll). */
    private val reachabilityProbe = SharedReachabilityProbe(2_000L)

    private val authenticator = TvheadendAuthenticator { preferences.tvheadendUsername to tokenStore.tvheadendPassword }
    private val hostConnectivityInterceptor = hosts.interceptor()

    private val client: OkHttpClient by lazy {
        // API JSON only (streams go through AuthenticatedStreamProxy with its own pool).
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool).dispatcher(sharedDispatcher)
            .addInterceptor(hostConnectivityInterceptor)
            .dns(com.ambient.tv.network.LanOnlyDns({ preferences.localNetworkOnly }))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .authenticator(authenticator)
            .addInterceptor { chain ->
                // No up-front Basic: the authenticator answers the server's challenge, so a Digest
                // server never receives the password in clear text. Only after the server itself
                // asked for Basic is it sent pre-emptively (saves the 401 round trip per request).
                val user = preferences.tvheadendUsername
                val request = if (user.isNotBlank() && authenticator.basicAccepted && chain.request().header("Authorization") == null) {
                    chain.request().newBuilder().header("Authorization", Credentials.basic(user, tokenStore.tvheadendPassword.orEmpty())).build()
                } else chain.request()
                chain.proceed(request)
            }
            .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor)
            // Also covers the authenticator: an answer to another host's challenge is stripped.
            .let(com.ambient.tv.network.CredentialRedirectGuard::install)
            .build()
    }

    /**
     * Basic credentials for consumers outside this OkHttp client (players, image loaders) that
     * cannot answer a Digest challenge themselves; TVHeadend streams and imagecache require them.
     * The value is bound to the configured TVHeadend origin, so image/launcher loaders drop it
     * for any other scheme/host/port (see [com.ambient.tv.network.CredentialScope]).
     */
    fun authHeaders(): Map<String, String> {
        val user = preferences.tvheadendUsername
        val pass = tokenStore.tvheadendPassword
        return if (user.isNotBlank()) {
            val value = Credentials.basic(user, pass.orEmpty())
            runCatching { buildUrl("", emptyMap()) }.getOrNull()?.let { com.ambient.tv.network.CredentialScope.bind(value, it) }
            mapOf("Authorization" to value)
        } else {
            emptyMap()
        }
    }

    /**
     * [explicit] is the settings test button: a 401 there marks the login rejected at once.
     * Automatic checks (Home, pending logins) only confirm or leave the state to the 401 policy.
     */
    /**
     * Set by [com.ambient.tv.server.TvheadendConnectionManager] while the user dismissed an outage
     * ("Continue"): API calls then no longer wake the server on their own, exactly like Plex.
     */
    @Volatile var automaticWakePaused = false

    /** Plain port check for the connection manager: no wake, no cache. */
    suspend fun portReachable(): Boolean = isTvheadendReachable()

    /** One direct serverinfo request, bypassing caches and the offline fallback. */
    suspend fun probeServerInfo(): Result<TvhServerInfo> = suspendResult {
        // No wake here: the connection manager calls this from inside its own wake.
        val obj = getJson("api/serverinfo", wake = false).jsonObject
        TvhServerInfo(name = obj.string("name") ?: "TVHeadend", version = obj.string("sw_version"), apiVersion = obj.int("api_version"))
    }

    /** Required playback API only: no stale fallback, optional admin metadata or implicit WOL. */
    suspend fun probePlaybackAccess(kind: String, id: String): Result<Boolean> = suspendResult {
        val scope = currentCacheScope()
        val path = when (kind) {
            "channel" -> "api/channel/grid"
            "recording" -> "api/dvr/entry/grid_finished"
            else -> return@suspendResult false
        }
        val rows = entries(getJson(path, mapOf("limit" to "9999"), wake = false))
        val allowed = id.isBlank() || rows.any { it.string("uuid") == id } ||
            (kind == "recording" && entries(getJson("api/dvr/entry/grid_failed", mapOf("limit" to "9999"), wake = false))
                .any { it.string("uuid") == id })
        check(scope == currentCacheScope()) { "TV source configuration changed." }
        allowed
    }

    suspend fun testConnection(explicit: Boolean = false): Result<TvhServerInfo> =
        suspendResult { serverInfo(force = true) }.onFailure { error ->
            if (explicit && error is TvheadendHttpException && error.code == 401 &&
                loginRejection.on401(TvheadendLoginRejection.PROBE_PATH, explicit = true, portReachable = true, lastWakeAtMs = 0L, nowMs = System.currentTimeMillis())
            ) {
                preferences.tvheadendLoginState = com.ambient.tv.data.local.TvheadendLoginState.REJECTED
            }
        }

    suspend fun dashboard(force: Boolean = false, preparedChannels: List<TvhChannel>? = null, infoPrepared: Boolean = false): TvhDashboard = coroutineScope {
        val warnings = java.util.concurrent.ConcurrentLinkedQueue<String>()
        suspend fun <T> optional(label: String, block: suspend () -> List<T>): List<T> = suspendResult { block() }.getOrElse {
            warnings.add("$label: ${it.message ?: "Unavailable"}"); emptyList()
        }
        val info = async { serverInfo(force && !infoPrepared) }
        val channels = async { preparedChannels ?: channels(force) }
        val events = async { optional("EPG") { epgEvents(windowHours = 12, force = force) } }
        val upcoming = async { optional("Timers") { upcomingRecordings(force) } }
        val recordings = async { optional("Recordings") { finishedRecordings(force) } }
        val failed = async { optional("Failed recordings") { failedRecordings(force) } }
        val autoRecs = async { optional("Series timers") { autoRecordings(force) } }
        TvhDashboard(
            serverInfo = info.await(),
            channels = channels.await(),
            events = events.await(),
            upcoming = upcoming.await(),
            recordings = recordings.await(),
            failedRecordings = failed.await(),
            autoRecs = autoRecs.await(),
            warnings = warnings.toList(),
        )
    }

    suspend fun search(query: String): TvhSearchResults = coroutineScope {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return@coroutineScope TvhSearchResults()

        val now = System.currentTimeMillis() / 1000L
        val channels = async { channels() }
        val programs = async { epgEvents(startEpochSeconds = now - 3600L, windowHours = 72) }
        val recordings = async { finishedRecordings() }

        TvhSearchResults(
            channels = channels.await()
                .filter { channel -> terms.all { it in listOf(channel.name, channel.number?.toString(), channel.tags.joinToString(" ")).joinToString(" ").lowercase() } }
                .take(24),
            programs = programs.await()
                .filter { event -> terms.all { it in listOfNotNull(event.title, event.subtitle, event.summary, event.description, event.channelName).joinToString(" ").lowercase() } }
                .distinctBy { it.eventId }
                .sortedBy { it.startEpochSeconds }
                .take(24),
            recordings = recordings.await()
                .filter { entry -> terms.all { it in listOfNotNull(entry.title, entry.subtitle, entry.description, entry.channelName).joinToString(" ").lowercase() } }
                .distinctBy { it.uuid }
                .take(24),
        )
    }

    suspend fun serverInfo(force: Boolean = false): TvhServerInfo = cached("server", 60_000, force,
        stale = { scope -> diskCache.read(scope, TvheadendDiskCache.SERVER, TvhServerInfo.serializer()) },
        persist = { scope, value -> diskCache.write(scope, TvheadendDiskCache.SERVER, TvhServerInfo.serializer(), value) },
    ) {
        val obj = getJson("api/serverinfo").jsonObject
        TvhServerInfo(
            name = obj.string("name") ?: "TVHeadend",
            version = obj.string("sw_version"),
            apiVersion = obj.int("api_version"),
        )
    }

    /** The required channel read never waits for either optional HbbTV transport. */
    suspend fun channels(force: Boolean = false): List<TvhChannel> {
        val state = restoreHbbtv()
        var topology: Map<String, List<String>>? = null
        val base = cached("channels", 5 * 60_000, force,
            stale = { scope -> diskCache.read(scope, TvheadendDiskCache.CHANNELS, CHANNEL_LIST) },
            persist = { scope, value -> diskCache.write(scope, TvheadendDiskCache.CHANNELS, CHANNEL_LIST, value) },
        ) { coroutineScope {
            val channelRows = entries(getJson("api/channel/grid", mapOf("limit" to "9999")))
            val tagNames = async { optionalChannelMetadata("api/channeltag/list").let(::tvhTagNames) }
            val services = async {
                optionalChannelMetadata("api/mpegts/service/grid").mapNotNull { row -> row.string("uuid")?.let { it to row } }.toMap()
            }
            val logoMap = suspendResult { channelLogoMap() }
                .onFailure { Timber.w(it, "TVHeadend channel logo playlist unavailable; falling back to channel icon field") }
                .getOrDefault(emptyMap())
            val names = tagNames.await()
            val serviceRows = services.await()
            val mapping = linkedMapOf<String, List<String>>()
            val channels = channelRows.mapNotNull { obj ->
                val uuid = obj.string("uuid") ?: return@mapNotNull null
                if (obj.boolean("enabled") == false) return@mapNotNull null
                val name = obj.string("name") ?: uuid
                val metadata = tvhChannelMetadata(obj, names, serviceRows)
                mapping[uuid] = obj.arrayStrings("services").distinct().sorted()
                TvhChannel(uuid = uuid, name = name, number = obj.numberAsInt("number"),
                    iconUrl = resolveChannelIconUrl(obj, logoMap, uuid, name),
                    tags = metadata.tags, kind = metadata.kind, access = metadata.access)
            }.distinctBy { it.uuid }.sortedWith(compareBy<TvhChannel> { it.number ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
            topology = mapping
            channels
        } }
        if (state != null) synchronized(hbbtvLock) {
            if (validHbbtv(state)) {
                val old = state.directory
                val newTopology = topology
                if (old != null && newTopology != null) {
                    val next = old.withTopology(newTopology)
                    if (next != old) {
                        // A successful previous probe should not delay a newly mapped service.
                        if (old.httpLoadedAt > 0L) state.lastHttpAttempt = null
                        if (old.htspLoadedAt > 0L) state.lastHtspAttempt = null
                        publishHbbtv(state, next)
                    }
                }
            }
        }
        requestHbbtvRefresh()
        return enrichHbbtvChannels(base)
    }

    private fun hbbtvKey(): HbbtvKey? = if (preferences.isTvheadendConfigured && !preferences.tvheadendLoginRejected)
        HbbtvKey(diskScope(), preferences.tvheadendHtspPort) else null

    /** All directory publication and scope retirement are serialized, never across network I/O. */
    private fun validHbbtv(state: HbbtvState): Boolean =
        hbbtvState === state && hbbtvKey() == state.key

    private fun hbbtvChanged() { _hbbtvChanges.value = _hbbtvChanges.value + 1 }

    private suspend fun restoreHbbtv(): HbbtvState? = withContext(Dispatchers.IO) {
        hbbtvRestoreMutex.withLock {
            val key = hbbtvKey() ?: return@withLock null
            val state = synchronized(hbbtvLock) {
                hbbtvState?.takeIf { it.key == key } ?: HbbtvState(key).also {
                    hbbtvState?.job?.cancel()
                    hbbtvState = it
                    hbbtvChanged()
                }
            }
            if (synchronized(hbbtvLock) { validHbbtv(state) && state.directory != null }) return@withLock state
            // Read by explicit hashed scope, without changing the active base channel cache.
            // An obsolete restore must never switch that cache back to an old account.
            val saved = suspendResult {
                browseDisk.read(key.scope, "hbbtv-directory-v1")["hbbtv-directory-v1"]?.let {
                    json.decodeFromString(TvheadendHbbtvDirectory.serializer(), it)
                }
            }.getOrNull()
            val directory = if (saved != null) saved.withHtspPort(key.htspPort) else {
                val legacy = suspendResult {
                    browseDisk.read(key.scope, TvheadendDiskCache.CHANNELS)[TvheadendDiskCache.CHANNELS]?.let {
                        json.decodeFromString(CHANNEL_LIST, it)
                    }
                }.getOrNull()
                TvheadendHbbtvDirectory.fromLegacy(key.htspPort, legacy.orEmpty())
            }
            synchronized(hbbtvLock) {
                if (!validHbbtv(state)) return@withLock null
                publishHbbtv(state, directory)
            }
            state
        }
    }

    /** Read-only restore for a cold sidebar/guide. No endpoint contact or WOL. */
    internal suspend fun restoreCachedHbbtv() { restoreHbbtv() }

    /** Sidebar activation only requires a channel read for a genuinely unknown directory.
     * Ordinary submenu visits and recovery do not refresh logos/tags or join the HbbTV worker. */
    internal suspend fun prepareHbbtvDiscovery() {
        val state = restoreHbbtv() ?: return
        val missing = synchronized(hbbtvLock) { validHbbtv(state) && state.directory?.servicesByChannel.isNullOrEmpty() }
        if (missing) channels() else requestHbbtvRefresh()
    }

    internal fun enrichHbbtvChannels(channels: List<TvhChannel>): List<TvhChannel> = synchronized(hbbtvLock) {
        hbbtvState?.takeIf(::validHbbtv)?.directory?.enrich(channels) ?: channels
    }

    internal fun hasCachedHbbtvApps(): Boolean = synchronized(hbbtvLock) {
        hbbtvState?.takeIf(::validHbbtv)?.directory?.hasMenuApps() == true
    }

    internal fun hbbtvPending(): Boolean = synchronized(hbbtvLock) {
        hbbtvState?.takeIf(::validHbbtv)?.job?.isActive == true
    }

    private fun publishHbbtv(state: HbbtvState, directory: TvheadendHbbtvDirectory) {
        if (!validHbbtv(state) || state.directory == directory) return
        state.directory = directory
        // Separate key: a new ticketed logo/channel snapshot cannot overwrite HbbTV results.
        runCatching {
            browseDisk.put(state.key.scope, "hbbtv-directory-v1", {
                synchronized(hbbtvLock) { validHbbtv(state) && state.directory === directory }
            }) { json.encodeToString(TvheadendHbbtvDirectory.serializer(), directory) }
        }.onFailure { Timber.d(it, "TVHeadend HbbTV cache write deferred") }
        hbbtvChanged()
    }

    /** Single flight per endpoint/account. The caller does not join the optional work. */
    internal fun requestHbbtvRefresh(force: Boolean = false) = synchronized(hbbtvLock) {
        val state = hbbtvState?.takeIf(::validHbbtv) ?: return@synchronized
        val directory = state.directory ?: return@synchronized
        if (directory.servicesByChannel.isEmpty() || state.job?.isActive == true ||
            !availability.hasNetwork() || hosts.health("tvheadend") == HostHealth.OFFLINE) return@synchronized
        fun due(loaded: Long, attempt: Long?) = hbbtvRefreshDue(loaded, attempt,
            System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), force)
        val http = directory.servicesByChannel.values.any { it.isNotEmpty() } && due(directory.httpLoadedAt, state.lastHttpAttempt)
        val htsp = due(directory.htspLoadedAt, state.lastHtspAttempt)
        if (!http && !htsp) return@synchronized
        val topology = directory.servicesByChannel
        val job = background.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[kotlinx.coroutines.Job]
            fun stillCurrent() = synchronized(hbbtvLock) {
                validHbbtv(state) && state.directory?.servicesByChannel == topology
            }
            var probeEpoch: Long? = null
            fun canProbe() = owner?.isActive == true && stillCurrent() &&
                (probeEpoch?.let(BrowseWorkBudget::idleSince) ?: BrowseWorkBudget.idle()) &&
                availability.hasNetwork() && hosts.health("tvheadend") != HostHealth.OFFLINE
            try {
                // Bounded idle wait, not a browse permit. No queue can keep a player waiting.
                val until = android.os.SystemClock.elapsedRealtime() + 30_000L
                while (stillCurrent() && !canProbe() && android.os.SystemClock.elapsedRealtime() < until) delay(250)
                val epoch = BrowseWorkBudget.gameEpoch()
                probeEpoch = epoch
                if (http && canProbe()) {
                    synchronized(hbbtvLock) { state.lastHttpAttempt = android.os.SystemClock.elapsedRealtime() }
                    val result = optionalHbbtvServiceMetadata(topology.values.flatten().toHashSet(), epoch, ::canProbe)
                    synchronized(hbbtvLock) {
                        if (result != null && canProbe()) state.directory?.let {
                            publishHbbtv(state, it.withHttp(result, System.currentTimeMillis()))
                        } else if (stillCurrent() && !canProbe()) state.lastHttpAttempt = null
                    }
                }
                if (htsp && canProbe()) {
                    synchronized(hbbtvLock) { state.lastHtspAttempt = android.os.SystemClock.elapsedRealtime() }
                    val result = optionalHtspHbbtvMetadata(epoch, ::canProbe)
                    synchronized(hbbtvLock) {
                        if (result != null && canProbe()) state.directory?.let {
                            publishHbbtv(state, it.withHtsp(result, System.currentTimeMillis()))
                        } else if (stillCurrent() && !canProbe()) state.lastHtspAttempt = null
                    }
                }
            } finally {
                synchronized(hbbtvLock) {
                    if (hbbtvState === state) { state.job = null; hbbtvChanged() }
                }
            }
        }
        state.job = job
        job.start()
        hbbtvChanged()
    }

    private suspend fun optionalChannelMetadata(path: String): List<JsonObject> =
        withContext(Dispatchers.IO) {
            suspendResult {
                ensureConfiguredAndAllowed()
                // A denied admin-only metadata request must not invalidate a working TV login.
                val request = Request.Builder().url(buildUrl(path, mapOf("limit" to "9999"))).get().build()
                client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) emptyList() else entries(json.parseToJsonElement(response.body.string().orEmpty()))
                }
            }.getOrDefault(emptyList())
        }


    /**
     * The HTTP raw export is administrator-only on many installations. HTSP's channelAdd
     * publishes the same AIT data to normal channel viewers; both sources are optional.
     */
    private val hbbtvHttpClient by lazy {
        // Failure of an optional admin endpoint is not evidence that ordinary TV is offline.
        client.newBuilder().apply { interceptors().remove(hostConnectivityInterceptor) }
            .callTimeout(4, TimeUnit.SECONDS).build()
    }

    private suspend fun optionalHbbtvServiceMetadata(serviceIds: Set<String>, epoch: Long, shouldContinue: () -> Boolean): Map<String, List<LiveTvHbbtvApp>>? =
        withContext(Dispatchers.IO) {
            suspendResult {
                ensureConfiguredAndAllowed()
                check(shouldContinue()) { "HbbTV browse work deferred" }
                val request = Request.Builder()
                    .url(buildUrl("api/raw/export", mapOf("class" to "mpegts_service"))).get().build()
                check(shouldContinue()) { "HbbTV source changed" }
                val call = hbbtvHttpClient.newCall(request)
                BrowseWorkBudget.interruptOnGameStart(call::cancel, epoch) {
                    call.execute().use { response ->
                        check(response.isSuccessful) { "TVHeadend raw metadata HTTP ${response.code}" }
                        val raw = json.parseToJsonElement(response.body.string()) as? JsonArray
                            ?: error("Invalid TVHeadend raw metadata")
                        raw.mapIndexedNotNull { index, element ->
                            if (index % 128 == 0) check(shouldContinue()) { "HbbTV source changed" }
                            val row = element as? JsonObject ?: error("Invalid TVHeadend service")
                            val id = row.string("uuid") ?: return@mapIndexedNotNull null
                            if (id !in serviceIds) return@mapIndexedNotNull null
                            val ait = row["hbbtv"]
                            check(ait == null || ait is JsonObject || ait is JsonArray || ait == kotlinx.serialization.json.JsonNull) {
                                "Invalid TVHeadend AIT metadata"
                            }
                            id to tvhHbbtvApplications(row).map { LiveTvHbbtvApp(it.url, it.name, it.visibility) }
                        }.toMap()
                    }
                }
            }.onFailure { Timber.d(it, "TVHeadend HbbTV raw export unavailable") }.getOrNull()
        }

    private suspend fun optionalHtspHbbtvMetadata(epoch: Long, shouldContinue: () -> Boolean): Map<String, List<LiveTvHbbtvApp>>? =
        withContext(Dispatchers.IO) {
            suspendResult {
                ensureConfiguredAndAllowed()
                check(shouldContinue()) { "HTSP browse work deferred" }
                val host = configuredTvheadendEndpoint().host
                val port = preferences.tvheadendHtspPort
                val username = preferences.tvheadendUsername
                val password = tokenStore.tvheadendPassword.orEmpty()
                check(shouldContinue()) { "HTSP source changed" }
                Socket().use { socket ->
                    // The watcher owns cancellation of this exact probe, including a
                    // blocked connect/read/write. The ordinary HTSP path remains unchanged.
                    BrowseWorkBudget.interruptOnGameStart({ runCatching { socket.close() }; Unit }, epoch) {
                        TvheadendHtspHbbtv.discover(socket, host, port, username,
                            password, shouldContinue = shouldContinue)
                            .mapValues { (_, apps) ->
                                check(shouldContinue()) { "HTSP source changed" }
                                apps.map { LiveTvHbbtvApp(it.url, it.name, it.visibility) }
                            }
                    }
                }
            }.onFailure { Timber.d(it, "TVHeadend HTSP HbbTV metadata unavailable or deferred") }.getOrNull()
        }

    suspend fun epgEvents(
        startEpochSeconds: Long = System.currentTimeMillis() / 1000L - 60L * 60L,
        windowHours: Int = 24,
        force: Boolean = false,
        channelId: String? = null,
    ): List<TvhEpgEvent> {
        val stop = startEpochSeconds + windowHours.coerceIn(1, 72) * 3600L
        val from = Math.floorDiv(startEpochSeconds, 1800L) * 1800L
        val to = Math.floorDiv(stop + 1799L, 1800L) * 1800L
        val scope = diskScope()
        val epoch = guideEpoch.get()
        if (!force && currentGuideScope(scope) &&
            freshGuideWindow(scope, startEpochSeconds, stop, 15 * 60_000L)) {
            indexedEpg(scope, startEpochSeconds, stop, channelId)?.let {
                if (epoch == guideEpoch.get() && currentGuideScope(scope)) return it
            }
        }
        val key = "epg:$channelId:$from:$to"
        return cached(key, 15 * 60_000L, force,
            stale = { storedScope -> indexedEpg(storedScope, from, to, channelId) },
        ) {
            syncGuideWindow(scope, from, to, channelId, wake = true, force = force)
        }.filter { it.stopEpochSeconds >= startEpochSeconds && it.startEpochSeconds <= stop }
    }

    suspend fun cachedGuideEvents(start: Long, hours: Int): List<TvhEpgEvent>? =
        indexedEpg(diskScope(), start, start + hours * 3600L)

    suspend fun cachedChannelGuideEvents(channelId: String, start: Long, hours: Int): List<TvhEpgEvent>? {
        val scope = diskScope()
        val now = System.currentTimeMillis() / 1000L
        val guide = indexedEpg(scope, start, start + hours * 3600L, channelId)
        if (!currentGuideScope(scope)) return null
        if (guide.orEmpty().any { it.isLive(now) }) return guide
        // Home may have loaded mode=now before the rolling guide has caught up.
        val current = diskCache.read(scope, TvheadendDiskCache.NOW, EVENT_LIST)
            ?.firstOrNull { it.channelUuid == channelId && it.isLive(now) }
        if (!currentGuideScope(scope)) return null
        return if (current == null) guide else (listOf(current) + guide.orEmpty()).distinctBy { it.eventId }
    }

    suspend fun cachedChannels(): List<TvhChannel>? {
        val scope = diskScope()
        restoreHbbtv()
        return diskCache.read(scope, TvheadendDiskCache.CHANNELS, CHANNEL_LIST)
            ?.takeIf { currentGuideScope(scope) }?.let(::enrichHbbtvChannels)
    }

    private suspend fun syncGuideWindow(scope: String, from: Long, to: Long, channelId: String?,
        wake: Boolean, force: Boolean = false, maxAgeMs: Long = 15 * 60_000L,
        expectedGameEpoch: Long = BrowseWorkBudget.gameEpoch()): List<TvhEpgEvent> = guideFetchMutex.withLock {
        val epoch = guideEpoch.get()
        val gameEpoch = expectedGameEpoch
        fun valid() = epoch == guideEpoch.get() && currentGuideScope(scope)
        fun canPersist() = valid() && (wake || BrowseWorkBudget.idleSince(gameEpoch))
        check(canPersist()) { "TVHeadend source changed or idle work interrupted" }
        if (!force && freshGuideWindow(scope, from, to, maxAgeMs)) {
            indexedEpg(scope, from, to, channelId)?.let { if (canPersist()) return@withLock it }
        }
        val rows = loadTvhEpgPages(from, to, channelId) { params ->
            currentCoroutineContext().ensureActive()
            check(canPersist()) { "TVHeadend source changed or idle work interrupted" }
            getJson("api/epg/events/grid", params, wake = wake, expectedGameEpoch = gameEpoch)
        }
        check(canPersist()) { "TVHeadend source changed or idle work interrupted" }
        // A malformed response is not authoritative. Do not remove previously cached programmes.
        val events = rows.mapIndexed { index, row ->
            if (index % 128 == 0) {
                currentCoroutineContext().ensureActive()
                check(canPersist()) { "TVHeadend source changed or idle work interrupted" }
            }
            mapEvent(row) ?: error("Invalid TVHeadend programme")
        }
            .filter { it.stopEpochSeconds >= from && it.startEpochSeconds <= to }
        require(events.all { it.stopEpochSeconds > it.startEpochSeconds && (channelId == null || it.channelUuid == channelId) })
        require(events.map { it.eventId }.toSet().size == events.size)
        val persisted = suspendResult {
            epgPersistMutex.withLock {
                check(canPersist()) { "TVHeadend source changed or idle work interrupted" }
                migrateGuideIfNeeded(scope)
                check(canPersist()) { "TVHeadend source changed or idle work interrupted" }
                if (guideFreshnessBlocked) guideIndex.expireSyncWindows(EPG_INDEX_OWNER, scope)
                guideIndex.reconcileWindow(EPG_INDEX_OWNER, scope, events, from, to,
                    System.currentTimeMillis() / 1000L, channelId, valid = ::canPersist)
                if (canPersist()) guideFreshnessBlocked = false
            }
        }
        check(canPersist()) { "TVHeadend source changed or idle work interrupted" }
        // Storage is optional for a live read; even a full/corrupt index must not hide a valid
        // server response. Idle prefetch stops this pass on a persistence failure.
        persisted.exceptionOrNull()?.let {
            Timber.w(it, "TVHeadend programme index write failed")
            if (!wake) throw it
        }
        events
    }

    /** Idle, foreground-only synchronization. Never wake a sleeping server for speculative EPG. */
    @Synchronized fun startGuideSync() {
        if (guideSyncJob?.isActive == true) return
        guideSyncJob = background.launch {
            kotlinx.coroutines.delay(3000)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                if (preferences.isTvheadendConfigured && !preferences.tvheadendLoginRejected &&
                    com.ambient.tv.data.browse.BrowseWorkBudget.idle()) {
                    suspendResult {
                        val scope = diskScope()
                        val gameEpoch = BrowseWorkBudget.gameEpoch()
                        val now = System.currentTimeMillis() / 1000L
                        guideIndex.trimExpired(EPG_INDEX_OWNER, scope, now) {
                            currentGuideScope(scope) && BrowseWorkBudget.idleSince(gameEpoch)
                        }
                        if (!BrowseWorkBudget.idleSince(gameEpoch) ||
                            !availability.hasNetwork() || !serverLikelyReachable()) return@suspendResult
                        // Six-hour aligned windows are bounded; fill the nearest missing window first.
                        val start = Math.floorDiv(now, 21600L) * 21600L
                        for (slot in 0 until 28) {
                            if (!BrowseWorkBudget.idleSince(gameEpoch) || !currentGuideScope(scope)) break
                            val windowStart = start + slot * 21600L
                            val from = maxOf(windowStart, Math.floorDiv(now - 3600L, 1800L) * 1800L)
                            val to = windowStart + 21600L
                            if (to <= from) break
                            val ttl = when (slot) { 0, 1 -> 15 * 60_000L; in 2..3 -> 60 * 60_000L
                                in 4..11 -> 3 * 3600_000L; else -> 6 * 3600_000L }
                            if (!freshGuideWindow(scope, from, to, ttl)) {
                                syncGuideWindow(scope, from, to, null, wake = false, maxAgeMs = ttl,
                                    expectedGameEpoch = gameEpoch)
                                // Yield between windows and re-evaluate foreground work.
                                kotlinx.coroutines.delay(750)
                            }
                        }
                    }.onFailure { Timber.d(it, "TVHeadend idle programme sync deferred") }
                }
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    suspend fun nowEvents(force: Boolean = false): List<TvhEpgEvent> {
        val now = System.currentTimeMillis() / 1000L
        val scope = diskScope()
        val epoch = guideEpoch.get()
        if (!force && currentGuideScope(scope) && freshGuideWindow(scope, now, now + 1, 15 * 60_000L)) {
            indexedEpg(scope, now, now + 1, inclusiveBounds = false)?.let {
                if (epoch == guideEpoch.get() && currentGuideScope(scope)) return it.filter { event -> event.isLive(now) }
            }
        }
        return cached("epg:now", 60_000, force,
        stale = { scope ->
            val now = System.currentTimeMillis() / 1000L
            val stored = diskCache.read(scope, TvheadendDiskCache.NOW, EVENT_LIST)
            val guide = indexedEpg(scope, now, now + 1, inclusiveBounds = false)
            if (stored == null && guide == null) null
            else (stored.orEmpty() + guide.orEmpty()).filter { it.isLive(now) }.distinctBy { it.channelUuid }
        },
        persist = { scope, value -> diskCache.write(scope, TvheadendDiskCache.NOW, EVENT_LIST, value) },
    ) {
        entries(
            getJson(
                "api/epg/events/grid",
                mapOf("mode" to "now", "limit" to "9999"),
            ),
        ).mapNotNull(::mapEvent)
    }
    }

    private fun currentGuideScope(scope: String): Boolean = preferences.tvheadendEnabled &&
        preferences.hasTvheadendEndpoint && diskScope() == scope

    private suspend fun indexedEpg(
        scope: String, from: Long, to: Long, channelId: String? = null, inclusiveBounds: Boolean = true,
    ): List<TvhEpgEvent>? = epgPersistMutex.withLock {
        if (!currentGuideScope(scope)) return@withLock null
        suspendResult {
            val existing = guideIndex.query(EPG_INDEX_OWNER, scope, from, to, channelId, inclusiveBounds)
            if (existing != null) existing else {
                migrateGuideIfNeeded(scope)
                if (!currentGuideScope(scope)) null else guideIndex.query(EPG_INDEX_OWNER, scope, from, to, channelId, inclusiveBounds)
            }
        }.getOrNull()?.takeIf { currentGuideScope(scope) }
    }

    /** Called under epgPersistMutex, including a first forced per-channel refresh after upgrade. */
    private suspend fun migrateGuideIfNeeded(scope: String) {
        if (!currentGuideScope(scope) || guideIndex.metadata(EPG_INDEX_OWNER, scope) != null) return
        // Upgrade the old display snapshot once; subsequent reads query only this channel/window.
        val legacy = diskCache.read(scope, TvheadendDiskCache.EPG, EVENT_LIST) ?: return
        val now = System.currentTimeMillis() / 1000L
        guideIndex.mergeWindow(EPG_INDEX_OWNER, scope, legacy, now, valid = { currentGuideScope(scope) })
        if (!currentGuideScope(scope)) return
        diskCache.write(scope, TvheadendDiskCache.EPG, EVENT_LIST, emptyList())
    }

    suspend fun upcomingRecordings(force: Boolean = false): List<TvhDvrEntry> = cached("dvr:upcoming", 60_000, force,
        stale = { scope -> diskCache.read(scope, "dvr:upcoming", RECORDING_LIST) },
        persist = { scope, value -> diskCache.write(scope, "dvr:upcoming", RECORDING_LIST, value) },
    ) {
        entries(getJson("api/dvr/entry/grid_upcoming", mapOf("limit" to "9999"))).mapNotNull(::mapDvr)
            .sortedBy { it.startEpochSeconds }
    }

    suspend fun finishedRecordings(force: Boolean = false): List<TvhDvrEntry> = cached("dvr:finished", 60_000, force,
        stale = { scope -> diskCache.read(scope, "dvr:finished", RECORDING_LIST) },
        persist = { scope, value -> diskCache.write(scope, "dvr:finished", RECORDING_LIST, value) },
    ) {
        entries(getJson("api/dvr/entry/grid_finished", mapOf("limit" to "9999"))).mapNotNull(::mapDvr)
            .sortedByDescending { it.startEpochSeconds }
    }

    suspend fun failedRecordings(force: Boolean = false): List<TvhDvrEntry> = cached("dvr:failed", 60_000, force,
        stale = { scope -> diskCache.read(scope, "dvr:failed", RECORDING_LIST) },
        persist = { scope, value -> diskCache.write(scope, "dvr:failed", RECORDING_LIST, value) },
    ) {
        entries(getJson("api/dvr/entry/grid_failed", mapOf("limit" to "9999"))).mapNotNull(::mapDvr)
            .sortedByDescending { it.startEpochSeconds }
    }

    /** The DVR profile list is admin-only; without it TVHeadend records with the account's default profile. */
    private suspend fun defaultDvrConfigUuid(): String =
        suspendResult { dvrConfigs() }.getOrDefault(emptyList()).firstOrNull()?.uuid.orEmpty()

    suspend fun dvrConfigs(force: Boolean = false): List<TvhDvrConfig> = cached("dvr:configs", 5 * 60_000, force) {
        entries(getJson("api/dvr/config/grid", mapOf("limit" to "999"))).mapNotNull {
            val uuid = it.string("uuid") ?: return@mapNotNull null
            TvhDvrConfig(uuid, it.string("name").orEmpty().ifBlank { "Default" })
        }
    }


    suspend fun autoRecordings(force: Boolean = false): List<TvhAutoRec> = cached("dvr:autorec", 60_000, force,
        stale = { scope -> diskCache.read(scope, "dvr:autorec", AUTOREC_LIST) },
        persist = { scope, value -> diskCache.write(scope, "dvr:autorec", AUTOREC_LIST, value) },
    ) {
        entries(getJson("api/dvr/autorec/grid", mapOf("limit" to "9999"))).mapNotNull { obj ->
            val uuid = obj.string("uuid") ?: return@mapNotNull null
            TvhAutoRec(
                uuid = uuid,
                name = obj.string("name").orEmpty().ifBlank { obj.localizedString("title") ?: "Series" },
                title = obj.localizedString("title") ?: obj.string("title"),
                channelName = obj.string("channel") ?: obj.string("channelname"),
                enabled = obj.boolean("enabled") != false,
                paddingBeforeMinutes = obj.numberAsInt("start_extra"),
                paddingAfterMinutes = obj.numberAsInt("stop_extra"),
                priority = obj.numberAsInt("pri"),
                canDisable = true,
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** A recording action may read fresh state before writing; entry expiry must wait for both. */
    private suspend fun <T> mutate(block: suspend () -> T): Result<T> =
        browseReconnects.get().withMutation("tvheadend") { suspendResult { block() } }

    suspend fun deleteAutoRecording(uuid: String): Result<Unit> = mutate {
        getJson("api/idnode/delete", mapOf("uuid" to uuid))
        patchCached("dvr:autorec", AUTOREC_LIST) { list -> list.filterNot { it.uuid == uuid } }
        invalidateDvrAndEpg()
    }

    /** Padding (minutes) and priority of a DVR entry, through TVHeadend's generic idnode save. */
    suspend fun updateTimer(uuid: String, edit: com.ambient.tv.livetv.LiveTvTimerEdit): Result<Unit> = mutate {
        getJson("api/idnode/save", mapOf("node" to tvhTimerNode(uuid, edit).toString()))
        patchCached("dvr:upcoming", RECORDING_LIST) { list -> list.map { if (it.uuid == uuid) it.copy(
            paddingBeforeMinutes = edit.paddingBeforeMinutes ?: it.paddingBeforeMinutes,
            paddingAfterMinutes = edit.paddingAfterMinutes ?: it.paddingAfterMinutes,
            priority = edit.priority ?: it.priority,
        ) else it } }
        invalidateDvrAndEpg()
    }

    suspend fun updateAutoRecording(uuid: String, edit: com.ambient.tv.livetv.LiveTvTimerEdit): Result<Unit> = mutate {
        getJson("api/idnode/save", mapOf("node" to tvhTimerNode(uuid, edit).toString()))
        patchCached("dvr:autorec", AUTOREC_LIST) { list -> list.map { if (it.uuid == uuid) it.copy(
            enabled = edit.enabled ?: it.enabled,
            paddingBeforeMinutes = edit.paddingBeforeMinutes ?: it.paddingBeforeMinutes,
            paddingAfterMinutes = edit.paddingAfterMinutes ?: it.paddingAfterMinutes,
            priority = edit.priority ?: it.priority,
        ) else it } }
        invalidateDvrAndEpg()
    }

    suspend fun scheduleRecording(eventId: String): Result<Unit> = mutate {
        val configUuid = defaultDvrConfigUuid()
        val params = buildMap {
            put("event_id", eventId)
            if (configUuid.isNotBlank()) put("config_uuid", configUuid)
        }
        getJson("api/dvr/entry/create_by_event", params)
        invalidateDvrAndEpg()
    }

    suspend fun recordCurrentProgramme(event: TvhEpgEvent): Result<Unit> = mutate {
        val configUuid = defaultDvrConfigUuid()
        val conf = currentProgrammeRecordingConfig(event, System.currentTimeMillis() / 1000, configUuid)
        getJson("api/dvr/entry/create", mapOf("conf" to conf.toString()))
        invalidateDvrAndEpg()
    }

    suspend fun scheduleSeries(eventId: String): Result<Unit> = mutate {
        val configUuid = defaultDvrConfigUuid()
        val params = buildMap {
            put("event_id", eventId)
            if (configUuid.isNotBlank()) put("config_uuid", configUuid)
        }
        getJson("api/dvr/autorec/create_by_series", params)
        invalidateDvrAndEpg()
    }

    suspend fun cancelRecording(uuid: String): Result<Unit> = mutate {
        // Re-read the server state: a scheduled timer may have started since the UI loaded.
        val entry = upcomingRecordings(force = true).firstOrNull { it.uuid == uuid }
            ?: error("Recording is no longer scheduled or running. Refresh the recordings.")
        getJson(recordingTerminationPath(entry.scheduleStatus), mapOf("uuid" to uuid))
        // Patch the stored lists at once; an offline restart must not show the cancelled timer.
        patchCached("dvr:upcoming", RECORDING_LIST) { list -> list.filterNot { it.uuid == uuid } }
        invalidateDvrAndEpg()
    }

    suspend fun deleteRecording(uuid: String): Result<Unit> = mutate {
        getJson("api/dvr/entry/remove", mapOf("uuid" to uuid))
        listOf("dvr:finished", "dvr:failed", "dvr:upcoming").forEach { key ->
            patchCached(key, RECORDING_LIST) { list -> list.filterNot { it.uuid == uuid } }
        }
        invalidateDvrAndEpg()
    }

    suspend fun streamProfiles(force: Boolean = false): List<TvhStreamProfile> = cached("profiles", 5 * 60_000, force) {
        entries(getJson("api/profile/list", mapOf("limit" to "999"))).mapNotNull { obj ->
            // profile/list uses `val` for the user-facing profile name and `key` for UUID.
            // Some TVHeadend builds also expose richer name/uuid/class fields, so keep those
            // as compatible fallbacks.
            val name = obj.string("val") ?: obj.string("name") ?: return@mapNotNull null
            TvhStreamProfile(
                uuid = obj.string("key") ?: obj.string("uuid"),
                name = name,
                profileClass = obj.string("class") ?: obj.string("type"),
                enabled = obj.boolean("enabled") != false,
            )
        }.filter { it.enabled }
    }

    suspend fun liveStream(channel: TvhChannel, profileOverride: String? = null): TvhPlaybackSource {
        ensureConfiguredAndAllowed()
        ensureAwakeIfNeeded()
        // UUIDs are stable and unambiguous. TVHeadend may expose 0 for several unnumbered
        // channels; /stream/channelnumber/0 then answers 403 or selects the wrong service.
        val remain = "stream/channel/${channel.uuid}"
        // TVHeadend's own channel playlists explicitly request the built-in `pass` profile.
        // Relying on the access entry's implicit default can select an audio/transcode profile
        // and, on some installations, produces an H.264 stream without usable parameter sets.
        // Keep an explicitly configured profile authoritative, but make the interoperable MPEG-TS
        // pass-through profile the deterministic default for Android players.
        val profile = profileOverride?.takeIf { it.isNotBlank() }
            ?: preferences.tvheadendStreamProfile.takeIf { it.isNotBlank() }
            ?: "pass"
        val params = mapOf("profile" to profile)
        return TvhPlaybackSource(
            // The server may issue a ticket successfully and then reject that ticket at the
            // stream endpoint (401/403). Direct stream URLs with Basic auth are understood by
            // Media3, VLC and MPV and use the same credentials as the working API requests.
            url = buildUrl(remain, params).toString(),
            headers = authHeaders(),
            title = channel.name,
            isLive = true,
            streamProfile = profile,
        )
    }

    suspend fun recordingStream(entry: TvhDvrEntry): TvhPlaybackSource {
        ensureConfiguredAndAllowed()
        ensureAwakeIfNeeded()
        val relative = entry.url?.trimStart('/').takeUnless { it.isNullOrBlank() } ?: "dvrfile/${entry.uuid}"
        return TvhPlaybackSource(
            url = buildUrl(relative, emptyMap()).toString(),
            headers = authHeaders(),
            title = entry.title,
            subtitle = entry.channelName,
            description = entry.description,
            durationMs = (entry.stopEpochSeconds - entry.startEpochSeconds).coerceAtLeast(0L) * 1000L,
            startPositionMs = (entry.playPositionSeconds ?: 0L) * 1000L,
            isLive = false,
            streamProfile = null,
        )
    }

    /** Resolve channel logos exactly the way TVHeadend exposes them to real clients.
     *
     * `/api/channel/grid` may return `file:/...`, `picon://...` or another source URI in `icon`.
     * `/playlist/ticket/channels` asks TVHeadend to resolve that source through its image cache and
     * returns a short-lived ticketed `/imagecache/<id>` URL that Coil can fetch without having to
     * implement Digest authentication itself. The channel cache lifetime intentionally matches the
     * ticket lifetime closely, so a refreshed dashboard also refreshes logo URLs.
     */
    private suspend fun channelLogoMap(): Map<String, String> = withContext(Dispatchers.IO) {
        ensureConfiguredAndAllowed()
        ensureAwakeIfNeeded()
        val url = buildUrl("playlist/ticket/channels", emptyMap())
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("TVHeadend logo playlist HTTP ${response.code}")
            parseChannelLogoPlaylist(response.body.string().orEmpty(), url)
        }
    }

    private fun parseChannelLogoPlaylist(body: String, baseUrl: HttpUrl): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val attr = Regex("""([A-Za-z0-9_-]+)=\"([^\"]*)\"""")
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("#EXTINF", ignoreCase = true)) return@forEach
            val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val logoRaw = attrs["tvg-logo"] ?: attrs["logo"] ?: return@forEach
            val logo = when {
                logoRaw.startsWith("http://", true) || logoRaw.startsWith("https://", true) -> logoRaw
                // TVHeadend commonly emits imagecache/... without a leading slash. Such paths
                // are server-root relative, not relative to /playlist/ticket/channels.
                else -> absoluteResourceUrl(logoRaw)
            } ?: return@forEach
            attrs["tvg-id"]?.takeIf { it.isNotBlank() }?.let { out[it] = logo }
            line.substringAfter(',', "").trim().takeIf { it.isNotBlank() }?.let { out[it] = logo }
        }
        return out
    }


    private fun resolveChannelIconUrl(obj: JsonObject, logoMap: Map<String, String>, uuid: String, name: String): String? {
        // TVHeadend 4.3 exposes a ready-to-use public URL in channel/grid. Prefer it over
        // playlist parsing because it is already backed by TVHeadend's image cache. Older
        // versions use icon/imagecache or require the ticket playlist fallback.
        logoMap[uuid]?.let { return it }
        logoMap[name]?.let { return it }
        val public = obj.string("iconPublicUrl") ?: obj.string("icon_public_url") ?: obj.string("icon_public")
        absoluteResourceUrl(public)?.let { return it }
        absoluteResourceUrl(obj.string("icon"))?.let { return it }

        // Some TVHeadend releases expose only the source URI (`file:`/`picon:`) in
        // channel/grid even though the same icon is available from their image cache.
        // The channel UUID is the cache key used by that endpoint, so keep this as a
        // final, server-provided fallback rather than showing a broken local file URI.
        return buildUrl("imagecache/$uuid", emptyMap()).toString()
    }

    /** Refresh: everything is expired, not deleted, so it stays available as offline fallback. */
    suspend fun invalidateAll() = cacheMutex.withLock {
        reachabilityProbe.invalidate()
        cache.replaceAll { _, value -> value.copy(createdAtMs = 0L) }
        cacheGeneration++
        lastReachableAtMs = 0L
    }

    private suspend fun invalidateDvrAndEpg() {
        // Fence a guide fetch that began before the acknowledged write, including idle prefetch.
        val scope = diskScope()
        val epoch = guideEpoch.incrementAndGet()
        guideFreshnessBlocked = true
        // The server write already succeeded. Local cache maintenance must not turn it into
        // an apparent failed mutation which the user might then repeat.
        suspendResult {
            epgPersistMutex.withLock {
                guideIndex.expireSyncWindows(EPG_INDEX_OWNER, scope)
                if (epoch == guideEpoch.get()) guideFreshnessBlocked = false
            }
        }.onFailure { Timber.w(it, "TVHeadend programme freshness invalidation failed") }
        cacheMutex.withLock {
            cache.replaceAll { key, value -> if (key.startsWith("dvr:") || key.startsWith("epg:")) value.copy(createdAtMs = 0L) else value }
            cacheGeneration++
        }
    }

    /** TVHeadend disabled: the stored server data is deleted, not only expired. */
    fun clearPersistentCache() {
        val epoch = guideEpoch.incrementAndGet()
        guideFreshnessBlocked = true
        val scopesToClear = synchronized(hbbtvLock) {
            val oldScope = hbbtvState?.key?.scope
            hbbtvState?.job?.cancel()
            hbbtvState = null
            hbbtvChanged()
            val cleared = diskCache.clear(runCatching { diskScope() }.getOrNull())
            if (oldScope != null && oldScope !in cleared) browseDisk.remove(oldScope)
            cleared
        }
        _servingCachedData.value = false
        background.launch {
            suspendResult {
                epgPersistMutex.withLock {
                    scopesToClear.forEach { guideIndex.removeScope(EPG_INDEX_OWNER, it) }
                    if (epoch == guideEpoch.get()) guideFreshnessBlocked = false
                }
            }.onFailure { Timber.w(it, "TVHeadend guide clear failed") }
            cacheMutex.withLock { cache.clear(); cacheGeneration++ }
        }
    }

    /** Applies a confirmed change to the memory and disk copy of one list (kept expired). */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> patchCached(key: String, serializer: kotlinx.serialization.KSerializer<List<T>>, transform: (List<T>) -> List<T>) {
        cacheMutex.withLock {
            cache[key]?.let { cache[key] = CacheValue(0L, transform(it.value as List<T>)) }
        }
        runCatching {
            val scope = diskScope()
            diskCache.read(scope, key, serializer)?.let { diskCache.write(scope, key, serializer, transform(it)) }
        }
    }

    private fun diskScope(): String = browseScopes.forProvider("tvheadend")

    /** Without a recent answer, a short socket check decides whether to wait for the server at all. */
    private suspend fun serverLikelyReachable(): Boolean =
        System.currentTimeMillis() - lastReachableAtMs < 10_000L || isTvheadendReachable()

    /** One background wake/reachability attempt per minute; success tells screens to reload. */
    private fun scheduleRevalidate() {
        val now = System.currentTimeMillis()
        if (revalidateJob?.isActive == true || now - lastRevalidateAtMs < 60_000L) return
        lastRevalidateAtMs = now
        revalidateJob = background.launch {
            runCatching {
                ensureConfiguredAndAllowed()
                ensureAwakeIfNeeded()
                if (isTvheadendReachable()) {
                    lastReachableAtMs = System.currentTimeMillis()
                    _revalidated.tryEmit(Unit)
                }
            }
        }
    }

    private fun mapEvent(obj: JsonObject): TvhEpgEvent? {
        val eventId = obj.long("eventId") ?: obj.long("event_id") ?: return null
        val channelUuid = obj.string("channelUuid") ?: obj.string("channel") ?: return null
        val start = obj.long("start") ?: return null
        val stop = obj.long("stop") ?: return null
        return TvhEpgEvent(
            eventId = eventId.toString(),
            channelUuid = channelUuid,
            channelName = obj.string("channelName") ?: obj.string("channelname").orEmpty(),
            title = obj.localizedString("title") ?: obj.string("title") ?: "–",
            subtitle = obj.localizedString("subtitle") ?: obj.string("subtitle"),
            summary = obj.localizedString("summary") ?: obj.string("summary"),
            description = obj.localizedString("description") ?: obj.string("description"),
            startEpochSeconds = start,
            stopEpochSeconds = stop,
            imageUrl = absoluteResourceUrl(obj.string("image")),
            seriesLink = obj.string("serieslink") ?: obj.string("seriesLink"),
            dvrUuid = obj.string("dvrUuid") ?: obj.string("dvr_uuid"),
        )
    }

    private fun mapDvr(obj: JsonObject): TvhDvrEntry? {
        val uuid = obj.string("uuid") ?: return null
        return TvhDvrEntry(
            uuid = uuid,
            channelUuid = obj.string("channel"),
            channelName = obj.string("channelname") ?: obj.string("channel_name").orEmpty(),
            title = obj.string("disp_title") ?: obj.localizedString("title") ?: "–",
            subtitle = obj.string("disp_subtitle") ?: obj.localizedString("subtitle"),
            description = obj.string("disp_description") ?: obj.string("disp_summary") ?: obj.localizedString("description") ?: obj.localizedString("summary"),
            startEpochSeconds = obj.long("start") ?: 0L,
            stopEpochSeconds = obj.long("stop") ?: 0L,
            status = obj.string("status"),
            scheduleStatus = obj.string("sched_status"),
            url = obj.string("url"),
            imageUrl = absoluteResourceUrl(obj.string("image")),
            fanartUrl = absoluteResourceUrl(obj.string("fanart_image")),
            fileSizeBytes = obj.long("filesize"),
            playPositionSeconds = obj.long("playposition"),
            broadcastEventId = obj.long("broadcast")?.toString(),
            paddingBeforeMinutes = obj.numberAsInt("start_extra"),
            paddingAfterMinutes = obj.numberAsInt("stop_extra"),
            priority = obj.numberAsInt("pri"),
        )
    }

    private suspend fun getJson(path: String, params: Map<String, String> = emptyMap(), wake: Boolean = true,
        expectedGameEpoch: Long = BrowseWorkBudget.gameEpoch()): JsonElement {
        val accessScope = browseScopes.forProvider("tvheadend")
        ensureConfiguredAndAllowed()
        if (wake) ensureAwakeIfNeeded()
        return try { withContext(Dispatchers.IO) {
            val url = buildUrl(path, params)
            val request = Request.Builder().url(url).get().build()
            val speculative = !wake && path == "api/epg/events/grid"
            // Cancelling optional work must not be mistaken for a failed server connection.
            val requestClient = if (speculative)
                client.newBuilder().apply { interceptors().remove(hostConnectivityInterceptor) }
                    .callTimeout(5, TimeUnit.SECONDS).build() else client
            val call = requestClient.newCall(request)
            suspend fun decode(response: okhttp3.Response): JsonElement {
                if (speculative) check(BrowseWorkBudget.idleSince(expectedGameEpoch)) { "Idle guide work interrupted" }
                if (!response.isSuccessful) {
                    timber.log.Timber.w("TVHeadend API %s returned HTTP %d", path, response.code)
                    // A 401 alone does not hide TVHeadend: right after a wake the port opens before
                    // the login works, and other paths answer 401 for a missing right. The policy
                    // rejects only on repeated serverinfo 401s on a settled, reachable server.
                    if (response.code == 401 && path == TvheadendLoginRejection.PROBE_PATH) {
                        val now = System.currentTimeMillis()
                        val lastWake = maxOf(com.ambient.tv.wol.WolManager.lastWakeBurstAtMs, lastUnreachableAtMs)
                        if (loginRejection.on401(path, explicit = false, portReachable = isTvheadendReachable(), lastWakeAtMs = lastWake, nowMs = now)) {
                            timber.log.Timber.w("TVHeadend login rejected after repeated HTTP 401")
                            preferences.tvheadendLoginState = com.ambient.tv.data.local.TvheadendLoginState.REJECTED
                        }
                    }
                    throw TvheadendHttpException(response.code, "TVHeadend HTTP ${response.code} ${response.message}")
                }
                // Optional EPG prefetch is deliberately not an authority for the
                // foreground login, cached-data or reachability state. A game can
                // invalidate its result even after an otherwise successful response.
                if (!speculative) {
                    loginRejection.onSuccess()
                    _servingCachedData.value = false
                    preferences.tvheadendLoginState = com.ambient.tv.data.local.TvheadendLoginState.VERIFIED
                    lastReachableAtMs = System.currentTimeMillis()
                }
                val body = response.body.string().orEmpty()
                if (speculative) check(BrowseWorkBudget.idleSince(expectedGameEpoch)) { "Idle guide work interrupted" }
                val decoded = if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)
                if (speculative) check(BrowseWorkBudget.idleSince(expectedGameEpoch)) { "Idle guide work interrupted" }
                return decoded
            }
            if (speculative) {
                BrowseWorkBudget.interruptOnGameStart(call::cancel, expectedGameEpoch) {
                    call.execute().use { decode(it) }
                }
            } else call.execute().use { decode(it) }
        } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            // An optional/admin-only API error must not revoke working channel access: a recording
            // list only counts when the server itself failed (transport, 5xx, 401), not for 403/404.
            val serverFailure = error !is TvheadendHttpException || error.code >= 500 || error.code == 401
            if ((path == "api/channel/grid" || (path.startsWith("api/dvr/entry/grid_") && serverFailure)) &&
                accessScope == browseScopes.forProvider("tvheadend")) availability.accessFailed("tvheadend")
            throw error
        }
    }

    /**
     * Full connect for an entry point that skips Home (a launcher channel): VPN route, then
     * Wake-on-LAN with the long wait and its notices, then a reachability check.
     */
    suspend fun ensureReachable(): Boolean {
        if (!preferences.isTvheadendConfigured) return false
        wireGuardRoute.ensureForTvheadend().getOrElse { return false }
        if (runCatching { ensureAwakeIfNeeded() }.isFailure) return false
        return isTvheadendReachable().also { if (it) lastReachableAtMs = System.currentTimeMillis() }
    }

    /**
     * Wake TVHeadend before an API call. The wake itself is the connection manager's: one run per
     * service with its state, card and gate, shared with every other caller - not a second wake
     * of its own next to it. It ends only once TVHeadend's API answers, not merely its port.
     */
    private suspend fun ensureAwakeIfNeeded() {
        if (preferences.tvheadendMacAddress.isNullOrBlank()) return
        if (!preferences.tvheadendAutoWakeEnabled || automaticWakePaused) return
        if (System.currentTimeMillis() - lastReachableAtMs < 10_000L) return
        if (isTvheadendReachable()) {
            lastReachableAtMs = System.currentTimeMillis()
            return
        }
        // Not connected yet the port opened (login refused, …): let the call report its own error.
        if (connection.get().ensureConnected() || isTvheadendReachable()) {
            lastReachableAtMs = System.currentTimeMillis()
            return
        }
        throw TvheadendWakeTimeoutException(context.getString(com.ambient.tv.R.string.tvh_wake_timeout))
    }

    private suspend fun isTvheadendReachable(): Boolean = withContext(Dispatchers.IO) {
        val endpoint = runCatching { configuredTvheadendEndpoint() }.getOrNull()
        val check = hosts.begin("tvheadend")
        reachabilityProbe.check(endpoint?.host to preferences.tvheadendPort) {
            runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress(endpoint!!.host, preferences.tvheadendPort), 900) }
                true
            }.getOrDefault(false)
        }.also { if (it) hosts.online(check) else { hosts.failure(check); lastUnreachableAtMs = System.currentTimeMillis() } }
    }

    private fun buildUrl(path: String, params: Map<String, String>): HttpUrl {
        val endpoint = configuredTvheadendEndpoint()
        val builder = HttpUrl.Builder()
            .scheme(endpoint.scheme)
            .host(endpoint.host)
            .port(preferences.tvheadendPort)
        if (endpoint.rootSuffix.isNotBlank()) {
            endpoint.rootSuffix.split('/').filter { it.isNotBlank() }.forEach(builder::addPathSegment)
        }
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach(builder::addPathSegment)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun absoluteResourceUrl(path: String?): String? {
        val value = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return runCatching {
                val parsed = value.toHttpUrl()
                if (parsed.host == "localhost" || parsed.host == "127.0.0.1" || parsed.host == "::1") {
                    val configured = buildUrl("", emptyMap())
                    parsed.newBuilder().scheme(configured.scheme).host(configured.host).port(configured.port).build().toString()
                } else value
            }.getOrDefault(value)
        }
        // With "Image caching" disabled in TVHeadend's own settings, channel/EPG "icon" fields
        // come back as a raw path on the *server's* local filesystem (e.g.
        // "file:/home/hts/.hts/tvheadend/logos/x.png") instead of a web-servable imagecache/<id>
        // path. There's no HTTP endpoint that serves an arbitrary local file, so treat these as
        // unavailable rather than building a URL that will always 404.
        if (value.startsWith("file:") || value.startsWith("picon:")) return null
        if (value.startsWith("imagecache:", ignoreCase = true)) {
            return buildUrl("imagecache/${value.substringAfter(':').trimStart('/')}", emptyMap()).toString()
        }
        return runCatching {
            val endpoint = configuredTvheadendEndpoint()
            val base = HttpUrl.Builder()
                .scheme(endpoint.scheme)
                .host(endpoint.host)
                .port(preferences.tvheadendPort)
                .apply {
                    endpoint.rootSuffix.split('/').filter { it.isNotBlank() }.forEach(::addPathSegment)
                    // Keep a directory-style trailing slash so resolve() appends relative
                    // imagecache paths instead of replacing the final root segment.
                    addPathSegment("")
                }
                .build()
            base.resolve(value.trimStart('/'))?.toString()
        }.getOrNull()
    }

    private suspend fun ensureConfiguredAndAllowed() = withContext(Dispatchers.IO) {
        // The address is enough to try: a successful call is what confirms the login.
        check(preferences.hasTvheadendEndpoint) { context.getString(com.ambient.tv.R.string.tvh_not_configured) }
        val host = configuredTvheadendEndpoint().host
        if (preferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(host)) {
            // Only a host that resolves to a non-LAN address is "outside the local network". A name
            // that does not resolve at all goes on to the request, which then reports the real DNS
            // failure instead of a misleading LAN-only rejection; nothing leaves the device either way.
            val addresses = LocalNetworkPolicy.resolvedAddresses(host)
            if (addresses.isNotEmpty()) {
                throw TvheadendNotLocalException(context.getString(com.ambient.tv.R.string.tvh_host_not_local), host, addresses)
            }
        }
        wireGuardRoute.ensureForTvheadend().getOrElse { error ->
            throw TvheadendVpnException(error.message ?: context.getString(com.ambient.tv.R.string.wg_failed), error)
        }
    }

    private fun configuredTvheadendEndpoint(): ConfiguredEndpoint {
        val configured = preferences.tvheadendHost.trim()
        val scheme = TvheadendUrlScheme.resolve(configured, preferences.tvheadendUseHttps)
        val schemeIndex = configured.indexOf("://")
        val withoutScheme = (if (schemeIndex >= 0) configured.substring(schemeIndex + 3) else configured).trimEnd('/')
        val rootSuffix = withoutScheme.substringAfter('/', "").trim('/')
        val host = NetworkEndpoint.extractHost(configured)
        require(host.isNotBlank()) { "TVHeadend host is blank" }
        return ConfiguredEndpoint(scheme = scheme, host = host, rootSuffix = rootSuffix)
    }

    private data class ConfiguredEndpoint(
        val scheme: String,
        val host: String,
        val rootSuffix: String,
    )

    private fun currentCacheScope(): List<String> = listOf(
        preferences.tvheadendHost, preferences.tvheadendPort.toString(),
        preferences.tvheadendHtspPort.toString(), preferences.tvheadendUseHttps.toString(),
        preferences.tvheadendUsername, tokenStore.tvheadendPassword.orEmpty(),
    )

    /**
     * Stale-while-revalidate: a fresh memory value is returned as is. An expired or stored (disk)
     * value is returned at once when the server does not answer the socket check, while a single
     * background attempt wakes it ([revalidated] then asks screens to reload); no caller waits for
     * Wake-on-LAN just to show cached data. A failed load also falls back to it, except for a
     * rejected login. [force] always asks the server (refresh, re-read before a mutation).
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(
        key: String,
        ttlMs: Long,
        force: Boolean,
        stale: (suspend (String) -> T?)? = null,
        persist: (suspend (String, T) -> Unit)? = null,
        loader: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        check(preferences.hasTvheadendEndpoint) { context.getString(com.ambient.tv.R.string.tvh_not_configured) }
        requestMutexes[(key.hashCode() and Int.MAX_VALUE) % requestMutexes.size].withLock {
            val scope = currentCacheScope()
            val persistedScope = diskScope()
            var expired: T? = null
            val generation = cacheMutex.withLock {
                if (cacheScope != scope) {
                    cache.clear()
                    cacheScope = scope
                    cacheGeneration++
                    lastReachableAtMs = 0L
                }
                val hit = cache[key]
                if (!force && hit != null && System.currentTimeMillis() - hit.createdAtMs < ttlMs) return@withContext hit.value as T
                if (hit != null) expired = hit.value as T
                cacheGeneration
            }
            suspend fun ensureCurrentScope() = cacheMutex.withLock {
                check(preferences.hasTvheadendEndpoint && scope == currentCacheScope() && generation == cacheGeneration) {
                    "TVHeadend configuration changed; please retry"
                }
            }
            val fallback: T? = if (force) null else expired ?: runCatching { stale?.invoke(persistedScope) }.getOrNull()
            ensureCurrentScope()
            if (fallback != null && !serverLikelyReachable()) {
                ensureCurrentScope()
                _servingCachedData.value = true
                fallbackCounter.incrementAndGet()
                scheduleRevalidate()
                return@withContext fallback
            }
            val value = try {
                loader()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (fallback == null || !tvhStoredDataMayReplace(error)) throw error
                ensureCurrentScope()
                Timber.i("TVHeadend %s unavailable (%s); showing stored data", key, error.javaClass.simpleName)
                // Offline only when the server is gone; one failing endpoint keeps the rest usable.
                if (tvhFallbackMarksOffline(error)) {
                    _servingCachedData.value = true
                    fallbackCounter.incrementAndGet()
                    scheduleRevalidate()
                }
                return@withContext fallback
            }
            currentCoroutineContext().ensureActive()
            cacheMutex.withLock {
                check(scope == currentCacheScope() && generation == cacheGeneration) { "TVHeadend configuration changed; please retry" }
                cache[key] = CacheValue(System.currentTimeMillis(), value)
                // EPG keys include a moving time window; bound them for long-running TV sessions.
                if (cache.size > 64) cache.minByOrNull { it.value.createdAtMs }?.key?.let(cache::remove)
            }
            runCatching { persist?.invoke(persistedScope, value) }
            value
        }
    }

    private fun entries(root: JsonElement): List<JsonObject> = root.jsonObject["entries"]
        ?.let { it as? JsonArray }
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.numberAsInt(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
    private fun JsonObject.arrayStrings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    private fun JsonObject.localizedString(key: String): String? {
        val value = this[key] ?: return null
        if (value is JsonPrimitive) return value.contentOrNull
        val map = value as? JsonObject ?: return null
        return map.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.firstOrNull { it.isNotBlank() }
    }

    private data class CacheValue<T>(val createdAtMs: Long, val value: T)
}

private val CHANNEL_LIST = kotlinx.serialization.builtins.ListSerializer(com.ambient.tv.livetv.LiveTvChannel.serializer())
private const val EPG_INDEX_OWNER = "tvheadend"
private val EVENT_LIST = kotlinx.serialization.builtins.ListSerializer(com.ambient.tv.livetv.LiveTvProgramme.serializer())
private val RECORDING_LIST = kotlinx.serialization.builtins.ListSerializer(com.ambient.tv.livetv.LiveTvRecording.serializer())
private val AUTOREC_LIST = kotlinx.serialization.builtins.ListSerializer(com.ambient.tv.livetv.LiveTvSeriesTimer.serializer())

/** A sleeping or failing server may be answered from stored data; a rejected login may not. */
internal fun tvhStoredDataMayReplace(error: Throwable): Boolean =
    !(error is TvheadendHttpException && (error.code == 401 || error.code == 403))

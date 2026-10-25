package com.ambient.tv.tvheadend.data

import com.ambient.tv.notifications.*

import com.ambient.tv.util.suspendResult
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
    browseDisk: com.ambient.tv.data.browse.BrowseDiskStore,
    private val browseScopes: com.ambient.tv.data.browse.BrowseScopes,
    /** Runs the one TVHeadend wake (state, card, gate); a Provider because it uses this repository. */
    private val connection: javax.inject.Provider<com.ambient.tv.server.TvheadendConnectionManager>,
) {
    /** Last answers per server/account on disk: channels, guide and recordings stay visible offline. */
    private val diskCache = TvheadendDiskCache(BrowseTvheadendCacheStore(browseDisk))
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var revalidateJob: kotlinx.coroutines.Job? = null
    @Volatile private var lastRevalidateAtMs = 0L
    private val _servingCachedData = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** True while answers come from the cache because the server is asleep or unreachable. */
    val servingCachedData: kotlinx.coroutines.flow.StateFlow<Boolean> = _servingCachedData
    private val _revalidated = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The server answered again after cached data was served; screens reload to get fresh data. */
    val revalidated: kotlinx.coroutines.flow.SharedFlow<Unit> = _revalidated

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cacheMutex = Mutex()
    /** The stored guide is read, merged and written by parallel per-channel loads. */
    private val epgPersistMutex = Mutex()
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

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(hosts.interceptor())
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

    suspend fun testConnection(explicit: Boolean = false): Result<TvhServerInfo> =
        suspendResult { serverInfo(force = true) }.onFailure { error ->
            if (explicit && error is TvheadendHttpException && error.code == 401 &&
                loginRejection.on401(TvheadendLoginRejection.PROBE_PATH, explicit = true, portReachable = true, lastWakeAtMs = 0L, nowMs = System.currentTimeMillis())
            ) {
                preferences.tvheadendLoginState = com.ambient.tv.data.local.TvheadendLoginState.REJECTED
            }
        }

    suspend fun dashboard(): TvhDashboard = coroutineScope {
        val info = async { serverInfo() }
        val channels = async { channels() }
        val events = async { suspendResult { epgEvents(windowHours = 12) }.getOrDefault(emptyList()) }
        val upcoming = async { suspendResult { upcomingRecordings() }.getOrDefault(emptyList()) }
        val recordings = async { suspendResult { finishedRecordings() }.getOrDefault(emptyList()) }
        val failed = async { suspendResult { failedRecordings() }.getOrDefault(emptyList()) }
        val autoRecs = async { suspendResult { autoRecordings() }.getOrDefault(emptyList()) }
        TvhDashboard(
            serverInfo = info.await(),
            channels = channels.await(),
            events = events.await(),
            upcoming = upcoming.await(),
            recordings = recordings.await(),
            failedRecordings = failed.await(),
            autoRecs = autoRecs.await(),
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

    suspend fun channels(force: Boolean = false): List<TvhChannel> = cached("channels", 5 * 60_000, force,
        stale = { scope -> diskCache.read(scope, TvheadendDiskCache.CHANNELS, CHANNEL_LIST) },
        persist = { scope, value -> diskCache.write(scope, TvheadendDiskCache.CHANNELS, CHANNEL_LIST, value) },
    ) { coroutineScope {
        val channelRows = entries(getJson("api/channel/grid", mapOf("limit" to "9999")))
        // Optional metadata must not make channels unavailable to non-admin accounts.
        val tagNames = async { optionalChannelMetadata("api/channeltag/list").let(::tvhTagNames) }
        val services = async {
            optionalChannelMetadata("api/mpegts/service/grid").mapNotNull { row -> row.string("uuid")?.let { it to row } }.toMap()
        }
        val logoMap = suspendResult { channelLogoMap() }
            .onFailure { Timber.w(it, "TVHeadend channel logo playlist unavailable; falling back to channel icon field") }
            .getOrDefault(emptyMap())

        val names = tagNames.await()
        val serviceRows = services.await()
        channelRows.mapNotNull { obj ->
            val uuid = obj.string("uuid") ?: return@mapNotNull null
            if (obj.boolean("enabled") == false) return@mapNotNull null
            val name = obj.string("name") ?: uuid
            val metadata = tvhChannelMetadata(obj, names, serviceRows)
            TvhChannel(
                uuid = uuid,
                name = name,
                number = obj.numberAsInt("number"),
                // Prefer TVHeadend's own playlist-generated logo URL. TVHeadend resolves local
                // picon/file paths through /imagecache/<id> here; the raw API `icon` value often
                // points at the server filesystem and cannot be loaded by an Android client.
                iconUrl = resolveChannelIconUrl(obj, logoMap, uuid, name),
                tags = metadata.tags,
                kind = metadata.kind,
                access = metadata.access,
            )
        }.distinctBy { it.uuid }.sortedWith(compareBy<TvhChannel> { it.number ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
    } }

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

    suspend fun epgEvents(
        startEpochSeconds: Long = System.currentTimeMillis() / 1000L - 60L * 60L,
        windowHours: Int = 24,
        force: Boolean = false,
        channelId: String? = null,
    ): List<TvhEpgEvent> {
        val stop = startEpochSeconds + windowHours.coerceIn(1, 72) * 3600L
        val key = "epg:$channelId:${startEpochSeconds / 300}:$windowHours"
        return cached(key, 10 * 60_000, force,
            stale = { scope ->
                diskCache.read(scope, TvheadendDiskCache.EPG, EVENT_LIST)?.filter {
                    it.stopEpochSeconds >= startEpochSeconds && it.startEpochSeconds <= stop && (channelId == null || it.channelUuid == channelId)
                }
            },
            persist = { scope, events -> epgPersistMutex.withLock {
                val now = System.currentTimeMillis() / 1000L
                val stored = diskCache.read(scope, TvheadendDiskCache.EPG, EVENT_LIST).orEmpty()
                diskCache.write(scope, TvheadendDiskCache.EPG, EVENT_LIST, TvheadendDiskCache.mergeEpg(stored, events, now))
            } },
        ) {
            loadTvhEpgPages(startEpochSeconds, stop, channelId) { params -> getJson("api/epg/events/grid", params) }
                .mapNotNull(::mapEvent).filter { it.stopEpochSeconds >= startEpochSeconds && it.startEpochSeconds <= stop }
        }
    }

    suspend fun nowEvents(force: Boolean = false): List<TvhEpgEvent> = cached("epg:now", 60_000, force,
        stale = { scope ->
            val now = System.currentTimeMillis() / 1000L
            val stored = diskCache.read(scope, TvheadendDiskCache.NOW, EVENT_LIST)
            val guide = diskCache.read(scope, TvheadendDiskCache.EPG, EVENT_LIST)
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
            )
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun deleteAutoRecording(uuid: String): Result<Unit> = suspendResult {
        getJson("api/idnode/delete", mapOf("uuid" to uuid))
        patchCached("dvr:autorec", AUTOREC_LIST) { list -> list.filterNot { it.uuid == uuid } }
        invalidateDvrAndEpg()
    }

    suspend fun scheduleRecording(eventId: String): Result<Unit> = suspendResult {
        val configUuid = defaultDvrConfigUuid()
        val params = buildMap {
            put("event_id", eventId)
            if (configUuid.isNotBlank()) put("config_uuid", configUuid)
        }
        getJson("api/dvr/entry/create_by_event", params)
        invalidateDvrAndEpg()
    }

    suspend fun recordCurrentProgramme(event: TvhEpgEvent): Result<Unit> = suspendResult {
        val configUuid = defaultDvrConfigUuid()
        val conf = currentProgrammeRecordingConfig(event, System.currentTimeMillis() / 1000, configUuid)
        getJson("api/dvr/entry/create", mapOf("conf" to conf.toString()))
        invalidateDvrAndEpg()
    }

    suspend fun scheduleSeries(eventId: String): Result<Unit> = suspendResult {
        val configUuid = defaultDvrConfigUuid()
        val params = buildMap {
            put("event_id", eventId)
            if (configUuid.isNotBlank()) put("config_uuid", configUuid)
        }
        getJson("api/dvr/autorec/create_by_series", params)
        invalidateDvrAndEpg()
    }

    suspend fun cancelRecording(uuid: String): Result<Unit> = suspendResult {
        // Re-read the server state: a scheduled timer may have started since the UI loaded.
        val entry = upcomingRecordings(force = true).firstOrNull { it.uuid == uuid }
            ?: error("Recording is no longer scheduled or running. Refresh the recordings.")
        getJson(recordingTerminationPath(entry.scheduleStatus), mapOf("uuid" to uuid))
        // Patch the stored lists at once; an offline restart must not show the cancelled timer.
        patchCached("dvr:upcoming", RECORDING_LIST) { list -> list.filterNot { it.uuid == uuid } }
        invalidateDvrAndEpg()
    }

    suspend fun deleteRecording(uuid: String): Result<Unit> = suspendResult {
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

    private suspend fun invalidateDvrAndEpg() = cacheMutex.withLock {
        cache.replaceAll { key, value -> if (key.startsWith("dvr:") || key.startsWith("epg:")) value.copy(createdAtMs = 0L) else value }
    }

    /** TVHeadend disabled: the stored server data is deleted, not only expired. */
    fun clearPersistentCache() {
        diskCache.clear(runCatching { diskScope() }.getOrNull())
        _servingCachedData.value = false
        background.launch { cacheMutex.withLock { cache.clear(); cacheGeneration++ } }
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
        )
    }

    private suspend fun getJson(path: String, params: Map<String, String> = emptyMap(), wake: Boolean = true): JsonElement {
        ensureConfiguredAndAllowed()
        if (wake) ensureAwakeIfNeeded()
        return withContext(Dispatchers.IO) {
            val url = buildUrl(path, params)
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
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
                loginRejection.onSuccess()
                _servingCachedData.value = false
                preferences.tvheadendLoginState = com.ambient.tv.data.local.TvheadendLoginState.VERIFIED
                lastReachableAtMs = System.currentTimeMillis()
                val body = response.body.string().orEmpty()
                if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)
            }
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
        preferences.tvheadendUseHttps.toString(),
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
private val EVENT_LIST = kotlinx.serialization.builtins.ListSerializer(com.ambient.tv.livetv.LiveTvProgramme.serializer())
private val RECORDING_LIST = kotlinx.serialization.builtins.ListSerializer(com.ambient.tv.livetv.LiveTvRecording.serializer())
private val AUTOREC_LIST = kotlinx.serialization.builtins.ListSerializer(com.ambient.tv.livetv.LiveTvSeriesTimer.serializer())

/** A sleeping or failing server may be answered from stored data; a rejected login may not. */
internal fun tvhStoredDataMayReplace(error: Throwable): Boolean =
    !(error is TvheadendHttpException && (error.code == 401 || error.code == 403))

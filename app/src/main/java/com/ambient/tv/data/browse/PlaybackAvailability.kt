package com.ambient.tv.data.browse

import android.net.Uri
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.connectivity.UnreachableServerGate
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.livetv.LiveTvRegistry
import com.ambient.tv.livetv.LiveTvRepository
import com.ambient.tv.util.suspendResult
import com.ambient.tv.wol.MachineWake
import com.ambient.tv.wol.ManualWakeRequest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Shared by navigation and the playback owners. A successful protected data read, scoped to
 * the exact endpoint/account, grants access. An open TCP port or public identity never does.
 */
@Singleton
class PlaybackAvailability @Inject constructor(
    private val availability: SourceAvailability,
    private val preferences: AppPreferences,
    private val scopes: BrowseScopes,
    private val mediaRepository: Provider<MediaRepository>,
    private val hosts: HostConnectivity,
    private val files: Provider<FileStore>,
    private val dlna: Provider<DlnaStore>,
    private val liveTv: Provider<LiveTvRegistry>,
    private val recovery: Provider<UnreachableServerGate>,
    private val machineWake: Provider<MachineWake>,
    private val wireGuard: Provider<com.ambient.tv.vpn.WireGuardRouteController>,
    private val moonlight: Provider<com.ambient.tv.moonlight.MoonlightSystemTransport>,
    private val moonlightStore: Provider<com.ambient.tv.moonlight.MoonlightLibraryStore>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision
    private val proofs = VerifiedPlaybackAccess()
    private val checks = Array(16) { Mutex() }

    init {
        scope.launch { availability.changes.collect { changed() } }
        scope.launch { preferences.changes().collect { changed() } }
    }

    @Synchronized private fun changed() { revision.value++ }

    fun network(): Boolean {
        BrowseWorkBudget.interact()
        return availability.hasNetwork().also { if (!it) availability.notifyBlocked() }
    }

    private fun mediaSystem(id: String): String = hosts.mediaSystem(mediaProvider(id), id) ?: mediaProvider(id)
    private fun accessSource(id: String): String = mediaSystem(id)
    private fun local(id: String): Boolean = mediaProvider(id) == "files" &&
        runCatching { FileKeys.parse(id).first }.getOrNull()?.let { sourceId ->
            preferences.fileSources.any { it.id == sourceId && it.kind == "local" }
        } == true

    /** Identity excludes transient failures so a Retry can recover the same pending request. */
    fun mediaIdentity(id: String): String = when (mediaProvider(id)) {
        "files" -> runCatching { FileKeys.parse(id).first.let { sourceId ->
            files.get().sources().firstOrNull { it.id == sourceId }?.let {
                hashIdentity(listOf(files.get().playbackScope(it), it, files.get().password(it), files.get().sshKey(it)).joinToString("\u0000"))
            }
        } }.getOrNull() ?: "files:missing"
        "dlna" -> runCatching { com.ambient.tv.data.dlna.DlnaKeys.parse(id).first }.getOrNull()?.let { sourceId ->
            dlna.get().server(sourceId)?.let { hashIdentity("${dlna.get().playbackScope(it)}\u0000$it") }
        } ?: "dlna:missing"
        else -> scopes.forProvider(mediaProvider(id))
    }

    fun mediaScope(id: String): String = mediaIdentity(id) + if (local(id)) "" else
        ":" + availability.accessGeneration(accessSource(id), mediaSystem(id))

    fun isMediaVerified(id: String): Boolean = local(id) || mediaProvider(id) == "shared" ||
        (availability.hasNetwork() && availability.mediaState(id) !in setOf(SourceHealth.OFFLINE, SourceHealth.AUTH_REQUIRED) &&
            proofs.contains(mediaSystem(id), mediaScope(id)))

    fun invalidateMedia(id: String) {
        proofs.invalidate(mediaSystem(id))
        availability.accessFailed(accessSource(id))
        changed()
    }

    /** Call only after a fresh, non-null protected metadata read; never for a cached item. */
    fun confirmMedia(id: String, scope: String): Boolean {
        if (!local(id) && !availability.hasNetwork()) return false
        if (!proofs.confirm(mediaSystem(id), scope, mediaScope(id))) return false
        hosts.online(hosts.begin(mediaSystem(id)))
        availability.online(mediaProvider(id))
        changed()
        return true
    }

    suspend fun checkMedia(id: String, force: Boolean = false): PlaybackAccessResult {
        BrowseWorkBudget.interact()
        val system = mediaSystem(id)
        fun answer(allowed: Boolean, error: Throwable? = null) = PlaybackAccessResult(
            allowed, system, displayName(system),
            authRequired = !allowed && (authenticationFailure(error) || availability.mediaState(id) == SourceHealth.AUTH_REQUIRED),
        )
        if (local(id) || mediaProvider(id) == "shared") return answer(true)
        if (!availability.hasNetwork()) return answer(false)
        return checks[(system.hashCode() and Int.MAX_VALUE) % checks.size].withLock {
            if (!force && isMediaVerified(id)) return@withLock answer(true)
            val identity = mediaIdentity(id)
            ensureTunnel(mediaProvider(id), id)
            if (identity != mediaIdentity(id)) return@withLock answer(false)
            val started = mediaScope(id)
            val result = withTimeoutOrNull(SERVER_PROBE_TIMEOUT_MS) {
                withContext(PlaybackAccessProbe) { suspendResult { mediaRepository.get().getMetadataFast(id).getOrThrow() } }
            }
            currentCoroutineContext().ensureActive()
            if (result?.getOrNull() != null && confirmMedia(id, started)) answer(true)
            else {
                if (identity == mediaIdentity(id)) {
                    proofs.invalidate(system)
                    availability.accessFailed(accessSource(id))
                }
                changed()
                answer(false, result?.exceptionOrNull())
            }
        }
    }

    /** Explicit Retry always reads first. Authentication failures never send a wake packet. */
    suspend fun retryMedia(id: String, allowWake: Boolean = true): PlaybackAccessResult {
        val identity = mediaIdentity(id)
        val first = checkMedia(id, force = true)
        if (first.allowed || first.authRequired || !allowWake || !availability.hasNetwork()) return first
        if (!recover(first.system) { mediaIdentity(id) == identity }) return first
        currentCoroutineContext().ensureActive()
        if (mediaIdentity(id) != identity) return first
        return checkMedia(id, force = true)
    }

    suspend fun media(id: String): Boolean = checkMedia(id).allowed

    private fun query(route: String, name: String): String? = route.substringAfter('?', "").split('&')
        .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')?.let(Uri::decode)

    private fun routeMedia(route: String): String = query(route, "versionSourceKey")?.takeIf(String::isNotBlank)
        ?: Uri.decode(route.substringAfter("player/").substringBefore('?'))

    fun routeIdentity(route: String): String = when {
        route.startsWith("player/") -> mediaIdentity(routeMedia(route))
        route.startsWith("tvh_player/") -> liveRouteIdentity(query(route, "sourceId"))
        else -> route.substringBefore('/')
    }

    fun isRouteVerified(route: String): Boolean = when {
        route.startsWith("player/") -> isMediaVerified(routeMedia(route))
        route.startsWith("tvh_player/") -> isLiveVerified(query(route, "sourceId"), route.substringBefore('?').split('/').getOrNull(1).orEmpty())
        else -> true
    }

    fun invalidateRoute(route: String) {
        when {
            route.startsWith("player/") -> invalidateMedia(routeMedia(route))
            route.startsWith("tvh_player/") -> invalidateLive(query(route, "sourceId"))
        }
    }

    suspend fun route(route: String): Boolean = checkRoute(route).allowed

    suspend fun checkRoute(route: String, retry: Boolean = false): PlaybackAccessResult = when {
        route.startsWith("player/") -> if (retry) retryMedia(routeMedia(route)) else checkMedia(routeMedia(route))
        route.startsWith("tvh_player/") -> {
            val parts = route.substringBefore('?').split('/')
            checkLive(query(route, "sourceId"), parts.getOrNull(1).orEmpty(),
                Uri.decode(parts.getOrNull(2).orEmpty()), retry = retry)
        }
        route.startsWith("youtube_player/") || route.startsWith("telegram_player/") ||
            route.startsWith("spotify_player") -> PlaybackAccessResult(availability.hasNetwork(),
                route.substringBefore('_'), displayName(route.substringBefore('_')))
        else -> PlaybackAccessResult(true, "", "")
    }

    private fun liveRepository(sourceId: String?): LiveTvRepository? = liveTv.get().configured().firstOrNull {
        if (sourceId.isNullOrBlank()) it.source.system == "tvheadend" else it.source.id == sourceId
    }

    private fun liveIdentity(repository: LiveTvRepository): String = liveTv.get().displayCacheIdentity(repository)
        ?.let(::hashIdentity) ?: "missing:${repository.source.id}"

    private fun liveRouteIdentity(sourceId: String?): String = liveRepository(sourceId)?.let(::liveIdentity)
        ?: "missing:${sourceId.orEmpty().ifBlank { "tvheadend" }}"

    private fun liveScope(repository: LiveTvRepository): String = liveIdentity(repository) + ":" +
        availability.accessGeneration(if (repository.source.system == "tvheadend") "tvheadend" else repository.source.id,
            if (repository.source.system == "tvheadend") "tvheadend" else repository.source.id)

    fun isLiveVerified(sourceId: String?, kind: String): Boolean {
        val repository = liveRepository(sourceId) ?: return false
        return availability.hasNetwork() && proofs.contains("${repository.source.id}:$kind", liveScope(repository))
    }

    fun invalidateLive(sourceId: String?) {
        val repository = liveRepository(sourceId) ?: return
        availability.accessFailed(if (repository.source.system == "tvheadend") "tvheadend" else repository.source.id)
        changed()
    }

    suspend fun retryLive(sourceId: String?): PlaybackAccessResult = checkLive(sourceId, "channel", "", retry = true)

    /** Live TV checks the selected registry endpoint and the required channels/DVR API. */
    suspend fun checkLive(sourceId: String?, kind: String, id: String, retry: Boolean = false, allowWake: Boolean = true): PlaybackAccessResult {
        val repository = liveRepository(sourceId) ?: return PlaybackAccessResult(false,
            sourceId?.substringBefore(':') ?: "tvheadend", displayName(sourceId?.substringBefore(':') ?: "tvheadend"))
        val identity = liveIdentity(repository)
        val system = repository.source.system
        val key = "${repository.source.id}:$kind"
        fun answer(allowed: Boolean, error: Throwable? = null) = PlaybackAccessResult(allowed,
            if (system == "tvheadend") system else repository.source.id, repository.source.name,
            authRequired = authenticationFailure(error))
        if (!availability.hasNetwork()) return answer(false)
        suspend fun verify(force: Boolean): PlaybackAccessResult = checks[(key.hashCode() and Int.MAX_VALUE) % checks.size].withLock {
            if (liveRouteIdentity(sourceId) != identity) return@withLock answer(false)
            if (!force && proofs.contains(key, liveScope(repository))) return@withLock answer(true)
            val target = manualRequest(repository.source.id)
            if (target?.wireGuardHost != null) withTimeoutOrNull(12_500L) {
                wireGuard.get().ensureForWakeHost(target.wireGuardHost)
            } else if (system == "tvheadend") ensureTunnel(system)
            if (liveRouteIdentity(sourceId) != identity) return@withLock answer(false)
            val started = liveScope(repository)
            val result = withTimeoutOrNull(SERVER_PROBE_TIMEOUT_MS) {
                withContext(PlaybackAccessProbe) { suspendResult { repository.verifyPlayback(kind, id) } }
            }
            currentCoroutineContext().ensureActive()
            val valid = result?.getOrNull() == true && liveRouteIdentity(sourceId) == identity &&
                proofs.confirm(key, started, liveScope(repository))
            if (!valid) proofs.invalidate(key)
            changed()
            answer(valid, result?.exceptionOrNull())
        }
        val first = verify(retry)
        if (!retry || first.allowed || first.authRequired || !allowWake) return first
        if (!recover(first.system) { liveRouteIdentity(sourceId) == identity }) return first
        currentCoroutineContext().ensureActive()
        return verify(true)
    }

    /** A new native stream always needs a protected catalog read, not just a live HTTP port. */
    fun moonlightIdentity(hostId: String, appId: Int): String {
        val host = preferences.moonlightHosts.firstOrNull { it.id == hostId && preferences.moonlightEnabled }
        return listOf(hostId, appId, host?.host, host?.port, moonlightStore.get().peerIdentity(hostId)).joinToString(":")
    }

    suspend fun checkMoonlight(hostId: String, appId: Int, retry: Boolean = false): PlaybackAccessResult {
        val host = preferences.moonlightHosts.firstOrNull { it.id == hostId && preferences.moonlightEnabled }
        val system = "moonlight:$hostId"
        val name = host?.name?.ifBlank { host.host } ?: "Moonlight"
        fun answer(ok: Boolean, auth: Boolean = false) = PlaybackAccessResult(ok, system, name, auth)
        if (!com.ambient.tv.BuildConfig.MOONLIGHT_NATIVE || host == null || appId <= 0) return answer(false)
        val identity = moonlightIdentity(hostId, appId)
        fun current() = moonlightIdentity(hostId, appId) == identity
        if (!moonlight.get().locallyPaired(hostId)) return answer(false, true)
        suspend fun verify(): PlaybackAccessResult {
            if (!current()) return answer(false)
            val token = moonlightStore.get().beginRequest(hostId) ?: return answer(false, true)
            val check = hosts.begin(system)
            val result = withTimeoutOrNull(25_000L) {
                withContext(Dispatchers.IO) {
                    suspendResult {
                        val catalog = moonlight.get().applications(hostId)
                        if (current()) moonlightStore.get().putCatalog(hostId, catalog, token)
                        catalog.apps.any { it.id == appId }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!current()) return answer(false)
            if (result?.isSuccess == true) {
                hosts.online(check)
                return answer(result.getOrNull() == true)
            }
            if (authenticationFailure(result?.exceptionOrNull())) moonlightStore.get().invalidateCatalog(hostId, token)
            else moonlightStore.get().markOffline(hostId, token)
            return answer(false, authenticationFailure(result?.exceptionOrNull()))
        }
        val first = verify()
        if (!retry || first.allowed || first.authRequired || !current() || host.macAddress.isBlank()) return first
        if (!recover(system, ::current)) return first
        return verify()
    }

    private suspend fun ensureTunnel(provider: String, id: String? = null) {
        withTimeoutOrNull(12_500L) {
            when (provider) {
                "plex" -> wireGuard.get().ensureForPlex()
                "jellyfin" -> wireGuard.get().ensureForJellyfin()
                "emby" -> wireGuard.get().ensureForEmby()
                "tvheadend" -> wireGuard.get().ensureForTvheadend()
                "files" -> runCatching { FileKeys.parse(id.orEmpty()).first }.getOrNull()?.let { sourceId ->
                    preferences.fileSources.firstOrNull { it.id == sourceId }?.let { wireGuard.get().ensureForFileSource(it) }
                }
            }
        }
    }

    private suspend fun recover(system: String, stillCurrent: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (!stillCurrent()) return false
        if (system in setOf("plex", "jellyfin", "emby", "tvheadend")) {
            recovery.get().retrySystemAndAwait(system)
            return true
        }
        val request = manualRequest(system) ?: return false
        val owner = currentCoroutineContext()[kotlinx.coroutines.Job]
        val outcome = machineWake.get().wakeAndAwait(request, stillCurrent = { owner?.isActive == true && stillCurrent() }) {
            currentCoroutineContext().ensureActive()
            if (owner?.isActive != true || !stillCurrent()) throw kotlinx.coroutines.CancellationException("Playback request changed")
            hosts.verifySystem(system)
        }
        return outcome == com.ambient.tv.wol.ManualWakeCoordinator.Outcome.AWAKE ||
            outcome == com.ambient.tv.wol.ManualWakeCoordinator.Outcome.ALREADY_WAKING
    }

    /** Automatic recovery honours the selected source's setting; an explicit Retry may wake it. */
    fun automaticMediaWakeAllowed(id: String): Boolean = automaticWakeAllowed(mediaSystem(id))
    fun automaticLiveWakeAllowed(sourceId: String?): Boolean = liveRepository(sourceId)?.let {
        automaticWakeAllowed(if (it.source.system == "tvheadend") "tvheadend" else it.source.id)
    } == true

    private fun automaticWakeAllowed(system: String): Boolean = when (system) {
        "plex" -> preferences.autoWakeEnabled && !preferences.macAddress.isNullOrBlank()
        "jellyfin" -> preferences.jellyfinAutoWakeEnabled && !preferences.jellyfinMacAddress.isNullOrBlank()
        "emby" -> preferences.embyAutoWakeEnabled && !preferences.embyMacAddress.isNullOrBlank()
        "tvheadend" -> preferences.tvheadendAutoWakeEnabled && !preferences.tvheadendMacAddress.isNullOrBlank()
        else -> when (system.substringBefore(':')) {
            "files" -> preferences.fileSources.firstOrNull { it.id == system.substringAfter(':') }?.let { it.wolAuto && it.wolMac.isNotBlank() } == true
            "dlna" -> dlna.get().server(system.substringAfter(':'))?.let { it.wolAuto && it.wolMac.isNotBlank() } == true
            "plex" -> preferences.serverProfiles.firstOrNull {
                system == com.ambient.tv.connectivity.mediaServerProfileSourceId("plex", it.id)
            }?.let { it.autoWakeEnabled && !it.macAddress.isNullOrBlank() } == true
            "jellyfin" -> preferences.jellyfinServerProfiles.firstOrNull {
                system == com.ambient.tv.connectivity.mediaServerProfileSourceId("jellyfin", it.id, it.userId)
            }?.let { it.autoWakeEnabled && !it.macAddress.isNullOrBlank() } == true
            "emby" -> preferences.embyServerProfiles.firstOrNull {
                system == com.ambient.tv.connectivity.mediaServerProfileSourceId("emby", it.id, it.userId)
            }?.let { it.autoWakeEnabled && !it.macAddress.isNullOrBlank() } == true
            else -> false
        }
    }

    /** Inactive live-TV profiles must never wake the active VOD profile by accident. */
    private fun manualRequest(system: String): ManualWakeRequest? {
        val id = system.substringAfter(':', "")
        return when (system.substringBefore(':')) {
            "files" -> preferences.fileSources.firstOrNull { it.id == id && it.kind != "local" }?.let {
                ManualWakeRequest("files", system, it.name, it.host, it.wolMac, it.port,
                    it.wolBroadcast, it.wolPort, it.host.takeIf { _ -> it.wireGuard })
            }
            "moonlight" -> preferences.moonlightHosts.firstOrNull { preferences.moonlightEnabled && it.id == id }?.let {
                ManualWakeRequest(system, system, it.name.ifBlank { it.host }, it.host, it.macAddress, it.port)
            }
            "dlna" -> dlna.get().server(id)?.let { server -> server.contentDirectoryUrl.toHttpUrlOrNull()?.let {
                ManualWakeRequest("dlna", system, server.name, it.host, server.wolMac, it.port)
            } }
            "plex" -> preferences.serverProfiles.firstOrNull { system == com.ambient.tv.connectivity.mediaServerProfileSourceId("plex", it.id) }?.let {
                ManualWakeRequest("plex", system, it.name, it.host, it.macAddress, it.port,
                    wireGuardHost = it.host.takeIf { preferences.wireGuardPlexEnabled })
            }
            "jellyfin" -> preferences.jellyfinServerProfiles.firstOrNull { system == com.ambient.tv.connectivity.mediaServerProfileSourceId("jellyfin", it.id, it.userId) }?.let {
                ManualWakeRequest("jellyfin", system, it.name, it.host, it.macAddress, it.port,
                    wireGuardHost = it.host.takeIf { preferences.wireGuardJellyfinEnabled })
            }
            "emby" -> preferences.embyServerProfiles.firstOrNull { system == com.ambient.tv.connectivity.mediaServerProfileSourceId("emby", it.id, it.userId) }?.let {
                ManualWakeRequest("emby", system, it.name, it.host, it.macAddress, it.port,
                    wireGuardHost = it.host.takeIf { preferences.wireGuardEmbyEnabled })
            }
            else -> null
        }
    }

    private fun displayName(system: String): String {
        if (system.startsWith("files:")) preferences.fileSources.firstOrNull { it.id == system.substringAfter(':') }?.name
            ?.takeIf(String::isNotBlank)?.let { return it }
        if (system.startsWith("dlna:")) dlna.get().server(system.substringAfter(':'))?.name
            ?.takeIf(String::isNotBlank)?.let { return it }
        val mediaSystem = com.ambient.tv.ui.common.MediaSystem.fromKey(system)
        return mediaSystem?.let { preferences.systemNames[it.key]?.takeIf(String::isNotBlank) ?: it.brand } ?: system
    }
}

private fun hashIdentity(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun authenticationFailure(error: Throwable?): Boolean = generateSequence(error) { it.cause }.take(8).any {
    when (it) {
        is SecurityException -> true
        is retrofit2.HttpException -> it.code() in setOf(401, 403)
        is com.ambient.tv.tvheadend.data.TvheadendHttpException -> it.code in setOf(401, 403)
        is com.ambient.tv.livetv.LiveTvHttpException -> it.status in setOf(401, 403)
        else -> false
    }
}

/** Probing access must not accidentally start an automatic wake inside a data repository. */
object PlaybackAccessProbe : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    object Key : kotlin.coroutines.CoroutineContext.Key<PlaybackAccessProbe>
}

/** The media clients connect with a 10 s timeout; the pre-play probe waits that long too. */
internal const val SERVER_PROBE_TIMEOUT_MS = 10_500L

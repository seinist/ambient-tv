package com.ambient.tv.network

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.ImageResult
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.connectivity.matchingHostEndpoint
import com.ambient.tv.data.browse.SourceAvailability
import com.ambient.tv.data.browse.SourceHealth
import com.ambient.tv.data.local.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Compose's request equality ignores networkCachePolicy. This marker restarts a failed painter. */
internal const val MEDIA_ARTWORK_NETWORK_EXTRA = "ambient.artwork.network"

internal data class ArtworkNetworkState(val blocked: Boolean = false, val recovery: Long = 0L) {
    fun update(blocked: Boolean): ArtworkNetworkState =
        ArtworkNetworkState(blocked, recovery + if (this.blocked && !blocked) 1L else 0L)
}

internal fun artworkNetworkBlocked(networkPresent: Boolean, service: SourceHealth?, host: HostHealth?): Boolean =
    !networkPresent || service == SourceHealth.OFFLINE || service == SourceHealth.AUTH_REQUIRED || host == HostHealth.OFFLINE

/** App-owned local file covers use a loopback HTTP bridge and need no active Android network. */
internal fun artworkLoopbackHost(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true) || host == "::1") return true
    val octets = host.split('.')
    return octets.size == 4 && octets.first() == "127" && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
}

/**
 * Display snapshots retain metadata; the existing bounded Coil disk cache retains the bytes.
 * A known outage permits those bytes, but no repeated socket timeouts for every visible card.
 * Network presence deliberately does not require Internet validation: local NAS artwork works
 * on an isolated LAN. Unrelated Internet sources never inherit a media server's outage.
 */
@Singleton
class MediaArtworkNetworkAccess @Inject constructor(
    private val availability: SourceAvailability,
    private val preferences: AppPreferences,
    private val hosts: HostConnectivity,
) {
    val changes = availability.changes

    fun blocked(model: Any?, headers: Map<String, String> = emptyMap()): Boolean {
        val raw = model as? String ?: return false
        if (raw.startsWith(com.ambient.tv.data.telegram.MONOGRAM_THUMB_SCHEME + "://") ||
            raw.startsWith(com.ambient.tv.data.telegram.MONOGRAM_PHOTO_SCHEME + "://")) return !availability.hasNetwork()
        val url = raw.toHttpUrlOrNull() ?: return false
        if (artworkLoopbackHost(url.host)) return false
        if (!availability.hasNetwork()) return true
        val provider = mediaImageProvider(url, mediaImageEndpoints(preferences) + MediaImageEndpoint(
            "tvheadend", preferences.tvheadendHost, preferences.tvheadendPort, preferences.tvheadendUseHttps,
        ))
        // Explicit credentials can belong to a captured Live TV profile on the same host.
        // A different account's login failure must not suppress its artwork; host failure can.
        val service = provider?.takeUnless { explicitMediaImageCredential(url, it, headers) }?.let(availability::reportedState)
        val host = if (provider != null) hosts.health(provider)
            else matchingHostEndpoint(hosts.endpoints(), url.host, url.port)?.let { hosts.health(it.system) }
        return artworkNetworkBlocked(networkPresent = true, service, host)
    }
}

internal fun ImageRequest.Builder.artworkNetworkAccess(state: ArtworkNetworkState, cacheOnly: Boolean = false) = apply {
    val blocked = cacheOnly || state.blocked
    if (blocked) networkCachePolicy(CachePolicy.DISABLED)
    if (blocked || state.recovery != 0L) memoryCacheKeyExtra(MEDIA_ARTWORK_NETWORK_EXTRA, "$blocked:${state.recovery}")
}

/**
 * Covers non-Compose image requests too. Strip the UI retry marker before Coil computes cache
 * keys, so going offline/reconnecting neither evicts a successful image nor duplicates it.
 */
@Singleton
class MediaArtworkNetworkInterceptor internal constructor(
    private val isBlocked: (Any?, Map<String, String>) -> Boolean,
) : Interceptor {
    @Inject constructor(access: MediaArtworkNetworkAccess) : this(access::blocked)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val headers = request.httpHeaders.asMap().mapValues { it.value.joinToString("\u0000") }
        val blocked = isBlocked(request.data, headers)
        if (!blocked && MEDIA_ARTWORK_NETWORK_EXTRA !in request.memoryCacheKeyExtras) return chain.proceed()
        return chain.withRequest(request.newBuilder()
            .memoryCacheKeyExtra(MEDIA_ARTWORK_NETWORK_EXTRA, null)
            .apply { if (blocked) networkCachePolicy(CachePolicy.DISABLED) }
            .build()).proceed()
    }
}

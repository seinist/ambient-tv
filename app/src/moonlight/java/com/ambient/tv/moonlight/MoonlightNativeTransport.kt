package com.ambient.tv.moonlight

import com.ambient.tv.data.local.AppPreferences
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Native GameStream control plane. Built ONLY with MOONLIGHT_NATIVE=1 and the pinned protocol
 * module; its HTTPS client, pairing crypto and app list use original upstream Moonlight code.
 * No player or input thread is initialized by these operations.
 */
@Singleton
class MoonlightNativeTransport @Inject constructor(
    private val prefs: AppPreferences,
    private val trust: MoonlightHostTrust,
    private val clientIdentity: MoonlightKeystoreCryptoProvider,
) {
    private val pairingMutex = Mutex()
    private data class ArtworkProof(val target: MoonlightVerifiedHost, val at: Long)
    private val artworkProofs = android.util.LruCache<String, ArtworkProof>(32)
    private val artworkProofLocks = Array(16) { Mutex() }

    private suspend fun artworkHost(hostId: String): MoonlightVerifiedHost =
        artworkProofLocks[(hostId.hashCode() and Int.MAX_VALUE) % artworkProofLocks.size].withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            val cached = artworkProofs.get(hostId)
            if (cached != null && now - cached.at in 0 until 15_000L && current(cached.target))
                return@withLock cached.target
            verifiedHost(hostId).also { artworkProofs.put(hostId, ArtworkProof(it, android.os.SystemClock.elapsedRealtime())) }
        }

    fun pairingPin(): String = PairingManager.generatePinString()

    /**
     * PairingManager verifies both signed challenge responses before it reports PAIRED.
     * Never record a discovered certificate from unauthenticated /serverinfo.
     * The initial upstream PIN request can wait for the user; our reviewed patch bounds
     * its read timeout to 120 seconds, and the caller must own the visible pairing state.
     */
    suspend fun pair(hostId: String, pin: String): MoonlightPairResult = pairingMutex.withLock {
        require(pin.matches(Regex("[0-9]{4}"))) { "Invalid GameStream PIN" }
        val host = requireHost(hostId)
        val epoch = trust.epoch(hostId)
        if (trust.isPaired(hostId)) return@withLock MoonlightPairResult.AlreadyPaired
        val result = withHttp(host, null) { http ->
            val raw = http.getServerInfo(false)
            val server = parseMoonlightServerInfo(raw.toByteArray())
            val state = http.pairingManager.pair(raw, pin)
            val cert = if (state == PairingManager.PairState.PAIRED)
                http.pairingManager.pairedCert ?: throw IOException("Pairing did not return a certificate")
                else null
            Triple(state, server, cert)
        }
        when (result.first) {
            PairingManager.PairState.PAIRED -> {
                val certificate = requireNotNull(result.third)
                val authenticated = withHttp(host, certificate, result.second.httpsPort) { http ->
                    val raw = http.getServerInfo(true)
                    if (http.getPairState(raw) != PairingManager.PairState.PAIRED)
                        throw SecurityException("Moonlight pairing not confirmed")
                    parseMoonlightServerInfo(raw.toByteArray())
                }
                if (authenticated.uniqueId != result.second.uniqueId)
                    throw SecurityException("Moonlight identity changed during pairing")
                currentCoroutineContext().ensureActive()
                if (requireHost(hostId) != host)
                    throw SecurityException("Moonlight host changed during pairing")
                trust.commitVerifiedPairing(hostId, MoonlightVerifiedPeer(authenticated.uniqueId, certificate),
                    authenticated.httpsPort, epoch)
                MoonlightPairResult.Paired(hostId)
            }
            PairingManager.PairState.PIN_WRONG -> MoonlightPairResult.IncorrectPin
            PairingManager.PairState.ALREADY_IN_PROGRESS -> MoonlightPairResult.Busy
            else -> MoonlightPairResult.Rejected
        }
    }

    /** The app list is HTTPS-only and never accepted from an unpaired or identity-swapped host. */
    suspend fun loadApplications(hostId: String): MoonlightAppCatalog =
        loadApplications(verifiedHost(hostId))

    internal suspend fun loadApplications(target: MoonlightVerifiedHost): MoonlightAppCatalog {
        val hostId = target.profile.id
        val catalog = withHttp(target.profile, target.pinnedCertificate, target.httpsPort) { http ->
            parseMoonlightAppList(http.appListRaw.toByteArray())
        }
        if (trust.identity(hostId) != target.identity || requireHost(hostId) != target.profile)
            throw SecurityException("Moonlight host identity changed")
        return catalog.copy(peerIdentity = target.identity, runningAppId = target.runningAppId)
    }

    internal suspend fun verifiedHost(hostId: String): MoonlightVerifiedHost {
        val host = requireHost(hostId)
        val serverId = trust.trustedServerId(host.id)
            ?: throw SecurityException("Moonlight host not paired")
        val identity = trust.identity(host.id)
            ?: throw SecurityException("Moonlight host identity missing")
        val pinned = trust.pinnedCertificate(host.id)
            ?: throw SecurityException("Moonlight host certificate missing")
        // Prefer the port last verified over pinned HTTPS. Legacy pins may discover it once.
        val (info, hostCodecModes, runningAppId) = withHttp(host, pinned, trust.httpsPort(hostId)) { http ->
            val raw = http.getServerInfo(true)
            if (http.getPairState(raw) != PairingManager.PairState.PAIRED)
                throw SecurityException("Moonlight pairing revoked")
            Triple(parseMoonlightServerInfo(raw.toByteArray()), http.getServerCodecModeSupport(raw),
                http.getCurrentGame(raw).takeIf { it >= 0 })
        }
        if (info.uniqueId != serverId || trust.identity(hostId) != identity || requireHost(hostId) != host)
            throw SecurityException("Moonlight host identity changed")
        trust.rememberHttpsPort(hostId, identity, info.httpsPort)
        return MoonlightVerifiedHost(host, info.httpsPort, pinned, identity, hostCodecModes, serverId, runningAppId)
    }

    internal fun current(target: MoonlightVerifiedHost): Boolean =
        prefs.moonlightEnabled && prefs.moonlightHosts.any { it == target.profile } &&
            trust.identity(target.profile.id) == target.identity

    internal val trustRevisions get() = trust.revisions

    /** Only pinned and size-limited art; decoding dimensions are checked by the cache. */
    suspend fun loadArtwork(hostId: String, appId: Int): ByteArray {
        require(appId > 0)
        val host = artworkHost(hostId)
        val data = withHttp(host.profile, host.pinnedCertificate, host.httpsPort) { http ->
            http.getBoxArt(com.limelight.nvstream.http.NvApp("Moonlight", appId, false)).use { stream ->
                val output = java.io.ByteArrayOutputStream(16 * 1024)
                val chunk = ByteArray(8192)
                while (true) {
                    val length = stream.read(chunk)
                    if (length < 0) break
                    if (output.size() + length > 3 * 1024 * 1024)
                        throw IOException("Moonlight artwork exceeds 3 MiB")
                    output.write(chunk, 0, length)
                }
                output.toByteArray().takeIf { it.isNotEmpty() }
                    ?: throw IOException("Sunshine has no artwork for this app")
            }
        }
        if (trust.identity(hostId) != host.identity || requireHost(hostId) != host.profile)
            throw SecurityException("Moonlight host changed")
        return data
    }

    /** Check the currently running remote app immediately before the explicit cancel. */
    suspend fun quitRemoteApp(hostId: String, expectedAppId: Int): Boolean {
        require(expectedAppId > 0)
        val host = verifiedHost(hostId)
        return withHttp(host.profile, host.pinnedCertificate, host.httpsPort) { http ->
            val raw = http.getServerInfo(true)
            if (parseMoonlightServerInfo(raw.toByteArray()).uniqueId != trust.trustedServerId(hostId) ||
                trust.identity(hostId) != host.identity || requireHost(hostId) != host.profile ||
                http.getCurrentGame(raw) != expectedAppId) false
            else http.quitApp()
        }
    }

    private suspend fun <T> withHttp(
        host: MoonlightHost, certificate: java.security.cert.X509Certificate?, httpsPort: Int = 0,
        action: (NvHTTP) -> T,
    ): T = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val current = AtomicReference<NvHTTP?>()
            val job = launch(Dispatchers.IO) {
                try {
                    val http = makeHttp(host, certificate, httpsPort)
                    current.set(http)
                    if (!continuation.isActive) {
                        http.cancelPendingRequests()
                        return@launch
                    }
                    continuation.resume(action(http))
                } catch (error: Exception) {
                    val identityFailure = certificate != null &&
                        (error is javax.net.ssl.SSLHandshakeException ||
                            error is javax.net.ssl.SSLPeerUnverifiedException ||
                            error is java.security.cert.CertificateException ||
                            error is com.limelight.nvstream.http.HostHttpResponseException &&
                                error.errorCode in setOf(401, 403))
                    // Do not propagate arbitrary host messages or URLs to a player/diagnostic UI.
                    continuation.resumeWithException(when {
                        identityFailure || error is SecurityException -> SecurityException("Moonlight host authentication failed")
                        else -> IOException("Moonlight request failed")
                    })
                }
            }
            continuation.invokeOnCancellation {
                current.get()?.cancelPendingRequests()
                job.cancel()
            }
        }
    }

    /** Device-local forget. Does NOT terminate the active game or send remote unpair commands. */
    fun forgetHost(hostId: String) { artworkProofs.remove(hostId); trust.clear(hostId) }

    suspend fun resetClientIdentity() = pairingMutex.withLock {
        withContext(Dispatchers.IO) {
            // Revoke local trust first. A partial Keystore failure never leaves accepted old pins.
            artworkProofs.evictAll()
            prefs.moonlightHosts.forEach { trust.clear(it.id) }
            clientIdentity.resetIdentity()
            prefs.resetMoonlightDeviceId()
        }
    }

    private fun requireHost(hostId: String): MoonlightHost =
        prefs.moonlightHosts.firstOrNull { prefs.moonlightEnabled && it.id == hostId }
            ?: throw SecurityException("Unknown or disabled Moonlight host")

    private fun makeHttp(host: MoonlightHost, certificate: java.security.cert.X509Certificate?, httpsPort: Int): NvHTTP =
        NvHTTP(ComputerDetails.AddressTuple(host.host, host.port), httpsPort,
            prefs.moonlightDeviceId, certificate, clientIdentity)
}


/** Not serializable: a native player may use this only for the active authenticated session. */
internal data class MoonlightVerifiedHost(
    val profile: MoonlightHost,
    val httpsPort: Int,
    val pinnedCertificate: java.security.cert.X509Certificate,
    val identity: String,
    val hostCodecModes: Long,
    val serverId: String,
    val runningAppId: Int?,
)

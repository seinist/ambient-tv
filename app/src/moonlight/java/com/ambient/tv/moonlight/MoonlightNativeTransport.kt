package com.ambient.tv.moonlight

import com.ambient.tv.data.local.AppPreferences
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
        if (trust.isPaired(hostId)) return@withLock MoonlightPairResult.AlreadyPaired
        withContext(Dispatchers.IO) {
            val http = makeHttp(host, null)
            val raw = http.getServerInfo(false)
            val server = parseMoonlightServerInfo(raw.toByteArray())
            val state = http.pairingManager.pair(raw, pin)
            when (state) {
                PairingManager.PairState.PAIRED -> {
                    val certificate = http.pairingManager.pairedCert
                        ?: throw IOException("Pairing succeeded without a server certificate")
                    // Native PairingManager verified the server's signature and PIN response.
                    // The host key remains immutable until the user explicitly unpairs.
                    trust.commitVerifiedPairing(host.id, MoonlightVerifiedPeer(server.uniqueId, certificate))
                    MoonlightPairResult.Paired(host.id)
                }
                PairingManager.PairState.PIN_WRONG -> MoonlightPairResult.IncorrectPin
                PairingManager.PairState.ALREADY_IN_PROGRESS -> MoonlightPairResult.Busy
                else -> MoonlightPairResult.Rejected
            }
        }
    }

    /** The app list is HTTPS-only and never accepted from an unpaired or identity-swapped host. */
    suspend fun loadApplications(hostId: String): MoonlightAppCatalog = withContext(Dispatchers.IO) {
        val host = requireHost(hostId)
        val serverId = trust.trustedServerId(host.id) ?: throw SecurityException("Moonlight host not paired")
        val pinned = trust.pinnedCertificate(host.id) ?: throw SecurityException("Moonlight host certificate missing")
        val http = makeHttp(host, pinned)
        // Reviewed patch prevents falling back to HTTP if an authenticated serverinfo fails.
        val info = parseMoonlightServerInfo(http.getServerInfo(true).toByteArray())
        if (info.uniqueId != serverId) throw SecurityException("Moonlight host identity changed")
        parseMoonlightAppList(http.appListRaw.toByteArray())
    }

    /**
     * The player must obtain its HTTPS port and host identity over pinned, mutually
     * authenticated HTTPS, not from public discovery. A changed identity fails closed.
     */
    internal suspend fun verifiedHost(hostId: String): MoonlightVerifiedHost = withContext(Dispatchers.IO) {
        val host = requireHost(hostId)
        val serverId = trust.trustedServerId(host.id)
            ?: throw SecurityException("Moonlight host not paired")
        val pinned = trust.pinnedCertificate(host.id)
            ?: throw SecurityException("Moonlight host certificate missing")
        val http = makeHttp(host, pinned)
        val info = parseMoonlightServerInfo(http.getServerInfo(true).toByteArray())
        if (info.uniqueId != serverId) throw SecurityException("Moonlight host identity changed")
        MoonlightVerifiedHost(host, info.httpsPort, pinned)
    }

    /** Authenticated cover-art asset; do not expose the self-signed host URL to normal image loaders. */
    suspend fun loadArtwork(hostId: String, appId: Int): ByteArray = withContext(Dispatchers.IO) {
        require(appId > 0)
        val host = verifiedHost(hostId)
        val http = makeHttp(host.profile, host.pinnedCertificate)
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

    /** Explicit remote quit, separate from disconnect. Never invoked automatically on Back. */
    suspend fun quitRemoteApp(hostId: String): Boolean = withContext(Dispatchers.IO) {
        val host = verifiedHost(hostId)
        makeHttp(host.profile, host.pinnedCertificate).quitApp()
    }

    /** Device-local forget. Does NOT terminate the active game or send remote unpair commands. */
    fun forgetHost(hostId: String) = trust.clear(hostId)

    private fun requireHost(hostId: String): MoonlightHost =
        prefs.moonlightHosts.firstOrNull { it.id == hostId }
            ?: throw IllegalArgumentException("Unknown Moonlight host")

    private fun makeHttp(host: MoonlightHost, certificate: java.security.cert.X509Certificate?): NvHTTP =
        NvHTTP(ComputerDetails.AddressTuple(host.host, host.port), 0,
            prefs.moonlightDeviceId, certificate, clientIdentity)
}


/** Not serializable: a native player may use this only for the active authenticated session. */
internal data class MoonlightVerifiedHost(
    val profile: MoonlightHost,
    val httpsPort: Int,
    val pinnedCertificate: java.security.cert.X509Certificate,
)

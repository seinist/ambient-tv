package com.ambient.tv.moonlight

import android.content.SharedPreferences
import android.util.Base64
import com.ambient.tv.data.local.EncryptedPrefs
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the authenticated pairing transport may commit a successful peer identity. */
data class MoonlightVerifiedPeer(val serverId: String, val certificate: X509Certificate)

enum class MoonlightTrustResult { NOT_PAIRED, MATCHED, IDENTITY_CHANGED }

/**
 * Encrypted, device-local host certificate pins. Never store this data in AppPreferences,
 * JSON backup, Companion sync or log messages. The first unauthenticated /serverinfo is
 * discovery only and MUST NOT be passed here as a pairing proof.
 */
@Singleton
class MoonlightHostTrust @Inject constructor(@EncryptedPrefs private val prefs: SharedPreferences) {
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()
    private val epochs = mutableMapOf<String, Long>()
    private val identityMemo = mutableMapOf<String, Pair<String, String>>()
    @Synchronized fun epoch(hostId: String): Long = epochs[hostId] ?: 0L
    @Synchronized fun httpsPort(hostId: String): Int = prefs.getInt("moonlight_https_port_$hostId", 0).takeIf { it in 1..65535 } ?: 0
    fun isPaired(hostId: String): Boolean = read(hostId) != null
    fun trustedServerId(hostId: String): String? = read(hostId)?.first
    /** A device-local cache key includes both authenticated UUID and exact certificate. */
    @Synchronized fun identity(hostId: String): String? {
        if (!validHostId(hostId)) return null
        val raw = prefs.getString(key(hostId), null) ?: return null
        val nonce = prefs.getString("moonlight_peer_nonce_$hostId", "").orEmpty()
        val lookup = raw + ":" + nonce
        identityMemo[hostId]?.takeIf { it.first == lookup }?.let { return it.second }
        val (id, cert) = read(hostId) ?: return null
        val material = id.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + cert
        val identity = MessageDigest.getInstance("SHA-256")
            .digest(if (nonce.isEmpty()) material else material + nonce.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        identityMemo[hostId] = lookup to identity
        return identity
    }

    fun check(hostId: String, candidate: MoonlightVerifiedPeer): MoonlightTrustResult {
        val trusted = read(hostId) ?: return MoonlightTrustResult.NOT_PAIRED
        return if (trusted.first == candidate.serverId &&
            constantTimeEqual(trusted.second, candidate.certificate.encoded))
            MoonlightTrustResult.MATCHED else MoonlightTrustResult.IDENTITY_CHANGED
    }

    /**
     * Call ONLY after upstream Moonlight pairing finishes with a verified challenge/signature
     * and authenticates the host's certificate. Existing certificate pins cannot silently rotate.
     */
    @Synchronized fun commitVerifiedPairing(hostId: String, peer: MoonlightVerifiedPeer,
        httpsPort: Int = 0, expectedEpoch: Long = epoch(hostId)) {
        require(validHostId(hostId) && validServerId(peer.serverId))
        if (epoch(hostId) != expectedEpoch) throw SecurityException("Moonlight pairing was revoked")
        peer.certificate.checkValidity()
        require(peer.certificate.encoded.size in 128..16_384)
        if (check(hostId, peer) == MoonlightTrustResult.IDENTITY_CHANGED)
            throw SecurityException("Moonlight host certificate changed: explicit unpair required")
        val encoded = Base64.encodeToString(peer.certificate.encoded, Base64.NO_WRAP)
        val edit = prefs.edit().putString(key(hostId), peer.serverId + ":" + encoded)
        if (!isPaired(hostId)) edit.putString("moonlight_peer_nonce_$hostId", java.util.UUID.randomUUID().toString())
        if (httpsPort in 1..65535) edit.putInt("moonlight_https_port_$hostId", httpsPort)
        edit.apply()
        changes.value++
    }

    /** Unpair explicitly; any cached app catalog must also be cleared by the caller. */
    @Synchronized fun clear(hostId: String) {
        if (!validHostId(hostId)) return
        epochs[hostId] = epoch(hostId) + 1
        identityMemo.remove(hostId)
        prefs.edit().remove(key(hostId)).remove("moonlight_peer_nonce_$hostId")
            .remove("moonlight_https_port_$hostId").apply()
        changes.value++
    }

    @Synchronized fun rememberHttpsPort(hostId: String, expected: String, port: Int) {
        if (port in 1..65535 && identity(hostId) == expected && httpsPort(hostId) != port)
            prefs.edit().putInt("moonlight_https_port_$hostId", port).apply()
    }

    /** Return the immutable TLS certificate pin only to the paired GameStream HTTPS client. */
    fun pinnedCertificate(hostId: String): X509Certificate? {
        val pair = read(hostId) ?: return null
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            val cert = factory.generateCertificate(pair.second.inputStream()) as X509Certificate
            cert.checkValidity()
            cert
        } catch (_: Exception) { null }
    }

    private fun read(hostId: String): Pair<String, ByteArray>? {
        if (!validHostId(hostId)) return null
        val saved = prefs.getString(key(hostId), null) ?: return null
        val idx = saved.indexOf(':')
        if (idx <= 0 || idx == saved.lastIndex) return null
        val id = saved.substring(0, idx)
        if (!validServerId(id)) return null
        val bytes = runCatching { Base64.decode(saved.substring(idx + 1), Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size !in 128..16_384) return null
        return id to bytes
    }

    private fun key(hostId: String) = "moonlight_peer_" + hostId
    private fun validHostId(id: String) = validMoonlightHostId(id)
    private fun validServerId(id: String) =
        id.length in 1..128 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        /** Constant-time public-key/certificate identity equality with no String coercion. */
        fun constantTimeEqual(expected: ByteArray, candidate: ByteArray): Boolean =
            MessageDigest.isEqual(expected, candidate)
    }
}

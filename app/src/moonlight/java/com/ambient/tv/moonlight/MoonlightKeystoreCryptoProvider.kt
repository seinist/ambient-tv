package com.ambient.tv.moonlight

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.limelight.nvstream.http.LimelightCryptoProvider
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal

/**
 * Moonlight-native client certificate backed by AndroidKeyStore, not a plaintext client.key.
 * The private signing key never leaves the device; backup/Companion cannot clone a paired client.
 */
@Singleton
class MoonlightKeystoreCryptoProvider @Inject constructor() : LimelightCryptoProvider {
    private val lock = Any()
    private val alias = "ambienttv_moonlight_client_v1"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun certificate(): X509Certificate = synchronized(lock) {
        val ks = keyStore()
        (ks.getCertificate(alias) as? X509Certificate)?.let { existing ->
            existing.checkValidity()
            return@synchronized existing
        }
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            // The pairing handshake uses the certificate's own signature bytes.
            .setCertificateSubject(X500Principal("CN=NVIDIA GameStream Client"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + 20L * 365 * 86_400_000L))
            .setUserAuthenticationRequired(false)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            .apply { initialize(spec) }.generateKeyPair()
        val newCert = keyStore().getCertificate(alias) as? X509Certificate
            ?: throw IllegalStateException("Moonlight client certificate not available")
        newCert.checkValidity()
        newCert
    }

    internal fun resetIdentity() = synchronized(lock) {
        keyStore().deleteEntry(alias)
    }

    override fun getClientCertificate(): X509Certificate = certificate()

    override fun getClientPrivateKey(): PrivateKey {
        certificate()
        return synchronized(lock) {
            keyStore().getKey(alias, null) as? PrivateKey
                ?: throw IllegalStateException("Moonlight hardware-backed signing key is unavailable")
        }
    }

    override fun getPemEncodedClientCertificate(): ByteArray {
        val base64 = Base64.encodeToString(certificate().encoded, Base64.NO_WRAP)
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            base64.chunked(64).joinToString("\n") +
            "\n-----END CERTIFICATE-----\n"
        return pem.toByteArray(StandardCharsets.US_ASCII)
    }

    override fun encodeBase64String(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)
}

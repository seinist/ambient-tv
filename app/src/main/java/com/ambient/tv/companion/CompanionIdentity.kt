package com.ambient.tv.companion

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** Non-exportable device key. QR pins the certificate; a paired reverse connection pins the authenticated public key. */
internal class CompanionIdentity(private val alias: String = DEFAULT_ALIAS) {
    init { require(alias.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) }
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt passes the TLS transcript digest to NONEwithECDSA internally.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Ambient TV Companion"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4_102_444_800_000L))
                    .build())
                generateKeyPair()
            }
        }
    }
    val certificate = store.getCertificate(alias) as X509Certificate
    val pin = fingerprint(certificate.encoded)
    private val key get() = store.getKey(alias, null) as PrivateKey
    fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(bytes); sign() }

    fun serverContext(): SSLContext {
        val manager = object : X509ExtendedKeyManager() {
            override fun getPrivateKey(a: String?) = key
            override fun getCertificateChain(a: String?) = arrayOf(certificate)
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = if (type == "EC") arrayOf(alias) else null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = if (type == "EC") alias else null
            override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?) = if (type == "EC") alias else null
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(manager), null, null) }
    }

    fun clientContext(expectedPin: String): SSLContext {
        require(expectedPin.matches(Regex("[a-f0-9]{64}")))
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Client TLS not used")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.singleOrNull() ?: throw CertificateException("Missing device identity")
                if (fingerprint(cert.encoded) != expectedPin && fingerprint(cert.publicKey.encoded) != expectedPin)
                    throw CertificateException("Device identity changed")
                cert.checkValidity()
                cert.verify(cert.publicKey)
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
    }

    companion object {
        const val DEFAULT_ALIAS = "ambient_companion_identity_v1"
    }
}

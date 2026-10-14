package com.ambient.tv.data.local

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Passphrase protection for a settings export that contains credentials (server tokens,
 * passwords, the WireGuard private key). The file is an envelope around the plain backup JSON:
 * AES-256-GCM with a key derived by PBKDF2-HMAC-SHA256 from the passphrase and a random salt.
 */
object BackupEncryption {
    const val FORMAT = "ambient-tv-encrypted-backup"
    const val MIN_PASSPHRASE_LENGTH = 8
    private const val ITERATIONS = 310_000
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    @Serializable
    private data class Envelope(
        val format: String,
        val version: Int = 1,
        val kdf: String = "PBKDF2WithHmacSHA256",
        val iterations: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    class WrongPassphraseException : Exception("Wrong passphrase or damaged backup")

    /** True when [raw] is an encrypted envelope and needs a passphrase to import. */
    fun isEncrypted(raw: String): Boolean =
        raw.trimStart().startsWith("{") && runCatching { json.decodeFromString<Envelope>(raw).format == FORMAT }.getOrDefault(false)

    fun encrypt(plainJson: String, passphrase: CharArray, iterations: Int = ITERATIONS): String {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase too short" }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(FORMAT.encodeToByteArray())
        val data = cipher.doFinal(plainJson.encodeToByteArray())
        val encoder = Base64.getEncoder()
        return json.encodeToString(Envelope.serializer(), Envelope(
            format = FORMAT, iterations = iterations,
            salt = encoder.encodeToString(salt), iv = encoder.encodeToString(iv), data = encoder.encodeToString(data),
        ))
    }

    fun decrypt(raw: String, passphrase: CharArray): String {
        val envelope = json.decodeFromString<Envelope>(raw)
        require(envelope.format == FORMAT && envelope.version == 1 && envelope.kdf == "PBKDF2WithHmacSHA256") {
            "Unsupported encrypted backup"
        }
        require(envelope.iterations in 10_000..5_000_000) { "Unsupported key derivation" }
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, decoder.decode(envelope.salt), envelope.iterations),
            GCMParameterSpec(128, decoder.decode(envelope.iv)))
        cipher.updateAAD(FORMAT.encodeToByteArray())
        return try {
            cipher.doFinal(decoder.decode(envelope.data)).decodeToString()
        } catch (_: AEADBadTagException) {
            throw WrongPassphraseException()
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

package com.ambient.tv.data.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private, account-partitioned, encrypted snapshot of browse results.
 * - Kept in noBackupFilesDir, never in shared Home or companion backups.
 * - AES-GCM key held in AndroidKeyStore (fail closed: no plaintext fallback).
 * - Max 240 entries per section, seven-day TTL; native chunks have a separate
 *   64-MiB on-disk LRU in the optional Monogram backend.
 */
@Singleton
class TelegramDiskCache @Inject constructor(@ApplicationContext context: Context) {
    private val root = File(context.noBackupFilesDir, "monogram-feeds").apply { mkdirs() }
    private val lock = Mutex()
    private val ttlMillis = 7L * 24 * 60 * 60 * 1000
    private val alias = "ambient.monogram.feed-cache.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
    }

    private fun hash(raw: String) = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun file(account: String, section: String): File =
        File(root, "${hash(account)}-${hash(section)}.bin")

    private fun encrypt(raw: ByteArray): ByteArray {
        val zip = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(raw) }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(zip)
    }
    private fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size >= 1 + 12 + 16 && encrypted[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        val plain = cipher.doFinal(encrypted, 13, encrypted.size - 13)
        return GZIPInputStream(ByteArrayInputStream(plain)).use { it.readBytes() }
    }

    private fun read(account: String, section: String): JSONArray? {
        val target = file(account, section)
        if (!target.isFile || System.currentTimeMillis() - target.lastModified() > ttlMillis) return null
        return runCatching {
            val data = AtomicFile(target).readFully()
            JSONObject(String(decrypt(data), Charsets.UTF_8)).takeIf {
                it.optInt("version") == 1 && it.optString("section") == section
            }?.optJSONArray("rows")
        }.getOrNull()
    }
    private fun write(account: String, section: String, entries: JSONArray) {
        val target = AtomicFile(file(account, section))
        val json = JSONObject().put("version", 1).put("section", section)
            .put("rows", entries).toString().toByteArray(Charsets.UTF_8)
        val bytes = encrypt(json)
        val out = target.startWrite()
        try { out.write(bytes); target.finishWrite(out) }
        catch (failure: Exception) { target.failWrite(out); throw failure }
    }

    private fun safePreview(url: String?): String? =
        url?.takeIf { it.isNotBlank() && (!it.startsWith("file:") ||
            runCatching { File(java.net.URI(it)).isFile }.getOrDefault(false)) }

    suspend fun media(account: String, section: String): List<TelegramMedia> =
        withContext(Dispatchers.IO) { lock.withLock {
            val array = read(account, "media:$section") ?: return@withLock emptyList()
            (0 until array.length()).mapNotNull { i -> runCatching {
                val o = array.getJSONObject(i)
                TelegramMedia(
                    chatId = o.getLong("chat"), messageId = o.getInt("message"),
                    channel = o.getString("channel"), title = o.getString("title"),
                    kind = TelegramMediaKind.valueOf(o.getString("kind")),
                    summary = o.optString("summary").takeIf(String::isNotBlank),
                    thumbnailUrl = safePreview(o.optString("thumb")),
                    durationMs = o.optLong("duration"), fileSize = o.optLong("size"),
                    publishedAtMs = o.optLong("published"),
                    youtubeVideoId = o.optString("youtube").takeIf(String::isNotBlank),
                    protectedContent = o.optBoolean("protected"),
                )
            }.getOrNull() }
        } }

    suspend fun channels(account: String): List<TelegramChannel> =
        withContext(Dispatchers.IO) { lock.withLock {
            val array = read(account, "channels") ?: return@withLock emptyList()
            (0 until array.length()).mapNotNull { i -> runCatching {
                val o = array.getJSONObject(i)
                TelegramChannel(
                    chatId = o.getLong("chat"), title = o.getString("title"),
                    description = o.optString("description").takeIf(String::isNotBlank),
                    thumbnailUrl = safePreview(o.optString("thumb")),
                    subscribed = if (o.has("member")) o.optBoolean("member") else null)
            }.getOrNull() }
        } }

    suspend fun saveMedia(account: String, section: String, items: List<TelegramMedia>, replace: Boolean) =
        withContext(Dispatchers.IO) { lock.withLock {
            runCatching {
                val past = if (replace) emptyList() else mediaUnsafe(account, section)
                // Pages arrive newest first. Keep the first 240, not the oldest 240;
                // after deep scrolling Home must still hydrate with recent media.
                val selected = (past + items).distinctBy(TelegramMedia::mediaKey).take(240)
                val array = JSONArray()
                selected.forEach { m -> array.put(JSONObject()
                    .put("chat", m.chatId).put("message", m.messageId)
                    .put("channel", m.channel).put("title", m.title).put("kind", m.kind.name)
                    .put("summary", m.summary).put("thumb", m.thumbnailUrl)
                    .put("duration", m.durationMs).put("size", m.fileSize)
                    .put("published", m.publishedAtMs).put("youtube", m.youtubeVideoId)
                    .put("protected", m.protectedContent))
                }
                write(account, "media:$section", array)
            }
        } }

    private fun mediaUnsafe(account: String, section: String): List<TelegramMedia> {
        val array = read(account, "media:$section") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> runCatching {
            val o = array.getJSONObject(i)
            TelegramMedia(o.getLong("chat"), o.getInt("message"), o.getString("channel"),
                o.getString("title"), TelegramMediaKind.valueOf(o.getString("kind")),
                summary = o.optString("summary").takeIf(String::isNotBlank),
                thumbnailUrl = safePreview(o.optString("thumb")),
                durationMs = o.optLong("duration"), fileSize = o.optLong("size"),
                publishedAtMs = o.optLong("published"),
                youtubeVideoId = o.optString("youtube").takeIf(String::isNotBlank),
                protectedContent = o.optBoolean("protected"))
        }.getOrNull() }
    }

    suspend fun saveChannels(account: String, entries: List<TelegramChannel>, replace: Boolean) =
        withContext(Dispatchers.IO) { lock.withLock {
            runCatching {
                val past = if (replace) emptyList() else {
                    val array = read(account, "channels")
                    (0 until (array?.length() ?: 0)).mapNotNull { i -> runCatching {
                        val o = array!!.getJSONObject(i)
                        TelegramChannel(o.getLong("chat"), o.getString("title"),
                            description = o.optString("description").takeIf(String::isNotBlank),
                            thumbnailUrl = safePreview(o.optString("thumb")),
                            subscribed = if (o.has("member")) o.optBoolean("member") else null)
                    }.getOrNull() }
                }
                val array = JSONArray()
                (past + entries).distinctBy(TelegramChannel::chatId).take(240).forEach { c ->
                    array.put(JSONObject().put("chat", c.chatId).put("title", c.title)
                        .put("description", c.description).put("thumb", c.thumbnailUrl)
                        .also { obj -> c.subscribed?.let { obj.put("member", it) } })
                }
                write(account, "channels", array)
            }
        } }

    suspend fun clearAccount(account: String) =
        withContext(Dispatchers.IO) { lock.withLock {
            val prefix = hash(account) + "-"
            root.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        } }
}

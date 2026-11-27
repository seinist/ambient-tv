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
    // Accessed only under lock. Never persisted outside the encrypted snapshot.
    private var cachedKey: SecretKey? = null
    private data class Snapshot(val modified: Long, val rows: JSONArray,
        var media: List<TelegramMedia>? = null, var channels: List<TelegramChannel>? = null)
    private val snapshots = LinkedHashMap<File, Snapshot>(16, 0.75f, true)
    private fun remember(target: File, rows: JSONArray) {
        val previous = snapshots[target]
        snapshots[target] = if (previous != null && previous.rows === rows)
            previous.copy(modified = target.lastModified()) else Snapshot(target.lastModified(), rows)
        while (snapshots.size > 12) snapshots.remove(snapshots.keys.first())
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ((store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()).also { cachedKey = it }
    }

    private fun hash(raw: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0xf])
            }
        }
    }
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
        if (!target.isFile || System.currentTimeMillis() - target.lastModified() > ttlMillis) {
            snapshots.remove(target)
            return null
        }
        snapshots[target]?.takeIf { it.modified == target.lastModified() }?.let { return it.rows }
        return runCatching {
            val data = AtomicFile(target).readFully()
            JSONObject(String(decrypt(data), Charsets.UTF_8)).takeIf {
                it.optInt("version") == 1 && it.optString("section") == section
            }?.optJSONArray("rows")?.also { remember(target, it) }
        }.getOrNull()
    }
    private fun write(account: String, section: String, entries: JSONArray, stillAuthorized: () -> Boolean) {
        if (!stillAuthorized()) return
        val destination = file(account, section)
        // A refresh with identical contents need not compress/encrypt/write again.
        if (destination.isFile && System.currentTimeMillis() - destination.lastModified() <= ttlMillis &&
            snapshots[destination]?.takeIf { it.modified == destination.lastModified() }
                ?.rows?.toString() == entries.toString()) {
            // A successful network refresh renews the offline cache TTL even when
            // the content is unchanged. Fall through to an atomic write if touch fails.
            if (stillAuthorized() && destination.setLastModified(System.currentTimeMillis())) {
                snapshots[destination]?.let { remember(destination, it.rows) }
                return
            }
        }
        val target = AtomicFile(destination)
        val json = JSONObject().put("version", 1).put("section", section)
            .put("rows", entries).toString().toByteArray(Charsets.UTF_8)
        val bytes = encrypt(json)
        if (!stillAuthorized()) return
        val out = target.startWrite()
        try {
            out.write(bytes)
            if (!stillAuthorized()) { target.failWrite(out); return }
            target.finishWrite(out)
            remember(destination, entries)
        }
        catch (failure: Exception) { target.failWrite(out); throw failure }
    }

    private fun safePreview(url: String?): String? =
        url?.takeIf { it.isNotBlank() && (!it.startsWith("file:") ||
            runCatching { File(java.net.URI(it)).isFile }.getOrDefault(false)) }

    private fun youtubeLinks(item: JSONObject): List<String> {
        val values = item.optJSONArray("youtubeLinks") ?: return emptyList()
        return java.util.Collections.unmodifiableList((0 until values.length()).mapNotNull { index ->
            values.optString(index).takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        })
    }

    suspend fun media(account: String, section: String): List<TelegramMedia> =
        withContext(Dispatchers.IO) { lock.withLock {
            mediaUnsafe(account, section).map { entry ->
                val thumb = safePreview(entry.thumbnailUrl)
                if (thumb == entry.thumbnailUrl) entry else entry.copy(thumbnailUrl = thumb)
            }
        } }

    private fun channelsUnsafe(account: String): List<TelegramChannel> {
        val array = read(account, "channels") ?: return emptyList()
        val snapshot = snapshots[file(account, "channels")]
        snapshot?.channels?.let { return it }
        val decoded = (0 until array.length()).mapNotNull { i -> runCatching {
            val o = array.getJSONObject(i)
            TelegramChannel(o.getLong("chat"), o.getString("title"),
                description = o.optString("description").takeIf(String::isNotBlank),
                thumbnailUrl = o.optString("thumb").takeIf(String::isNotBlank),
                subscribed = if (o.has("member")) o.optBoolean("member") else null)
        }.getOrNull() }
        return java.util.Collections.unmodifiableList(decoded).also { snapshot?.channels = it }
    }

    suspend fun channels(account: String): List<TelegramChannel> =
        withContext(Dispatchers.IO) { lock.withLock {
            channelsUnsafe(account).map { entry ->
                val thumb = safePreview(entry.thumbnailUrl)
                if (thumb == entry.thumbnailUrl) entry else entry.copy(thumbnailUrl = thumb)
            }
        } }

    suspend fun saveMedia(account: String, section: String, items: List<TelegramMedia>, replace: Boolean,
        stillAuthorized: () -> Boolean = { true }) =
        withContext(Dispatchers.IO) { lock.withLock {
            runCatching {
                if (!stillAuthorized()) return@runCatching
                val past = if (replace) emptyList() else mediaUnsafe(account, section)
                // Pages arrive newest first. Keep the first 240, not the oldest 240;
                // after deep scrolling Home must still hydrate with recent media.
                if (!replace && past.size >= 240) return@runCatching
                val selected = (past + items).distinctBy(TelegramMedia::mediaKey).take(240)
                val array = JSONArray()
                selected.forEach { m -> array.put(JSONObject()
                    .put("chat", m.chatId).put("message", m.messageId)
                    .put("channel", m.channel).put("title", m.title).put("kind", m.kind.name)
                    .put("summary", m.summary).put("thumb", m.thumbnailUrl)
                    .put("duration", m.durationMs).put("size", m.fileSize)
                    .put("published", m.publishedAtMs).put("youtube", m.youtubeVideoId)
                    .put("youtubeLinks", JSONArray(m.linkedYouTubeVideoIds))
                    .put("protected", m.protectedContent)
                    .put("width", m.width).put("height", m.height).put("file", m.fileName)
                    .put("nativeMediaKey", m.nativeMediaKey))
                }
                if (stillAuthorized()) write(account, "media:$section", array, stillAuthorized)
            }
        } }

    private fun mediaUnsafe(account: String, section: String): List<TelegramMedia> {
        val array = read(account, "media:$section") ?: return emptyList()
        val snapshot = snapshots[file(account, "media:$section")]
        snapshot?.media?.let { return it }
        val decoded = (0 until array.length()).mapNotNull { i -> runCatching {
            val o = array.getJSONObject(i)
            TelegramMedia(o.getLong("chat"), o.getInt("message"), o.getString("channel"),
                o.getString("title"), TelegramMediaKind.valueOf(o.getString("kind")),
                summary = o.optString("summary").takeIf(String::isNotBlank),
                thumbnailUrl = o.optString("thumb").takeIf(String::isNotBlank),
                durationMs = o.optLong("duration"), fileSize = o.optLong("size"),
                publishedAtMs = o.optLong("published"),
                youtubeVideoId = o.optString("youtube").takeIf(String::isNotBlank),
                protectedContent = o.optBoolean("protected"),
                linkedYouTubeVideoIds = youtubeLinks(o),
                width = o.optInt("width").coerceAtLeast(0),
                height = o.optInt("height").coerceAtLeast(0),
                fileName = o.optString("file").takeIf(String::isNotBlank),
                nativeMediaKey = o.optString("nativeMediaKey").takeIf(String::isNotBlank))
        }.getOrNull() }
        return java.util.Collections.unmodifiableList(decoded).also { snapshot?.media = it }
    }

    suspend fun saveChannels(account: String, entries: List<TelegramChannel>, replace: Boolean,
        stillAuthorized: () -> Boolean = { true }) =
        withContext(Dispatchers.IO) { lock.withLock {
            runCatching {
                if (!stillAuthorized()) return@runCatching
                val past = if (replace) emptyList() else channelsUnsafe(account)
                val array = JSONArray()
                (past + entries).distinctBy(TelegramChannel::chatId).take(240).forEach { c ->
                    array.put(JSONObject().put("chat", c.chatId).put("title", c.title)
                        .put("description", c.description).put("thumb", c.thumbnailUrl)
                        .also { obj -> c.subscribed?.let { obj.put("member", it) } })
                }
                if (stillAuthorized()) write(account, "channels", array, stillAuthorized)
            }
        } }

    /** Remove edited/deleted posts from all persisted sections, including unopened ones. */
    suspend fun invalidateMedia(account: String, messages: Set<Pair<Long, Int>>, all: Boolean) =
        withContext(Dispatchers.IO) { lock.withLock {
            val prefix = hash(account) + "-"
            root.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.endsWith(".bin") }
                .forEach { target ->
                    val decoded = runCatching {
                        JSONObject(String(decrypt(AtomicFile(target).readFully()), Charsets.UTF_8))
                    }.getOrNull()
                    if (decoded == null) {
                        snapshots.remove(target)
                        AtomicFile(target).delete()
                        return@forEach
                    }
                    val section = decoded.optString("section")
                    if (!section.startsWith("media:")) return@forEach
                    val rows = decoded.optJSONArray("rows") ?: return@forEach
                    val filtered = JSONArray()
                    if (!all) for (index in 0 until rows.length()) {
                        val item = rows.optJSONObject(index) ?: continue
                        if ((item.optLong("chat") to item.optInt("message")) !in messages) filtered.put(item)
                    }
                    if (filtered.length() != rows.length()) {
                        val modified = target.lastModified()
                        snapshots.remove(target)
                        // A deletion must never renew the offline TTL; fail closed on write failure.
                        try {
                            write(account, section, filtered) { true }
                            target.setLastModified(modified)
                            remember(target, filtered)
                        } catch (_: Exception) {
                            snapshots.remove(target)
                            AtomicFile(target).delete()
                        }
                    }
                }
        } }

    suspend fun clearAccount(account: String) =
        withContext(Dispatchers.IO) { lock.withLock {
            val prefix = hash(account) + "-"
            snapshots.keys.removeAll { it.name.startsWith(prefix) }
            root.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        } }
}

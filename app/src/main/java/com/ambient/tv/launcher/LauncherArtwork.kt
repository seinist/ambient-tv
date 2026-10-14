package com.ambient.tv.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Posters for the launcher's rows. Server artwork URLs can carry credentials (Plex appends
 * X-Plex-Token), and those must never be written into the TV provider. The image is fetched once
 * by the app and handed out through [LauncherArtworkProvider] under an opaque content URI.
 */
internal object LauncherArtwork {
    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val RETRY_AFTER_MS = 15 * 60_000L
    /** URLs that failed recently: a dead or slow server must not stall every launcher refresh. */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Last [fetch] skipped a recently failed URL instead of trying it. */
    @Volatile var lastSkipped = false
        private set
    private val NAME = Regex("[0-9a-f]{40}")

    fun dir(context: Context): File = File(context.cacheDir, "launcher_art").apply { mkdirs() }

    private fun nameFor(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun uri(context: Context, name: String): Uri = Uri.parse("content://${context.packageName}.launcherart/$name")

    fun isValidName(name: String?): Boolean = name != null && NAME.matches(name)

    /** Downloads [url] once (blocking; call off the main thread) and returns its content URI, or null. */
    fun fetch(context: Context, url: String?, headers: Map<String, String> = emptyMap()): Uri? {
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        val name = nameFor(url)
        val target = File(dir(context), name)
        lastSkipped = false
        if (target.isFile && target.length() > 0) return uri(context, name)
        val now = System.currentTimeMillis()
        failures[name]?.let { failedAt -> if (now - failedAt < RETRY_AFTER_MS) { lastSkipped = true; return null } }
        if (failures.size > 512) failures.clear()
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
                // Auth headers (TVHeadend, Jellyfin/Emby) must not follow a redirect to another host.
                instanceFollowRedirects = false
                com.ambient.tv.network.CredentialScope.scopedHeaders(url, headers).forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                // A login page or error document is not a poster (some servers send images untyped).
                val type = connection.contentType.orEmpty().lowercase()
                if (type.startsWith("text/") || "json" in type || "xml" in type && !type.startsWith("image/")) return@runCatching null
                val partial = File(dir(context), "$name.part")
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) { partial.delete(); return@runCatching null }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (!partial.renameTo(target)) { partial.delete(); return@runCatching null }
                uri(context, name)
            } finally {
                connection.disconnect()
            }
        }.getOrNull().also { if (it == null) failures[name] = now else failures.remove(name) }
    }

    /** Removes cached posters no row references any more. */
    fun prune(context: Context, keep: Set<Uri>) {
        val names = keep.mapNotNull { it.lastPathSegment }.toSet()
        dir(context).listFiles()?.forEach { file -> if (file.name !in names) file.delete() }
    }
}

/** Read-only, posters only: the launcher reads the content URIs written into its rows. */
class LauncherArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Launcher artwork is read-only")
        val context = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment?.takeIf(LauncherArtwork::isValidName) ?: throw FileNotFoundException()
        val file = File(LauncherArtwork.dir(context), name)
        if (!file.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

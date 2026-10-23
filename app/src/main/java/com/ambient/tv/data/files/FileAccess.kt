package com.ambient.tv.data.files

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import com.ambient.tv.vpn.WireGuardRouteController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

@kotlinx.serialization.Serializable
data class FileListing(val entries: List<FileEntry>, val more: Boolean)

class WireGuardUnavailableException(message: String?) : java.io.IOException(message)

@Singleton
class FileAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: FileStore,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val wake: WakeOnLan,
    private val wireGuardRoute: WireGuardRouteController,
) {
    private suspend fun <T> observedIO(source: FileSource, action: () -> T): T =
        runInterruptible(Dispatchers.IO) { observe(source, action) }

    private fun <T> observe(source: FileSource, action: () -> T): T {
        val check = if (source.kind == "local") null else hosts.beginEndpoint("files:${source.id}", source.host, source.port)
        try {
            return action().also { hosts.online(check) }
        } catch (error: java.io.IOException) {
            if (!Thread.currentThread().isInterrupted) hosts.failure(check)
            throw error
        }
    }

    private val smb2 = SmbAccess()
    private val smb1 = Smb1Access()
    private val nfs = NfsAccess()
    private val webdav = WebDavAccess(okhttp3.OkHttpClient())
    private val sftp = SftpAccess()
    private fun smb(source: FileSource): SmbProtocol = if (source.smbVersion == 1) smb1 else smb2
    fun root(source: FileSource): String = if (source.kind == "local") DocumentsContract.getTreeDocumentId(Uri.parse(source.treeUri)) else ""
    fun uri(source: FileSource, path: String): Uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(source.treeUri), path)
    internal suspend fun list(source: FileSource, path: String, limit: Int = 300, accept: (FileEntry) -> Boolean = FileEntry::playable): FileListing {
        prepare(source)
        return observedIO(source) {
        val listing = if (source.kind == "smb") smb(source).list(source, path, store.password(source), limit, accept) else if (source.kind == "nfs") nfs.list(source, path, limit, accept)
        else if (source.kind == FileKinds.WEBDAV) webdav.list(source, path, store.password(source), limit, accept)
        else if (source.kind == FileKinds.SFTP) sftp.list(source, path, store.password(source), store.sshKey(source), limit, accept) else {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(source.treeUri), path)
            val entries = mutableListOf<FileEntry>(); var more = false
            context.contentResolver.query(children, COLUMNS, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val entry = entry(source, cursor)
                    if (!entry.directory && !accept(entry)) continue
                    if (entries.size >= limit) { more = true; break }; entries += entry
                }
            } ?: throw java.io.FileNotFoundException()
            FileListing(entries, more)
        }
        listing.copy(entries = listing.entries.sortedWith(compareBy<FileEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }))
    }
    }
    /** Reads a small text file such as a WireGuard config; larger files are rejected instead of truncated. */
    suspend fun readText(source: FileSource, path: String, maxBytes: Int = 64 * 1024): String {
        prepare(source)
        return observedIO(source) {
            open(source, path).use { media ->
                if (media.size > maxBytes) throw java.io.IOException("File too large")
                val out = java.io.ByteArrayOutputStream(media.size.toInt()); val chunk = ByteArray(8 * 1024); var position = 0L
                while (position < media.size) {
                    val read = media.read(position, chunk, minOf(chunk.size.toLong(), media.size - position).toInt())
                    if (read <= 0) break
                    out.write(chunk, 0, read); position += read
                }
                // UTF-8, UTF-8 with BOM or the UTF-16 that Windows tools write.
                com.ambient.tv.vpn.decodeWireGuardBytes(out.toByteArray())
            }
        }
    }
    /** Brings up the WireGuard route (if this source uses it), then wakes the NAS when configured. */
    suspend fun prepare(source: FileSource) {
        wireGuardRoute.ensureForFileSource(source).getOrElse { throw WireGuardUnavailableException(it.message) }
        wake.ensureAwake(source)
    }
    suspend fun testNfs(source: FileSource) = observedIO(source) { nfs.list(source, "", 1); Unit }
    suspend fun testSmb(source: FileSource, password: String) = observedIO(source) { smb(source).list(source, "", password, 1); Unit }
    suspend fun testWebDav(source: FileSource, password: String) = observedIO(source) { webdav.test(source, password) }
    suspend fun testSftp(source: FileSource, password: String, privateKey: String) = observedIO(source) { sftp.test(source, password, privateKey) }
    suspend fun stat(source: FileSource, path: String): FileEntry {
        prepare(source)
        return observedIO(source) {
        if (source.kind == "smb") smb(source).stat(source, path, store.password(source)) else if (source.kind == "nfs") nfs.stat(source, path)
        else if (source.kind == FileKinds.WEBDAV) webdav.stat(source, path, store.password(source))
        else if (source.kind == FileKinds.SFTP) sftp.stat(source, path, store.password(source), store.sshKey(source)) else {
            context.contentResolver.query(uri(source, path), COLUMNS, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw java.io.FileNotFoundException()
                entry(source, cursor)
            } ?: throw java.io.FileNotFoundException()
        }
    }
    }
    /** Called only by the bridge's bounded IO workers. A new read handle belongs to one request. */
    fun open(source: FileSource, path: String): SeekableMedia {
        val media = observe(source) { openSource(source, path) }
        if (source.kind == "local") return media
        val binding = hosts.beginEndpoint("files:${source.id}", source.host, source.port)
        return object : SeekableMedia {
            override val size get() = media.size
            override fun read(position: Long, buffer: ByteArray, count: Int): Int {
                val check = hosts.nextCheck(binding)
                try { return media.read(position, buffer, count) }
                catch (error: java.io.IOException) { hosts.failure(check); throw error }
            }
            override fun close() = media.close()
        }
    }

    private fun openSource(source: FileSource, path: String): SeekableMedia {
        store.withCurrentSource(source) { Unit }
        if (source.kind == "smb") return smb(source).open(source, path, store.password(source))
        if (source.kind == "nfs") return nfs.open(source, path)
        if (source.kind == FileKinds.WEBDAV) return webdav.open(source, path, store.password(source))
        if (source.kind == FileKinds.SFTP) return sftp.open(source, path, store.password(source), store.sshKey(source))
        val descriptor = context.contentResolver.openFileDescriptor(uri(source, path), "r") ?: throw java.io.FileNotFoundException()
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        try {
            val channel = input.channel
            channel.position(0) // Reject a provider pipe before advertising a seekable video.
            val length = channel.size()
            require(length >= 0) { "Document is not seekable" }
            return object : SeekableMedia {
                override val size = length
                override fun read(position: Long, buffer: ByteArray, count: Int): Int = channel.read(ByteBuffer.wrap(buffer, 0, count), position)
                override fun close() = input.close()
            }
        } catch (error: Throwable) { input.close(); throw error }
    }
    private fun entry(source: FileSource, c: Cursor): FileEntry {
        val mime = c.getString(2).orEmpty()
        return FileEntry(source.id, c.getString(0), c.getString(1).orEmpty(), mime == DocumentsContract.Document.MIME_TYPE_DIR,
            if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4), mime)
    }
    companion object {
        private val COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    }
}

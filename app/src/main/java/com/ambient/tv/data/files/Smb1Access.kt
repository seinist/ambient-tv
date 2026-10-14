package com.ambient.tv.data.files

import java.util.Properties
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.SmbConstants
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile

/**
 * Legacy SMB1/CIFS for old NAS and routers that offer nothing newer. Only used when the source is
 * explicitly set to SMB1: SMB1 and SMB2 are pinned per source, there is no automatic downgrade.
 * Read-only, no DFS, no NetBIOS/browser scanning, no credentials in URLs.
 */
class Smb1Access : SmbProtocol {
    private fun context(source: FileSource, password: String): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB1"); setProperty("jcifs.smb.client.maxVersion", "SMB1")
            setProperty("jcifs.smb.client.connTimeout", "4000"); setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.soTimeout", "20000"); setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.resolveOrder", "DNS"); setProperty("jcifs.smb.client.port445", "true")
        }
        val base = BaseContext(PropertyConfiguration(properties))
        return if (source.guest) base.withGuestCrendentials() else base.withCredentials(NtlmPasswordAuthenticator(source.domain, source.username, password))
    }

    /** Builds the resource name by name, never by URL parsing, so '#', '%' or '?' in names stay literal. */
    private fun file(source: FileSource, relative: String, password: String, directory: Boolean): SmbFile {
        source.validated()
        val context = context(source, password)
        // IPv6 literals need brackets in the server URL; the share joins by name like every path part.
        val host = if (':' in source.host) "[${source.host}]" else source.host
        var file = SmbFile(SmbFile("smb://$host:${source.port}/", context), "${source.share}/")
        val parts = (FilePaths.relative(source.rootPath).split('/') + FilePaths.relative(relative).split('/')).filter(String::isNotEmpty)
        parts.forEachIndexed { index, name -> file = SmbFile(file, if (directory || index < parts.lastIndex) "$name/" else name) }
        return file
    }

    /** Each call owns its context: closing it drops the pooled SMB1 transport as well. */
    private inline fun <T> SmbFile.release(block: (SmbFile) -> T): T = try { block(this) } finally { runCatching { close() }; runCatching { context.close() } }

    private fun entry(source: FileSource, relative: String, file: SmbFile, name: String): FileEntry {
        val folder = file.isDirectory
        return FileEntry(source.id, relative, name, folder, if (folder) 0 else file.length(), file.lastModified(), FilePaths.mime(name))
    }

    override fun list(source: FileSource, relative: String, password: String, limit: Int, accept: (FileEntry) -> Boolean): FileListing =
        file(source, relative, password, directory = true).release { directory ->
            val entries = mutableListOf<FileEntry>(); var more = false
            directory.children().use { children ->
                while (children.hasNext()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val child = children.next() as SmbFile
                    child.use {
                        val name = child.name.trimEnd('/')
                        val path = runCatching { FilePaths.child(relative, name) }.getOrNull() ?: return@use
                        val entry = entry(source, path, child, name)
                        if (!entry.directory && !accept(entry)) return@use
                        if (entries.size >= limit) { more = true; return@use }
                        entries += entry
                    }
                    if (more) break
                }
            }
            FileListing(entries, more)
        }

    override fun stat(source: FileSource, relative: String, password: String): FileEntry {
        val path = FilePaths.relative(relative)
        return file(source, path, password, directory = path.isEmpty()).release { entry(source, path, it, path.substringAfterLast('/').ifEmpty { source.name }) }
    }

    override fun open(source: FileSource, relative: String, password: String): SeekableMedia {
        val file = file(source, relative, password, directory = false)
        try {
            if (file.isDirectory) throw java.io.FileNotFoundException("Not a file")
            val length = file.length()
            return object : SeekableMedia {
                override val size = length
                // SmbRandomAccessFile opens with OPEN_IF, which read-only shares may refuse: plain
                // FILE_OPEN streams instead, reopened (skip only moves the offset) on every seek.
                private var stream: java.io.InputStream? = null
                private var streamPosition = 0L
                override fun read(position: Long, buffer: ByteArray, count: Int): Int = synchronized(this) {
                    if (stream == null || position != streamPosition) {
                        stream?.close(); stream = null
                        val opened = file.openInputStream(SmbConstants.O_RDONLY, SmbConstants.FILE_READ_DATA,
                            SmbConstants.FILE_SHARE_READ or SmbConstants.FILE_SHARE_WRITE or SmbConstants.FILE_SHARE_DELETE)
                        var remaining = position
                        while (remaining > 0) { val skipped = opened.skip(remaining); if (skipped <= 0) { opened.close(); throw java.io.EOFException() }; remaining -= skipped }
                        stream = opened; streamPosition = position
                    }
                    val read = stream!!.read(buffer, 0, count)
                    if (read > 0) streamPosition += read
                    read
                }
                override fun close() { file.release { synchronized(this) { stream?.close() } } }
            }
        } catch (error: Throwable) { file.release { }; throw error }
    }
}

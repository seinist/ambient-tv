package com.ambient.tv.data.files

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** Explicit SMB2/3 source; no scanning, SMB1 fallback, credential URL or write permissions. */
class SmbAccess : SmbProtocol {
    private class Lease(val client: SMBClient, val connection: Connection, val session: Session, val share: DiskShare) : Closeable {
        override fun close() {
            runCatching { share.close() }; runCatching { session.close() }
            runCatching { connection.close() }; runCatching { client.close() }
        }
    }
    private class GuestLogin(val connection: Connection, val session: Session)

    /**
     * Guest shares: anonymous NTLM first, then the "Guest" account with an empty password. Over
     * SMB 3.x a server that accepts the anonymous login without flagging it as guest/null leaves
     * smbj without a session key (NullPointerException in key derivation); the named guest login
     * carries a key and works there.
     */
    private fun guestSession(client: SMBClient, first: Connection, source: FileSource): GuestLogin {
        return try {
            GuestLogin(first, first.authenticate(AuthenticationContext.anonymous()))
        } catch (error: Exception) {
            runCatching { first.close() }
            val retry = client.connect(source.host, source.port)
            try {
                GuestLogin(retry, retry.authenticate(AuthenticationContext.guest()))
            } catch (second: Exception) {
                runCatching { retry.close() }
                second.addSuppressed(error)
                throw second
            }
        }
    }

    private fun connect(source: FileSource, password: String): Lease {
        source.validated()
        val client = SMBClient(SmbConfig.builder()
            .withSocketFactory(com.hierynomus.protocol.commons.socket.ProxySocketFactory(4000))
            .withTimeout(15, TimeUnit.SECONDS).withSoTimeout(20, TimeUnit.SECONDS)
            .withReadBufferSize(1024 * 1024).withDfsEnabled(false)
            .withAuthenticators(NtlmAuthenticator.Factory()).withEncryptData(source.encrypt).build())
        var connection: Connection? = null; var session: Session? = null
        val secret = password.toCharArray()
        val auth = if (source.guest) AuthenticationContext.anonymous() else AuthenticationContext(source.username, secret, source.domain)
        secret.fill('\u0000')
        try {
            val first = client.connect(source.host, source.port).also { connection = it }
            val login = if (source.guest) guestSession(client, first, source) else GuestLogin(first, first.authenticate(auth))
            connection = login.connection; session = login.session
            val share = login.session.connectShare(source.share)
            require(share is DiskShare) { "Not a disk share" }
            return Lease(client, login.connection, login.session, share)
        } catch (error: Throwable) {
            runCatching { session?.close() }; runCatching { connection?.close() }; runCatching { client.close() }; throw error
        } finally { auth.password.fill('\u0000') }
    }
    private fun path(source: FileSource, relative: String): String = listOf(FilePaths.relative(source.rootPath), FilePaths.relative(relative))
        .filter(String::isNotEmpty).joinToString("/").replace('/', '\\')

    override fun list(source: FileSource, relative: String, password: String, limit: Int, accept: (FileEntry) -> Boolean): FileListing = connect(source, password).use { lease ->
        lease.share.openDirectory(path(source, relative), setOf(AccessMask.FILE_LIST_DIRECTORY), setOf(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, setOf(SMB2CreateOptions.FILE_DIRECTORY_FILE)).use { directory ->
            val entries = mutableListOf<FileEntry>()
            val iterator = directory.iterator(FileIdBothDirectoryInformation::class.java)
            var more = false
            while (iterator.hasNext()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val info = iterator.next(); val name = info.fileName
                if (name == "." || name == "..") continue
                val folder = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                val entry = FileEntry(source.id, FilePaths.child(relative, name), name, folder, info.endOfFile, info.lastWriteTime.toEpochMillis(), FilePaths.mime(name))
                if (!folder && !accept(entry)) continue
                if (entries.size >= limit) { more = true; break }
                entries += entry
            }
            FileListing(entries, more)
        }
    }
    override fun stat(source: FileSource, relative: String, password: String): FileEntry = connect(source, password).use { lease ->
        val info = lease.share.getFileInformation(path(source, relative))
        FileEntry(source.id, FilePaths.relative(relative), relative.substringAfterLast('/').ifEmpty { source.name }, info.standardInformation.isDirectory,
            info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.toEpochMillis(), FilePaths.mime(relative))
    }
    override fun open(source: FileSource, relative: String, password: String): SeekableMedia {
        val lease = connect(source, password)
        try {
            val file = lease.share.openFile(path(source, relative), setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES), setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            return object : SeekableMedia {
                override val size = file.fileInformation.standardInformation.endOfFile
                override fun read(position: Long, buffer: ByteArray, count: Int) = file.read(buffer, position, 0, count)
                // smbj matches responses to requests by message id: concurrent reads share the session.
                override val parallelReads = true
                override fun close() { try { file.close() } finally { lease.close() } }
            }
        } catch (error: Throwable) { lease.close(); throw error }
    }
}

/** One SMB dialect family; a source is pinned to exactly one of them. */
interface SmbProtocol {
    fun list(source: FileSource, relative: String, password: String, limit: Int, accept: (FileEntry) -> Boolean = FileEntry::playable): FileListing
    fun stat(source: FileSource, relative: String, password: String): FileEntry
    fun open(source: FileSource, relative: String, password: String): SeekableMedia
}

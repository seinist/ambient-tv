package com.ambient.tv.data.files

import java.io.Closeable
import java.security.PublicKey
import java.security.Security
import java.util.EnumSet
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Read-only SFTP. Login with password or private key (the password slot then holds the key's
 * passphrase). The host key must have been confirmed in the editor: an unknown key fails with
 * its fingerprint instead of being accepted silently, a changed key always fails.
 */
class SftpAccess {
    private class Lease(val ssh: SSHClient, val sftp: SFTPClient, val start: String) : Closeable {
        override fun close() { runCatching { sftp.close() }; runCatching { ssh.disconnect() }; runCatching { ssh.close() } }
    }

    private fun connect(source: FileSource, password: String, privateKey: String): Lease {
        source.validated()
        ensureBouncyCastle()
        val ssh = SSHClient(DefaultConfig())
        var seen: Pair<String, Boolean>? = null // fingerprint, matches
        ssh.connectTimeout = 10_000
        ssh.timeout = 30_000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fingerprint = sshFingerprint(key)
                val ok = source.serverKey.isNotEmpty() && fingerprint == source.serverKey
                seen = fingerprint to ok
                return ok
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            try {
                ssh.connect(source.host, source.port)
            } catch (error: Exception) {
                val (fingerprint, ok) = seen ?: throw error
                if (!ok) throw if (source.serverKey.isEmpty()) UnverifiedServerKeyException(fingerprint, certificate = false)
                    else ServerKeyChangedException(fingerprint, certificate = false)
                throw error
            }
            try {
                if (privateKey.isNotBlank()) {
                    val finder = password.takeIf(String::isNotEmpty)?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    ssh.authPublickey(source.username, ssh.loadKeys(privateKey, null, finder))
                } else {
                    ssh.authPassword(source.username, password)
                }
            } catch (error: Exception) {
                throw SecurityException("SFTP login failed", error)
            }
            val sftp = ssh.newSFTPClient()
            val start = source.share.ifEmpty { runCatching { sftp.canonicalize(".") }.getOrDefault("/") }.trimEnd('/')
            return Lease(ssh, sftp, start)
        } catch (error: Throwable) {
            runCatching { ssh.disconnect() }; runCatching { ssh.close() }
            throw error
        }
    }

    private fun remote(lease: Lease, relative: String): String {
        val path = FilePaths.relative(relative)
        return when {
            path.isEmpty() -> lease.start.ifEmpty { "/" }
            else -> lease.start + "/" + path
        }
    }

    fun list(source: FileSource, relative: String, password: String, privateKey: String, limit: Int,
             accept: (FileEntry) -> Boolean = FileEntry::playable): FileListing = connect(source, password, privateKey).use { lease ->
        val dir = FilePaths.relative(relative)
        val entries = mutableListOf<FileEntry>(); var more = false
        for (info in lease.sftp.ls(remote(lease, dir))) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val name = info.name
            if (name == "." || name == ".." || name.startsWith('.') || name.any { it.code < 32 || it == '\\' || it == ':' }) continue
            var attributes = info.attributes
            // Symlinks are common on NAS shares (e.g. a media folder linked into home): follow them.
            if (attributes.type == FileMode.Type.SYMLINK) attributes = runCatching { lease.sftp.stat(info.path) }.getOrNull() ?: continue
            val folder = attributes.type == FileMode.Type.DIRECTORY
            val entry = FileEntry(source.id, FilePaths.child(dir, name), name, folder, if (folder) 0 else attributes.size,
                attributes.mtime * 1000, FilePaths.mime(name))
            if (!folder && !accept(entry)) continue
            if (entries.size >= limit) { more = true; break }
            entries += entry
        }
        FileListing(entries, more)
    }

    fun stat(source: FileSource, relative: String, password: String, privateKey: String): FileEntry = connect(source, password, privateKey).use { lease ->
        val path = FilePaths.relative(relative)
        val attributes = lease.sftp.stat(remote(lease, path))
        FileEntry(source.id, path, path.substringAfterLast('/').ifEmpty { source.name }, attributes.type == FileMode.Type.DIRECTORY,
            attributes.size, attributes.mtime * 1000, FilePaths.mime(path))
    }

    fun open(source: FileSource, relative: String, password: String, privateKey: String): SeekableMedia {
        val lease = connect(source, password, privateKey)
        try {
            val file = lease.sftp.open(remote(lease, relative), EnumSet.of(OpenMode.READ))
            val length = file.length()
            return object : SeekableMedia {
                override val size = length
                override fun read(position: Long, buffer: ByteArray, count: Int): Int = file.read(position, buffer, 0, count)
                // SFTP matches replies to requests by id: several reads may be in flight on one channel.
                override val parallelReads = true
                override fun close() { try { file.close() } finally { lease.close() } }
            }
        } catch (error: Throwable) { lease.close(); throw error }
    }

    fun test(source: FileSource, password: String, privateKey: String) { list(source, "", password, privateKey, 1) }

    private companion object {
        private val providerLock = Any()
        @Volatile private var providerReady = false

        /**
         * Android ships a stripped "BC" provider; sshj asks for algorithms by that provider name and
         * needs the full one. Checked 2026-10: nothing else in the app looks "BC" up by name (smbj uses
         * BouncyCastle classes directly, jcifs passes a provider instance, WireGuard uses the math
         * classes), and the provider is appended last, so lookups without a name still reach Conscrypt.
         * Done lazily, only once an SFTP source is actually used.
         */
        fun ensureBouncyCastle() {
            if (providerReady) return
            synchronized(providerLock) {
                if (providerReady) return
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)?.javaClass != BouncyCastleProvider::class.java) {
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                    Security.addProvider(BouncyCastleProvider())
                }
                providerReady = true
            }
        }
    }
}

/** OpenSSH "SHA256:..." fingerprint of a host key, as `ssh-keygen -lf` prints it. */
internal fun sshFingerprint(key: PublicKey): String = sha256Fingerprint(Buffer.PlainBuffer().putPublicKey(key).compactData)

/** Rough check before storing: a private key in OpenSSH, PEM/PKCS#8 or PuTTY format, no public key or config. */
internal fun looksLikePrivateKey(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length !in 64..SSH_KEY_LIMIT) return false
    return (trimmed.startsWith("-----BEGIN ") && trimmed.contains("PRIVATE KEY-----")) ||
        trimmed.startsWith("PuTTY-User-Key-File-")
}

internal const val SSH_KEY_LIMIT = 16 * 1024

package com.ambient.tv.data.files

import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class FileSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: String,
    val treeUri: String = "",
    val host: String = "",
    val port: Int = 445,
    val share: String = "",
    val rootPath: String = "",
    val username: String = "",
    val domain: String = "",
    val guest: Boolean = false,
    val encrypt: Boolean = false,
    val nfsUid: Int = 65534, val nfsGid: Int = 65534, val nfsMountPort: Int = 0,
    /** 2 = SMB 2/3 (smbj); 1 = legacy SMB1/CIFS, only when chosen explicitly (never a fallback). */
    val smbVersion: Int = 2,
    /** 3 = NFSv3 with MOUNT/RPCbind; 4 = NFSv4.0 via the server's pseudo filesystem. */
    val nfsVersion: Int = 3,
    val wolMac: String = "", val wolBroadcast: String = "255.255.255.255", val wolPort: Int = 9,
    val wolAuto: Boolean = false, val wolWaitSeconds: Int = 60,
    /** Route this NAS (and its wake packets) through the configured WireGuard tunnel. */
    val wireGuard: Boolean = false,
    /** WebDAV: HTTPS (default) or plain HTTP. */
    val secure: Boolean = true,
    /**
     * Confirmed server identity: the SFTP host key, or the certificate of an HTTPS server the
     * system does not trust (self-signed NAS). Format "SHA256:<base64>", empty = not confirmed.
     */
    val serverKey: String = "",
) {
    val network: Boolean get() = kind in FileKinds.NETWORK
    val usesPassword: Boolean get() = kind in FileKinds.WITH_PASSWORD
    /** Paths and resume points are reusable only while they still identify the same root. */
    fun sameLocation(other: FileSource): Boolean = kind == other.kind && when (kind) {
        "local" -> treeUri == other.treeUri
        else -> host.equals(other.host, ignoreCase = true) && port == other.port &&
            share == other.share && rootPath == other.rootPath
    }

    fun validated(): FileSource {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(name.isNotBlank() && name.length <= 160)
        require(kind in FileKinds.ALL)
        require(!wireGuard || kind != "local")
        if (kind == "local") {
            val tree = runCatching { java.net.URI(treeUri) }.getOrNull()
            require(tree != null && tree.scheme == "content" && !tree.rawAuthority.isNullOrBlank() &&
                tree.rawPath.orEmpty().startsWith("/tree/") && tree.rawPath.substringAfter("/tree/").substringBefore('/').isNotBlank())
        } else {
            require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\@?#" })
            require(port in 1..65535)
            if (kind == "smb") require(smbVersion in setOf(1, 2) && !(smbVersion == 1 && encrypt))
            if (kind == "smb") require(share.isNotBlank() && share !in setOf(".", "..") && share.length <= 255 && share.none { it in "/\\:;*?\"<>|" })
            if (kind == "nfs") {
                require(share.startsWith("/") && share.length <= 1024)
                FilePaths.relative(share)
                require(nfsUid >= 0 && nfsGid >= 0 && nfsMountPort in 0..65535 && nfsVersion in setOf(3, 4))
            }
            // WebDAV base path (e.g. /remote.php/dav/files/me) or SFTP start folder; empty = server root / home.
            if (kind == "webdav" || kind == "sftp") {
                require(share.isEmpty() || (share.startsWith("/") && share.length <= 1024))
                FilePaths.relative(share)
            }
            if (kind == "sftp") require(username.isNotBlank())
            require(serverKey.isEmpty() || serverKey.matches(Regex("SHA256:[A-Za-z0-9+/=]{20,64}")))
            if (wolMac.isNotBlank()) WakeOnLan.packet(wolMac)
            require(!wolAuto || wolMac.isNotBlank())
            require(wolPort in 1..65535 && wolWaitSeconds in 10..180)
            require(wolBroadcast.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && wolBroadcast.split('.').all { it.toInt() in 0..255 })
            require(username.length <= 256 && domain.length <= 256)
        }
        val cleanShare = if (kind == "webdav" || kind == "sftp") share.trim().trimEnd('/') else share.trim()
        return copy(name = name.trim(), host = host.trim(), share = cleanShare, rootPath = FilePaths.relative(rootPath))
    }
}

/** Source kinds; "smb_password_<id>" in the token store holds the password of every [WITH_PASSWORD] kind. */
object FileKinds {
    const val LOCAL = "local"; const val SMB = "smb"; const val NFS = "nfs"; const val WEBDAV = "webdav"; const val SFTP = "sftp"
    val NETWORK = setOf(SMB, NFS, WEBDAV, SFTP)
    val ALL = NETWORK + LOCAL
    val WITH_PASSWORD = setOf(SMB, WEBDAV, SFTP)
    fun defaultPort(kind: String, secure: Boolean = true): Int = when (kind) {
        NFS -> 2049; WEBDAV -> if (secure) 443 else 80; SFTP -> 22; else -> 445
    }
    fun label(kind: String): String = when (kind) { WEBDAV -> "WebDAV"; SFTP -> "SFTP"; else -> kind.uppercase(Locale.ROOT) }
}

/** The server presented an identity that has not been confirmed yet; the editor offers to trust it. */
class UnverifiedServerKeyException(val fingerprint: String, val certificate: Boolean) :
    java.io.IOException("Server identity not confirmed: $fingerprint")

/** The confirmed identity changed: possibly a reinstalled server, possibly an attack. Never auto-accepted. */
class ServerKeyChangedException(val fingerprint: String, val certificate: Boolean) :
    java.io.IOException("Server identity changed: $fingerprint")

/** OpenSSH-style "SHA256:<base64 without padding>" of a key or certificate encoding. */
internal fun sha256Fingerprint(encoded: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(encoded))

@Serializable
data class FileEntry(
    val sourceId: String, val path: String, val name: String, val directory: Boolean,
    val size: Long = 0, val modified: Long = 0, val mime: String = "",
) {
    val id: String get() = FileKeys.key(sourceId, path)
    val audio: Boolean get() = mime.startsWith("audio/") || FilePaths.extension(name) in FilePaths.audioExtensions
    val playable: Boolean get() = !directory && (audio || mime.startsWith("video/") || FilePaths.extension(name) in FilePaths.videoExtensions)
    val image: Boolean get() = !directory && com.ambient.tv.data.photos.PhotoFormats.isImage(name, mime)
}

@Serializable
data class FileHistory(val entry: FileEntry, val positionMs: Long = 0, val durationMs: Long = 0, val playedAt: Long = 0, val completed: Boolean = false)

object FileKeys {
    fun owns(id: String) = id.startsWith("files:")
    fun key(source: String, path: String): String = "files:$source:" + Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
    fun parse(id: String): Pair<String, String> {
        require(owns(id))
        val parts = id.split(':', limit = 3)
        require(parts.size == 3 && parts[1].matches(Regex("[a-zA-Z0-9-]{1,80}")))
        return parts[1] to String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
    }
}

object FilePaths {
    val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "mts", "mpeg", "mpg", "wmv", "flv", "vob", "ogv")
    val audioExtensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff", "alac", "ape")
    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun relative(path: String): String {
        val parts = path.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." || it.any { c -> c.code < 32 || c == ':' } })
        return parts.joinToString("/")
    }
    fun child(parent: String, name: String): String {
        require(name.isNotBlank() && name.none { it == '/' || it == '\\' })
        return relative(listOf(parent, name).filter(String::isNotEmpty).joinToString("/"))
    }
    fun mime(name: String): String = when (extension(name)) {
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "ts", "m2ts", "mts" -> "video/mp2t"; "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"
        "wav" -> "audio/wav"; "ogg", "opus" -> "audio/ogg"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "bmp" -> "image/bmp"; "heic", "heif" -> "image/heif"; "avif" -> "image/avif"
        else -> "application/octet-stream"
    }
}

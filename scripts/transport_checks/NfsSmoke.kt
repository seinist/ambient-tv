import com.ambient.tv.data.files.*
import java.net.URL
import java.net.HttpURLConnection
fun main() {
    val access = NfsAccess(23111)
    val source = FileSource(name = "NFS fixture", kind = "nfs", host = "127.0.0.1", port = 23049, share = "/media")
    val root = access.list(source, "", 300)
    check(root.entries.size == 2 && !root.more)
    check(root.entries.any { it.name == "Müsik.flac" && it.audio })
    check(access.list(source, "", 1).more)
    val video = access.list(source, "folder", 300).entries.single()
    check(video.name == "sample.mp4" && video.size == 1004000L)
    check(access.stat(source, "folder/sample.mp4").size == video.size)
    access.open(source, "folder/sample.mp4").use { file ->
        val buffer = ByteArray(90000); val n = file.read(70001, buffer, buffer.size)
        check(n in 1..buffer.size)
        repeat(n) { check(buffer[it] == ((70001 + it) % 251).toByte()) }
    }
    MediaFileServer().use { bridge ->
        val c = URL(bridge.register(source.id, "video/mp4") { access.open(source, video.path) }).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.setRequestProperty("Range", "bytes=500000-649999")
        check(c.responseCode == 206)
        val bytes = c.inputStream.use { it.readBytes() }; check(bytes.size == 150000)
        bytes.forEachIndexed { index, byte -> check(byte == ((500000 + index) % 251).toByte()) }; c.disconnect()
    }
    var denied = false
    try { access.stat(source.copy(nfsUid = 999), "folder/sample.mp4") } catch (_: SecurityException) { denied = true }
    check(denied)
    // NFSv4.0 against the same fixture: pseudo-filesystem lookup, paged READDIR, anonymous-stateid READ.
    val v4 = source.copy(nfsVersion = 4)
    val v4Root = access.list(v4, "", 300)
    check(v4Root.entries.map { it.name }.toSet() == setOf("folder", "Müsik.flac")) { v4Root.toString() } // symlink 'link' skipped
    check(access.list(v4, "", 1).more)
    val v4Video = access.list(v4, "folder", 300).entries.single()
    check(v4Video.name == "sample.mp4" && v4Video.size == 1004000L && v4Video.modified == 1700000000005L)
    check(access.stat(v4, "folder").directory)
    access.open(v4, "folder/sample.mp4").use { file ->
        val buffer = ByteArray(90000); val n = file.read(70001, buffer, buffer.size)
        check(n in 1..buffer.size); repeat(n) { check(buffer[it] == ((70001 + it) % 251).toByte()) }
        check(file.read(1004000L, buffer, 10) == -1)
    }
    check(runCatching { access.open(v4, "link") }.exceptionOrNull()?.message?.contains("symbolic") == true)
    check(runCatching { access.stat(v4, "missing.mp4") }.isFailure)
    check(runCatching { access.stat(v4.copy(nfsUid = 999), "folder") }.exceptionOrNull() is SecurityException)
    println("NFSv4.0 fixture: COMPOUND lookup, paged READDIR, attributes, anonymous READ, EOF, symlink, missing file and access-denied handling passed")
    println("NFSv3 fixture: RPCbind/MOUNT, AUTH_SYS, fragmented records, paged directories, UTF-8 names, metadata, positional reads, range playback and access-denied handling passed")
}

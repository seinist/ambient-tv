import com.ambient.tv.data.files.*
import java.net.HttpURLConnection
import java.net.URL

fun main() {
    val access = SmbAccess()
    val source = FileSource(name = "SMB fixture", kind = "smb", host = "127.0.0.1", port = 14450, share = "media", username = "testuser")
    val root = access.list(source, "", "fixture-secret", 300)
    check(root.entries.any { it.name == "folder" && it.directory })
    check(root.entries.any { it.name == "track.flac" && it.audio })
    val folder = access.list(source, "folder", "fixture-secret", 300)
    val entry = folder.entries.single()
    check(entry.name == "sample.mp4" && entry.size == 1004000L)
    check(access.stat(source, "folder/sample.mp4", "fixture-secret").size == entry.size)
    access.open(source, "folder/sample.mp4", "fixture-secret").use { stream ->
        val buffer = ByteArray(65537)
        val n = stream.read(12345, buffer, buffer.size)
        check(n > 0)
        repeat(n) { check(buffer[it] == ((12345 + it) % 251).toByte()) }
    }
    MediaFileServer().use { bridge ->
        val url = bridge.register(source.id, "video/mp4") { access.open(source, entry.path, "fixture-secret") }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000; connection.readTimeout = 10000
        connection.setRequestProperty("Range", "bytes=400000-499999")
        check(connection.responseCode == 206)
        val bytes = connection.inputStream.use { it.readBytes() }
        check(bytes.size == 100000)
        bytes.forEachIndexed { index, byte -> check(byte == ((400000 + index) % 251).toByte()) }
        connection.disconnect()
    }
    var rejected = false
    try { access.list(source, "", "wrong-password", 1) } catch (_: Exception) { rejected = true }
    check(rejected)
    println("SMB2 fixture: login, listing, metadata, positional reads, HTTP range playback and wrong-password rejection passed")
}

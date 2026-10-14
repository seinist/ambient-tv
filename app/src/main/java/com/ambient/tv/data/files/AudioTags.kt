package com.ambient.tv.data.files

import java.nio.charset.Charset

/** The few tags a folder view needs. */
@kotlinx.serialization.Serializable
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
) {
    val isEmpty: Boolean get() = title == null && artist == null && album == null && track == null
}

/**
 * Reads tags from the start of MP3 (ID3v2.2–2.4), FLAC (Vorbis comment) and MP4/M4A (iTunes
 * `ilst`, also when `moov` sits at the end). Only the needed bytes are read, so it works over SMB,
 * NFS, WebDAV and SFTP. Ogg, WMA and APE are not read (the file name is shown instead).
 */
object AudioTagReader {
    private const val MAX_HEADER = 512 * 1024
    private const val MAX_MOOV = 4 * 1024 * 1024

    fun read(media: SeekableMedia, name: String): AudioTags? = runCatching {
        when (FilePaths.extension(name)) {
            "mp3", "aac" -> readId3(media)
            "flac" -> readFlac(media)
            "m4a", "mp4", "alac", "m4b" -> readMp4(media)
            else -> null
        }
    }.getOrNull()?.takeUnless { it.isEmpty }

    private fun bytes(media: SeekableMedia, position: Long, count: Int): ByteArray {
        val out = ByteArray(count); var done = 0
        val part = ByteArray(minOf(count, 256 * 1024))
        while (done < count) {
            val n = media.read(position + done, part, minOf(part.size, count - done))
            if (n <= 0) return out.copyOf(done)
            System.arraycopy(part, 0, out, done, n); done += n
        }
        return out
    }

    // ---- ID3v2 ----

    private fun readId3(media: SeekableMedia): AudioTags? {
        val header = bytes(media, 0, 10)
        if (header.size < 10 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val size = syncsafe(header, 6)
        return parseId3(header + bytes(media, 10, minOf(size, MAX_HEADER)))
    }

    internal fun parseId3(data: ByteArray): AudioTags? {
        if (data.size < 10 || String(data, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        val version = data[3].toInt()
        val flags = data[5].toInt()
        val end = minOf(data.size, 10 + syncsafe(data, 6))
        var pos = 10
        if (flags and 0x40 != 0 && version >= 3) {
            // Extended header: skip it (its size is syncsafe in 2.4, plain in 2.3 and excludes itself).
            pos += if (version == 4) syncsafe(data, pos) else int32(data, pos) + 4
        }
        val frames = mutableMapOf<String, String>()
        val idLength = if (version == 2) 3 else 4
        val headerLength = if (version == 2) 6 else 10
        while (pos + headerLength <= end) {
            val id = String(data, pos, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = when (version) {
                2 -> ((data[pos + 3].toInt() and 0xff) shl 16) or ((data[pos + 4].toInt() and 0xff) shl 8) or (data[pos + 5].toInt() and 0xff)
                4 -> syncsafe(data, pos + 4)
                else -> int32(data, pos + 4)
            }
            val start = pos + headerLength
            if (size <= 0 || start + size > end) break
            if (id.startsWith("T") && id != "TXXX" && id != "TXX") frames.putIfAbsent(id, text(data, start, size))
            pos = start + size
        }
        fun frame(v2: String, v3: String) = (frames[v3] ?: frames[v2])?.trim()?.takeIf(String::isNotEmpty)
        return AudioTags(
            title = frame("TT2", "TIT2"),
            artist = frame("TP1", "TPE1"),
            album = frame("TAL", "TALB"),
            albumArtist = frame("TP2", "TPE2"),
            track = number(frame("TRK", "TRCK")),
            disc = number(frame("TPA", "TPOS")),
            year = (frame("TYE", "TYER") ?: frames["TDRC"])?.take(4)?.toIntOrNull(),
        )
    }

    private fun text(data: ByteArray, start: Int, size: Int): String {
        val encoding = data[start].toInt()
        val charset: Charset = when (encoding) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        // Several values are separated by NUL; the first one is the one to show.
        return String(data, start + 1, size - 1, charset).split('\u0000').firstOrNull { it.isNotBlank() }.orEmpty()
    }

    // ---- FLAC ----

    private fun readFlac(media: SeekableMedia): AudioTags? = parseFlac(bytes(media, 0, MAX_HEADER))

    internal fun parseFlac(data: ByteArray): AudioTags? {
        if (data.size < 8 || String(data, 0, 4, Charsets.ISO_8859_1) != "fLaC") return null
        var pos = 4
        while (pos + 4 <= data.size) {
            val type = data[pos].toInt() and 0x7f
            val last = data[pos].toInt() and 0x80 != 0
            val length = ((data[pos + 1].toInt() and 0xff) shl 16) or ((data[pos + 2].toInt() and 0xff) shl 8) or (data[pos + 3].toInt() and 0xff)
            val start = pos + 4
            if (type == 4 && start + length <= data.size) return vorbisComments(data, start, length)
            if (last) break
            pos = start + length
        }
        return null
    }

    internal fun vorbisComments(data: ByteArray, start: Int, length: Int): AudioTags? {
        var pos = start
        val end = start + length
        val vendor = int32le(data, pos); pos += 4 + vendor
        if (pos + 4 > end) return null
        val count = int32le(data, pos); pos += 4
        val fields = mutableMapOf<String, String>()
        repeat(minOf(count, 1000)) {
            if (pos + 4 > end) return@repeat
            val size = int32le(data, pos); pos += 4
            if (size < 0 || pos + size > end) return@repeat
            val entry = String(data, pos, size, Charsets.UTF_8); pos += size
            val key = entry.substringBefore('=').uppercase()
            fields.putIfAbsent(key, entry.substringAfter('=', ""))
        }
        fun field(vararg keys: String) = keys.firstNotNullOfOrNull { fields[it]?.trim()?.takeIf(String::isNotEmpty) }
        return AudioTags(field("TITLE"), field("ARTIST"), field("ALBUM"), field("ALBUMARTIST", "ALBUM ARTIST"),
            number(field("TRACKNUMBER")), number(field("DISCNUMBER")), field("DATE", "YEAR")?.take(4)?.toIntOrNull())
    }

    // ---- MP4 ----

    private fun readMp4(media: SeekableMedia): AudioTags? {
        var pos = 0L
        repeat(64) {
            if (pos + 8 > media.size) return null
            val header = bytes(media, pos, 16)
            if (header.size < 8) return null
            var size = int32(header, 0).toLong() and 0xffffffffL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L && header.size >= 16) size = long64(header, 8)
            if (size == 0L) size = media.size - pos
            if (size < 8) return null
            if (type == "moov") return if (size > MAX_MOOV) null else parseMoov(bytes(media, pos, size.toInt()))
            pos += size
        }
        return null
    }

    /** [data] is a whole `moov` atom (with its header). */
    internal fun parseMoov(data: ByteArray): AudioTags? {
        val ilst = find(data, 8, data.size, listOf("udta", "meta", "ilst")) ?: return null
        val values = mutableMapOf<String, ByteArray>()
        var pos = ilst.first
        while (pos + 8 <= ilst.second) {
            val size = int32(data, pos)
            if (size < 8 || pos + size > ilst.second) break
            val key = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            // Each item holds a "data" atom: size, "data", 4 bytes type, 4 bytes locale, then the value.
            find(data, pos + 8, pos + size, listOf("data"))?.let { (start, end) -> if (end - start >= 8) values[key] = data.copyOfRange(start + 8, end) }
            pos += size
        }
        fun string(key: String) = values[key]?.toString(Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)
        fun pair(key: String) = values[key]?.takeIf { it.size >= 4 }?.let { ((it[2].toInt() and 0xff) shl 8) or (it[3].toInt() and 0xff) }?.takeIf { it > 0 }
        return AudioTags(string("©nam"), string("©ART"), string("©alb"), string("aART"),
            pair("trkn"), pair("disk"), string("©day")?.take(4)?.toIntOrNull())
    }

    /** Content range of the atom at [path] inside [from]..[to]; `meta` has 4 extra version/flag bytes. */
    private fun find(data: ByteArray, from: Int, to: Int, path: List<String>): Pair<Int, Int>? {
        var pos = from
        while (pos + 8 <= to) {
            val size = int32(data, pos)
            if (size < 8 || pos + size > to) return null
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            if (type == path.first()) {
                var start = pos + 8
                if (type == "meta") start += 4
                return if (path.size == 1) start to pos + size else find(data, start, pos + size, path.drop(1))
            }
            pos += size
        }
        return null
    }

    // ---- helpers ----

    /** "3/12" -> 3. */
    private fun number(value: String?): Int? = value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    private fun syncsafe(b: ByteArray, at: Int) = ((b[at].toInt() and 0x7f) shl 21) or ((b[at + 1].toInt() and 0x7f) shl 14) or
        ((b[at + 2].toInt() and 0x7f) shl 7) or (b[at + 3].toInt() and 0x7f)
    private fun int32(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
        ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
    private fun int32le(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
        ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
    private fun long64(b: ByteArray, at: Int): Long = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (b[at + i].toLong() and 0xff) }
}

/** Album folders: tracks by disc and number when most files carry one, covers by the usual names. */
object MusicFolder {
    private val coverNames = listOf("cover", "folder", "front", "albumart", "album")

    fun cover(entries: List<FileEntry>): FileEntry? = entries.filter { it.image }
        .sortedBy { entry -> coverNames.indexOfFirst { entry.name.lowercase().startsWith(it) }.let { if (it < 0) Int.MAX_VALUE else it } }
        .firstOrNull { entry -> coverNames.any { entry.name.lowercase().startsWith(it) } }

    fun sort(entries: List<FileEntry>, tags: Map<String, AudioTags>): List<FileEntry> {
        val audio = entries.filter { it.audio && !it.directory }
        val numbered = audio.count { tags[it.id]?.track != null }
        if (audio.size < 2 || numbered * 2 < audio.size) return entries
        val sortedAudio = audio.sortedWith(compareBy<FileEntry>({ tags[it.id]?.disc ?: 1 }, { tags[it.id]?.track ?: Int.MAX_VALUE })
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val iterator = sortedAudio.iterator()
        // Folders and other files keep their places; audio slots are refilled in track order.
        return entries.map { if (it.audio && !it.directory) iterator.next() else it }
    }
}

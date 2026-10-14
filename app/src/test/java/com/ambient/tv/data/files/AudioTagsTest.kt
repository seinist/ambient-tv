package com.ambient.tv.data.files

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class AudioTagsTest {
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun syncsafe(v: Int) = byteArrayOf((v ushr 21 and 0x7f).toByte(), (v ushr 14 and 0x7f).toByte(), (v ushr 7 and 0x7f).toByte(), (v and 0x7f).toByte())

    private fun id3v23(vararg frames: Pair<String, String>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, value) ->
            val text = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8) // UTF-8 encoding byte
            body.write(id.toByteArray()); body.write(be32(text.size)); body.write(byteArrayOf(0, 0)); body.write(text)
        }
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe(body.size()) + body.toByteArray()
    }

    @Test fun id3v23Frames() {
        val tags = AudioTagReader.parseId3(id3v23("TIT2" to "Für Elise", "TPE1" to "Beethoven", "TALB" to "Klavier", "TRCK" to "3/12", "TPOS" to "1/2", "TYER" to "1810"))!!
        assertEquals(AudioTags("Für Elise", "Beethoven", "Klavier", null, 3, 1, 1810), tags)
    }

    @Test fun flacVorbisComment() {
        val comments = listOf("TITLE=Song", "ARTIST=Band", "ALBUM=Record", "TRACKNUMBER=07", "DATE=2021-05-01").map { it.toByteArray() }
        val block = ByteArrayOutputStream().apply {
            val vendor = "test".toByteArray(); write(le32(vendor.size)); write(vendor); write(le32(comments.size))
            comments.forEach { write(le32(it.size)); write(it) }
        }.toByteArray()
        val streamInfo = ByteArray(34)
        val data = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + streamInfo +
            byteArrayOf((0x80 or 4).toByte(), (block.size ushr 16).toByte(), (block.size ushr 8).toByte(), block.size.toByte()) + block
        assertEquals(AudioTags("Song", "Band", "Record", null, 7, null, 2021), AudioTagReader.parseFlac(data))
    }

    @Test fun mp4Ilst() {
        fun atom(type: String, content: ByteArray) = be32(content.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + content
        fun item(key: String, value: ByteArray) = atom(key, atom("data", be32(1) + be32(0) + value))
        val ilst = atom("ilst", item("©nam", "Track".toByteArray()) + item("©ART", "Artist".toByteArray()) +
            item("trkn", byteArrayOf(0, 0, 0, 5, 0, 10, 0, 0)))
        val moov = atom("moov", atom("udta", atom("meta", ByteArray(4) + ilst)))
        val tags = AudioTagReader.parseMoov(moov)!!
        assertEquals("Track", tags.title); assertEquals("Artist", tags.artist); assertEquals(5, tags.track)
    }

    @Test fun albumsSortByDiscAndTrackAndFindTheCover() {
        fun e(name: String, dir: Boolean = false) = FileEntry("s", "A/$name", name, dir)
        val entries = listOf(e("Sub", true), e("b.mp3"), e("a.mp3"), e("c.mp3"), e("Folder.jpg"), e("photo.jpg"))
        val tags = mapOf(entries[1].id to AudioTags(track = 1), entries[2].id to AudioTags(track = 2, disc = 1), entries[3].id to AudioTags(track = 1, disc = 2))
        assertEquals(listOf("Sub", "b.mp3", "a.mp3", "c.mp3", "Folder.jpg", "photo.jpg"), MusicFolder.sort(entries, tags).map { it.name })
        assertEquals("Folder.jpg", MusicFolder.cover(entries)?.name)
        assertSame(entries, MusicFolder.sort(entries, mapOf(entries[1].id to AudioTags(track = 1))))
    }
}

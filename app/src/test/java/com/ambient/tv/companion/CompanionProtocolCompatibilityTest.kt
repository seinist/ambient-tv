package com.ambient.tv.companion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompanionProtocolCompatibilityTest {
    private fun framed(json: String): DataInputStream {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(bytes.size); write(bytes) }
        return DataInputStream(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `a frame from a newer peer with extra fields still decodes`() {
        val frame = framed("""{"version":1,"type":"status","nonce":"n","sequence":3,"capabilities":["queue"],"futureFlag":true}""").readFrame()
        assertEquals("status", frame.type)
        assertEquals(3L, frame.sequence)
    }

    @Test
    fun `protocol version and frame size are still enforced`() {
        assertThrows(IllegalArgumentException::class.java) { framed("""{"version":2,"type":"status"}""").readFrame() }
        val oversized = DataInputStream(ByteArrayInputStream(byteArrayOf(0, 1, 0, 1)))
        assertThrows(IllegalArgumentException::class.java) { oversized.readFrame() }
    }

    @Test
    fun `frames round-trip`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeFrame(CompanionFrame(type = "seek", nonce = "x", sequence = 7, position = 42_000))
        val frame = DataInputStream(ByteArrayInputStream(out.toByteArray())).readFrame()
        assertEquals("seek", frame.type)
        assertEquals(42_000L, frame.position)
    }

    @Test fun `now playing status and identity round trip within the existing frame limit`() {
        val original = CompanionFrame(type = "state", nonce = "n".repeat(43), sequence = 10,
            active = true, playbackState = "buffering", playbackSessionId = 18,
            media = CompanionMedia("library", "j:episode", "a".repeat(64)),
            parentIds = listOf("j:series", "j:season"), title = "Series", subtitle = "Episode",
            position = 23_000, duration = 100_000, canSeek = true, artwork = "A".repeat(4096))
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeFrame(original)
        assertEquals(original, DataInputStream(ByteArrayInputStream(out.toByteArray())).readFrame())
        org.junit.Assert.assertTrue(out.size() < 16_388)
    }

    @Test fun `legacy playback state still drives mini player without guessing an item identity`() {
        val legacy = framed("""{"type":"state","title":"Movie","position":12000,"duration":100000,"playing":true}""").readFrame()
        org.junit.Assert.assertTrue(legacy.playbackActive)
        assertEquals("playing", legacy.phase)
        org.junit.Assert.assertNull(legacy.media)
        org.junit.Assert.assertTrue(legacy.seekable)
    }

    @Test fun `long unicode file identity cannot break the control connection or be truncated`() {
        val huge = CompanionFrame(type = "state", active = true, playbackState = "playing",
            media = CompanionMedia("file", "界".repeat(4096), "a".repeat(64)),
            parentIds = List(8) { "界".repeat(128) }, title = "界".repeat(200), subtitle = "界".repeat(200),
            artwork = "A".repeat(4096), playbackSessionId = 5)
        val safe = boundedPlaybackFrame(huge)
        org.junit.Assert.assertNull(safe.media)
        assertEquals(huge.title, safe.title)
        assertEquals(5L, safe.playbackSessionId)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeFrame(safe.copy(nonce = "n".repeat(43), sequence = 99, controller = "phone".repeat(8)))
        assertEquals(safe.title, DataInputStream(ByteArrayInputStream(out.toByteArray())).readFrame().title)
    }
    @Test fun `options and queue state round trip and keep provider URLs off the queue wire`() {
        val frame = CompanionFrame(type = "state", capabilities = listOf("next", "previous", "select", "volume", "queue_move"),
            options = listOf(CompanionOptionGroup("audio", listOf(CompanionOption("3", "Deutsch", true)))),
            volume = 5, volumeMax = 15, queue = listOf(CompanionQueueItem("row-a", title = "Movie", kind = "library")),
            queueTotal = 40, queueRevision = 7, queueOffset = 8)
        val bytes = ByteArrayOutputStream(); DataOutputStream(bytes).writeFrame(frame)
        assertEquals(frame, DataInputStream(ByteArrayInputStream(bytes.toByteArray())).readFrame())
        assertEquals(null, frame.queue.single().media)
    }
    @Test fun `oversized option pages cannot disconnect a receiver or keep unusable select capability`() {
        val huge = CompanionFrame(type = "state", active = true, title = "Movie",
            media = CompanionMedia("library", "item", "server"), capabilities = listOf("select", "play", "queue_page"),
            options = listOf(CompanionOptionGroup("audio", List(100) { CompanionOption("$it", "界".repeat(200)) })),
            queue = List(8) { CompanionQueueItem("$it", title = "界".repeat(160), kind = "library") }, queueTotal = 64)
        val safe = boundedPlaybackFrame(huge)
        org.junit.Assert.assertFalse("select" in safe.capabilities)
        assertEquals(huge.media, safe.media)
        assertEquals(64, safe.queueTotal)
        DataOutputStream(ByteArrayOutputStream()).writeFrame(safe.copy(nonce = "n".repeat(43), sequence = 1))
    }

}

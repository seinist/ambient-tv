package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionPlaybackQueueTest {
    private fun item(token: String, kind: String = "library") = CompanionQueueItem(token, CompanionMedia(kind, token, "server"))
    @Test fun `queue retains media scope and stable tokens through reorder and remove`() {
        val queue = CompanionPlaybackQueue()
        queue.add(item("a")); queue.add(item("b", "file")); queue.add(item("c", "dlna"))
        queue.move("c", -1, queue.revision)
        assertEquals(listOf("a", "c", "b"), queue.entries.map { it.token })
        assertEquals("dlna", queue.entries[1].media?.kind)
        queue.remove("a", queue.revision)
        assertEquals("c", queue.takeNext()?.token)
        assertEquals("b", queue.takeNext()?.token)
        assertFalse(queue.hasNext)
    }
    @Test fun `late row edits fail without removing or moving a replacement`() {
        val queue = CompanionPlaybackQueue(); queue.add(item("a")); queue.add(item("b"))
        val old = queue.revision; queue.remove("a", old)
        assertThrows(IllegalArgumentException::class.java) { queue.remove("b", old) }
        assertThrows(IllegalArgumentException::class.java) { queue.move("b", -1, old) }
        assertEquals(listOf("b"), queue.entries.map { it.token })
    }
    @Test fun `failed starts restore the exact entry and history stays bounded`() {
        val queue = CompanionPlaybackQueue(); queue.add(item("a")); val a = queue.takeNext()!!
        queue.restore(a); assertEquals(a, queue.entries.single())
        repeat(30) { queue.remember(CompanionMedia("library", "$it", "server")) }
        assertEquals("29", queue.previous()?.id)
        repeat(23) { assertNotNull(queue.previous()) }; assertNull(queue.previous())
    }
    @Test fun `failed start of a selected middle row preserves its original position`() {
        val queue = CompanionPlaybackQueue()
        queue.add(item("a")); queue.add(item("b")); queue.add(item("c"))
        val selected = queue.takeNext("b")!!
        queue.restore(selected, 1)
        assertEquals(listOf("a", "b", "c"), queue.entries.map { it.token })
        assertThrows(IllegalArgumentException::class.java) { queue.add(item("b")) }
    }
    @Test fun `queue capacity and missing identities are rejected`() {
        val queue = CompanionPlaybackQueue(); repeat(64) { queue.add(item("$it")) }
        assertThrows(IllegalArgumentException::class.java) { queue.add(item("overflow")) }
        val empty = CompanionPlaybackQueue()
        assertThrows(IllegalArgumentException::class.java) { empty.add(CompanionQueueItem("wire-only", kind = "file")) }
        queue.clear(queue.revision); assertFalse(queue.hasNext)
    }
    @Test fun `Spotify references only accept typed opaque ids and optional track offsets`() {
        assertTrue(validSpotifyMedia(CompanionMedia("spotify:album", "a".repeat(22), offsetId = "b".repeat(22))))
        assertFalse(validSpotifyMedia(CompanionMedia("spotify:show", "a".repeat(22))))
        assertFalse(validSpotifyMedia(CompanionMedia("spotify:track", "https://other/track")))
        assertFalse(validSpotifyMedia(CompanionMedia("spotify:track", "a".repeat(22), source = "foreign")))
        assertFalse(validSpotifyMedia(CompanionMedia("spotify:album", "a".repeat(22), offsetId = "bad")))
    }

    @Test fun `checkpoint preserves queue order history version and collection context after recreation`() {
        val queue = CompanionPlaybackQueue()
        val scoped = CompanionMedia("library", "j:episode", "a".repeat(64), mediaIndex = 2, collectionId = "j:collection", collectionSort = "title:asc")
        queue.add(CompanionQueueItem("first", scoped, "Episode"))
        queue.add(CompanionQueueItem("second", CompanionMedia("file", "Filme/Grüße 🎬.mkv", "b".repeat(64)), "Film"))
        queue.move("second", -1, queue.revision)
        queue.remember(scoped.copy(id = "j:previous"))
        val checkpoint = decodeQueueCheckpoint(encodeQueueCheckpoint(queue.checkpoint()))
        val restored = CompanionPlaybackQueue().apply { restoreCheckpoint(checkpoint) }
        assertEquals(queue.entries, restored.entries)
        assertEquals(scoped.copy(id = "j:previous"), restored.previousMedia)
        assertEquals(queue.revision + 1, restored.revision)
        assertThrows(IllegalArgumentException::class.java) { restored.remove("first", queue.revision) }
    }

    @Test fun `shared URLs live channels and Spotify credentials never enter the durable queue`() {
        val queue = CompanionPlaybackQueue()
        val shared = CompanionMedia("url", "https://host/movie?token=private")
        queue.add(CompanionQueueItem("url", shared)); queue.remember(shared)
        queue.add(CompanionQueueItem("youtube", CompanionMedia("youtube", "abcdefghijk")))
        val bytes = encodeQueueCheckpoint(queue.checkpoint())
        val checkpoint = decodeQueueCheckpoint(bytes)
        assertEquals(listOf("youtube"), checkpoint.entries.map { it.token })
        assertTrue(checkpoint.history.isEmpty())
        assertFalse(bytes.toString(Charsets.UTF_8).contains("private"))
        assertFalse(persistableQueueMedia(CompanionMedia("live:channel", "1", "a".repeat(64))))
        assertFalse(persistableQueueMedia(CompanionMedia("spotify:track", "a".repeat(22))))
        assertTrue(persistableQueueMedia(CompanionMedia("live:recording", "1", "a".repeat(64))))
        assertEquals(2, queue.entries.size) // URL remains usable during this process.
    }

    @Test fun `corrupt future oversized and ambiguous checkpoints are rejected before restore`() {
        val safe = CompanionQueueCheckpoint(entries = listOf(CompanionQueueItem("row", CompanionMedia("youtube", "abcdefghijk"))))
        assertThrows(IllegalArgumentException::class.java) { encodeQueueCheckpoint(safe.copy(version = 2)) }
        assertThrows(IllegalArgumentException::class.java) { encodeQueueCheckpoint(safe.copy(entries = safe.entries + safe.entries)) }
        assertThrows(IllegalArgumentException::class.java) { encodeQueueCheckpoint(safe.copy(revision = Long.MAX_VALUE)) }
        assertThrows(IllegalArgumentException::class.java) { encodeQueueCheckpoint(safe.copy(entries = List(65) { safe.entries.single().copy(token = "row$it") })) }
        assertThrows(IllegalArgumentException::class.java) { decodeQueueCheckpoint(ByteArray(2 * 1024 * 1024 + 1)) }
        assertTrue(runCatching { decodeQueueCheckpoint("{broken".toByteArray()) }.isFailure)
        assertThrows(IllegalArgumentException::class.java) { encodeQueueCheckpoint(safe.copy(entries = listOf(safe.entries.single().copy(media = CompanionMedia("url", "https://host"))))) }
    }
}

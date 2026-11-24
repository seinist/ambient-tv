package com.ambient.tv.romm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RommStateSyncTest {
    private fun asset(id: Long, updated: String, name: String = "ambient-m1.x.state") =
        RommAsset(id = id, romId = 7, fileName = name, updatedAt = updated)

    private val server = asset(5, "2026-11-20T10:00:00Z")
    private val matched = RommMirrorEntry(5, server.fileName, server.updatedAt, local = "stamp-a")

    @Test fun anUnchangedServerCopyNeverOverwritesOrRevivesTheDevicesCopy() {
        // Same on both sides.
        assertEquals(RommStateSync.Action.KEEP, RommStateSync.action(matched, "stamp-a", 1L, server))
        // Changed here: the upload sends it.
        assertEquals(RommStateSync.Action.KEEP, RommStateSync.action(matched, "stamp-b", 1L, server))
        // Deleted here (also offline): it stays deleted, the upload removes it from the server.
        assertEquals(RommStateSync.Action.KEEP, RommStateSync.action(matched, null, null, server))
    }

    @Test fun aNewerServerCopyComesDownUnlessTheDeviceMadeANewerOne() {
        val newer = asset(9, "2026-11-22T10:00:00Z", "ambient-m1.y.state")
        assertEquals(RommStateSync.Action.DOWNLOAD, RommStateSync.action(matched, "stamp-a", 1L, newer))
        assertEquals(RommStateSync.Action.DOWNLOAD, RommStateSync.action(null, null, null, newer))
        val createdLater = java.time.Instant.parse("2026-11-23T10:00:00Z").toEpochMilli()
        assertEquals(RommStateSync.Action.KEEP, RommStateSync.action(matched, "stamp-b", createdLater, newer))
        val createdEarlier = java.time.Instant.parse("2026-11-21T10:00:00Z").toEpochMilli()
        assertEquals(RommStateSync.Action.DOWNLOAD, RommStateSync.action(matched, "stamp-b", createdEarlier, newer))
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    @Test fun aGameWithoutAFileIsNotPlayableAndMetadataDoesNotChangeTheDownload() {
        val physical = json.decodeFromString(RommRom.serializer(),
            """{"id":3,"fs_name":"Box only","has_file_on_disk":false,"missing_from_fs":false}""")
        assertFalse(physical.playable)
        val rom = json.decodeFromString(RommRom.serializer(),
            """{"id":4,"fs_name":"Game.iso","fs_size_bytes":100,"updated_at":"2026-11-20T10:00:00Z"}""")
        // A new cover or summary only changes updated_at: the downloaded copy stays valid.
        assertEquals(rom.contentStamp, rom.copy(updatedAt = "2026-11-24T10:00:00Z", summary = "New").contentStamp)
        assertNotEquals(rom.contentStamp, rom.copy(fsSizeBytes = 101).contentStamp)
    }
}

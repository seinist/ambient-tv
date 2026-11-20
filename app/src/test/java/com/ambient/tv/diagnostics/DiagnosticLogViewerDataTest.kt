package com.ambient.tv.diagnostics

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogViewerDataTest {
    private fun line(second: Int, level: Char, tag: String, text: String) =
        "2026-11-20 14:38:${second.toString().padStart(2, '0')}.001 ${level} ${tag} [worker] ${text}\n"

    @Test
    fun parsesMultilineStackTracesAndReturnsLatestFirst() {
        val parser = DiagnosticViewerParser()
        (line(1, 'E', "mpv", "Seek failed") +
            "\tat com.ambient.tv.Player.seek(Player.kt:12)\n" +
            "Caused by: java.lang.IllegalStateException\n" +
            line(2, 'W', "youtube-relay", "HTTP 403")).lineSequence().filter(String::isNotEmpty).forEach(parser::acceptLine)
        val entries = parser.finish()
        assertEquals(2, entries.size)
        assertEquals("youtube-relay", entries[0].tag)
        assertEquals(DiagnosticLevel.ERROR, entries[1].priority)
        assertTrue(entries[1].message.contains("Caused by:"))
        assertTrue(entries[1].copyText().contains("Seek failed"))
    }

    @Test
    fun capsEntriesAndIgnoresOrphanLines() {
        val parser = DiagnosticViewerParser(maxEntries = 2, maxMessageChars = 12)
        parser.acceptLine("orphan stacktrace without a timestamp")
        (line(1, 'I', "app", "first") +
            line(2, 'W', "app", "second") +
            line(3, 'E', "app", "third and more than twelve chars")).lineSequence().filter(String::isNotEmpty).forEach(parser::acceptLine)
        val entries = parser.finish()
        assertEquals(listOf("third and mo", "second"), entries.map { it.message })
        assertEquals(listOf(DiagnosticLevel.ERROR, DiagnosticLevel.WARN), entries.map { it.priority })
    }

    @Test
    fun rotationAndClearInvalidateSnapshotWhileUnchangedFilesSkipWork() {
        val folder = Files.createTempDirectory("viewer").toFile()
        try {
            val files = RotatingLogFiles(folder, maxBytes = 60, maxFiles = 3)
            files.append(line(1, 'W', "plex", "old"))
            val first = requireNotNull(files.snapshotForViewer(null))
            assertEquals(1, first.entries.size)
            assertNull(files.snapshotForViewer(first.revision))
            files.append(line(2, 'E', "mpv", "new"))
            val second = requireNotNull(files.snapshotForViewer(first.revision))
            assertEquals(listOf("new", "old"), second.entries.map { it.message })
            files.clear()
            val cleared = requireNotNull(files.snapshotForViewer(second.revision))
            assertTrue(cleared.entries.isEmpty())
            assertFalse(cleared.revision == second.revision)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun oldSensitiveContentIsRedactedBeforeViewer() {
        val folder = Files.createTempDirectory("viewer-secret").toFile()
        try {
            val files = RotatingLogFiles(folder, 2048, 2)
            files.append(line(1, 'E', "network", "Authorization: Bearer abcdefghijklm"))
            val entry = requireNotNull(files.snapshotForViewer(null)).entries.single()
            assertFalse(entry.message.contains("abcdefghijklm"))
            assertTrue(entry.message.contains("…"))
        } finally {
            folder.deleteRecursively()
        }
    }
}

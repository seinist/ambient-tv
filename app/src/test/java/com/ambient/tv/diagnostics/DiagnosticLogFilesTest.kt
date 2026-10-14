package com.ambient.tv.diagnostics

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogFilesTest {
    @Test
    fun `credentials are removed but addresses stay`() {
        val raw = """GET http://192.168.1.5:32400/library/sections?X-Plex-Token=SECRET1&type=1
            |Authorization: Basic dXNlcjpwYXNz
            |http://admin:hunter2@192.168.1.9:9981/api/serverinfo
            |https://jf.local/Items?api_key=SECRET2 Bearer abcdefghijklmnop
            |{"password":"hunter3","AccessToken":"SECRET5"} refresh_token=SECRET4""".trimMargin()
        val clean = redactDiagnostic(raw)
        listOf("SECRET1", "dXNlcjpwYXNz", "hunter2", "SECRET2", "abcdefghijklmnop", "hunter3", "SECRET4", "SECRET5").forEach {
            assertFalse("$it leaked: $clean", clean.contains(it))
        }
        assertTrue(clean.contains("http://192.168.1.5:32400/library/sections"))
        assertTrue(clean.contains("&type=1"))
        assertTrue(clean.contains("192.168.1.9:9981/api/serverinfo"))
        assertEquals("https://host/videoplayback", diagnosticUrl("https://host/videoplayback?sig=abc&n=1"))
    }

    @Test
    fun `lines carry level, tag and a bounded stack trace`() {
        val line = formatDiagnosticLine(0L, DiagnosticLevel.ERROR, "playback", "MPV failed", IllegalStateException("boom"))
        assertTrue(line.contains(" E playback "))
        assertTrue(line.contains("MPV failed"))
        assertTrue(line.contains("IllegalStateException: boom"))
        assertTrue(line.endsWith("\n"))
    }

    @Test
    fun `files rotate and keep at most the configured count`() {
        val dir = Files.createTempDirectory("diag").toFile()
        val files = RotatingLogFiles(dir, maxBytes = 100, maxFiles = 3)
        repeat(10) { files.append("line $it ".padEnd(60, '.') + "\n") }
        val kept = files.files()
        assertEquals(3, kept.size)
        assertTrue(kept.last().readText().contains("line 9"))
        assertFalse(kept.joinToString("") { it.readText() }.contains("line 0 "))
        files.clear()
        assertTrue(files.files().isEmpty())
    }

    @Test
    fun `zip holds device info, log and logcat`() {
        val dir = Files.createTempDirectory("diagzip").toFile()
        val files = RotatingLogFiles(dir, 1000, 2).apply { append("hello\n") }
        val out = ByteArrayOutputStream()
        writeDiagnosticZip(out, files.files(), "Device: test", "logcat line")
        val entries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString() }
        }
        assertEquals(setOf("device.txt", "app.log", "logcat.txt"), entries.keys)
        assertEquals("hello\n", entries["app.log"])
    }

    @Test
    fun `media browser tokens and googlevideo urls are removed`() {
        val raw = """MediaBrowser Client="Ambient", Token="SECRET6", Version="1"
            |GET https://rr4---sn-abc.googlevideo.com/videoplayback?expire=1&ip=203.0.113.9&sig=SECRET7 failed
            |hls https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1/ip/203.0.113.9/sig/SECRET8/file/index.m3u8""".trimMargin()
        val clean = redactDiagnostic(raw)
        listOf("SECRET6", "SECRET7", "SECRET8", "203.0.113.9").forEach { assertFalse("$it leaked: $clean", clean.contains(it)) }
        assertTrue(clean.contains("https://rr4---sn-abc.googlevideo.com/…"))
        assertTrue(clean.contains("Client=\"Ambient\""))
    }

    @Test
    fun `repeats are counted instead of written`() {
        val filter = DiagnosticRepeatFilter(windowMs = 10_000)
        assertEquals(0, filter.admit("http GET /a failed after 120 ms", 0))
        assertEquals(null, filter.admit("http GET /a failed after 98 ms", 1_000))
        assertEquals(null, filter.admit("http GET /a failed after 101 ms", 2_000))
        assertEquals(2, filter.admit("playback error", 3_000))
        // Same message again after the window is written again.
        assertEquals(0, filter.admit("playback error", 20_000))
    }
}

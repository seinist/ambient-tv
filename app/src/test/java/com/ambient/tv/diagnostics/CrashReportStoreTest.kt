package com.ambient.tv.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CrashReportStoreTest {
    private val dir = Files.createTempDirectory("crash").toFile()
    private val store = CrashReportStore(dir)
    private val header = "App: com.ambient.tv 1.0 (1)\nDevice: NVIDIA SHIELD Android TV (foster)"

    @After fun cleanup() { dir.deleteRecursively(); CrashBreadcrumbs.clearForTest() }

    @Test fun uncaughtCrashIsWrittenWithStackThreadPlaybackAndBreadcrumbs() {
        val error = IllegalStateException("can't get VLCObject instance http://x/?X-Plex-Token=SECRET1")
        store.writeUncaught(1_700_000_000_000L, "main", error, header, "backend=vlc source=plex live=false", listOf("10:00:00.000 session [main] stop backend=vlc"))
        val text = store.lastCrashFile.readText()
        assertTrue(text.contains("Kind: crash"))
        assertTrue(text.contains("Thread: main"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("CrashReportStoreTest")) // stack frames
        assertTrue(text.contains("backend=vlc"))
        assertTrue(text.contains("stop backend=vlc"))
        assertTrue(text.contains("NVIDIA SHIELD"))
        assertFalse("token leaked", text.contains("SECRET1"))
        val latest = store.latest()!!
        assertEquals(1_700_000_000_000L, latest.timeMs)
        assertEquals("crash", latest.kind)
        assertTrue(latest.headline.contains("IllegalStateException"))
        assertFalse(dir.resolve("last_crash.txt.tmp").exists())
    }

    @Test fun uncaughtWriterNeverThrowsAndStaysBounded() {
        val huge = RuntimeException("x".repeat(CrashReportStore.MAX_FILE_CHARS * 2))
        store.writeUncaught(1L, "t", huge, header, "p", List(50) { "e$it".repeat(300) })
        assertTrue(store.lastCrashFile.length() <= CrashReportStore.MAX_FILE_CHARS * 2L) // chars → UTF-8 bytes
        // A directory where the file should be: must not throw.
        CrashReportStore(dir.resolve("last_crash.txt")).writeUncaught(1L, "t", huge, header, "p", emptyList())
    }

    @Test fun exitRecordsAreDedupedByLastProcessedTimeAndLimited() {
        val records = (1..5).map { ExitRecord(it * 1_000L, "anr", "Input dispatching timed out", "com.ambient.tv", 100, 1234,
            trace = { ByteArrayInputStream("\"main\" prio=5 tid=1 Blocked\n  at Vlc.stop".toByteArray()) }) }
        assertEquals(CrashReportStore.MAX_EXIT_RECORDS, store.recordExits(records, header))
        assertEquals(5_000L, store.lastProcessedExitMs())
        assertEquals(CrashReportStore.MAX_EXIT_RECORDS, store.exitFiles().size)
        assertTrue(store.exitFiles().first().readText().contains("tid=1 Blocked"))
        // Same records again (next start): nothing new.
        assertEquals(0, store.recordExits(records, header))
        // A newer one is added; the oldest file drops out.
        assertEquals(1, store.recordExits(records + ExitRecord(9_000L, "crash_native", "signal 11", "com.ambient.tv", 100, 1), header))
        assertEquals(CrashReportStore.MAX_EXIT_RECORDS, store.exitFiles().size)
        assertEquals("crash_native", store.latest()!!.kind)
        assertEquals(9_000L, store.latest()!!.timeMs)
    }

    @Test fun javaCrashAlreadyCapturedIsNotStoredTwice() {
        store.writeUncaught(50_000L, "main", IllegalStateException("boom"), header, "p", emptyList())
        val written = store.recordExits(listOf(ExitRecord(52_000L, "crash", "boom", "p", 100, 1)), header)
        assertEquals(0, written)
        assertEquals(52_000L, store.lastProcessedExitMs())
        assertEquals(1, store.recordExits(listOf(ExitRecord(90_000L, "crash", "other", "p", 100, 1)), header))
    }

    @Test fun nativeTombstoneKeepsReadableStringsOnlyAndIsRedacted() {
        val proto = byteArrayOf(0x0a, 0x05) + "SIGSEGV".toByteArray() + byteArrayOf(0, 1, 2) +
            "libmpv.so (vo_gpu_next+0x1c)".toByteArray() + byteArrayOf(0x12) + "ab".toByteArray() + byteArrayOf(0) +
            "Cookie: session=SECRET2".toByteArray()
        store.recordExits(listOf(ExitRecord(7L, "crash_native", "crash", "p", 100, 1, trace = { ByteArrayInputStream(proto) })), header)
        val text = store.exitFiles().single().readText()
        assertTrue(text.contains("SIGSEGV"))
        assertTrue(text.contains("libmpv.so (vo_gpu_next+0x1c)"))
        assertFalse(text.lines().any { it == "ab" }) // shorter than 4 characters
        assertFalse(text.contains("SECRET2"))
        assertEquals("abcd\nefgh\n", CrashReportStore.printableStrings("abcd".toByteArray() + byteArrayOf(0) + "efgh".toByteArray()))
    }

    @Test fun traceReadIsBoundedAndFailingTraceIsTolerated() {
        val big = ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() }
        assertEquals(512 * 1024, CrashReportStore.readTrace(ByteArrayInputStream(big), "anr").length)
        assertEquals(1, store.recordExits(listOf(ExitRecord(3L, "anr", "d", "p", 1, 1, trace = { error("no trace") })), header))
        assertTrue(store.exitFiles().single().readText().contains("(unavailable)"))
    }

    @Test fun reportIsNewestFirstRedactedAndGoesIntoTheZip() {
        assertNull(store.report())
        store.writeUncaught(10_000L, "main", RuntimeException("old api_key=SECRET3"), header, "p", emptyList())
        store.recordExits(listOf(ExitRecord(20_000L, "anr", "newer", "p", 1, 1)), header)
        val report = store.report()!!
        assertTrue(report.indexOf("Kind: anr") < report.indexOf("Kind: crash\n"))
        assertFalse(report.contains("SECRET3"))
        val out = ByteArrayOutputStream()
        writeDiagnosticZip(out, emptyList(), "device", null, report)
        val names = ZipInputStream(out.toByteArray().inputStream()).use { zip -> generateSequence { zip.nextEntry?.name }.toList() }
        assertTrue("crash.txt" in names)
        store.clear()
        assertNull(store.latest())
    }

    @Test fun breadcrumbsKeepTheLastFifty() {
        CrashBreadcrumbs.clearForTest()
        repeat(60) { CrashBreadcrumbs.add("session", "stop $it") }
        val crumbs = CrashBreadcrumbs.snapshot()
        assertEquals(CrashBreadcrumbs.CAPACITY, crumbs.size)
        assertTrue(crumbs.first().endsWith("stop 10"))
        assertTrue(crumbs.last().endsWith("stop 59"))
    }
}

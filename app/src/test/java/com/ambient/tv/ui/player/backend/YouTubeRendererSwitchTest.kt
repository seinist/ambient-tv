package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.PlaybackMetadata
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class YouTubeRendererSwitchTest {
    // Y1: the stall log names the running step and how long it runs.
    @Test fun ownerStepRemembersStepAndStart() {
        var now = 1_000L
        val step = MpvOwnerStep { now }
        assertEquals("waiting_for_previous_player", step.name)
        now = 2_000L
        step.operation("surface")
        now = 2_500L
        step.mark("surface:vulkan_probe")
        assertEquals("surface:vulkan_probe", step.name)
        assertEquals(2_500L, step.sinceMs)
        step.operation(null)
        assertEquals("idle", step.name)
    }

    @Test fun stallReportNamesStepAndDurations() {
        val line = mpvStallReport("surface:force_window", stepSinceMs = 4_000L, lastProgressMs = 3_000L,
            nowMs = 20_000L, opening = true)
        assertTrue(line, line.contains("step=surface:force_window"))
        assertTrue(line, line.contains("running 16000 ms"))
        assertTrue(line, line.contains("no progress for 17000 ms"))
        assertTrue(line, line.contains("phase=opening"))
        assertFalse(line, line.contains("http"))
    }

    @Test fun mailboxReportsTheRunningOperation() {
        val seen = mutableListOf<String?>()
        val queue = MpvCommandQueue(schedule = { it() }, failed = {}, running = { seen += it })
        queue.submit("initialize") { seen += "work" }
        queue.submit("sample") { error("boom") }
        assertEquals(listOf("initialize", "work", null, "sample", null), seen)
    }

    // Y2: 15 s while MPV opens the stream, 5 s once a picture is there.
    @Test fun openingAllowsFifteenSeconds() {
        assertEquals(15_000L, MPV_OPENING_UNRESPONSIVE_MS)
        assertFalse(mpvBackendUnresponsive(MPV_UNRESPONSIVE_MS + 1, 0L, ownerStarted = true, opening = true))
        assertFalse(mpvBackendUnresponsive(MPV_OPENING_UNRESPONSIVE_MS - 1, 0L, ownerStarted = true, opening = true))
        assertTrue(mpvBackendUnresponsive(MPV_OPENING_UNRESPONSIVE_MS, 0L, ownerStarted = true, opening = true))
        assertTrue(mpvBackendUnresponsive(MPV_UNRESPONSIVE_MS, 0L, ownerStarted = true, opening = false))
        assertFalse(mpvBackendUnresponsive(60_000L, 0L, ownerStarted = false, opening = true))
    }

    @Test fun firstPictureEndsOpening() {
        assertFalse(mpvOpeningDone(fileReady = false, audioOnly = false, videoWidth = 1920))
        assertFalse(mpvOpeningDone(fileReady = true, audioOnly = false, videoWidth = 0))
        assertTrue(mpvOpeningDone(fileReady = true, audioOnly = false, videoWidth = 1920))
        assertTrue(mpvOpeningDone(fileReady = true, audioOnly = true, videoWidth = 0))
    }

    // Y3/Y4: YouTube passes its panel to both player dialogs (hint + focus back on the panel).
    @Test fun youTubeDialogsGetThePanel() {
        val screen = File("src/main/java/com/ambient/tv/ui/youtube/YouTubePlayerScreen.kt").readText()
        assertTrue(screen.contains("MpvRenderOverloadDialog(controller, panelNavigation)"))
        assertTrue(screen.contains("VlcRenderOverloadDialog(controller, panelNavigation)"))
        assertFalse(screen.contains("MpvRenderOverloadDialog(controller)"))
    }

    // Y5: every Media3 player, YouTube included, goes through the release gate.
    @Test fun allMedia3PlayersUseTheGate() {
        val youtube = File("src/main/java/com/ambient/tv/ui/youtube/YouTubePlayerScreen.kt").readText()
        assertTrue(youtube.contains("PlaybackEngineFactory.gatedMedia3Handle"))
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("Media3PlaybackHandle(") }.map { it.name }.toSet()
        // Only the gate helper and the class itself construct a handle.
        assertEquals(setOf("PlaybackEngineFactory.kt", "Media3PlaybackHandle.kt"), sources)
    }

    // Y6: a size reported later does not change the MPV start key.
    @Test fun startSizeKeepsTheFirstKnownSize() {
        val start = MpvStartSize()
        assertNull(start.resolve(null))
        assertEquals(1280 to 720, start.resolve(1280 to 720))
        assertEquals(1280 to 720, start.resolve(1920 to 1080))
        assertEquals(1280 to 720, start.resolve(null))
    }

    @Test fun sourceSizeNeedsWidthAndHeight() {
        assertNull(mpvSourceSize(1920, null))
        assertNull(mpvSourceSize(0, 1080))
        assertEquals(1920 to 1080, mpvSourceSize(1920, 1080))
    }

    @Test fun profileMetadataUsesTheStartSize() {
        val metadata = PlaybackMetadata(route = "youtube/x", title = "Video", source = "youtube", mediaId = "x",
            sourceWidth = 1920, sourceHeight = 1080)
        val start = mpvProfileMetadata(metadata, 1280 to 720)
        assertEquals(1280, start.sourceWidth)
        assertEquals(720, start.sourceHeight)
        assertSame(metadata, mpvProfileMetadata(metadata, null))
    }
}

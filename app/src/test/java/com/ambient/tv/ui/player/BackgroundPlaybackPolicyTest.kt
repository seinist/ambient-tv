package com.ambient.tv.ui.player

import com.ambient.tv.livetv.isLeftoverBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackPolicyTest {
    private fun action(
        active: Boolean = true,
        isLive: Boolean = false,
        hasVideo: Boolean = true,
        television: Boolean = true,
        screenOn: Boolean = true,
        externalFlow: Boolean = false,
    ) = backgroundPlaybackAction(active, isLive, hasVideo, television, screenOn, externalFlow)

    @Test fun nothingPlayingDoesNothing() {
        assertEquals(BackgroundPlaybackAction.NONE, action(active = false))
        assertEquals(BackgroundPlaybackAction.NONE, action(active = false, isLive = true, screenOn = false))
    }

    @Test fun videoPausesThenStopsOnTvAndPhone() {
        assertEquals(BackgroundPlaybackAction.PAUSE_THEN_STOP, action(television = true))
        assertEquals(BackgroundPlaybackAction.PAUSE_THEN_STOP, action(television = false))
        // Phone screen off and TV standby: same rule for a video.
        assertEquals(BackgroundPlaybackAction.PAUSE_THEN_STOP, action(television = false, screenOn = false))
        assertEquals(BackgroundPlaybackAction.PAUSE_THEN_STOP, action(television = true, screenOn = false))
    }

    @Test fun liveTvStopsAtOnce() {
        assertEquals(BackgroundPlaybackAction.STOP, action(isLive = true))
        assertEquals(BackgroundPlaybackAction.STOP, action(isLive = true, television = false))
        assertEquals(BackgroundPlaybackAction.STOP, action(isLive = true, hasVideo = false))
        assertEquals(BackgroundPlaybackAction.STOP, action(isLive = true, screenOn = false))
    }

    @Test fun musicKeepsPlayingOnPhoneAlsoWithScreenOff() {
        assertEquals(BackgroundPlaybackAction.KEEP, action(hasVideo = false, television = false))
        assertEquals(BackgroundPlaybackAction.KEEP, action(hasVideo = false, television = false, screenOn = false))
    }

    @Test fun musicOnTvKeepsPlayingUntilStandby() {
        assertEquals(BackgroundPlaybackAction.KEEP, action(hasVideo = false, television = true))
        assertEquals(BackgroundPlaybackAction.STOP, action(hasVideo = false, television = true, screenOn = false))
    }

    @Test fun ownExternalScreenDefersButStandbyDoesNot() {
        assertEquals(BackgroundPlaybackAction.DEFER, action(externalFlow = true))
        assertEquals(BackgroundPlaybackAction.DEFER, action(isLive = true, externalFlow = true))
        assertEquals(BackgroundPlaybackAction.DEFER, action(hasVideo = false, externalFlow = true))
        assertEquals(BackgroundPlaybackAction.STOP, action(isLive = true, externalFlow = true, screenOn = false))
        assertEquals(BackgroundPlaybackAction.PAUSE_THEN_STOP, action(externalFlow = true, screenOn = false))
    }

    @Test fun externalFlowCountsOnlyShortlyAfterTheLaunch() {
        assertFalse(externalFlowActive(null, 10_000))
        assertTrue(externalFlowActive(10_000, 10_000))
        assertTrue(externalFlowActive(10_000, 10_000 + EXTERNAL_FLOW_WINDOW_MS))
        assertFalse(externalFlowActive(10_000, 10_001 + EXTERNAL_FLOW_WINDOW_MS))
        assertFalse(externalFlowActive(10_000, 9_000))
    }

    @Test fun onlyOtherAppsCountAsExternalScreens() {
        assertTrue(opensOtherApp(null, "com.ambient.tv"))
        assertTrue(opensOtherApp("com.android.documentsui", "com.ambient.tv"))
        assertFalse(opensOtherApp("com.ambient.tv", "com.ambient.tv"))
    }

    @Test fun timingsMatchTheAgreedRule() {
        assertEquals(30_000L, BACKGROUND_VIDEO_STOP_MS)
        assertTrue(EXTERNAL_FLOW_LIMIT_MS > BACKGROUND_VIDEO_STOP_MS)
    }

    @Test fun leftoverTimeshiftFilesAreOnlyOldBuffers() {
        assertTrue(isLeftoverBuffer("live-123.ts", 1_000, 2_000))
        assertFalse(isLeftoverBuffer("live-123.ts", 2_000, 2_000))
        assertFalse(isLeftoverBuffer("live-123.ts", 3_000, 2_000))
        assertFalse(isLeftoverBuffer("other.ts", 1_000, 2_000))
        assertFalse(isLeftoverBuffer("live-123.tmp", 1_000, 2_000))
        assertFalse(isLeftoverBuffer("live-123.ts", 0, 2_000))
    }
}

package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPlaybackPipTest {
    private fun action(television: Boolean, pip: Boolean, screenOn: Boolean = true) = backgroundPlaybackAction(
        active = true, isLive = true, hasVideo = true, television = television,
        screenOn = screenOn, externalFlow = false, pictureInPicture = pip,
    )

    @Test fun pictureInPictureIsNotBackground() {
        assertEquals(BackgroundPlaybackAction.KEEP, action(television = true, pip = true))
        assertEquals(BackgroundPlaybackAction.KEEP, action(television = false, pip = true))
    }

    @Test fun withoutPictureInPictureTheRulesStay() {
        assertEquals(BackgroundPlaybackAction.STOP, action(television = true, pip = false))
        assertEquals(BackgroundPlaybackAction.KEEP, action(television = false, pip = false))
    }

    @Test fun screenOffWithPictureInPictureFollowsTheNormalRules() {
        assertEquals(BackgroundPlaybackAction.STOP, action(television = true, pip = true, screenOn = false))
    }
}

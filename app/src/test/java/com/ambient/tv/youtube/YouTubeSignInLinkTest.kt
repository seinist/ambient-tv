package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class YouTubeSignInLinkTest {
    @Test fun qrAndBrowserUseTheDisplayedActivationAddress() {
        assertEquals("https://yt.be/activate", YouTubeSignInLink.qrPayload())
        assertEquals(YouTubeSignInLink.qrPayload(), YouTubeSignInLink.browserUrl())
        assertEquals("https://${YouTubeSignInLink.ACTIVATE_ADDRESS}", YouTubeSignInLink.browserUrl())
    }

    @Test fun codeIsEnteredSeparatelyAtTheShortAddress() {
        val url = YouTubeSignInLink.qrPayload()
        assertFalse(url.contains("user_code"))
        assertFalse(url.contains(" "))
        assertFalse(url.contains("youtube.com"))
        assertFalse(url.contains("/qr/"))
    }
}

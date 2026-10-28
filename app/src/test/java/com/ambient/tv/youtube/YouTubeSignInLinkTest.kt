package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class YouTubeSignInLinkTest {
    @Test fun qrAndBrowserPrefillTheCodeOnTheFullActivationPage() {
        assertEquals("https://www.youtube.com/activate?user_code=ABC-DEF-GHI", YouTubeSignInLink.qrPayload("ABC-DEF-GHI"))
        assertEquals(YouTubeSignInLink.qrPayload("ABC-DEF-GHI"), YouTubeSignInLink.browserUrl("ABC-DEF-GHI"))
    }

    @Test fun codeIsTrimmedAndEncoded() {
        val url = YouTubeSignInLink.qrPayload(" AB CD ")
        assertFalse(url.contains(" "))
        assertEquals("https://www.youtube.com/activate?user_code=AB+CD", url)
    }

    @Test fun prefillIsNotSentToTheShortAddress() {
        assertFalse(YouTubeSignInLink.qrPayload("X").contains("yt.be"))
        assertEquals("yt.be/activate", YouTubeSignInLink.ACTIVATE_ADDRESS)
    }
}

package com.ambient.tv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlexAuthLinkTest {
    @Test fun qrEncodesStrongCodeOnAppPlexAuth() {
        val url = PlexAuthLink.qrPayload("11111111-2222-3333-4444-555555555555", "sqkgomwox27dyzivq09dbuvaq")
        assertEquals(
            "https://app.plex.tv/auth/#!?clientID=11111111-2222-3333-4444-555555555555&code=sqkgomwox27dyzivq09dbuvaq",
            url,
        )
        assertFalse(url.contains("plex.tv/link"))
        assertFalse(url.contains("ABCD"))
    }

    @Test fun typedCodeKeepsASeparateShortPin() {
        assertEquals("ABCD", PlexAuthLink.typedCode("sqkgomwox27dyzivq09dbuvaq", "ABCD"))
        assertEquals("sqkgomwox27dyzivq09dbuvaq", PlexAuthLink.typedCode("sqkgomwox27dyzivq09dbuvaq", null))
        assertEquals("sqkgomwox27dyzivq09dbuvaq", PlexAuthLink.typedCode("sqkgomwox27dyzivq09dbuvaq", " "))
        assertEquals("sqkgomwox27dyzivq09dbuvaq", PlexAuthLink.typedCode("sqkgomwox27dyzivq09dbuvaq", "sqkgomwox27dyzivq09dbuvaq"))
    }
}

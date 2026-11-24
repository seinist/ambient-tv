package com.ambient.tv.romm

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommKeysTest {
    @Test fun typedAddressesBecomeTheWebInterfaceBase() {
        assertEquals("http://nas:8080/", RommKeys.normalizeUrl("nas:8080").toString())
        assertEquals("https://games.example.com/romm", RommKeys.normalizeUrl(" https://games.example.com/romm/ ").toString())
        assertEquals("http://192.168.1.5:3000/", RommKeys.normalizeUrl("http://192.168.1.5:3000/api/").toString())
        assertEquals("http://nas/", RommKeys.normalizeUrl("http://nas/?x=1#top").toString())
        assertNull(RommKeys.normalizeUrl(""))
        assertNull(RommKeys.normalizeUrl("ftp://nas"))
    }

    @Test fun gameIdsRoundTripAndRejectOtherIds() {
        val key = RommKeys.serverKey("http://nas:8080/".toHttpUrl(), "Player")
        assertTrue(key.matches(Regex("[0-9a-f]{12}")))
        val id = RommKeys.fileId(key, 42)
        assertTrue(RommKeys.owns(id))
        assertEquals(key to 42L, RommKeys.parse(id))
        assertNull(RommKeys.parse("romm:nothex:42"))
        assertNull(RommKeys.parse("romm:$key:-1"))
        assertNull(RommKeys.parse("files:abc:def"))
        assertFalse(RommKeys.owns("files:abc:def"))
    }

    @Test fun serverKeySeparatesServersAndAccountsButIgnoresCase() {
        val base = "http://nas:8080/".toHttpUrl()
        assertEquals(RommKeys.serverKey(base, "player"), RommKeys.serverKey(base, "Player"))
        assertNotEquals(RommKeys.serverKey(base, "a"), RommKeys.serverKey(base, "b"))
        assertNotEquals(RommKeys.serverKey(base, "a"), RommKeys.serverKey("http://nas:8081/".toHttpUrl(), "a"))
    }

    @Test fun apiPathsStayBelowTheBaseAndServerPathsCannotLeaveIt() {
        assertEquals("http://nas:8080/api/roms/3", RommClient.api("http://nas:8080/".toHttpUrl(), "roms/3").toString())
        assertEquals("https://h/romm/api/heartbeat", RommClient.api("https://h/romm".toHttpUrl(), "heartbeat").toString())
        assertEquals("https://h/romm/api/states/4/content?timestamp=2026&name=a%20b.state",
            RommClient.resolve("https://h/romm".toHttpUrl(), "/api/states/4/content?timestamp=2026&name=a%20b.state").toString())
        assertNull(RommClient.resolve("https://h/".toHttpUrl(), "/api/../../etc"))
        assertNull(RommClient.resolve("https://h/".toHttpUrl(), "https://elsewhere/api/x"))
        assertEquals("https://h/api/x", RommClient.resolve("https://h/".toHttpUrl(), "https://h/api/x").toString())
    }

    @Test fun serverTimesWithAndWithoutOffsetParse() {
        assertEquals(1_764_000_000_000L, RommLibrary.parseInstant("2025-11-24T16:00:00+00:00"))
        assertEquals(1_764_000_000_000L, RommLibrary.parseInstant("2025-11-24T16:00:00"))
        assertNull(RommLibrary.parseInstant("yesterday"))
    }
}

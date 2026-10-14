package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvCachePolicyTest {
    @Test
    fun `home network servers keep the cache off`() {
        assertFalse(mpvUsesNetworkCache("http://192.168.1.10:32400/library/parts/1/file.mkv", false))
        assertFalse(mpvUsesNetworkCache("http://10.0.0.5:8096/Videos/1/stream", false))
        assertFalse(mpvUsesNetworkCache("http://nas.local:32400/x", false))
        assertFalse(mpvUsesNetworkCache("http://plexserver:32400/x", false))
        assertFalse(mpvUsesNetworkCache("https://192-168-1-10.0123abcd.plex.direct:32400/x", false))
        assertFalse(mpvUsesNetworkCache("http://[fd00::10]:32400/x", false))
        assertFalse(mpvUsesNetworkCache("file:///sdcard/movie.mkv", false))
    }

    @Test
    fun `remote servers and forced callers use the cache`() {
        assertTrue(mpvUsesNetworkCache("https://plex.example.com/library/parts/1/file.mkv", false))
        assertTrue(mpvUsesNetworkCache("https://203-0-113-7.0123abcd.plex.direct:32400/x", false))
        assertTrue(mpvUsesNetworkCache("http://203.0.113.7:32400/x", false))
        assertTrue(mpvUsesNetworkCache("http://192.168.1.10:32400/x", true))
    }

    @org.junit.Test fun `an end well before the duration is a broken stream`() {
        org.junit.Assert.assertTrue(mpvEndedEarly(positionMs = 17_000, durationMs = 60_000))
        org.junit.Assert.assertFalse(mpvEndedEarly(positionMs = 58_000, durationMs = 60_000))
        org.junit.Assert.assertFalse(mpvEndedEarly(positionMs = 5_000, durationMs = 30_000)) // short clip
        org.junit.Assert.assertFalse(mpvEndedEarly(positionMs = 5_000, durationMs = 0)) // live / unknown
        org.junit.Assert.assertFalse(mpvEndedEarly(positionMs = 0, durationMs = 7_200_000)) // never played
    }

    @org.junit.Test fun `network streams get reconnect options`() {
        org.junit.Assert.assertTrue(mpvNeedsReconnect("https://plex.local:32400/video.mkv"))
        org.junit.Assert.assertTrue(mpvNeedsReconnect("edl://!new_stream;%10%http://x"))
        org.junit.Assert.assertFalse(mpvNeedsReconnect("file:///storage/movie.mkv"))
        org.junit.Assert.assertFalse(mpvNeedsReconnect("smb://nas/movie.mkv"))
    }
}

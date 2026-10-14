package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLoungeBridgeTest {
    /** Shape of SmartTube's normalized Command (getters only). */
    @Suppress("unused")
    class FakeCommand {
        fun getType(): Int = YouTubeLinkCommand.OPEN_VIDEO
        fun getVideoId(): String = "dQw4w9WgXcQ"
        fun getCurrentTimeMs(): Long = 42_000L
        fun getVolume(): Int = 30
        fun getDeviceName(): String = "Pixel"
    }

    /** Shape of SmartTube's LoungeService posting methods (primitive parameters). */
    @Suppress("unused")
    class FakeLounge {
        val calls = mutableListOf<String>()
        fun postStartPlaying(videoId: String, positionMs: Long, durationMs: Long, state: Int) { calls += "start:$videoId:$positionMs:$durationMs:$state" }
        fun postStateChange(positionMs: Long, durationMs: Long, state: Int) { calls += "state:$positionMs:$durationMs:$state" }
        fun postVolumeChange(volume: Int) { calls += "volume:$volume" }
        fun resetData() { calls += "reset" }
    }

    @Test
    fun `commands are read from the getters`() {
        assertEquals(YouTubeLinkCommand(YouTubeLinkCommand.OPEN_VIDEO, "dQw4w9WgXcQ", 42_000L, 30, "Pixel"), YouTubeLoungeBridge.toLinkCommand(FakeCommand()))
        assertEquals(YouTubeLinkCommand(-1, null, 0L, -1, null), YouTubeLoungeBridge.toLinkCommand(Any()))
    }

    @Test
    fun `posts reach methods with primitive parameters`() {
        val lounge = FakeLounge()
        YouTubeLoungeBridge.postNowPlaying(lounge, "dQw4w9WgXcQ", 1_000L, 60_000L, 1)
        YouTubeLoungeBridge.postState(lounge, 2_000L, 60_000L, 2)
        YouTubeLoungeBridge.postVolume(lounge, 55)
        YouTubeLoungeBridge.reset(lounge)
        assertEquals(listOf("start:dQw4w9WgXcQ:1000:60000:1", "state:2000:60000:2", "volume:55", "reset"), lounge.calls)
    }

    @Test
    fun `video ids are validated`() {
        assertTrue(isYouTubeVideoId("dQw4w9WgXcQ"))
        assertFalse(isYouTubeVideoId("dQw4w9WgXc"))
        assertFalse(isYouTubeVideoId("../../etc/x"))
        assertFalse(isYouTubeVideoId(null))
    }
}

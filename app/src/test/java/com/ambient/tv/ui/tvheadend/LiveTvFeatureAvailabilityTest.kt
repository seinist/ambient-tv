package com.ambient.tv.ui.tvheadend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvFeatureAvailabilityTest {
    private fun configurable(
        url: String = "https://tv.example/live.ts",
        isLive: Boolean = true,
        hasChannel: Boolean = true,
        playingRecording: Boolean = false,
        changingChannel: Boolean = false,
    ) = liveTimeshiftConfigurable(isLive, hasChannel, playingRecording, changingChannel, url)

    @Test fun directTransportStreamsAndTheExistingLocalRingCanBeConfigured() {
        for (url in listOf("https://tv.example/live.ts?token=abc", "http://tv.example/live.ts",
            "http://127.0.0.1:1234/0123456789abcdef0123456789abcdef/188.ts")) {
            assertTrue(url, configurable(url))
        }
    }

    @Test fun extensionlessHttpStreamsRemainEligibleForTheRuntimeFormatProbe() {
        assertTrue(configurable("https://tv.example/stream/channel/abc?profile=pass"))
        assertTrue(configurable("https://tv.example/Videos/channel/stream"))
    }

    @Test fun hlsRemainsUnsupportedWithQueriesFragmentsAndUppercaseExtensions() {
        for (url in listOf("https://tv.example/live.m3u8", "https://tv.example/live.M3U8?token=abc",
            "https://tv.example/live.m3u8#view", "https://tv.example/live.m3u8?file=live.ts#view")) {
            assertFalse(url, configurable(url))
        }
    }

    @Test fun aPlaylistNameInsideQueryOrFragmentDoesNotPrejudgeTheRuntimeFormatProbe() {
        for (url in listOf("https://tv.example/stream/channel/abc?label=live.m3u8",
            "https://tv.example/stream/channel/abc#live.m3u8",
            "HTTP://tv.example/live.ts?profile=pass#live.m3u8")) {
            assertTrue(url, configurable(url))
        }
    }

    @Test fun aRecordingBufferMustNotBeReplacedByTheLocalTimeshiftSwitch() {
        assertFalse(configurable("http://127.0.0.1:1234/recording/at/123.ts", playingRecording = true))
    }

    @Test fun switchingChannelsAndNonChannelPlaybackCannotApplyTheSetting() {
        assertFalse(configurable(changingChannel = true))
        assertFalse(configurable(hasChannel = false))
        assertFalse(configurable(isLive = false))
    }

    @Test fun sourcesTheHttpBufferCannotOpenAreNotConfigurable() {
        for (url in listOf("", "file:///recording.ts", "rtsp://tv.example/live.ts", "udp://@239.1.1.1:1234")) {
            assertFalse(url, configurable(url))
        }
    }
}

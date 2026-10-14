package com.ambient.tv.livetv

import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.ui.player.AudioRouteCapabilities
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LiveTvAudioProfileTest {
    @Test fun nativeFallbackKeepsOriginalSurroundForLocalAc3EvenOnStereoPcmRoute() {
        val profile = planLiveTvAudioProfile(AudioOptions(), true, "media3", AudioRouteCapabilities(setOf("ac3"), 2, true), mapOf("aac" to 2))
        assertEquals(8, profile.maxChannels)
        assertTrue(profile.codecs.containsAll(listOf("ac3", "eac3", "dts", "truehd")))
        val device = profile.browserDeviceProfile()
        assertTrue(device.getValue("DirectPlayProfiles").jsonArray.isNotEmpty())
        assertEquals("8", device.getValue("TranscodingProfiles").jsonArray[0].jsonObject.getValue("MaxAudioChannels").jsonPrimitive.content)
        assertEquals("8", profile.plexQuery()["maxAudioChannels"])
        assertEquals("1", profile.plexQuery()["directPlay"])
    }
    @Test fun media3WithoutFallbackOnlyAdvertisesDecodersAndPermittedBitstreams() {
        val profile = planLiveTvAudioProfile(AudioOptions(output = "pcm", localFallback = "off"), false, "media3",
            AudioRouteCapabilities(setOf("ac3"), 8, true), mapOf("aac" to 6, "mp3" to 2))
        assertEquals(listOf("aac", "mp3"), profile.codecs)
        assertEquals(6, profile.maxChannels)
    }

    @Test fun nativeDecodeAdvertisesDtsHdInThePlexQuery() {
        val profile = planLiveTvAudioProfile(AudioOptions(), false, "mpv", AudioRouteCapabilities(emptySet(), 2, true), emptyMap())
        assertTrue(profile.codecs.contains("dts-hd"))
        val index = profile.codecs.indexOf("dts")
        assertEquals(index + 1, profile.codecs.indexOf("dts-hd"))
        assertTrue(profile.plexQuery()["audioCodec"]!!.split(",").contains("dts-hd"))
        assertTrue(profile.plexQuery()["audioCodec"]!!.split(",").contains("dca"))
    }

    @Test fun dtsHdPassthroughIsAdvertisedOnlyWhenTheRouteAndAllowListAgree() {
        val denied = planLiveTvAudioProfile(
            AudioOptions(output = "pcm", localFallback = "off", allowedCodecs = listOf("ac3")),
            true, "media3",
            AudioRouteCapabilities(setOf("dts-hd"), 2, false),
            emptyMap(),
        )
        assertFalse(denied.codecs.contains("dts-hd"))
        val allowed = planLiveTvAudioProfile(
            AudioOptions(output = "pcm", localFallback = "off", allowedCodecs = listOf("dts", "dts-hd")),
            false, "media3",
            AudioRouteCapabilities(setOf("dts-hd"), 2, false),
            emptyMap(),
        )
        assertEquals(listOf("dts-hd"), allowed.codecs.filter { it == "dts-hd" })
        assertTrue(allowed.codecs.contains("dts-hd"))
    }
}

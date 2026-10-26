package com.ambient.tv.ui.player

import android.media.AudioDeviceInfo
import androidx.media3.common.MimeTypes
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.AudioSettings
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.data.remote.plexAacChannelLimitation
import com.ambient.tv.data.remote.serverAudioBitrate
import com.ambient.tv.data.remote.withServerAudioChannels
import com.ambient.tv.livetv.MEDIA3_LOCAL_DECODERS
import com.ambient.tv.livetv.planLiveTvAudioProfile
import com.ambient.tv.ui.player.backend.MPV_AUDIO_DROP_CONFIRM_NS
import com.ambient.tv.ui.player.backend.mpvAudioOutputDropped
import com.ambient.tv.ui.player.backend.vlcTrackCodec
import com.ambient.tv.ui.tvheadend.tvheadendFallbackProfileRank
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Regression tests for the audio audit findings (A-H1 … A-G8). */
class AudioAuditFixesTest {
    class FakeVlcTrack(@JvmField val codec: Int)

    private fun fourcc(text: String): Int = text.foldIndexed(0) { index, acc, c -> acc or (c.code shl (8 * index)) }

    // A-H1
    @Test fun vlcCodecNamesMapToPassthroughKeys() {
        assertEquals("ac3", normalizeSourceCodec("a52 "))
        assertEquals("ac3", normalizeSourceCodec("A52 Audio (aka AC3)"))
        assertEquals("eac3", normalizeSourceCodec("A/52 B Audio (aka E-AC3)"))
        assertEquals("eac3", normalizeSourceCodec("eac3"))
        assertEquals("truehd", normalizeSourceCodec("trhd"))
        assertEquals("truehd", normalizeSourceCodec("mlp "))
        assertEquals("truehd", normalizeSourceCodec("TrueHD Audio"))
        assertEquals("dts", normalizeSourceCodec("dts "))
        assertEquals("dts", normalizeSourceCodec("DTS Audio"))
        assertNull(normalizeSourceCodec("mp4a"))
        assertNull(normalizeSourceCodec("AAC Audio"))
    }

    @Test fun vlcFourccTrackIsPassedThroughWhenTheOutputReportsIt() {
        val codec = vlcTrackCodec(FakeVlcTrack(fourcc("a52 ")))
        assertEquals("ac3", normalizeSourceCodec(codec))
        val plan = planAudioOutput(AudioOptions(output = "auto"), codec, AudioRouteCapabilities(setOf("ac3"), 2), "vlc", true, sourceChannels = 6)
        assertEquals(AudioOutputMode.PASSTHROUGH, plan.mode)
        assertEquals("truehd", normalizeSourceCodec(vlcTrackCodec(FakeVlcTrack(fourcc("trhd")))))
    }

    // A-M1
    @Test fun mpvDroppedAudioIsDetectedOnlyForVideoWithASelectedTrack() {
        assertTrue(mpvAudioOutputDropped("1", null, hasAudioTracks = true, hasVideoTracks = true))
        assertTrue(mpvAudioOutputDropped("auto", "", hasAudioTracks = true, hasVideoTracks = true))
        assertFalse(mpvAudioOutputDropped("1", "1", hasAudioTracks = true, hasVideoTracks = true))
        assertFalse(mpvAudioOutputDropped("no", null, hasAudioTracks = true, hasVideoTracks = true))
        assertFalse(mpvAudioOutputDropped("false", null, hasAudioTracks = true, hasVideoTracks = true))
        assertFalse(mpvAudioOutputDropped(null, null, hasAudioTracks = true, hasVideoTracks = true))
        // Audio-only files report the failure as END_FILE instead.
        assertFalse(mpvAudioOutputDropped("1", null, hasAudioTracks = true, hasVideoTracks = false))
        assertFalse(mpvAudioOutputDropped("1", null, hasAudioTracks = false, hasVideoTracks = true))
        assertTrue(MPV_AUDIO_DROP_CONFIRM_NS >= 1_000_000_000L)
    }

    // A-G1
    @Test fun routeCacheIsInvalidatedByAChangeAndIgnoresProbesFromBeforeIt() {
        val cache = RouteCapabilitiesCache(1_000)
        val stereo = AudioRouteCapabilities(pcmChannels = 2)
        val surround = AudioRouteCapabilities(setOf("ac3"), 8)
        cache.store(100, cache.generation, stereo)
        assertEquals(stereo, cache.get(500))
        assertNull(cache.get(1_100))
        val started = cache.generation
        cache.invalidate()
        assertNull(cache.get(500))
        // A probe that started before the HDMI replug must not refill the cache.
        cache.store(400, started, stereo)
        assertNull(cache.get(500))
        cache.store(600, cache.generation, surround)
        assertEquals(surround, cache.get(700))
    }

    @Test fun onlyRealHdmiPlugChangesCount() {
        assertTrue(hdmiPlugIsChange(1, initialSticky = false))
        assertTrue(hdmiPlugIsChange(0, initialSticky = false))
        assertFalse(hdmiPlugIsChange(1, initialSticky = true))
        assertFalse(hdmiPlugIsChange(-1, initialSticky = false))
    }

    // A-M2
    @Test fun plexVideoFallbackKeepsSurroundAac() {
        assertNull(plexAacChannelLimitation(2))
        assertTrue(plexAacChannelLimitation(6)!!.contains("name=audio.channels&value=6"))
        assertTrue(plexAacChannelLimitation(8)!!.contains("value=8"))
        assertTrue(plexAacChannelLimitation(12)!!.contains("value=8"))
    }

    @Test fun embyJellyfinTranscodingUrlGetsTheOutputChannels() {
        val url = "http://server:8096/videos/1/master.m3u8?AudioCodec=aac&aac-audiochannels=2&MaxAudioChannels=2&AudioBitrate=192000"
        val raised = withServerAudioChannels(url, 6).toHttpUrl()
        assertEquals("6", raised.queryParameter("aac-audiochannels"))
        assertEquals("6", raised.queryParameter("MaxAudioChannels"))
        assertEquals("6", raised.queryParameter("TranscodingMaxAudioChannels"))
        assertTrue(raised.queryParameter("AudioBitrate")!!.toInt() >= serverAudioBitrate("aac", 6))
        assertEquals(url, withServerAudioChannels(url, 2))
        assertEquals("not a url", withServerAudioChannels("not a url", 6))
    }

    @Test fun videoFallbackChannelsFollowTheOutput() {
        val hdmi = AudioRouteCapabilities(setOf("ac3"), 8)
        assertEquals(8, videoFallbackAudioChannels(AudioOptions(), hdmi, 8))
        assertEquals(6, videoFallbackAudioChannels(AudioOptions(), hdmi, 6))
        assertEquals(6, videoFallbackAudioChannels(AudioOptions(pcmChannelLimit = 6), hdmi, 8))
        assertEquals(2, videoFallbackAudioChannels(AudioOptions(), AudioRouteCapabilities(pcmChannels = 2), 6))
        assertEquals(2, videoFallbackAudioChannels(AudioOptions(channels = "stereo"), hdmi, 8))
    }

    // A-G5
    @Test fun serverConversionFollowsThePlaybackSession() {
        val forced = AudioOptions(output = "force", pcmChannelLimit = 6, forceChannels = 6, localAc3 = true)
        val stereo = serverConversionOptions(forced, forceStereoPcm = true, forcedChannelsFailed = false)
        assertEquals("pcm", stereo.output)
        assertEquals(2, stereo.pcmChannelLimit)
        assertEquals(0, stereo.forceChannels)
        assertFalse(stereo.localAc3)
        val failed = serverConversionOptions(forced, forceStereoPcm = false, forcedChannelsFailed = true)
        assertEquals(0, failed.forceChannels)
        assertEquals(0, failed.pcmChannelLimit)
        assertFalse(failed.forcesPassthrough())
        assertEquals(forced, serverConversionOptions(forced, forceStereoPcm = false, forcedChannelsFailed = false))
        val stereoRoute = AudioRouteCapabilities(setOf("ac3"), 2)
        assertEquals("ac3", serverAudioTarget(AudioOptions(output = "auto"), stereoRoute, 6))
        assertEquals("aac", serverAudioTarget(stereo, stereoRoute, 6))
        assertEquals(2, serverAudioChannels(AudioOptions(channels = "mono"), AudioRouteCapabilities(pcmChannels = 8), 8, "aac"))
    }

    // A-G4
    @Test fun dtsXIsNotSentAsDtsHd() {
        assertNull(Media3AudioProcessing.codecKey(MimeTypes.AUDIO_DTS_X))
        assertEquals("dts-hd", Media3AudioProcessing.codecKey(MimeTypes.AUDIO_DTS_HD))
        assertEquals("eac3", Media3AudioProcessing.codecKey(MimeTypes.AUDIO_E_AC3_JOC))
    }

    // A-G6
    @Test fun liveTvProfileCountsMedia3LocalDecoders() {
        val route = AudioRouteCapabilities(emptySet(), 2, false)
        val local = planLiveTvAudioProfile(AudioOptions(output = "pcm"), false, "media3", route, mapOf("aac" to 2), MEDIA3_LOCAL_DECODERS)
        assertTrue(local.codecs.containsAll(listOf("ac3", "eac3", "truehd", "dts", "dts-hd", "mp2")))
        assertEquals(local.codecs.indexOf("dts") + 1, local.codecs.indexOf("dts-hd"))
        assertEquals(8, local.maxChannels)
        val off = planLiveTvAudioProfile(AudioOptions(output = "pcm", localFallback = "off"), false, "media3", route,
            mapOf("aac" to 2), MEDIA3_LOCAL_DECODERS)
        assertEquals(listOf("aac"), off.codecs)
        assertEquals(2, off.maxChannels)
        val none = planLiveTvAudioProfile(AudioOptions(output = "pcm"), false, "media3", route, mapOf("aac" to 2))
        assertFalse("ac3" in none.codecs)
    }

    // A-G7: migrations
    @Test fun legacyValuesMigrateWithoutChangingBehaviour() {
        assertEquals(AudioOptions(), AudioOptions().normalized())
        val mix51 = AudioOptions(channels = "5.1").normalized()
        assertEquals("auto", mix51.channels)
        assertEquals(6, mix51.pcmChannelLimit)
        assertEquals(6, AudioOptions(channels = "7.1", pcmChannelLimit = 6).normalized().pcmChannelLimit)
        assertEquals(2, AudioOptions(channels = "7.1", pcmChannelLimit = 2).normalized().pcmChannelLimit)
        assertEquals(8, AudioOptions(channels = "7.1").normalized().pcmChannelLimit)
        assertEquals("auto", AudioOptions(output = "bitstream").normalized().output)
        assertEquals("force", AudioOptions(output = "bitstream", forceChannels = 6).normalized().output)
        for (o in listOf(AudioOptions(channels = "7.1", output = "bitstream"), AudioOptions(output = "force"), AudioOptions(output = "source")))
            assertEquals(o.normalized(), o.normalized().normalized())
        // Same plan before and after the channel-mix migration.
        val hdmi = AudioRouteCapabilities(emptySet(), 8)
        assertEquals(planAudioOutput(AudioOptions(channels = "5.1", output = "pcm"), "truehd", hdmi, "mpv", false, sourceChannels = 8).channels,
            planAudioOutput(AudioOptions(channels = "5.1", output = "pcm").normalized(), "truehd", hdmi, "mpv", false, sourceChannels = 8).channels)
    }

    @Test fun storedLegacySettingsAreReadMigrated() {
        val preferences = AppPreferences(memoryPreferences())
        preferences.audioSettings = AudioSettings().update(AudioOptions(output = "bitstream", channels = "7.1", forceChannels = 8))
        val tv = preferences.audioSettings.currentForRoute("tv")
        assertEquals("force", tv.output)
        assertEquals("auto", tv.channels)
        assertEquals(8, tv.pcmChannelLimit)
        assertEquals(8, tv.forceChannels)
    }

    @Test fun forcePassthroughSendsDolbyAndDtsWithoutAReport() {
        val silent = AudioRouteCapabilities(emptySet(), 2, true)
        val force = AudioOptions(output = "force")
        assertTrue(force.forcesPassthrough())
        assertEquals(setOf("ac3", "eac3", "dts"), silent.forcedBy(force).passthrough)
        assertEquals(2, silent.forcedBy(force).pcmChannels)
        assertEquals(AudioOutputMode.PASSTHROUGH, planAudioOutput(force, "eac3", silent, "mpv", true, sourceChannels = 6).mode)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(force.withoutForcing(), "eac3", silent, "mpv", true, sourceChannels = 6).mode)
        // "Automatisch" never guesses.
        assertEquals(silent, silent.forcedBy(AudioOptions(output = "auto")))
        assertEquals("auto", force.withoutForcing().output)
        assertEquals("pcm", AudioOptions(output = "pcm", forceChannels = 6).withoutForcing().output)
    }

    @Test fun mergedControlsMapToTheStoredFields() {
        assertEquals("auto", displayedAudioOutput(AudioOptions(output = "source"), true))
        assertEquals("pcm", displayedAudioOutput(AudioOptions(output = "source"), false))
        assertEquals("auto", displayedAudioOutput(AudioOptions(output = "bitstream"), false))
        assertEquals("force", displayedAudioOutput(AudioOptions(output = "bitstream", forceChannels = 6), true))
        assertEquals("force", withAudioOutput(AudioOptions(channels = "stereo"), "force").output)
        assertEquals("auto", withAudioOutput(AudioOptions(channels = "stereo"), "force").channels)

        for (key in listOf("0", "2", "6", "8")) assertEquals(key, displayedPcmChannels(withPcmChannels(AudioOptions(), key)))
        assertEquals(6, withPcmChannels(AudioOptions(), "6").forceChannels)
        assertEquals(0, withPcmChannels(AudioOptions(forceChannels = 8), "2").forceChannels)
        assertEquals("6", displayedPcmChannels(AudioOptions(pcmChannelLimit = 6)))
        // A 5.1 choice also raises a stereo report, but never above the setting.
        val surround = withPcmChannels(AudioOptions(), "6")
        assertEquals(6, surround.pcmChannels(2))
        assertEquals(6, surround.pcmChannels(8))

        for (key in listOf("auto", "ac3", "pcm", "server", "off"))
            assertEquals(key, displayedUnsupportedAudio(withUnsupportedAudio(AudioOptions(), key)))
        assertEquals("server", displayedUnsupportedAudio(AudioOptions(conversionLocation = "server", localFallback = "off")))
        assertEquals("auto", withUnsupportedAudio(AudioOptions(localFallback = "off"), "server").localFallback)
        assertTrue(withUnsupportedAudio(AudioOptions(output = "auto"), "ac3").transcodesAc3(false, 6, 2, true))
    }

    // A-G2
    @Test fun automaticProfileExplainsTheRoute() {
        fun route(profile: String, vararg types: Int) = AudioRouteCapabilities(profile = profile, outputTypes = types.toList())
        assertEquals(AudioProfileReason.HDMI, audioProfileReason(route("avr", AudioDeviceInfo.TYPE_HDMI)))
        assertEquals(AudioProfileReason.ARC, audioProfileReason(route("avr", AudioDeviceInfo.TYPE_HDMI_EARC)))
        assertEquals(AudioProfileReason.ARC, audioProfileReason(route("avr", AudioDeviceInfo.TYPE_HDMI_ARC)))
        assertEquals(AudioProfileReason.SPEAKER, audioProfileReason(route("tv", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertEquals(AudioProfileReason.PERSONAL, audioProfileReason(route("headphones", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertEquals(AudioProfileReason.MIXED, audioProfileReason(route("tv", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI)))
        // Profile keys stay as they were.
        assertEquals("avr", routedAudioProfile(listOf(AudioRouteDevice(AudioDeviceInfo.TYPE_HDMI, emptySet(), 8))))
    }

    // A-G8
    @Test fun tvheadendFallbackPrefersVideoTranscodeThenSurround() {
        val profiles = listOf("pass", "webtv-h264-aac-stereo", "webtv-h264-ac3-5.1", "matroska-ac3", "webtv-h264-aac-mpegts")
        val sorted = profiles.sortedByDescending { tvheadendFallbackProfileRank(it, null) }
        assertEquals("webtv-h264-ac3-5.1", sorted.first())
        assertEquals("webtv-h264-aac-stereo", sorted[2])
        assertEquals("pass", sorted.last())
        assertTrue(tvheadendFallbackProfileRank("h264-stereo", null) > tvheadendFallbackProfileRank("matroska-ac3", null))
    }
}

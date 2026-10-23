package com.ambient.tv.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.ui.player.audio.dtsCoreFrameSize
import com.ambient.tv.ui.player.audio.dtsCoreOutputFormat
import com.ambient.tv.ui.player.audio.localDecodableSourceCodec
import com.ambient.tv.ui.player.audio.localDecoderName
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3AudioFallbackTest {
    // --- No usable audio (PlayerScreen and TVHeadend share this rule) ---

    @Test fun unsupportedOnlyTracksStartTheFallback() {
        assertTrue(media3AudioUnusable(listOf(listOf(false), listOf(false, false)), audioFormatPresent = false))
    }

    @Test fun oneSupportedTrackOrPlayingAudioIsEnough() {
        assertFalse(media3AudioUnusable(listOf(listOf(false), listOf(false, true)), audioFormatPresent = false))
        assertFalse(media3AudioUnusable(listOf(listOf(false)), audioFormatPresent = true))
    }

    @Test fun missingAudioGroupsOnlyCountWhenMetadataExpectsAudio() {
        // TVHeadend: a channel without audio tracks is not a decoder problem.
        assertFalse(media3AudioUnusable(emptyList(), audioFormatPresent = false))
        // Plex/Jellyfin/Emby: metadata names an audio codec, Media3 exposes nothing.
        assertTrue(media3AudioUnusable(emptyList(), audioFormatPresent = false, expectAudioWithoutTracks = true))
    }

    @Test fun fallbackChainAfterMedia3IsMpvThenVlc() {
        assertEquals("mpv", LocalAudioFallbackPolicy.nextBackend("media3", setOf("media3"), mpvAvailable = true))
        assertEquals("vlc", LocalAudioFallbackPolicy.nextBackend("media3", setOf("media3", "mpv"), mpvAvailable = true))
        assertNull(LocalAudioFallbackPolicy.nextBackend("media3", setOf("media3"), mpvAvailable = true, enabled = false))
    }

    // --- MPEG audio layer I/II ---

    @Test fun mp2IsNotMistakenForMp3() {
        val caps = DeviceCodecCapabilities()
        assertEquals(MimeTypes.AUDIO_MPEG_L2, caps.audioMimeType("mp2"))
        assertEquals(MimeTypes.AUDIO_MPEG_L1, caps.audioMimeType("mp1"))
        assertEquals(MimeTypes.AUDIO_MPEG, caps.audioMimeType("mp3"))
    }

    @Test fun localRendererDecodesMpegLayersAndBitstreamCodecs() {
        assertEquals("mp2", localDecoderName(MimeTypes.AUDIO_MPEG_L2))
        assertEquals("mp1", localDecoderName(MimeTypes.AUDIO_MPEG_L1))
        assertEquals("dca", localDecoderName(MimeTypes.AUDIO_DTS_HD))
        assertEquals("eac3", localDecoderName(MimeTypes.AUDIO_E_AC3_JOC))
        // MediaCodec handles these on every device.
        assertNull(localDecoderName(MimeTypes.AUDIO_AAC))
        assertNull(localDecoderName(MimeTypes.AUDIO_MPEG))
        assertTrue(localDecodableSourceCodec("mp2"))
        assertTrue(localDecodableSourceCodec("dca-ma"))
        assertFalse(localDecodableSourceCodec("aac"))
    }

    @Test fun mpegLayerLabels() {
        assertEquals("MP2", audioCodecLabel("audio/mpeg-L2"))
        assertEquals("MP1", audioCodecLabel("mp1"))
        assertEquals("MP3", audioCodecLabel("audio/mpeg"))
    }

    // --- DTS-HD → DTS core in Media3 ---

    private val dtsOnly = AudioRouteCapabilities(passthrough = setOf("ac3", "dts"), pcmChannels = 2)
    private val bitstream = AudioOptions(output = "bitstream")

    @Test fun media3SendsDtsCoreItselfWithoutMpv() {
        for (backend in listOf("media3", "auto")) {
            val plan = planAudioOutput(bitstream, "dts-hd", dtsOnly, backend, true)
            assertEquals(backend, AudioOutputMode.DTS_CORE, plan.mode)
            assertFalse(backend, plan.needsMpv)
            assertEquals(listOf("ac3", "dts"), plan.spdifCodecs)
        }
        // VLC cannot strip the extension; MPV still takes over there.
        val vlc = planAudioOutput(bitstream, "dts-hd", dtsOnly.copy(mpvAvailable = true), "vlc", true)
        assertEquals(AudioOutputMode.DTS_CORE, vlc.mode)
        assertTrue(vlc.needsMpv)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(bitstream, "dts-hd", dtsOnly, "vlc", true).mode)
    }

    @Test fun permittedDtsCoreIsIndependentOfPcmConnectionWidth() {
        val hdmi = dtsOnly.copy(pcmChannels = 8)
        assertEquals(AudioOutputMode.DTS_CORE, planAudioOutput(AudioOptions(output = "source"), "dts-hd", hdmi, "media3", true).mode)
        assertEquals(AudioOutputMode.DTS_CORE, planAudioOutput(bitstream, "dts-hd", hdmi, "media3", true).mode)
        // Stereo-only PCM (ARC): the core keeps 5.1.
        assertEquals(AudioOutputMode.DTS_CORE, planAudioOutput(AudioOptions(output = "source"), "dts-hd", dtsOnly, "media3", true).mode)
    }

    @Test fun noDtsCoreWhenHdIsConfirmedOrDtsIsNotOrFallbackIsOff() {
        val hd = dtsOnly.copy(passthrough = setOf("dts", "dts-hd"))
        assertEquals(AudioOutputMode.PASSTHROUGH, planAudioOutput(bitstream, "dts-hd", hd, "media3", true).mode)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(bitstream, "dts-hd", dtsOnly.copy(passthrough = setOf("ac3")), "media3", true).mode)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(bitstream.copy(localFallback = "pcm"), "dts-hd", dtsOnly, "media3", true).mode)
        assertEquals(AudioOutputMode.PCM, planAudioOutput(AudioOptions(output = "pcm"), "dts-hd", dtsOnly, "media3", true).mode)
    }

    private fun coreFrame(size: Int, packetPadding: Int = 0): ByteBuffer {
        val bytes = ByteArray(size + packetPadding)
        bytes[0] = 0x7F; bytes[1] = 0xFE.toByte(); bytes[2] = 0x80.toByte(); bytes[3] = 0x01
        val field = size - 1 // FSIZE: low 2 bits of byte 5, byte 6, high nibble of byte 7
        bytes[4] = 0xFC.toByte() // FTYPE=1, SHORT=31, CPF=0, NBLKS high bit 0
        bytes[5] = (0x7C or ((field shr 12) and 0x03)).toByte()
        bytes[6] = ((field shr 4) and 0xFF).toByte()
        bytes[7] = (((field and 0x0F) shl 4) or 0x02).toByte()
        // Extension substream after the core.
        if (packetPadding >= 4) { bytes[size] = 0x64; bytes[size + 1] = 0x58; bytes[size + 2] = 0x20; bytes[size + 3] = 0x25 }
        return ByteBuffer.wrap(bytes)
    }

    @Test fun coreFrameSizeComesFromTheHeader() {
        // 1006 bytes: the core of 1509 kbit/s DTS at 48 kHz, 512 samples per frame.
        assertEquals(1006, dtsCoreFrameSize(coreFrame(1006, packetPadding = 2000), 0, 3006))
        assertEquals(2012, dtsCoreFrameSize(coreFrame(2012), 0, 2012))
    }

    @Test fun packetsWithoutACoreOrTruncatedFramesAreRejected() {
        val noCore = ByteBuffer.wrap(byteArrayOf(0x64, 0x58, 0x20, 0x25, 0, 0, 0, 0, 0, 0, 0, 0))
        assertNull(dtsCoreFrameSize(noCore, 0, 12))
        assertNull(dtsCoreFrameSize(coreFrame(1006), 0, 900))
        assertNull(dtsCoreFrameSize(coreFrame(1006), 0, 6))
    }

    @Test fun coreOutputIsAtMostFiveOneAtTheCoreRate() {
        val ma71 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(8).setSampleRate(96_000).build()
        val core = dtsCoreOutputFormat(ma71)
        assertEquals(MimeTypes.AUDIO_DTS, core.sampleMimeType)
        assertEquals(6, core.channelCount)
        assertEquals(48_000, core.sampleRate)
        val stereo = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(2).setSampleRate(44_100).build()
        assertEquals(2, dtsCoreOutputFormat(stereo).channelCount)
        assertEquals(44_100, dtsCoreOutputFormat(stereo).sampleRate)
    }

    // --- OSD audio chip ---

    private fun badge(local: String? = null, server: String? = null, mode: AudioOutputMode? = null, codec: String? = null) =
        audioOutputBadgeLabel(local, server, mode, codec, "Passthrough", "DTS-Kern", "Server")

    @Test fun downmixChipUsesOnlySourceAndDestinationLayout() {
        assertEquals("5.1 → Stereo", audioOutputBadgeLabel("AC3 5.1 → PCM 2.0", null,
            AudioOutputMode.PCM, "ac3", "Passthrough", "DTS-Kern", "Server", downmix = "5.1 → Stereo"))
    }

    @Test fun audioChipNamesTheActualConversionFirst() {
        assertEquals("DTS-HD 7.1 → DTS 5.1", badge(local = "DTS-HD 7.1 → DTS 5.1", mode = AudioOutputMode.DTS_CORE, codec = "dts-hd"))
        assertEquals("E-AC3 5.1 → PCM 5.1", badge(local = "E-AC3 5.1 → PCM 5.1", mode = AudioOutputMode.PCM, codec = "eac3"))
        assertEquals("Server → AC3", badge(server = "ac3", mode = AudioOutputMode.PCM, codec = "truehd"))
    }

    @Test fun audioChipShowsBitstreamAndBitstreamDecodesOnly() {
        assertEquals("Passthrough", badge(mode = AudioOutputMode.PASSTHROUGH, codec = "truehd"))
        assertEquals("DTS-Kern", badge(mode = AudioOutputMode.DTS_CORE, codec = "dts-hd"))
        assertEquals("→ PCM", badge(mode = AudioOutputMode.PCM, codec = "dts"))
        // Ordinary decodes stay quiet.
        assertNull(badge(mode = AudioOutputMode.PCM, codec = "aac"))
        assertNull(badge())
    }

    @Test fun conversionTextKeepsBothFormats() {
        assertEquals("DTS-HD 7.1 → DTS 5.1", audioConversionFormats("audio/vnd.dts.hd", 8, "audio/vnd.dts", 6))
    }

    // --- Settings: confirmed bitstream formats ---

    @Test fun routeSummaryListsConfirmedAndMissingFormats() {
        val summary = bitstreamRouteSummary(AudioRouteCapabilities(passthrough = setOf("ac3", "eac3", "dts")))
        assertEquals(listOf("ac3", "eac3", "dts"), summary.confirmed)
        assertEquals(listOf("dts-hd", "truehd"), summary.unconfirmed)
        val rejected = bitstreamRouteSummary(AudioRouteCapabilities(passthrough = setOf("ac3", "dts")), setOf("dts"))
        assertEquals(listOf("ac3"), rejected.confirmed)
        assertTrue(bitstreamRouteSummary(AudioRouteCapabilities()).confirmed.isEmpty())
    }
}

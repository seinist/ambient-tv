package com.ambient.tv.ui.player

import com.ambient.tv.ui.player.backend.MpvPlaybackSnapshot
import com.ambient.tv.ui.player.backend.VlcPlaybackSnapshot
import com.ambient.tv.ui.player.backend.VlcRuntimeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNerdStatsOverlayTest {
    @Test fun bitstreamVisibilityFollowsOutputModeAcrossBackends() {
        for (mode in listOf(null) + AudioOutputMode.entries) {
            val models = listOf(
                buildMedia3NerdOverlayModel(Media3NerdStats(), audioOutputMode = mode),
                buildMpvNerdOverlayModel(MpvPlaybackSnapshot(), audioOutputMode = mode),
                buildVlcNerdOverlayModel(VlcPlaybackSnapshot(), audioOutputMode = mode),
            )
            for (model in models) {
                val bitstream = model.audio.find { it.label == "Bitstream" }
                if (mode == null || mode == AudioOutputMode.PCM) {
                    assertEquals(null, bitstream)
                } else {
                    assertEquals("Active", bitstream?.value)
                    assertTrue(model.audio.any { it.label == "Audio output" })
                }
            }
        }
    }

    @Test fun cadenceUsesActiveOutputAcrossBackends() {
        val media3 = buildMedia3NerdOverlayModel(Media3NerdStats(), cadenceFps = 25.0, displayRefreshHz = 50.0)
        val vlc = buildVlcNerdOverlayModel(VlcPlaybackSnapshot(hasVideo = true,
            runtimeStats = VlcRuntimeStats(videoFps = 25.0)), displayRefreshHz = 50.0)
        for (model in listOf(media3, vlc)) {
            assertEquals("Double frame rate", model.system.first { it.label == "Display cadence" }.value)
        }
        val snapshot = MpvPlaybackSnapshot(containerFps = 23.976, fps = 23.976, displayFps = 23.976)
        val pulldown = buildMpvNerdOverlayModel(snapshot, displayRefreshHz = 59.94)
        assertEquals("3:2 pulldown", pulldown.system.first { it.label == "Display cadence" }.value)
        val interpolated = buildMpvNerdOverlayModel(snapshot.copy(fps = 59.94), displayRefreshHz = 59.94)
        assertTrue(interpolated.system.none { it.label == "Display cadence" })
        assertTrue(buildMpvNerdOverlayModel(snapshot).system.none { it.label == "Display cadence" })
    }

    @Test
    fun media3DroppedFramesAndUnderrunsWarn() {
        val model = buildMedia3NerdOverlayModel(
            stats = Media3NerdStats(
                videoDecoderName = "c2.android.avc.decoder",
                droppedVideoFrames = 3,
                audioUnderruns = 2,
                bufferDurationMs = 4500,
                surfaceSize = "1920×1080",
            ),
            videoSummary = "1920×1080 · 23.976 fps",
            audioOutputMode = AudioOutputMode.PCM,
            displayRefreshHz = 59.94,
        )
        assertTrue(model.video.any { it.label == "Dropped frames" && it.warn && it.value == "3" })
        assertTrue(model.audio.any { it.label == "Audio underruns" && it.warn })
        assertTrue(model.system.any { it.value == "Media3" })
        assertTrue(model.system.any { it.label == "Display refresh" })
        assertTrue(model.audio.any { it.label == "Audio output" && it.value == "PCM" })
    }

    @Test
    fun media3CleanStatsDoNotWarn() {
        val model = buildMedia3NerdOverlayModel(
            stats = Media3NerdStats(droppedVideoFrames = 0, audioUnderruns = 0),
        )
        assertTrue(model.video.none { it.warn })
        assertTrue(model.audio.none { it.warn })
    }

    @Test
    fun mpvOverlayWarnsOnDropsAndAvSync() {
        val model = buildMpvNerdOverlayModel(
            snapshot = MpvPlaybackSnapshot(
                videoSize = "3840×2160",
                fps = 23.976,
                videoCodec = "hevc",
                decoder = "yes",
                frameDropCount = 5,
                avSyncSeconds = 0.2,
                cacheDurationSeconds = 12.5,
                displayFps = 59.94,
                audioCodec = "eac3",
            ),
            audioOutputMode = AudioOutputMode.PASSTHROUGH,
        )
        assertTrue(model.video.any { it.label == "Dropped frames" && it.warn })
        assertTrue(model.audio.any { it.label == "A/V sync" && it.warn })
        assertEquals("MPV", model.system.first { it.label == "Backend" }.value)
        assertFalse(model.isEmpty)
    }

    @Test
    fun vlcOverlayWarnsOnLostPicturesAndAudio() {
        val model = buildVlcNerdOverlayModel(
            snapshot = VlcPlaybackSnapshot(
                hasVideo = true,
                runtimeStats = VlcRuntimeStats(
                    videoWidth = 1280,
                    videoHeight = 720,
                    videoFps = 50.0,
                    lostPictures = 4,
                    lostAudioBuffers = 1,
                    displayedPictures = 1000,
                ),
            ),
            audioCodec = "ac3",
            audioOutputMode = AudioOutputMode.PCM,
            displayRefreshHz = 50.0,
        )
        assertTrue(model.video.any { it.label == "Lost frames" && it.warn })
        assertTrue(model.audio.any { it.label == "Lost audio buffers" && it.warn })
        assertTrue(model.system.any { it.value == "VLC" })
    }

    @Test
    fun unsampledBackendsDoNotInventAppliedProcessing() {
        val media3 = buildMedia3NerdOverlayModel(stats = Media3NerdStats())
        assertEquals("off", media3.video.first { it.label == "Video filters" }.value)
        assertEquals("n/a", media3.video.first { it.label == "Deinterlacer" }.value)
        assertEquals("n/a", media3.video.first { it.label == "Upscaler" }.value)
        assertEquals("n/a", media3.video.first { it.label == "Frame interpolation" }.value)
        val mpv = buildMpvNerdOverlayModel(
            snapshot = MpvPlaybackSnapshot(activeScaler = "lanczos", activeDeinterlace = "yes"),
        )
        val vlc = buildVlcNerdOverlayModel(snapshot = VlcPlaybackSnapshot(hasVideo = true))
        for (model in listOf(mpv, vlc)) {
            assertEquals("n/a", model.video.first { it.label == "Video filters" }.value)
            assertEquals("n/a", model.video.first { it.label == "Deinterlacer" }.value)
            assertEquals("n/a", model.video.first { it.label == "Upscaler" }.value)
            assertEquals("n/a", model.video.first { it.label == "Frame interpolation" }.value)
        }
    }

    @Test
    fun media3ReportsOnlyEffectsThatWereApplied() {
        val active = buildMedia3NerdOverlayModel(
            stats = Media3NerdStats(),
            applied = media3AppliedProcessing(
                effectsActive = true,
                scaler = "lanczos",
                brightness = -5,
                contrast = 0,
                saturation = 10,
                outputWidth = 1920,
                outputHeight = 1080,
            ),
        )
        assertEquals("brightness -5, saturation 10", active.video.first { it.label == "Video filters" }.value)
        assertEquals("Lanczos", active.video.first { it.label == "Upscaler" }.value)
        assertEquals("n/a", active.video.first { it.label == "Deinterlacer" }.value)
        assertEquals("n/a", active.video.first { it.label == "Frame interpolation" }.value)

        val idle = media3AppliedProcessing(
            effectsActive = false,
            scaler = "lanczos",
            brightness = -5,
            contrast = 0,
            saturation = 10,
            outputWidth = 1920,
            outputHeight = 1080,
        )
        assertEquals("off", idle.videoFilters)
        assertEquals("n/a", idle.upscaler)
    }

    @Test
    fun mpvOverlayUsesLivePropertiesNotConfiguredDefaults() {
        val model = buildMpvNerdOverlayModel(
            snapshot = MpvPlaybackSnapshot(
                processingSampled = true,
                gpuScalerExposed = true,
                activeVideoFilters = "@plex-denoise:lavfi=[hqdn3d=1.5:1.5:3:3],@plex-deint:lavfi=[bwdif=mode=send_frame:parity=auto:deint=all]",
                activeDeinterlace = "no",
                activeScaler = "ewa_lanczossharp",
                activeShaderPreset = null,
            ),
        )
        assertEquals("denoise", model.video.first { it.label == "Video filters" }.value)
        assertEquals("bwdif", model.video.first { it.label == "Deinterlacer" }.value)
        assertEquals("EWA LanczosSharp", model.video.first { it.label == "Upscaler" }.value)
        assertEquals("off", model.video.first { it.label == "Frame interpolation" }.value)

        val direct = buildMpvNerdOverlayModel(
            snapshot = MpvPlaybackSnapshot(
                processingSampled = true,
                gpuScalerExposed = false,
                activeScaler = "lanczos",
                activeDeinterlace = "no",
            ),
        )
        assertEquals("off", direct.video.first { it.label == "Video filters" }.value)
        assertEquals("off", direct.video.first { it.label == "Deinterlacer" }.value)
        assertEquals("n/a", direct.video.first { it.label == "Upscaler" }.value)
        assertEquals("off", direct.video.first { it.label == "Frame interpolation" }.value)

        val sameSize = buildMpvNerdOverlayModel(
            snapshot = MpvPlaybackSnapshot(
                processingSampled = true,
                gpuScalerExposed = true,
                configuredScaler = "nnedi3_nns32",
                activeScaler = "lanczos",
                activeUpscaling = false,
            ),
        )
        assertEquals("off", sameSize.video.first { it.label == "Upscaler" }.value)
    }

    @Test
    fun mpvSvpAndShaderAreReportedFromTheRunningChain() {
        val model = buildMpvNerdOverlayModel(
            snapshot = MpvPlaybackSnapshot(
                processingSampled = true,
                gpuScalerExposed = true,
                activeVideoFilters = "@plex-svp:ambientsvp=source=23.976:fps=60:preset=2:backend=0",
                activeDeinterlace = "no",
                activeScaler = "lanczos",
                activeShaderPreset = "fsr",
            ),
        )
        assertEquals("svp", model.video.first { it.label == "Video filters" }.value)
        assertEquals("off", model.video.first { it.label == "Deinterlacer" }.value)
        assertEquals("svp", model.video.first { it.label == "Frame interpolation" }.value)
        assertTrue(model.video.first { it.label == "Upscaler" }.value.startsWith("FSR"))
    }

    @Test
    fun mpvIdleNnediConfiguredScalerIsNotShownAsLanczosAlone() {
        val model = buildMpvNerdOverlayModel(
            snapshot = MpvPlaybackSnapshot(
                processingSampled = true,
                gpuScalerExposed = true,
                activeScaler = "lanczos",
                activeShaderPreset = null,
                configuredScaler = "nnedi3_nns32",
                activeDeinterlace = "no",
            ),
        )
        val upscaler = model.video.first { it.label == "Upscaler" }.value
        assertTrue(upscaler, upscaler.startsWith("NNEDI3 Quality (nns32)"))
        assertTrue(upscaler, "Lanczos finish" in upscaler)
    }

    @Test
    fun mpvSvpRowShowsRatesAndFpsRowSourceToOutput() {
        val model = buildMpvNerdOverlayModel(
            snapshot = MpvPlaybackSnapshot(
                processingSampled = true,
                activeVideoFilters = "@plex-svp:ambientsvp=source=23.976:fps=60:preset=2:backend=0",
                svpStatus = "23.976 → 60 fps · balanced",
                videoSize = "1920×1080",
                containerFps = 23.976,
                fps = 59.94,
            ),
        )
        assertEquals("SVP 23.976 → 60 fps · balanced", model.video.first { it.label == "Frame interpolation" }.value)
        assertEquals("23.976 → 59.940 fps", model.video.first { it.label == "FPS" }.value)
        assertTrue(model.video.first { it.label == "Video" }.value.contains("23.976 fps"))
    }

    @Test
    fun vlcReportsInstanceChainAndDoesNotInventAScaler() {
        val plain = vlcInstanceAppliedProcessing(
            deinterlaceMode = "on",
            deinterlaceFilter = "yadif",
            svpMode = "off",
            svpTargetFps = 60.0,
        )
        assertEquals("off", plain.videoFilters)
        assertEquals("yadif", plain.deinterlacer)
        assertEquals("n/a", plain.upscaler)
        assertEquals("off", plain.frameInterpolation)

        val svp = vlcInstanceAppliedProcessing(
            deinterlaceMode = "off",
            deinterlaceFilter = "yadif",
            svpMode = "balanced",
            svpTargetFps = 60.0,
        )
        assertEquals("svp", svp.videoFilters)
        assertEquals("off", svp.deinterlacer)
        assertEquals("n/a", svp.upscaler)
        assertEquals("svp · balanced · 60 fps", svp.frameInterpolation)

        val model = buildVlcNerdOverlayModel(
            snapshot = VlcPlaybackSnapshot(
                hasVideo = false,
                processingSampled = true,
                appliedVideoFilters = svp.videoFilters,
                appliedDeinterlacer = svp.deinterlacer,
                appliedUpscaler = svp.upscaler,
                appliedFrameInterpolation = svp.frameInterpolation,
            ),
        )
        assertEquals("svp · balanced · 60 fps", model.video.first { it.label == "Frame interpolation" }.value)
    }
}

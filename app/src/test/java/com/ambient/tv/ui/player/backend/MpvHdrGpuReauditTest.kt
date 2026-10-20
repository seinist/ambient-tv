package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.PlayerVideoSettings
import com.ambient.tv.ui.player.titleMpvOutputToStore
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Re-audit of "HDR über GPU" (#24/#25/#28): M1–M6, G1–G8 and the behaviour gaps. */
class MpvHdrGpuReauditTest {
    private val vulkan12 = (1L shl 22) or (2L shl 12)
    private val caps = VideoAccelerationCapabilities(
        checked = true,
        device = VulkanDeviceCapabilities(render = true, device = "gpu", version = vulkan12, hostQueryReset = true,
            surfaceColorSpaces = listOf(0, 1000104008)),
        mpvOutputBuilt = true,
    )
    private fun decide(source: HdrStandard?, output: String = "direct", explicitDirect: Boolean = false,
        failure: MpvHdrGpuReason? = null, effectsActive: Boolean = true) =
        mpvHdrGpuDecision(true, output, source, setOf(HdrStandard.HDR10), caps, false, failure, explicitDirect, effectsActive, true)

    @After fun clear() = MpvHdrGpuSession.clear()

    // M2: the player's automatic "direct" ------------------------------------------------------

    @Test fun automaticDirectIsNoExplicitChoice() {
        val configured = PlayerVideoSettings(mpvOutput = "auto", mpvDecoder = "vulkan")
        val automatic = configured.withAutomaticHdrDirect()
        assertEquals("direct", automatic.mpvOutput)
        assertTrue(automatic.automaticHdrDirect)
        // Same decoder rule as a user's "direct".
        assertEquals(configured.withMpvOutput("direct").mpvDecoder, automatic.mpvDecoder)
        // A user's later choice replaces it, also a "direct" of their own.
        assertFalse(automatic.withMpvOutput("direct").automaticHdrDirect)
        assertFalse(automatic.copy(mpvDecoder = "software").withMpvOutput("auto").automaticHdrDirect)
        // Profiles and the title store see the configured output, not the automatic one.
        assertEquals("auto", automatic.chosen(configured).mpvOutput)
        assertFalse(automatic.chosen(configured).automaticHdrDirect)
        assertEquals("gpu", PlayerVideoSettings(mpvOutput = "gpu").chosen(configured).mpvOutput)
        // Decision: the override is filtered before explicitness is judged (videoSettingsOverride).
        val sessionOutput = automatic.takeUnless { it.automaticHdrDirect }?.mpvOutput
        assertFalse(mpvHdrGpuExplicitDirect(sessionOutput, null, null, globalOutput = "auto"))
        assertEquals(MpvHdrGpuDecision.GPU, decide(HdrStandard.HDR10, output = automatic.mpvOutput,
            explicitDirect = mpvHdrGpuExplicitDirect(sessionOutput, null, null, globalOutput = "auto")))
    }

    @Test fun titleStoreKeepsItsOutputForAnAutomaticDirect() {
        val automatic = PlayerVideoSettings(mpvOutput = "auto").withAutomaticHdrDirect()
        assertNull(titleMpvOutputToStore(null, automatic, defaultOutput = "auto", snapshot = false))
        assertEquals("gpu", titleMpvOutputToStore("gpu", automatic, defaultOutput = "auto", snapshot = true))
        // A user's choice is stored as before.
        assertEquals("direct", titleMpvOutputToStore(null, PlayerVideoSettings().withMpvOutput("direct"), "auto", false))
        assertNull(titleMpvOutputToStore("gpu", PlayerVideoSettings().withMpvOutput("auto"), "auto", false))
    }

    @Test fun viewMarksItsOwnDirectAndProfilesStoreTheChosenOutput() {
        val view = File("src/main/java/com/ambient/tv/ui/player/backend/MpvPlaybackView.kt").readText()
        assertTrue(view.contains("settings.withAutomaticHdrDirect()"))
        assertFalse(view.contains("settings.withMpvOutput(\"direct\")"))
        val profiles = File("src/main/java/com/ambient/tv/ui/player/ResolutionVideoProfileStore.kt").readText()
        assertTrue(profiles.contains("BackgroundPlaybackSession.chosenVideoSettings("))
        val session = File("src/main/java/com/ambient/tv/ui/player/BackgroundPlaybackSession.kt").readText()
        assertTrue(session.contains("videoSettingsOverride?.takeUnless { it.automaticHdrDirect }"))
    }

    // M3: failure keys follow the title, not the shared remux URL ---------------------------------

    @Test fun plexRemuxTitlesDoNotShareAFailure() {
        val remux = "https://plex:32400/video/:/transcode/universal/start.mkv?path=%2Flibrary%2Fmetadata%2F"
        val a = mpvHdrGpuMediaKey("plex:srv", "101", null, "player/101?mediaIndex=0", false, remux + "101")!!
        val b = mpvHdrGpuMediaKey("plex:srv", "202", null, "player/202?mediaIndex=0", false, remux + "202")!!
        assertNotEquals(a, b)
        MpvHdrGpuSession.recordFailure(a, "3840x2160", "tv", MpvHdrGpuReason.OVERLOAD)
        assertEquals(MpvHdrGpuReason.OVERLOAD, MpvHdrGpuSession.failure(a, "3840x2160", "tv"))
        assertNull(MpvHdrGpuSession.failure(b, "3840x2160", "tv"))
        // Another session URL of the same title keeps its failure.
        assertEquals(a, mpvHdrGpuMediaKey("plex:srv", "101", null, "player/101?mediaIndex=0", false, remux + "101&session=9"))
        // Versions of a title are separate; so are sources and live servers.
        assertNotEquals(a, mpvHdrGpuMediaKey("plex:srv", "101", null, "player/101?mediaIndex=1", false, remux))
        assertNotEquals(a, mpvHdrGpuMediaKey("jellyfin:srv", "101", null, "player/101?mediaIndex=0", false, remux))
        assertNotEquals(mpvHdrGpuMediaKey("tvh", "ch1", "s1", "tvh/ch1", false, null),
            mpvHdrGpuMediaKey("tvh", "ch1", "s2", "tvh/ch1", false, null))
        // Trailers and playbacks without an id fall back to the URL without query.
        assertEquals("url|https://h/t.mp4", mpvHdrGpuMediaKey("plex:srv", "101", null, null, true, "https://h/t.mp4?x=1"))
        assertEquals("url|https://h/v.mkv", mpvHdrGpuMediaKey(null, " ", null, null, false, "https://h/v.mkv?t=1"))
        assertNull(mpvHdrGpuMediaKey(null, null, null, null, false, null))
        assertNull(MpvHdrGpuSession.failure(null, null, "tv"))
    }

    // M4: Dolby Vision on every route --------------------------------------------------------------

    @Test fun dolbyVisionReadOnTheDirectRouteKeepsTheFileOffThePassthrough() {
        // The direct route reports the PQ base layer as HDR10; its track list says DV.
        assertEquals(HdrStandard.DOLBY_VISION, mpvHdrGpuSourceStandard(HdrStandard.HDR10, HdrStandard.HDR10, dolbyVisionRuntime = true))
        assertEquals(MpvHdrGpuReason.DOLBY_VISION, decide(mpvHdrGpuSourceStandard(HdrStandard.HDR10, null, true)).reason)
        // An earlier DV detection wins over a server HDR10 and over the base layer's later HDR10.
        val key = "media|plex|1|1|0"
        assertTrue(MpvHdrGpuSession.recordDetected(key, HdrStandard.DOLBY_VISION, nowMs = 0L))
        assertFalse(MpvHdrGpuSession.recordDetected(key, HdrStandard.HDR10, nowMs = 10L))
        assertEquals(HdrStandard.DOLBY_VISION, MpvHdrGpuSession.detectedHdr(key, nowMs = 20L))
        assertEquals(HdrStandard.DOLBY_VISION, mpvHdrGpuSourceStandard(HdrStandard.HDR10, MpvHdrGpuSession.detectedHdr(key, 20L), false))
        // Without DV the declared standard still wins over the runtime one.
        assertEquals(HdrStandard.HDR10_PLUS, mpvHdrGpuSourceStandard(HdrStandard.HDR10_PLUS, HdrStandard.HDR10, false))
        assertEquals(HdrStandard.HDR10, mpvHdrGpuSourceStandard(null, HdrStandard.HDR10, false))
        assertNull(mpvHdrGpuSourceStandard(null, null, false))
        // Live: a channel may change programme, DV does not stick there.
        assertTrue(MpvHdrGpuSession.recordDetected("live", HdrStandard.DOLBY_VISION, live = true, nowMs = 0L))
        MpvHdrGpuSession.forgetDetected("live")
        assertNull(MpvHdrGpuSession.detectedHdr("live", nowMs = 1L))
    }

    @Test fun engineLatchesTheDolbyVisionProfileOnEveryRoute() {
        val engine = File("src/main/java/com/ambient/tv/ui/player/backend/MpvPlaybackEngine.kt").readText()
        // No passthrough-only read any more, and no HDR10 overwrite in the image path.
        assertFalse(engine.contains("hdrPassthroughActive && hdr)"))
        assertFalse(engine.contains("sourceHdrStandard = hdrStandardFromMpvTransfer("))
        assertEquals(2, Regex("sourceHdrStandard = runtimeSourceHdrStandard\\(").findAll(engine).count())
        assertTrue(engine.contains("getPropertyDouble(MPV_PROP_DOLBY_VISION_PROFILE, 0.0)"))
        // Re-read per file.
        assertTrue(engine.contains("dolbyVisionProfile = null"))
        val view = File("src/main/java/com/ambient/tv/ui/player/backend/MpvPlaybackView.kt").readText()
        assertTrue(view.contains("dolbyVisionRuntime = activeSame?.dolbyVisionDetected == true"))
    }

    // M5: real enlargement -------------------------------------------------------------------------

    @Test fun fittedScaleComesFromSourceAndDisplayMode() {
        assertEquals(1.0, mpvHdrGpuFittedScale(3840, 2160, 3840, 2160), 1e-9)
        assertEquals(2.0, mpvHdrGpuFittedScale(1920, 1080, 3840, 2160), 1e-9)
        assertEquals(2.0, mpvHdrGpuFittedScale(1920, 1080, 2160, 3840), 1e-9)
        // Scope 2.39:1 on 16:9 is limited by the width.
        assertEquals(1.0, mpvHdrGpuFittedScale(3840, 1606, 3840, 2160), 1e-9)
        assertEquals(Double.POSITIVE_INFINITY, mpvHdrGpuFittedScale(null, null, 3840, 2160), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, mpvHdrGpuFittedScale(1920, 1080, null, null), 0.0)
        // A 4K title on a 4K display: the luma upscaler alone does not justify the GPU route.
        val ravu = MpvImageOptions(scaler = "ravu_lite_r3", sharpening = "off", deband = "off", filmGrain = "off")
        assertFalse(mpvHdrGpuEffectsActive(ravu, mpvHdrGpuFittedScale(3840, 2160, 3840, 2160)))
        assertTrue(mpvHdrGpuEffectsActive(ravu, mpvHdrGpuFittedScale(1920, 1080, 3840, 2160)))
        assertTrue(mpvHdrGpuEffectsActive(ravu, mpvHdrGpuFittedScale(null, null, 3840, 2160)))
        // Sharpening runs at any scale.
        assertTrue(mpvHdrGpuEffectsActive(ravu.copy(sharpening = "cas"), 1.0))
    }

    // G1: transient NOT_CONFIRMED, Vulkan fallback -------------------------------------------------

    @Test fun confirmationResetsTheNotConfirmedCount() {
        MpvHdrGpuSession.recordFailure("a", null, "tv", MpvHdrGpuReason.NOT_CONFIRMED)
        MpvHdrGpuSession.recordConfirmed("tv")
        MpvHdrGpuSession.recordFailure("b", null, "tv", MpvHdrGpuReason.NOT_CONFIRMED)
        // Not two in a row: the display stays open, only the two titles are blocked.
        assertNull(MpvHdrGpuSession.failure("c", null, "tv"))
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, MpvHdrGpuSession.failure("b", null, "tv"))
        MpvHdrGpuSession.recordFailure("d", null, "tv", MpvHdrGpuReason.NOT_CONFIRMED)
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, MpvHdrGpuSession.failure("c", null, "tv"))
    }

    @Test fun vulkanFallbackStaysWithTheTitle() {
        repeat(3) { MpvHdrGpuSession.recordFailure("v$it", null, "tv", MpvHdrGpuReason.VULKAN_FALLBACK) }
        assertNull(MpvHdrGpuSession.failure("other", null, "tv"))
        assertEquals(MpvHdrGpuReason.VULKAN_FALLBACK, MpvHdrGpuSession.failure("v1", null, "tv"))
        assertEquals("direct", mpvHdrGpuOutputMode(decide(HdrStandard.HDR10, failure = MpvHdrGpuReason.VULKAN_FALLBACK),
            "auto", MpvHdrGpuReason.VULKAN_FALLBACK))
        val engine = File("src/main/java/com/ambient/tv/ui/player/backend/MpvPlaybackEngine.kt").readText()
        assertTrue(engine.contains("failHdrPassthrough(MpvHdrGpuReason.VULKAN_FALLBACK)"))
    }

    // G5: live keys --------------------------------------------------------------------------------

    @Test fun liveFailuresIgnoreAStaleWidth() {
        val live = com.ambient.tv.ui.player.PlaybackMetadata("tvh/1", "", source = "tvh", mediaId = "ch1",
            isLive = true, sourceWidth = 1920, sourceHeight = 1080)
        assertNull(mpvHdrGpuResolutionKey(live))
        assertEquals("3840x2160", mpvHdrGpuResolutionKey(live.copy(isLive = false, sourceWidth = 3840, sourceHeight = 2160)))
    }

    // K2 / G6: failure hold and background ---------------------------------------------------------

    @Test fun failedPassthroughHoldsOnlyWhileItWouldShow() {
        // Steady playback: no failure, play.
        assertTrue(mpvHdrNativePlayAllowed(playIntent = true, failure = null, attached = true))
        // Failure on screen: the frame is held, the intent survives for the direct reopen.
        val hold = mpvHdrFailureHold(playIntent = true, attached = true)
        assertTrue(hold.pauseNative && hold.playIntent)
        assertFalse(mpvHdrNativePlayAllowed(hold.playIntent, MpvHdrGpuReason.OVERLOAD, attached = true))
        // Moved to the background: audio continues; back in the foreground the view reopens.
        assertTrue(mpvHdrNativePlayAllowed(hold.playIntent, MpvHdrGpuReason.OVERLOAD, attached = false))
        assertFalse(mpvHdrFailureHold(playIntent = true, attached = false).pauseNative)
        // A paused user stays paused everywhere.
        assertFalse(mpvHdrNativePlayAllowed(false, MpvHdrGpuReason.OVERLOAD, attached = false))
        val engine = File("src/main/java/com/ambient/tv/ui/player/backend/MpvPlaybackEngine.kt").readText()
        assertTrue(engine.contains("mpvHdrNativePlayAllowed(desiredPlayWhenReady, hdrGpuFailure, attached)"))
        assertTrue(engine.contains("mpvHdrFailureHold(desiredPlayWhenReady, attached)"))
    }

    // G2 / G3 / G4: one decision for player and panel ----------------------------------------------

    @Test fun panelUsesThePlayersInputsAndConfirmation() {
        val panel = File("src/main/java/com/ambient/tv/ui/player/PlayerVideoOutputControls.kt").readText()
        assertTrue(panel.contains("mpvHdrGpuConfiguredImageOptions(preferences, title, resolution)"))
        assertTrue(panel.contains("sameVideoSettingsPlayback(metadata"))
        assertTrue(panel.contains("if (passthroughConfirmed) ImageHint(stringResource(R.string.player_output_hdr_gpu_active))"))
        val view = File("src/main/java/com/ambient/tv/ui/player/backend/MpvPlaybackView.kt").readText()
        assertTrue(view.contains("mpvHdrGpuConfiguredImageOptions(preferences, title, resolution)"))
        assertTrue(view.contains("MpvHdrGpuSession.recordConfirmed(displayKey)"))
        val controller = File("src/main/java/com/ambient/tv/ui/player/backend/MpvPlaybackController.kt").readText()
        assertTrue(controller.contains("hdrGpuPassthrough && hdrGpuFailure == null && state.hdrPassthroughVulkan"))
        val context = File("src/main/java/com/ambient/tv/ui/player/backend/MpvHdrGpuContext.kt").readText()
        assertFalse(context.contains("FEATURE_LEANBACK"))
        assertTrue(context.contains("isTelevision(context)"))
    }

    // G8: provenance of the local HDR CAS derivative -----------------------------------------------

    @Test fun hdrCasDerivativeIsInTheManifest() {
        val manifest = File("../SHADER_SOURCES_027.json").readText()
        val block = Regex("""\{[^{}]*"key":\s*"cas_hdr"[^{}]*\}""").find(manifest)!!.value
        fun field(name: String) = Regex(""""$name":\s*"([^"]+)"""").find(block)!!.groupValues[1]
        val file = File("../" + field("file"))
        assertEquals(MPV_CAS_HDR_FILE, file.name)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(field("sha256"), sha)
        assertEquals("cas", field("derived_from"))
        assertTrue(field("local_patch").contains("LUT"))
        // Same upstream as the pinned CAS.
        val cas = Regex("""\{[^{}]*"key":\s*"cas"[^{}]*\}""").find(manifest)!!.value
        assertEquals(Regex(""""sha256":\s*"([^"]+)"""").find(cas)!!.groupValues[1], field("upstream_sha256"))
    }
}

package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3SurfaceResolutionPolicyTest {
    @Test fun prospectiveTargetStaysAvailableBeforeAndAfterTheFirstEffect() {
        for (key in listOf("auto", "panel")) {
            val before = policy(resolutionKey = key, processingEnabled = false)
            val active = policy(resolutionKey = key, gpuPipelineInitialized = true)
            val removed = policy(resolutionKey = key, processingEnabled = false, gpuPipelineInitialized = true)
            assertTrue(key, listOf(before, active, removed).all { it.targetAtDisplayResolution })
            assertFalse(key, before.fixSurfaceSize)
            assertTrue(key, active.fixSurfaceSize)
            assertTrue(key, removed.fixSurfaceSize)
        }
    }

    @Test fun explicitUiFollowsTheUiWhileLowEndAutoPreservesTheOriginalWindow() {
        for (enabled in listOf(false, true)) {
            assertEquals(Media3SurfaceResolutionPolicy(false, false),
                policy(resolutionKey = "ui", processingEnabled = enabled))
            val auto = policy(resolutionKey = "auto", lowEndDevice = true, processingEnabled = enabled)
            assertEquals(VideoRenderTargetMode.WINDOW, auto.targetMode)
            assertEquals(if (enabled) VideoRenderTargetMode.WINDOW else VideoRenderTargetMode.UI,
                auto.bufferTargetMode)
            assertEquals(enabled, auto.fixSurfaceSize)
            assertFalse(auto.fixesBuffer(uiScaled = false))
            assertEquals(enabled, auto.fixesBuffer(uiScaled = true))
        }
        // A deliberate Panel choice keeps the existing override on a slower device.
        assertEquals(Media3SurfaceResolutionPolicy(true, true),
            policy(resolutionKey = "panel", lowEndDevice = true))
    }

    @Test fun originalWindowTargetNeverConstrainsDirectOrBlockedProcessing() {
        val blocked = listOf(
            policy(resolutionKey = "auto", lowEndDevice = true, gpuEffectsSupported = false),
            policy(resolutionKey = "auto", lowEndDevice = true, sdrEffectsAllowed = false),
            policy(resolutionKey = "auto", lowEndDevice = true, processingEnabled = false),
            policy(resolutionKey = "auto", lowEndDevice = true, reducedEffects = true),
            policy(resolutionKey = "auto", lowEndDevice = true, effectsFailed = true),
        )
        for (policy in blocked) {
            assertEquals(VideoRenderTargetMode.WINDOW, policy.targetMode)
            assertFalse(policy.fixesBuffer(uiScaled = true))
        }
        assertFalse(policy(resolutionKey = "ui").fixesBuffer(uiScaled = true))
    }

    @Test fun neverInitializedOutputStaysDirectWhenEffectsAreUnavailableOrFailed() {
        val blocked = listOf(
            "unsupported GPU" to policy(gpuEffectsSupported = false),
            "HDR, DRM or unknown source" to policy(sdrEffectsAllowed = false),
            "processing disabled" to policy(processingEnabled = false),
            "reduced effects" to policy(reducedEffects = true),
            "effect failure" to policy(effectsFailed = true),
        )
        for ((reason, policy) in blocked) {
            assertTrue(reason, policy.targetAtDisplayResolution)
            assertFalse(reason, policy.fixSurfaceSize)
        }
    }

    @Test fun removingLastEffectKeepsTheInitializedGpuOutputAtTheOriginalWindow() {
        val active = policy(resolutionKey = "auto", gpuPipelineInitialized = true)
        val removed = policy(resolutionKey = "auto", processingEnabled = false, gpuPipelineInitialized = true)
        val direct = policy(resolutionKey = "auto", processingEnabled = false, gpuPipelineInitialized = false)

        assertEquals(VideoRenderTargetMode.DISPLAY, active.bufferTargetMode)
        // Keep offering the panel scaler even though Auto's empty sink uses the window budget.
        assertEquals(VideoRenderTargetMode.DISPLAY, removed.targetMode)
        assertEquals(VideoRenderTargetMode.WINDOW, removed.bufferTargetMode)
        assertTrue(removed.fixesBuffer(uiScaled = true))
        assertFalse(removed.fixesBuffer(uiScaled = false))
        assertEquals(VideoRenderTargetMode.UI, direct.bufferTargetMode)
        assertFalse(direct.fixesBuffer(uiScaled = true))
    }

    @Test fun retainedGpuOutputUsesWindowWhenEffectsAreReducedFailedOrSourceBlocked() {
        val retained = listOf(
            policy(resolutionKey = "auto", reducedEffects = true, gpuPipelineInitialized = true),
            policy(resolutionKey = "auto", effectsFailed = true, gpuPipelineInitialized = true),
            // Restricting HDR/DRM effects does not turn an existing sink back into direct output.
            policy(resolutionKey = "auto", sdrEffectsAllowed = false, gpuPipelineInitialized = true),
        )
        for (policy in retained) {
            assertEquals(VideoRenderTargetMode.DISPLAY, policy.targetMode)
            assertEquals(VideoRenderTargetMode.WINDOW, policy.bufferTargetMode)
            assertTrue(policy.fixesBuffer(uiScaled = true))
            assertFalse(policy.fixesBuffer(uiScaled = false))
        }
    }

    @Test fun retainedGpuOutputHonorsExplicitPanelAndUiWithoutActiveEffects() {
        for (lowEnd in listOf(false, true)) {
            val panel = policy(resolutionKey = "panel", lowEndDevice = lowEnd,
                processingEnabled = false, gpuPipelineInitialized = true)
            assertEquals(VideoRenderTargetMode.DISPLAY, panel.bufferTargetMode)
            assertTrue(panel.fixesBuffer(uiScaled = false))
            assertTrue(panel.fixesBuffer(uiScaled = true))

            val ui = policy(resolutionKey = "ui", lowEndDevice = lowEnd,
                processingEnabled = false, gpuPipelineInitialized = true)
            assertEquals(VideoRenderTargetMode.UI, ui.bufferTargetMode)
            assertFalse(ui.fixSurfaceSize)
            assertFalse(ui.fixesBuffer(uiScaled = true))
        }
    }

    @Test fun neverInitializedOutputOnlyAcceptsEligibleEffectRequests() {
        val states = listOf(
            policy(sdrEffectsAllowed = false),
            policy(),
            policy(effectsFailed = true),
            policy(),
            policy(reducedEffects = true),
            policy(),
            policy(sdrEffectsAllowed = false),
        )
        assertTrue(states.all { it.targetAtDisplayResolution })
        assertEquals(listOf(false, true, false, true, false, true, false), states.map { it.fixSurfaceSize })
    }

    @Test fun fullHdSourceOnFourKOutputNeedsUpscalingBeforeTheMasterIsEnabled() {
        val policy = policy(resolutionKey = "auto", processingEnabled = false)
        assertTrue(policy.targetAtDisplayResolution)
        assertFalse(policy.fixSurfaceSize)
        val target = physicalVideoRenderSize(1920, 1080, 1920, 1080, 3840, 2160)!!
        assertEquals(3840 to 2160, target)
        assertTrue(media3NeedsUpscaling(1920, 1080, target.first, target.second))
        assertTrue(media3NeedsUpscaling(1280, 720, target.first, target.second))
        // The logical surface alone must not be used to decide this capability.
        assertFalse(media3NeedsUpscaling(1920, 1080, 1920, 1080))
    }

    @Test fun nativeFourKAndLetterboxedFourKDoNotAcquireAnUpscaler() {
        val fullscreen = physicalVideoRenderSize(1920, 1080, 1920, 1080, 3840, 2160)!!
        assertFalse(media3NeedsUpscaling(3840, 2160, fullscreen.first, fullscreen.second))
        val letterbox = physicalVideoRenderSize(1920, 804, 1920, 1080, 3840, 2160)!!
        assertEquals(3840 to 1608, letterbox)
        assertFalse(media3NeedsUpscaling(3840, 1608, letterbox.first, letterbox.second))
        assertTrue(media3NeedsUpscaling(1920, 804, letterbox.first, letterbox.second))
    }

    @Test fun currentModeChangesAlterUpscalingWhileLogicalGeometryStaysTheSame() {
        val modes = listOf(1920 to 1080, 3840 to 2160, 1920 to 1080)
        val targets = modes.map { (width, height) ->
            physicalVideoRenderSize(1920, 1080, 1920, 1080, width, height)!!
        }
        assertEquals(modes, targets)
        assertEquals(listOf(false, true, false), targets.map { (width, height) ->
            media3NeedsUpscaling(1920, 1080, width, height)
        })
    }

    @Test fun miniPlayerUsesItsPhysicalAreaAndStopsUnnecessaryUpscaling() {
        val fullscreen = physicalVideoRenderSize(1920, 1080, 1920, 1080, 3840, 2160)!!
        val miniPlayer = physicalVideoRenderSize(480, 270, 1920, 1080, 3840, 2160)!!
        assertEquals(960 to 540, miniPlayer)
        assertTrue(media3NeedsUpscaling(1920, 1080, fullscreen.first, fullscreen.second))
        assertFalse(media3NeedsUpscaling(1920, 1080, miniPlayer.first, miniPlayer.second))
    }

    @Test fun portraitTargetKeepsSourceRotationInTheUpscalingDecision() {
        // Display dimensions have been oriented to match the logical portrait layout.
        val target = physicalVideoRenderSize(1080, 1920, 1080, 1920, 2160, 3840)!!
        assertEquals(2160 to 3840, target)
        assertTrue(media3NeedsUpscaling(1920, 1080, target.first, target.second, rotationDegrees = 90))
        assertFalse(media3NeedsUpscaling(3840, 2160, target.first, target.second, rotationDegrees = 90))
    }

    private fun policy(
        resolutionKey: String = "panel",
        lowEndDevice: Boolean = false,
        gpuEffectsSupported: Boolean = true,
        sdrEffectsAllowed: Boolean = true,
        processingEnabled: Boolean = true,
        reducedEffects: Boolean = false,
        effectsFailed: Boolean = false,
        gpuPipelineInitialized: Boolean = false,
    ) = media3SurfaceResolutionPolicy(
        resolutionKey = resolutionKey,
        lowEndDevice = lowEndDevice,
        gpuEffectsSupported = gpuEffectsSupported,
        sdrEffectsAllowed = sdrEffectsAllowed,
        processingEnabled = processingEnabled,
        reducedEffects = reducedEffects,
        effectsFailed = effectsFailed,
        gpuPipelineInitialized = gpuPipelineInitialized,
    )
}

package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3SurfaceResolutionPolicyTest {
    @Test fun targetIsAvailableBeforeProcessingAndStableAcrossItsToggle() {
        for (key in listOf("auto", "panel")) {
            for (enabled in listOf(false, true, false)) {
                val policy = policy(resolutionKey = key, processingEnabled = enabled)
                assertTrue("$key / processing=$enabled", policy.targetAtDisplayResolution)
                assertEquals("$key / processing=$enabled", enabled, policy.fixSurfaceSize)
            }
        }
    }

    @Test fun explicitUiAndLowEndAutoKeepTheLogicalRenderTarget() {
        for (enabled in listOf(false, true)) {
            assertEquals(Media3SurfaceResolutionPolicy(false, false),
                policy(resolutionKey = "ui", processingEnabled = enabled))
            assertEquals(Media3SurfaceResolutionPolicy(false, false),
                policy(resolutionKey = "auto", lowEndDevice = true, processingEnabled = enabled))
        }
        // A deliberate Panel choice keeps the existing override on a slower device.
        assertEquals(Media3SurfaceResolutionPolicy(true, true),
            policy(resolutionKey = "panel", lowEndDevice = true))
    }

    @Test fun unavailableOrFailedEffectsDoNotForceANativeSurfaceBuffer() {
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

    @Test fun sourceAndFailureTransitionsRestoreSizingOnlyWhenEligible() {
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
    ) = media3SurfaceResolutionPolicy(
        resolutionKey = resolutionKey,
        lowEndDevice = lowEndDevice,
        gpuEffectsSupported = gpuEffectsSupported,
        sdrEffectsAllowed = sdrEffectsAllowed,
        processingEnabled = processingEnabled,
        reducedEffects = reducedEffects,
        effectsFailed = effectsFailed,
    )
}

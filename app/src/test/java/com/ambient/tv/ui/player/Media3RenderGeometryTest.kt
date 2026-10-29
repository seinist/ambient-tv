package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3RenderGeometryTest {
    @Test fun requestedFourKUnlocksTheScalerBeforeTheRealSurfaceHasResized() {
        val owner = Any()
        val geometry = Media3RenderGeometry()
        geometry.attach(owner)
        geometry.updateSurface(1920, 1080)

        assertTrue(geometry.updateTarget(owner, 3840 to 2160))
        assertEquals(3840 to 2160, geometry.scalerViewport)
        assertEquals(1920 to 1080, geometry.confirmedSurface)
        assertTrue(needsUpscaling(geometry.scalerViewport))
        assertFalse(needsUpscaling(geometry.confirmedSurface))

        assertTrue(geometry.updateSurface(3840, 2160))
        assertEquals(3840 to 2160, geometry.confirmedSurface)
        assertTrue(needsUpscaling(geometry.confirmedSurface))
    }

    @Test fun nativeFourKDoesNotNeedAScalerAfterTheSurfaceCatchesUp() {
        val owner = Any()
        val geometry = Media3RenderGeometry()
        geometry.attach(owner)
        geometry.updateTarget(owner, 3840 to 2160)
        geometry.updateSurface(1920, 1080)
        assertFalse(needsUpscaling(geometry.scalerViewport, 3840, 2160))
        assertFalse(needsUpscaling(geometry.confirmedSurface, 3840, 2160))
        geometry.updateSurface(3840, 2160)
        assertFalse(needsUpscaling(geometry.confirmedSurface, 3840, 2160))
    }

    @Test fun uiTargetDoesNotAdvertiseThePreviousFourKSurfaceAsTheIntendedViewport() {
        val owner = Any()
        val geometry = Media3RenderGeometry()
        geometry.attach(owner)
        geometry.updateTarget(owner, 3840 to 2160)
        geometry.updateSurface(3840, 2160)

        assertTrue(geometry.updateTarget(owner, 1920 to 1080))
        assertEquals(1920 to 1080, geometry.scalerViewport)
        assertFalse(needsUpscaling(geometry.scalerViewport))
        // The confirmed output changes only when its real surface callback arrives.
        assertEquals(3840 to 2160, geometry.confirmedSurface)
        geometry.updateSurface(1920, 1080)
        assertFalse(needsUpscaling(geometry.confirmedSurface))
    }

    @Test fun oldOwnerCannotOverwriteOrDetachTheReplacementSurface() {
        val oldOwner = Any()
        val newOwner = Any()
        val geometry = Media3RenderGeometry()
        geometry.attach(oldOwner)
        geometry.updateTarget(oldOwner, 3840 to 2160)
        geometry.updateSurface(1920, 1080)

        assertTrue(geometry.attach(newOwner))
        assertNull(geometry.requestedTarget)
        assertEquals(1920 to 1080, geometry.confirmedSurface)
        assertTrue(geometry.updateTarget(newOwner, 960 to 540))
        assertFalse(geometry.updateTarget(oldOwner, 3840 to 2160))
        assertFalse(geometry.detach(oldOwner))
        assertEquals(960 to 540, geometry.scalerViewport)

        assertTrue(geometry.detach(newOwner))
        assertFalse(geometry.updateTarget(newOwner, 3840 to 2160))
        assertNull(geometry.requestedTarget)
        assertEquals(1920 to 1080, geometry.scalerViewport)
    }

    @Test fun transientSurfaceDestructionKeepsTheConfirmedOutputAndDoesNotRequestAnUpdate() {
        val owner = Any()
        val geometry = Media3RenderGeometry()
        geometry.attach(owner)
        geometry.updateTarget(owner, 3840 to 2160)
        geometry.updateSurface(3840, 2160)

        for ((width, height) in listOf(0 to 0, 0 to 2160, 3840 to 0, -1 to -1)) {
            assertFalse(geometry.updateSurface(width, height))
            assertEquals(3840 to 2160, geometry.confirmedSurface)
            assertEquals(3840 to 2160, geometry.scalerViewport)
            assertTrue(needsUpscaling(geometry.confirmedSurface))
        }
        assertFalse(geometry.updateSurface(3840, 2160))
    }

    @Test fun repeatedAttachAndIdenticalDimensionsDoNotTriggerProcessingUpdates() {
        val owner = Any()
        val geometry = Media3RenderGeometry()
        geometry.attach(owner)
        geometry.updateTarget(owner, 3840 to 2160)
        geometry.updateSurface(3840, 2160)
        assertFalse(geometry.attach(owner))
        assertFalse(geometry.updateTarget(owner, 3840 to 2160))
        assertFalse(geometry.updateSurface(3840, 2160))
        assertEquals(3840 to 2160, geometry.requestedTarget)
    }

    @Test fun missingOrInvalidTargetFallsBackToTheLastConfirmedSurface() {
        val owner = Any()
        val geometry = Media3RenderGeometry()
        assertNull(geometry.scalerViewport)
        assertFalse(needsUpscaling(geometry.scalerViewport))
        geometry.attach(owner)
        geometry.updateSurface(1920, 1080)
        geometry.updateTarget(owner, 3840 to 2160)
        assertTrue(geometry.updateTarget(owner, 0 to 2160))
        assertNull(geometry.requestedTarget)
        assertEquals(1920 to 1080, geometry.scalerViewport)
        assertFalse(needsUpscaling(geometry.scalerViewport))
    }

    private fun needsUpscaling(size: Pair<Int, Int>?, width: Int = 1920, height: Int = 1080): Boolean =
        size?.let { media3NeedsUpscaling(width, height, it.first, it.second) } == true
}

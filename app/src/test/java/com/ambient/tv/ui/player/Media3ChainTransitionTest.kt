package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class Media3ChainTransitionTest {
    private fun t(prevActive: Boolean, nextEmpty: Boolean, gpu: Boolean) = media3ChainTransition(prevActive, nextEmpty, gpu)

    @Test
    fun beforeGpuSinkEmptyChainDoesNothing() {
        assertEquals(Media3ChainTransition.NONE, t(prevActive = false, nextEmpty = true, gpu = false))
        assertEquals(Media3ChainTransition.NONE, t(prevActive = true, nextEmpty = true, gpu = false))
    }

    @Test
    fun firstEffectRestarts() {
        assertEquals(Media3ChainTransition.RESTART, t(prevActive = false, nextEmpty = false, gpu = false))
        assertEquals(Media3ChainTransition.RESTART, t(prevActive = true, nextEmpty = false, gpu = false))
    }

    @Test
    fun disablingActiveEffectsRestartsLikeEnabling() {
        assertEquals(Media3ChainTransition.RESTART, t(prevActive = true, nextEmpty = true, gpu = true))
    }

    @Test
    fun changesOnExistingSinkUpdateInPlace() {
        assertEquals(Media3ChainTransition.UPDATE, t(prevActive = true, nextEmpty = false, gpu = true))
        assertEquals(Media3ChainTransition.UPDATE, t(prevActive = false, nextEmpty = false, gpu = true))
        assertEquals(Media3ChainTransition.UPDATE, t(prevActive = false, nextEmpty = true, gpu = true))
    }
}

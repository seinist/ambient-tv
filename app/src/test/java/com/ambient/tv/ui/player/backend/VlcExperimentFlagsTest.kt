package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.VlcExperimentFlags as F
import org.junit.Assert.*
import org.junit.Test

class VlcExperimentFlagsTest {
    @Test fun defaultsAndUnknownFlagsAreOff() {
        assertEquals(0, F.normalize(0))
        assertEquals(0, F.normalize(1024))
        assertEquals(0, F.normalize(F.FEL or F.DOVI))
        assertFalse(F.enabled(F.AHB, F.FEL))
    }
    @Test fun dependenciesAndIndependentHdrPlus() {
        val all = F.toggle(F.toggle(0, F.FEL, true), F.HDR10PLUS, true)
        assertEquals(15, all)
        assertEquals(F.AHB or F.HDR10PLUS, F.toggle(all, F.DOVI, false))
        assertEquals(0, F.toggle(all, F.AHB, false))
        assertEquals(F.AHB or F.DOVI or F.FEL, F.toggle(all, F.HDR10PLUS, false))
        assertEquals(F.AHB or F.DOVI, F.toggle(0, F.DOVI, true))
    }
}

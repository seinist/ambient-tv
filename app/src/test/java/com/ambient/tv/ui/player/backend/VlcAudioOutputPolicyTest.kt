package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class VlcAudioOutputPolicyTest {
    @Test fun encodedDeviceContainsOnlyRequestedFormats() {
        assertEquals("encoded:32", vlcEncodedDevice(listOf("ac3")))
        assertEquals("encoded:96", vlcEncodedDevice(listOf("ac3", "eac3")))
        assertEquals("encoded:0", vlcEncodedDevice(emptyList()))
    }

    @Test fun downmixReservesCoherentPeakHeadroom() {
        for ((channels, peak) in listOf(3 to 1.5, 4 to 2.5, 6 to 2.414214, 7 to 3.414214, 8 to 2.207107)) {
            val gain = 10.0.pow(-vlcDownmixHeadroomDb(channels) / 20.0)
            assertTrue("channels=$channels", peak * gain <= 1.000001)
        }
        assertEquals(0f, vlcDownmixHeadroomDb(2), 0f)
        assertEquals(vlcDownmixHeadroomDb(7), vlcDownmixHeadroomDb(null), 0f)
    }
}

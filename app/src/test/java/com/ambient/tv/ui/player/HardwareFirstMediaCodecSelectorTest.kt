package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class HardwareFirstMediaCodecSelectorTest {
    private data class Codec(val name: String, val hw: Boolean, val sw: Boolean)
    @Test fun hardwarePartitionPreservesMedia3OrderingWithinTiers() {
        val firstSoftware = Codec("software-first", false, true)
        val preferred = Codec("first-device-decoder", true, false)
        val second = Codec("second-device-decoder", true, false)
        val fallback = Codec("fallback", false, true)
        val result = preferHardwareVideoDecoders(listOf(firstSoftware, preferred, fallback, second),
            Codec::hw, Codec::sw)
        assertEquals(listOf(preferred, second, firstSoftware, fallback), result)
    }

    @Test fun uniformListIsNotCopiedOrReordered() {
        val list = listOf(Codec("a", true, false), Codec("b", true, false))
        assertSame(list, preferHardwareVideoDecoders(list, Codec::hw, Codec::sw))
    }
}

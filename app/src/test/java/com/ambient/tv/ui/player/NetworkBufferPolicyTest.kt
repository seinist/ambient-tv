package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkBufferPolicyTest {
    @Test fun mediumKeepsThePreviousValues() {
        assertEquals(MpvNetworkCache("64MiB", 20), mpvNetworkCache("medium", lowRam = false))
        assertEquals(1_200, vlcNetworkCachingMs("medium"))
        assertEquals("medium", normalizeNetworkBufferKey(null))
        assertEquals("medium", normalizeNetworkBufferKey("huge"))
    }

    @Test fun largeIsSmallerOnLowRamDevices() {
        assertEquals("160MiB", mpvNetworkCache("high", lowRam = false).maxBytes)
        assertEquals("96MiB", mpvNetworkCache("high", lowRam = true).maxBytes)
        assertEquals(MpvNetworkCache("32MiB", 10), mpvNetworkCache("low", lowRam = true))
        assertEquals(800, vlcNetworkCachingMs("low"))
        assertEquals(3_000, vlcNetworkCachingMs("high"))
    }
}

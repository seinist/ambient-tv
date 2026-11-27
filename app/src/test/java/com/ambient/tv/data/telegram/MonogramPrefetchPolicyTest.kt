package com.ambient.tv.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

class MonogramPrefetchPolicyTest {
    @Test fun speculativeBandwidthBudget() {
        assertEquals(0, monogramPrefetchNetworkLanes(false, false, 100_000))
        assertEquals(1, monogramPrefetchNetworkLanes(true, true, 100_000))
        assertEquals(1, monogramPrefetchNetworkLanes(true, false, 2_500))
        assertEquals(2, monogramPrefetchNetworkLanes(true, false, 2_501))
        assertEquals(2, monogramPrefetchNetworkLanes(true, false, 0))
    }
    @Test fun nativeFullDownloadWindowKeepsDemandLanesSeparate() {
        assertEquals(1, monogramNativePipelineParts(false, 2_500, false))
        assertEquals(2, monogramNativePipelineParts(false, 0, false))
        assertEquals(2, monogramNativePipelineParts(false, 14_999, false))
        assertEquals(2, monogramNativePipelineParts(true, 100_000, false))
        assertEquals(2, monogramNativePipelineParts(false, 100_000, true))
        assertEquals(4, monogramNativePipelineParts(false, 15_000, false))
    }
}

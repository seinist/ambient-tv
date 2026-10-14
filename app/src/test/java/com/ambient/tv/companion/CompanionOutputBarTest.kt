package com.ambient.tv.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionOutputBarTest {
    private val tv = CompanionPeer(fingerprint = "a".repeat(64), name = "Wohnzimmer")

    @Test fun hiddenWhenNoTvIsPaired() {
        assertFalse(showCompanionOutputBar(CompanionState()))
        assertFalse(showCompanionOutputBar(CompanionState(online = true, target = tv)))
    }

    @Test fun hiddenWhenPairedTvIsNotConnected() {
        assertFalse(showCompanionOutputBar(CompanionState(paired = listOf(tv), target = tv, online = false)))
        assertFalse(showCompanionOutputBar(CompanionState(paired = listOf(tv))))
    }

    @Test fun visibleOnlyWhenPairedTvIsConnected() {
        assertTrue(showCompanionOutputBar(CompanionState(paired = listOf(tv), target = tv, online = true)))
        val other = tv.copy(fingerprint = "b".repeat(64), name = "Other")
        assertFalse(showCompanionOutputBar(CompanionState(paired = listOf(tv), target = other, online = true)))
    }
}

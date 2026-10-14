package com.ambient.tv.ui.display

import org.junit.Assert.*
import org.junit.Test

class AmbientHdrTest {
    private val facts = UiHdrEligibility(34, true, true, true, true, true, 1f)
    private val request = SdrToHdrRequest(400, 0, 1)

    @Test fun generatedContentCanBootstrapButIsCapped() {
        assertEquals(UI_HDR_HEADROOM_CAP, ambientHdrHeadroom(facts, request), 0f)
        assertEquals(UI_HDR_HEADROOM_CAP, ambientHdrHeadroom(facts.copy(headroom = 4f), request.copy(peakNits = 600)), 0f)
        assertEquals(1.1f, ambientHdrHeadroom(facts.copy(headroom = 1.1f), request), 0f)
    }

    @Test fun confirmedSurfaceRequestsHdrBeforeDisplayRatioRises() {
        val eligible = ambientSurfaceHdrEligibility(facts.copy(sdk = 35), request, SdrToHdrStatus.ACTIVE)
        assertTrue(eligible.allowed)
        assertEquals(UI_HDR_HEADROOM_CAP, eligible.requestedHeadroom, 0f)
        // Android 14 retains PQ surface output but cannot request a capped HDR UI window.
        assertFalse(ambientSurfaceHdrEligibility(facts, request, SdrToHdrStatus.ACTIVE).allowed)
    }

    @Test fun unconfirmedOrStoppedSurfaceNeverRequestsHdrWindow() {
        for (status in SdrToHdrStatus.entries.filter { it != SdrToHdrStatus.ACTIVE }) {
            assertFalse(ambientSurfaceHdrEligibility(facts.copy(sdk = 35), request, status).allowed)
        }
        assertFalse(ambientSurfaceHdrEligibility(facts.copy(sdk = 35), null, SdrToHdrStatus.ACTIVE).allowed)
        assertFalse(ambientSurfaceHdrEligibility(facts.copy(sdk = 35, sourceSupported = false), request, SdrToHdrStatus.ACTIVE).allowed)
    }

    @Test fun masterOffUnknownAndMissingCapabilitiesStaySdr() {
        assertEquals(1f, ambientHdrHeadroom(facts, null), 0f)
        for (missing in listOf(facts.copy(sdk = 33), facts.copy(automatic = false),
            facts.copy(windowReady = false), facts.copy(displayHdr = false), facts.copy(wideColor = false),
            facts.copy(sourceSupported = false), facts.copy(headroom = null),
            facts.copy(headroom = Float.NaN), facts.copy(headroom = .9f))) {
            assertEquals(1f, ambientHdrHeadroom(missing, request), 0f)
        }
        for (peak in listOf(-1, 0, 203, 299, 601, Int.MAX_VALUE))
            assertEquals(1f, ambientHdrHeadroom(facts, request.copy(peakNits = peak)), 0f)
    }
}

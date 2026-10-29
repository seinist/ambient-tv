package com.ambient.tv.ui.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioCountryPreferenceTest {
    @Test fun networkCountryWinsWhileTravelling() {
        assertEquals("AT", preferredRadioCountryCode("at", "de", "DE", "DE"))
    }

    @Test fun tvFallsBackToConfiguredRegionWithoutTelephony() {
        assertEquals("DE", preferredRadioCountryCode(null, null, "de", "US"))
    }

    @Test fun invalidCodesAreSkipped() {
        assertEquals("DE", preferredRadioCountryCode("unknown", "", "de", null))
        assertNull(preferredRadioCountryCode("", "1", "USA", null))
    }
}

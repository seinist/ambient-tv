package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSleepStandbyTest {
    @Test fun neverAllowsHandheldOrTabletStandby() {
        assertFalse(TvSleepStandby.supportedDevice(tv = false, adminFeature = false))
        assertFalse(TvSleepStandby.supportedDevice(tv = false, adminFeature = true))
    }

    @Test fun immediateStandbyRequiresTvAndDeviceAdminSupport() {
        assertFalse(TvSleepStandby.supportedDevice(tv = true, adminFeature = false))
        assertTrue(TvSleepStandby.supportedDevice(tv = true, adminFeature = true))
    }
}

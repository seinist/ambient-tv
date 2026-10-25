package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.mpvZeroCopyLockActive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvZeroCopyLockTest {
    private val caps = VideoAccelerationCapabilities(checked = true)

    // Z1
    @Test fun onlyVideoPathErrorsLockZeroCopy() {
        assertTrue(mpvZeroCopyFailureError(MPV_ERROR_VO_INIT_FAILED, loadedWithVideo = false))
        // Nothing played from a file that opened with video: decoder/hwdec init or no frame.
        assertTrue(mpvZeroCopyFailureError(MPV_ERROR_NOTHING_TO_PLAY, loadedWithVideo = true))
        // Nothing played because the stream never loaded (network drop): not zero-copy's fault.
        assertFalse(mpvZeroCopyFailureError(MPV_ERROR_NOTHING_TO_PLAY, loadedWithVideo = false))
        listOf(-13, -14, -17, -18, -20, 0).forEach {
            assertFalse("error $it", mpvZeroCopyFailureError(it, loadedWithVideo = true))
        }
    }

    @Test fun theLockEndsWithAnAppUpdate() {
        assertTrue(mpvZeroCopyLockActive(failed = true, recordedVersionCode = 120, currentVersionCode = 120))
        assertFalse(mpvZeroCopyLockActive(failed = true, recordedVersionCode = 120, currentVersionCode = 121))
        // A lock from before versions were recorded also ends with the next update check.
        assertFalse(mpvZeroCopyLockActive(failed = true, recordedVersionCode = -1, currentVersionCode = 121))
        assertFalse(mpvZeroCopyLockActive(failed = false, recordedVersionCode = 121, currentVersionCode = 121))
    }

    // Z2
    @Test fun toneMappingNeverUsesZeroCopy() {
        assertTrue(planMpvAcceleration("gpu", "mediacodec", caps, gpuProcessing = true, zeroCopy = true).zeroCopy)
        assertFalse(planMpvAcceleration("gpu", "mediacodec", caps, gpuProcessing = true, toneMappingRequired = true,
            zeroCopy = true).zeroCopy)
    }
}

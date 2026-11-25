package com.ambient.tv.moonlight

import org.junit.Assert.*
import org.junit.Test

class MoonlightHostTrustTest {
    @Test fun constantTimeCertificatePinComparison() {
        assertTrue(MoonlightHostTrust.constantTimeEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(MoonlightHostTrust.constantTimeEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(MoonlightHostTrust.constantTimeEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(MoonlightHostTrust.constantTimeEqual(byteArrayOf(), byteArrayOf(1)))
    }
}

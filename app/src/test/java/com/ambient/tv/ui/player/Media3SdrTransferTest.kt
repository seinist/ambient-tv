package com.ambient.tv.ui.player

import androidx.media3.common.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3SdrTransferTest {
    @Test fun sdrSrgbAndGamma22AreSdr() {
        assertTrue(media3TransferIsSdr(C.COLOR_TRANSFER_SDR))
        assertTrue(media3TransferIsSdr(C.COLOR_TRANSFER_SRGB))
        assertTrue(media3TransferIsSdr(C.COLOR_TRANSFER_GAMMA_2_2))
    }

    @Test fun hdrAndUnknownStayFailClosed() {
        assertFalse(media3TransferIsSdr(C.COLOR_TRANSFER_ST2084))
        assertFalse(media3TransferIsSdr(C.COLOR_TRANSFER_HLG))
        assertFalse(media3TransferIsSdr(C.COLOR_TRANSFER_LINEAR))
        assertFalse(media3TransferIsSdr(Format_NO_VALUE))
        assertFalse(media3TransferIsSdr(null))
    }

    private companion object { const val Format_NO_VALUE = -1 }
}

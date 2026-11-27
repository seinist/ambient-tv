package com.ambient.tv.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

class MonogramPreviewSizeTest {
    @Test fun targetBucketsAreBoundedAndOrientationIndependent() {
        assertEquals(320, monogramPreviewTarget(0, 0))
        assertEquals(160, monogramPreviewTarget(160, 90))
        assertEquals(320, monogramPreviewTarget(180, 320))
        assertEquals(640, monogramPreviewTarget(321, 180))
        assertEquals(640, monogramPreviewTarget(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(320, monogramPreviewTarget(coil3.size.Size(640, 360), 0))
    }
}

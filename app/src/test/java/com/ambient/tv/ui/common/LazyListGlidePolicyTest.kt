package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyListGlidePolicyTest {
    @Test fun catalogWrapOnlyAnimatesNearItsDestinationInEitherDirection() {
        val count = 10_000
        val visible = 6
        val forward = requireNotNull(listGlideJumpIndex(0, count - 1, visible))
        val backward = requireNotNull(listGlideJumpIndex(count - 1, 0, visible))
        assertEquals(count - 1 - visible * 2, forward)
        assertEquals(visible * 2, backward)
        assertTrue(forward in 1 until count - 1)
        assertTrue(backward in 1 until count - 1)
    }

    @Test fun shortMovesStayContinuousIncludingTheVisibleRangeBoundary() {
        assertNull(listGlideJumpIndex(0, 12, visibleItemCount = 6))
        assertNull(listGlideJumpIndex(12, 0, visibleItemCount = 6))
        assertNull(listGlideJumpIndex(5, 5, visibleItemCount = 6))
        assertEquals(1, listGlideJumpIndex(0, 13, visibleItemCount = 6))
        assertEquals(12, listGlideJumpIndex(13, 0, visibleItemCount = 6))
    }

    @Test fun unavailableVisibleRangeStillBoundsTheAnimation() {
        assertEquals(9_996, listGlideJumpIndex(0, 9_999, visibleItemCount = 0))
        assertEquals(3, listGlideJumpIndex(9_999, 0, visibleItemCount = 0))
        assertNull(listGlideJumpIndex(0, 3, visibleItemCount = 0))
    }

    @Test fun indexAndVisibleRangeArithmeticDoesNotOverflow() {
        assertEquals(Int.MAX_VALUE - 12, listGlideJumpIndex(0, Int.MAX_VALUE, 6))
        assertEquals(12, listGlideJumpIndex(Int.MAX_VALUE, 0, 6))
        assertNull(listGlideJumpIndex(0, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}

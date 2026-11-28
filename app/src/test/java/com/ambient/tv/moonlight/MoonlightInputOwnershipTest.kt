package com.ambient.tv.moonlight
import org.junit.Assert.*
import org.junit.Test
class MoonlightInputOwnershipTest {
    @Test fun sharedMouseButtonReleasesOnlyAfterBothSourcesRelease() {
        val input = MoonlightInputOwnership()
        assertEquals(true, input.mouse(1, 0, true))
        assertNull(input.mouse(1, 2, true))
        assertNull(input.mouse(1, 0, false))
        assertEquals(false, input.mouse(1, 2, false))
    }
    @Test fun touchIdsAreIsolatedAndUnknownMovesRejected() {
        val input = MoonlightInputOwnership()
        val local = requireNotNull(input.touchId(0, 1))
        val phone = requireNotNull(input.touchId(1, 1))
        assertNotEquals(local, phone)
        assertFalse(input.acceptsTouch(phone, 3))
        input.acceptedTouch(local, 1)
        input.acceptedTouch(phone, 1)
        input.acceptedTouch(local, 4)
        assertTrue(input.acceptsTouch(phone, 3))
        assertFalse(input.acceptsTouch(local, 3))
    }
}

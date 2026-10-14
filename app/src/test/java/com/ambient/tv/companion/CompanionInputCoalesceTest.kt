package com.ambient.tv.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionInputCoalesceTest {
    private fun move(x: Float, y: Float) = CompanionFrame(type = "input", input = CompanionInput("move", x = x, y = y))

    @Test
    fun `consecutive moves are summed`() {
        val merged = coalesceCompanionInput(move(0.1f, 0.2f), move(0.3f, -0.1f))!!
        assertEquals(0.4f, merged.input!!.x, 1e-6f)
        assertEquals(0.1f, merged.input!!.y, 1e-6f)
    }

    @Test
    fun `other frames and out of range sums are kept separate`() {
        assertNull(coalesceCompanionInput(move(0.9f, 0f), move(0.2f, 0f)))
        assertNull(coalesceCompanionInput(move(0.1f, 0f), CompanionFrame(type = "input", input = CompanionInput("click"))))
        assertNull(coalesceCompanionInput(move(0.1f, 0f), CompanionFrame(type = "status")))
        assertNull(coalesceCompanionInput(move(0.1f, 0f), CompanionFrame(type = "input", input = CompanionInput("scroll", y = 0.1f))))
    }
}

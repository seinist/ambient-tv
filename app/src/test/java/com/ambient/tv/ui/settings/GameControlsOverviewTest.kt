package com.ambient.tv.ui.settings

import android.view.KeyEvent
import com.ambient.tv.ui.gamepad.retro.RetroInputKind
import com.ambient.tv.ui.gamepad.retro.RetroInputTarget
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.defaultRetroProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GameControlsOverviewTest {
    private val defaults = defaultRetroProfile(RetroInputKind.GAMEPAD)
    private val snes = consoleArtwork("snes")
    private fun name(code: Int) = "k$code"

    @Test fun consoleLegendLabelsEveryMappedButtonAndOnlyHighlightsChanges() {
        val legend = consoleLegend(snes, defaults, defaults, ::name)
        snes.keys.filter { it.target != null && defaults.keys(RetroInputTarget.Button(it.target)).isNotEmpty() }
            .forEach { assertTrue(it.id, it.id in legend.labels) }
        assertTrue(legend.changed.isEmpty())

        val bEntry = snes.keys.first { it.target == RetroPadButton.B }
        val changed = defaults.assign(RetroInputTarget.Button(RetroPadButton.B), KeyEvent.KEYCODE_BUTTON_Y)
        val after = consoleLegend(snes, changed, defaults, ::name)
        assertEquals("k${KeyEvent.KEYCODE_BUTTON_Y}", after.labels[bEntry.id])
        assertTrue(bEntry.id in after.changed)
    }

    @Test fun severalKeysShowTheFirstAndACount() {
        val bEntry = snes.keys.first { it.target == RetroPadButton.B }
        val profile = defaults.copy(buttons = defaults.buttons +
            (RetroPadButton.B to listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X)))
        assertEquals("k${KeyEvent.KEYCODE_BUTTON_A} +1", consoleLegend(snes, profile, defaults, ::name).labels[bEntry.id])
    }

    @Test fun deviceLegendIsTheReverseMapping() {
        val pad = attachedArtwork("controller")
        val legend = deviceLegend(pad, defaults, defaults, { visualKeyCode("controller", it) }) { it.key }
        assertEquals(RetroPadButton.B.key, legend.labels["a"])
        assertTrue(legend.changed.isEmpty())
        val swapped = defaults.assign(RetroInputTarget.Button(RetroPadButton.Y), KeyEvent.KEYCODE_BUTTON_A)
        val after = deviceLegend(pad, swapped, defaults, { visualKeyCode("controller", it) }) { it.key }
        assertEquals(RetroPadButton.Y.key, after.labels["a"])
        assertTrue("a" in after.changed)
    }

    @Test fun compactNamesFitAPill() {
        assertEquals("▲", compactKeyName(KeyEvent.KEYCODE_DPAD_UP, ::name))
        assertEquals("OK", compactKeyName(KeyEvent.KEYCODE_DPAD_CENTER, ::name))
        assertEquals("k${KeyEvent.KEYCODE_Z}", compactKeyName(KeyEvent.KEYCODE_Z, ::name))
    }

    @Test fun assignAllWalksOnlyTheConsolesButtonsInOrder() {
        val order = assignAllOrder(snes)
        assertEquals(order.sortedBy { it.ordinal }, order)
        assertFalse(RetroPadButton.L3 in order)
        assertEquals(RetroPadButton.UP, order.first())
        assertEquals(RetroPadButton.entries, assignAllOrder(InputArtwork("x", InputArtworkShape.PAD, emptyList())))
    }

    @Test fun assignAllSkipsOnTimeoutAndStopsAfterTwoInARow() {
        val order = listOf(RetroPadButton.UP, RetroPadButton.DOWN, RetroPadButton.LEFT, RetroPadButton.RIGHT)
        assertEquals(AssignAllStep(RetroPadButton.DOWN, 0), nextAssignAllStep(order, RetroPadButton.UP, 0, false))
        assertEquals(AssignAllStep(RetroPadButton.LEFT, 1), nextAssignAllStep(order, RetroPadButton.DOWN, 0, true))
        // A recorded key resets the count.
        assertEquals(AssignAllStep(RetroPadButton.RIGHT, 0), nextAssignAllStep(order, RetroPadButton.LEFT, 1, false))
        assertNull(nextAssignAllStep(order, RetroPadButton.LEFT, 1, true))
        assertNull(nextAssignAllStep(order, RetroPadButton.RIGHT, 0, false))
    }

    @Test fun mappingEntriesNameKeyAndAssignment() {
        assertEquals("B → A", assignedButtonLabel("B", "A"))
        assertEquals("B", assignedButtonLabel("B", null))
    }
}

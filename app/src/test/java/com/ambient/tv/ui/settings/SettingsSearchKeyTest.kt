package com.ambient.tv.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchKeyTest {
    private fun opens(
        search: Boolean = true, down: Boolean = true, repeat: Int = 0,
        handheld: Boolean = false, field: Boolean = false, dialog: Boolean = false,
    ) = settingsSearchKeyOpens(search, down, repeat, handheld, field, dialog)

    @Test fun freshSearchPressOpensSearch() = assertTrue(opens())

    @Test fun otherKeysUpEventsAndRepeatsDoNot() {
        assertFalse(opens(search = false))
        assertFalse(opens(down = false))
        assertFalse(opens(repeat = 1))
    }

    @Test fun skippedWhileTypingInADialogOrOnPhones() {
        assertFalse(opens(field = true))
        assertFalse(opens(dialog = true))
        assertFalse(opens(handheld = true))
    }
}

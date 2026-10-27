package com.ambient.tv.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A method may take at most 255 parameter slots. The data class's default-arguments constructor
 * and copy$default take every field, one Int mask per 32 fields and a marker; past that limit
 * the class fails to load (ClassFormatError) and every settings screen crashes.
 */
class SettingsUiStateSizeTest {
    @Test fun settingsUiStateStaysBelowTheJvmParameterLimit() {
        val fields = SettingsUiState::class.java.declaredFields.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val masks = (fields + 31) / 32
        // `this` + fields + masks + DefaultConstructorMarker (no Long/Double fields, one slot each).
        val slots = 1 + fields + masks + 1
        assertTrue("SettingsUiState uses $slots of 255 parameter slots ($fields fields): group related fields into a nested class",
            slots <= 255)
    }
}

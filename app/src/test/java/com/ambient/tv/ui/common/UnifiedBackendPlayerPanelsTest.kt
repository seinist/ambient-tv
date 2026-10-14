package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedBackendPlayerPanelsTest {
    @Test
    fun visiblePlayerInfoLines_hidesMissingPlaceholdersButKeepsRealValues() {
        val lines = listOf(
            "Blank" to "",
            "Whitespace" to "   ",
            "EmDash" to "—",
            "Hyphen" to "-",
            "NA" to "N/A",
            "Zero" to "0",
            "Backend" to "MPV",
        )

        assertEquals(
            listOf("Zero" to "0", "Backend" to "MPV"),
            visiblePlayerInfoLines(lines),
        )
    }
}

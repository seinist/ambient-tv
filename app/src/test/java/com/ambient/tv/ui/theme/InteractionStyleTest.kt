package com.ambient.tv.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionStyleTest {
    private val surfaces = listOf(AppSurface, Color(0xFF090909), Color(0xFF1A1D22))

    @Test fun darkCustomAccentsRemainReadableInEveryTheme() {
        val accents = listOf(Color.Black, Color(0xFF020202), Color(0xFF240040), Color(0xFF001A1A), Color(0xFF003366))
        for (surface in surfaces) for (accent in accents) {
            val result = accessibleAccent(accent, surface)
            assertTrue("Accent must remain legible against the panel", contrastRatio(result, surface) >= 4.5f)
            assertTrue("Primary action text must remain legible", contrastRatio(contentOnAccent(result), result) >= 4.5f)
        }
    }

    @Test fun alreadyReadableAccentIsPreserved() {
        for (surface in surfaces) for (accent in listOf(Color.White, Gold, Color(0xFF66DDCC))) {
            assertEquals(accent, accessibleAccent(accent, surface))
        }
    }

    @Test fun transparencyCannotDefeatTheContrastGuard() {
        val result = accessibleAccent(Color.Black.copy(alpha = .1f), AppSurface)
        assertEquals(1f, result.alpha, 0f)
        assertTrue(contrastRatio(result, AppSurface) >= 4.5f)
    }

    @Test fun foregroundSwitchesWithTheActionFill() {
        assertEquals(Color.Black, contentOnAccent(Color.White))
        assertEquals(Color.White, contentOnAccent(Color.Black))
    }
}

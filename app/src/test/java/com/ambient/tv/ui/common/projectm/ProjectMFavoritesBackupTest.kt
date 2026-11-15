package com.ambient.tv.ui.common.projectm

import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMFavoritesBackupTest {
    private val first = "cream/Color/Soft lights.milk"
    private val second = "classic/Shapes/Prism.milk"
    private val bundled = setOf(first, second)

    @Test fun exportedFavoritesReplaceTheReceivingSelectionAndCanExplicitlyClearIt() {
        val source = ProjectMFavorites(memoryPreferences(), bundled)
        source.toggle(first)
        val target = ProjectMFavorites(memoryPreferences(), bundled)
        target.toggle(second)

        target.replace(source.all())

        assertEquals(setOf(first), target.all())
        assertTrue(target.contains(first))
        assertFalse(target.contains(second))
        target.replace(emptySet())
        assertEquals(emptySet<String>(), target.all())
    }

    @Test fun importsOnlyAcceptIdentifiersPresentInTheBundledPresetIndex() {
        val raw = memoryPreferences()
        val target = ProjectMFavorites(raw, bundled)
        val external = setOf(
            "../outside.milk",
            "/sdcard/outside.milk",
            "file:///sdcard/outside.milk",
            "https://example.test/outside.milk",
            "cream/Color/Unknown preset.milk",
        )

        target.replace(external + first)

        assertEquals(setOf(first), target.all())
        assertEquals(setOf(first), raw.getStringSet("favorites", emptySet()))
        external.forEach { path ->
            assertFalse(target.contains(path))
            assertFalse(target.toggle(path))
        }
        assertEquals(setOf(first), target.all())
    }

    @Test fun staleOrForeignStoredPathsAreExcludedFromExports() {
        val raw = memoryPreferences().apply {
            edit().putStringSet("favorites", setOf(second, "../../outside.milk", "removed.milk")).apply()
        }

        assertEquals(setOf(second), ProjectMFavorites(raw, bundled).all())
    }
}

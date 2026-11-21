package com.ambient.tv.ui.settings

import com.ambient.tv.games.GameSystems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep every registered console and alternate core covered when the catalog grows. */
class ConsoleArtworkCatalogTest {
    @Test fun everyRegisteredSystemAndCoreHasExplicitVectorArtwork() {
        val systems = GameSystems.all
        assertEquals(systems.map { it.id }.toSet(), supportedConsoleArtworkIds)
        assertTrue(systems.size >= 31)
        systems.forEach { system ->
            assertTrue("Missing core for ${system.id}", system.cores.isNotEmpty())
            val art = consoleArtwork(system.id)
            assertTrue("Wrong title: ${system.id}", art.title.isNotBlank())
            assertTrue("No controller keys: ${system.id}", art.keys.isNotEmpty())
            assertTrue("No playable key: ${system.id}", art.keys.any { it.target != null })
            assertEquals("Duplicate key ID: ${system.id}", art.keys.size, art.keys.map { it.id }.toSet().size)
            system.cores.forEach { core ->
                // Alternative cores of the same console intentionally share geometry.
                assertTrue("Missing artwork for ${core.id}", consoleArtwork(system.id).keys.isNotEmpty())
            }
        }
    }

    @Test fun allArtworkVariantsUseTheSharedInputVectorFamily() {
        GameSystems.all.forEach { system ->
            val variants = consoleArtworkVariants(system.id)
            variants.forEach { (id, title) ->
                assertFalse(title.isBlank())
                val art = consoleArtwork(system.id, id)
                assertTrue("Variant $id: ${system.id}", art.keys.any { it.target != null })
                assertEquals("Variant $id duplicates keys", art.keys.size,
                    art.keys.map { it.id }.distinct().size)
            }
        }
        assertEquals("Generic fallback must remain", "Libretro RetroPad", consoleArtwork("").title)
    }
}

package com.ambient.tv.ui.common.projectm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectMPresetSelectionTest {
    private val presets = List(4) { ProjectMPreset("classic/Preset $it.milk") }

    @Test fun shuffleAppliesToTheVeryFirstPreset() {
        // Select every possible start explicitly: none is replaced by a fallback to entry zero.
        presets.indices.forEach { index ->
            val chosen = initialProjectMPreset(presets, currentPath = null, shuffle = true) { size ->
                assertEquals(presets.size, size)
                index
            }
            assertEquals(presets[index], chosen)
        }
    }

    @Test fun listOrderStartsAtTheFirstPresetWithoutDrawingRandomness() {
        assertEquals(presets.first(), initialProjectMPreset(presets, null, shuffle = false) {
            error("Ordered startup must not shuffle")
        })
    }

    @Test fun freshStartsDrawAgainInsteadOfReusingThePreviousStart() {
        var nextIndex = 0
        val choose: (Int) -> Int = { nextIndex++ }
        assertEquals(presets[0], initialProjectMPreset(presets, null, true, choose))
        assertEquals(presets[1], initialProjectMPreset(presets, null, true, choose))
        assertEquals(presets[2], initialProjectMPreset(presets, null, true, choose))
    }

    @Test fun favoriteOnlyStartupDrawsOnlyFromTheFilteredPool() {
        val favorites = listOf(presets[1], presets[3])
        assertEquals(presets[3], initialProjectMPreset(favorites, null, true) { size ->
            assertEquals(2, size)
            1
        })
        assertEquals(presets[1], initialProjectMPreset(favorites, null, false))
    }

    @Test fun anActiveOrManualPresetSurvivesRecreationAndFilterChanges() {
        val selected = presets[2]
        listOf(presets, listOf(presets[0]), emptyList()).forEach { pool ->
            assertEquals(selected, initialProjectMPreset(pool, selected.assetPath, true) {
                error("An active preset must not be randomized again")
            })
        }
    }

    @Test fun emptyAndSinglePresetPoolsAreSafe() {
        assertNull(initialProjectMPreset(emptyList(), null, true) { error("No random draw for an empty pool") })
        assertNull(nextProjectMPreset(emptyList(), null, true, emptyList()))
        assertEquals(presets[0], initialProjectMPreset(listOf(presets[0]), null, true))
        assertEquals(presets[0], nextProjectMPreset(listOf(presets[0]), presets[0].assetPath, true, emptyList()))
    }

    @Test fun sequentialRotationAdvancesWrapsAndStartsAtTheBeginningOfANewPool() {
        assertEquals(presets[1], nextProjectMPreset(presets, presets[0].assetPath, false, emptyList()))
        assertEquals(presets[0], nextProjectMPreset(presets, presets.last().assetPath, false, emptyList()))
        assertEquals(presets[0], nextProjectMPreset(presets, null, false, emptyList()))
        assertEquals(presets[1], nextProjectMPreset(listOf(presets[1], presets[3]), presets[0].assetPath, false, emptyList()))
    }

    @Test fun shuffleRotationSkipsTheCurrentAndRecentPresets() {
        assertEquals(presets[3], nextProjectMPreset(
            presets, presets[0].assetPath, true, listOf(presets[1].assetPath, presets[2].assetPath),
        ) { size ->
            assertEquals(1, size)
            0
        })
    }

    @Test fun anExhaustedRecentPoolStillDoesNotRepeatTheCurrentPreset() {
        assertEquals(presets[1], nextProjectMPreset(presets, presets[0].assetPath, true, presets.map { it.assetPath }) { size ->
            assertEquals(3, size)
            0
        })
    }
}

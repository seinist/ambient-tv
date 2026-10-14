package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FilmGrainTest {
    @Test fun grainLevelsMapToBundledShadersAndRunLast() {
        assertEquals(listOf("cas", "filmgrain_light"),
            mpvImageShaderKeys(MpvImageOptions(sharpening = "cas", filmGrain = "light"), sdr = true, upscaling = false))
        assertEquals(listOf("filmgrain_medium"), mpvImageShaderKeys(MpvImageOptions(filmGrain = "medium"), sdr = true, upscaling = false))
        assertEquals(emptyList<String>(), mpvImageShaderKeys(MpvImageOptions(filmGrain = "medium"), sdr = false))
        for (key in listOf("filmgrain_light", "filmgrain_medium")) {
            val asset = MPV_IMAGE_SHADERS.getValue(key)
            assertFalse(asset.requiresCompute)
            assertFalse(asset.requiresGpuNext)
        }
    }

    @Test fun unknownValuesNormalizeOffAndReducedEffectsDisableGrain() {
        assertEquals("off", MpvImageOptions(filmGrain = "strong").normalized().filmGrain)
        assertEquals("off", effectiveMpvImageOptions(MpvImageOptions(filmGrain = "medium"), reducedEffects = true).filmGrain)
        assertEquals("medium", effectiveMpvImageOptions(MpvImageOptions(filmGrain = "medium"), reducedEffects = false).filmGrain)
    }

    @Test fun grainIsPartOfTheBackedUpOptionsAndOldBackupsDefaultOff() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(MpvImageOptions.serializer(), MpvImageOptions(filmGrain = "light"))
        assertEquals("light", json.decodeFromString(MpvImageOptions.serializer(), encoded).filmGrain)
        assertEquals("off", json.decodeFromString(MpvImageOptions.serializer(), """{"scaler":"lanczos"}""").filmGrain)
    }

    @Test fun grainSourcesAreFragmentOnlyWithDifferentIntensity() {
        val raw = java.io.File("src/main/res/raw").listFiles()!!.filter { it.name.startsWith("quality_filmgrain") }
        assertEquals(2, raw.size)
        raw.forEach { assertFalse(it.readText().contains("//!COMPUTE")) }
        assertTrue(raw.single { "light" in it.name }.readText().contains("#define INTENSITY 0.03"))
        assertTrue(raw.single { "medium" in it.name }.readText().contains("#define INTENSITY 0.06"))
    }
}

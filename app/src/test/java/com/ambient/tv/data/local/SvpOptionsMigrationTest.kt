package com.ambient.tv.data.local

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SvpOptionsMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun storedAutoBecomesLight() {
        assertEquals("light", MpvImageOptions(svpMode = "auto").normalized().svpMode)
        assertEquals("light", VlcImageOptions(svpMode = "auto").normalized().svpMode)
        val stored = json.decodeFromString(MpvImageOptions.serializer(), """{"svpMode":"auto","svp":{"block":16}}""").normalized()
        assertEquals("light", stored.svpMode)
        assertEquals(16, stored.svp.block)
        assertEquals("off", normalizeSvpMode("smooth"))
        assertEquals("quality", normalizeSvpMode("quality"))
    }

    @Test fun removedPresetChoicesReadAsTheirEquivalent() {
        // artifacts 0 ("preset") and 1 ("off") were both no area mask; scene 0 ("preset") was normal.
        val legacy = json.decodeFromString(SvpOptions.serializer(), """{"artifacts":1,"scene":0}""").normalized()
        assertEquals(0, legacy.artifacts)
        assertEquals(2, legacy.scene)
        assertEquals(SvpOptions(), legacy)
        assertEquals(3, SvpOptions(artifacts = 3).normalized().artifacts)
    }

    @Test fun newLimitsDefaultSafelyAndValidate() {
        val old = json.decodeFromString(SvpOptions.serializer(), "{}")
        assertEquals(1080, old.maxHeight)
        assertTrue(old.filmOnly)
        assertEquals(1080, SvpOptions(maxHeight = 1440).normalized().maxHeight)
        assertEquals(2160, SvpOptions(maxHeight = 2160).normalized().maxHeight)
        assertEquals(3, SvpOptions(rateDivisor = 3).normalized().rateDivisor)
        assertEquals(1, SvpOptions(rateDivisor = 4).normalized().rateDivisor)
    }

    @Test fun customMeansAnalysisOverridesOnly() {
        assertFalse(SvpOptions(artifacts = 3, scene = 1, rateDivisor = 2, maxHeight = 720, filmOnly = false, backend = 2, threads = 4).isCustom)
        for (o in listOf(SvpOptions(algorithm = 23), SvpOptions(block = 8), SvpOptions(overlap = 4), SvpOptions(pel = 2), SvpOptions(search = 1)))
            assertTrue("$o", o.isCustom)
        val cleared = SvpOptions(algorithm = 23, search = 3, artifacts = 2, backend = 1).withoutAnalysisOverrides()
        assertFalse(cleared.isCustom)
        assertEquals(SvpOptions(artifacts = 2, backend = 1), cleared)
    }
}

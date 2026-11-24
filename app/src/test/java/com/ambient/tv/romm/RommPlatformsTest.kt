package com.ambient.tv.romm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommPlatformsTest {
    @Test fun currentAndOlderSlugsMapToThePlayingConsole() {
        assertEquals("snes", RommPlatforms.systemId("snes"))
        assertEquals("snes", RommPlatforms.systemId("sfam"))
        assertEquals("gb", RommPlatforms.systemId("gbc"))
        assertEquals("megadrive", RommPlatforms.systemId("genesis"))
        assertEquals("megadrive", RommPlatforms.systemId("genesis-slash-megadrive"))
        assertEquals("megadrive", RommPlatforms.systemId("gamegear"))
        assertEquals("pce", RommPlatforms.systemId("turbografx16--1"))
        assertEquals("psx", RommPlatforms.systemId("PSX"))
        assertEquals("gamecube", RommPlatforms.systemId("ngc"))
        assertEquals("dreamcast", RommPlatforms.systemId("dc"))
        assertEquals("plus4", RommPlatforms.systemId("c-plus-4"))
        assertEquals("wswan", RommPlatforms.systemId("swancrystal"))
        // The folder slug decides when the platform slug is unknown.
        assertEquals("n64", RommPlatforms.systemId("unknown-custom", "n64"))
        assertNull(RommPlatforms.systemId("switch"))
        assertNull(RommPlatforms.systemId(null, null))
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }

    @Test fun romsOfRomm5And3Parse() {
        val current = json.decodeFromString(RommRom.serializer(), """
            {"id":12,"platform_id":3,"platform_slug":"snes","platform_fs_slug":"snes","fs_name":"Game (USA).zip",
             "fs_name_no_tags":"Game","fs_name_no_ext":"Game (USA)","fs_size_bytes":524288,"name":"Game","summary":null,
             "metadatum":{"rom_id":12,"genres":["Platform"],"companies":[],"publishers":["P"],"developers":["D"],
               "player_count":"1-2","first_release_date":785808000000,"average_rating":null},
             "path_cover_large":"/assets/romm/resources/roms/3/12/cover/big.png?ts=1","url_cover":null,
             "merged_screenshots":["/assets/romm/resources/roms/3/12/screenshots/0.jpg"],"regions":["USA"],
             "has_multiple_files":false,"rom_user":{"last_played":"2026-11-20T10:00:00Z","rating":0},"unknown":{"a":1}}
        """.trimIndent())
        assertEquals("Game", current.displayTitle)
        assertEquals(1994, current.releaseYear)
        assertFalse(current.zipped)
        assertEquals("1-2", current.metadatum?.playerCount)

        val legacy = json.decodeFromString(RommRom.serializer(), """
            {"id":5,"platform_id":1,"platform_slug":"psx","file_name":"x","fs_name":"Disc Game","multi":true,
             "files":[{"file_name":"d1.chd"},{"file_name":"d2.chd"}],"name":null,"regions":null}
        """.trimIndent())
        assertTrue(legacy.zipped)
        assertEquals("Disc Game", legacy.displayTitle)
        assertTrue(legacy.regions.isEmpty())
    }

    @Test fun platformTitlesFollowTheNameGivenInRomm() {
        val platform = json.decodeFromString(RommPlatform.serializer(),
            """{"id":3,"slug":"snes","fs_slug":"snes","name":"Super Nintendo Entertainment System","custom_name":"SNES","rom_count":2}""")
        assertEquals("SNES", platform.title)
        assertEquals("snes", RommPlatforms.system(platform)?.id)
    }
}

package com.ambient.tv.games.metadata

import com.ambient.tv.games.GameSystems
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameNameDatabasesTest {
    @Test fun everyDatabaseBelongsToAPlayableCartridgeSystem() {
        assertTrue(GameNameDatabases.systems.isNotEmpty())
        for (system in GameNameDatabases.systems) {
            assertTrue(system, GameSystems.byId(system) != null)
            for (path in GameNameDatabases.files(system)) {
                assertTrue(path, path.startsWith("metadat/no-intro/") || path.startsWith("metadat/headered/") || path.startsWith("metadat/redump/"))
                assertTrue(path, path.endsWith(".dat"))
            }
        }
        // Disc release catalogs supply title candidates, never full-image checksums.
        for (disc in listOf("psx", "ps2", "saturn", "dreamcast", "gamecube", "wii", "psp")) {
            assertTrue(disc, GameNameDatabases.files(disc).isNotEmpty())
            assertFalse(disc, GameNameDatabases.usesChecksums(disc))
        }
        assertEquals(2, GameNameDatabases.files("nds").size)
        assertTrue(GameNameDatabases.usesChecksums("nds"))
        // Combined systems take every console they play.
        assertEquals(2, GameNameDatabases.files("gb").size)
        assertEquals(4, GameNameDatabases.files("megadrive").size)
    }

    @Test fun onlyLibretroDatabaseFilesMayBeFetched() {
        val path = GameNameDatabases.files("gba").single()
        val url = HttpUrl.Builder().scheme("https").host(GameMetadataProviders.LIBRETRO_DATABASE_HOST)
            .addPathSegments(GameMetadataProviders.LIBRETRO_DATABASE_ROOT)
            .apply { path.split('/').forEach(::addPathSegment) }.build()
        assertEquals("https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro/" +
            "Nintendo%20-%20Game%20Boy%20Advance.dat", url.toString())
        assertTrue(allowedGameProviderHost(GameMetadataProviderIds.LIBRETRO_DATABASE, url))
        assertFalse(allowedGameProviderHost(GameMetadataProviderIds.LIBRETRO_DATABASE,
            "https://raw.githubusercontent.com/someone/else/master/metadat/x.dat".toHttpUrl()))
        assertFalse(allowedGameProviderHost(GameMetadataProviderIds.LIBRETRO_DATABASE,
            "https://raw.githubusercontent.com/libretro/libretro-database/master/scripts/x.sh".toHttpUrl()))
        // Name databases are not artwork: an image URL there is never accepted for Libretro.
        assertFalse(allowedGameProviderHost(GameMetadataProviderIds.LIBRETRO, url))
    }
}

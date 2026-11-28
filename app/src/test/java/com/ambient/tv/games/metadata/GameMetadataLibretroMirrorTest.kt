package com.ambient.tv.games.metadata

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class GameMetadataLibretroMirrorTest {
    private val provider = GameMetadataProviderIds.LIBRETRO
    private val namespace = "Nintendo - Super Nintendo Entertainment System"
    private val repository = "Nintendo_-_Super_Nintendo_Entertainment_System"

    private fun primary(system: String = namespace, directory: String = "Named_Boxarts", name: String = "Super Mario World (USA).png") =
        HttpUrl.Builder().scheme("https").host("thumbnails.libretro.com")
            .addPathSegment(system).addPathSegment(directory).addPathSegment(name).build()

    @Test fun officialMirrorKeepsTheExactFilenameAndArtworkKindForEveryMappedNamespace() {
        for ((system, repo) in GameMetadataPlatforms.libretroThumbnailRepositories) {
            for (directory in listOf("Named_Boxarts", "Named_Snaps", "Named_Titles")) {
                val canonical = primary(system, directory, "Puzzle _ Quest_ 100% #1 (Europe).png")
                val mirror = requireNotNull(libretroArtworkMirrorUrl(canonical))
                assertEquals("https", mirror.scheme)
                assertEquals("raw.githubusercontent.com", mirror.host)
                assertEquals(listOf("libretro-thumbnails", repo, "master", directory,
                    canonical.pathSegments.last()), mirror.pathSegments)
                assertTrue(allowedGameProviderHost(provider, mirror))
                assertNull(mirror.query)
                assertNull(mirror.fragment)
            }
        }
        assertEquals("Nintendo_-_Game_Boy", libretroArtworkMirrorUrl(primary("Nintendo - Game Boy",
            name = "Tetris (World) (Rev 1).png"))!!.pathSegments[1])
    }

    @Test fun releaseTagsSurviveStrictUriValidationAndMirrorConstruction() {
        val title = "[Release] Super Mario World (Europe) [!]"
        val candidate = GameMetadataPlatforms.libretroCandidates(GameMetadataQuery(
            "snes", title, "$title.sfc")).first().metadata
        for (reference in listOf(candidate.coverUrl, candidate.backdropUrl, candidate.titleScreenUrl)) {
            val safe = requireNotNull(sanitizedGameArtworkUrl(provider, reference))
            val url = safe.toHttpUrl()
            assertEquals("$title.png", url.pathSegments.last())
            assertTrue(url.encodedPath.contains("%5BRelease%5D"))
            val mirror = requireNotNull(libretroArtworkMirrorUrl(url))
            assertEquals("$title.png", mirror.pathSegments.last())
            assertNotNull(sanitizedGameArtworkUrl(provider, mirror.toString()))
        }
    }

    @Test fun fallbackConstructionDropsEveryQueryAndFragmentWithoutChangingTheCanonicalReference() {
        val canonical = primary().newBuilder().addQueryParameter("apikey", "secret")
            .addQueryParameter("download", "1").fragment("secret").build()
        val mirror = requireNotNull(libretroArtworkMirrorUrl(canonical))
        assertNull(mirror.query)
        assertNull(mirror.fragment)
        assertEquals("", mirror.username)
        assertEquals("", mirror.password)
        val candidate = GameMetadataPlatforms.libretroCandidates(GameMetadataQuery(
            "snes", "Super Mario World (USA)", "Super Mario World (USA).sfc")).first().metadata
        assertEquals(primary().toString(), candidate.coverUrl)
        assertEquals("$namespace|Super Mario World (USA)", candidate.providerGameId)
    }

    @Test fun bracketedRomNamesSurviveStrictUriValidationAndMirrorFallback() {
        val name = "[Release] Super Mario World (Europe) [!]"
        val query = GameMetadataQuery("snes", name, "$name.sfc")
        val candidate = GameMetadataPlatforms.libretroCandidates(query).first().metadata
        val cover = requireNotNull(candidate.coverUrl)
        assertTrue(cover.contains("%5BRelease%5D"))
        assertEquals(cover, sanitizedGameMetadataUrl(cover))
        assertEquals(cover, sanitizedGameArtworkUrl(provider, cover))
        val canonical = cover.toHttpUrl()
        val mirror = requireNotNull(libretroArtworkMirrorUrl(canonical))
        assertEquals("$name.png", canonical.pathSegments.last())
        assertEquals(canonical.pathSegments.last(), mirror.pathSegments.last())
        assertEquals(mirror.pathSegments, credentialFreeGameUrl(mirror).pathSegments)
        assertEquals(cover, candidate.bounded().coverUrl)
    }

    @Test fun rawHostIsRestrictedToOfficialRepositoriesBranchesDirectoriesAndSinglePngFiles() {
        val root = "https://raw.githubusercontent.com/libretro-thumbnails/$repository/master/Named_Boxarts"
        val valid = "$root/Game.png".toHttpUrl()
        assertTrue(allowedGameProviderHost(provider, valid))
        for (url in listOf(
            "http://raw.githubusercontent.com/libretro-thumbnails/$repository/master/Named_Boxarts/Game.png",
            "https://raw.githubusercontent.com:444/libretro-thumbnails/$repository/master/Named_Boxarts/Game.png",
            "https://user:secret@raw.githubusercontent.com/libretro-thumbnails/$repository/master/Named_Boxarts/Game.png",
            "https://raw.githubusercontent.com.evil.example/libretro-thumbnails/$repository/master/Named_Boxarts/Game.png",
            "https://raw.githubusercontent.com/other/$repository/master/Named_Boxarts/Game.png",
            "https://raw.githubusercontent.com/libretro-thumbnails/unmapped-system/master/Named_Boxarts/Game.png",
            "https://raw.githubusercontent.com/libretro-thumbnails/$repository/main/Named_Boxarts/Game.png",
            "https://raw.githubusercontent.com/libretro-thumbnails/$repository/master/other/Game.png",
            "$root/subdirectory/Game.png", "$root/Game.jpg", "$root/Game.png.exe", "$root/.png",
            "$root/../Game.png", "$root/%2e%2e/config.png", "$root/..%2fGame.png", "$root/..%5cGame.png",
            "$root/%252e%252e%252fGame.png", "$root/Game%00.png", "$root/Game.png?download=1", "$root/Game.png#token",
        )) assertFalse(url, allowedGameProviderHost(provider, url.toHttpUrl()))
        assertFalse(allowedGameProviderHost(GameMetadataProviderIds.THEGAMESDB, valid))
        assertFalse(allowedGameProviderHost(GameMetadataProviderIds.LIBRETRO_DATABASE, valid))
    }

    @Test fun malformedOrForeignPrimaryReferencesCannotInventAMirror() {
        for (url in listOf(primary("Unknown System"), primary(directory = "other"), primary(name = "sub/Game.png"),
            primary(name = "..\\Game.png"), primary(name = "Game.jpg"), primary(name = ".png"),
            primary().newBuilder().scheme("http").build(), primary().newBuilder().host("other.example").build(),
            primary().newBuilder().username("user").password("secret").build(),
            primary().newBuilder().addPathSegment("extra.png").build())) {
            assertNull(url.toString(), libretroArtworkMirrorUrl(url))
        }
        assertNull(libretroArtworkMirrorUrl(requireNotNull(libretroArtworkMirrorUrl(primary()))))
    }
}

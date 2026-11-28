package com.ambient.tv.games.metadata

import com.ambient.tv.games.GameSystems
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class GameMetadataProvidersTest {
    private fun query(system: String = "gb", filename: String = "Tetris (World).gb") =
        GameMetadataQuery(system, "Tetris", filename, language = "de", region = "eu")
    private fun payload(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test fun dsAndDsiUseOfficialThumbnailNamespacesAndPspUsesCanonicalCatalogNames() {
        val ds = GameMetadataPlatforms.forQuery(query("nds", "Game.nds"))
        assertEquals(listOf("Nintendo - Nintendo DS", "Nintendo - Nintendo DSi"), ds.map { it.libretro })
        assertEquals("Nintendo - Nintendo DSi",
            GameMetadataPlatforms.forQuery(query("nds", "Game.dsi")).first().libretro)
        val title = "Ridge Racer (Europe)"
        val choices = GameMetadataPlatforms.libretroCandidates(
            query("psp", "Ridge Racer.cso").copy(title = "Ridge Racer"), listOf(title))
        assertEquals(title, choices.first().metadata.canonicalTitle)
        assertEquals(listOf("Sony - PlayStation Portable", "Named_Boxarts", "$title.png"),
            choices.first().metadata.coverUrl!!.toHttpUrl().pathSegments)
        assertFalse(choices.first().exactMatch)
    }

    @Test fun unsupportedPreferredProviderUsesOnlyAnAlreadyConfiguredCompatibleService() {
        val libretro = GameMetadataProviderIds.LIBRETRO
        val gamesDb = GameMetadataProviderIds.THEGAMESDB
        val available = listOf(libretro, gamesDb)
        assertEquals(gamesDb, GameMetadataPlatforms.automaticProvider(query("c128"), libretro, available))
        assertEquals(libretro, GameMetadataPlatforms.automaticProvider(query("c128"), libretro, listOf(libretro)))
        assertEquals(libretro, GameMetadataPlatforms.automaticProvider(query("psp"), libretro, available))
        assertTrue(GameMetadataPlatforms.forQuery(query("psp")).single().gamesDbNames.contains("sonyplaystationportable"))
        assertFalse(GameMetadataPlatforms.supports(query("cbm2"), libretro))
    }

    @Test fun combinedSystemsUseTheRomExtensionAndNeverDefaultToUnrelatedPlatforms() {
        assertEquals(listOf(10), GameMetadataPlatforms.forQuery(query(filename = "Mario.gbc")).map { it.screenScraper })
        assertEquals(listOf(9), GameMetadataPlatforms.forQuery(query()).map { it.screenScraper })
        assertEquals(listOf(21), GameMetadataPlatforms.forQuery(query("megadrive", "Sonic.gg")).map { it.screenScraper })
        assertEquals(listOf(106), GameMetadataPlatforms.forQuery(query("nes", "Zelda.fds")).map { it.screenScraper })
        assertEquals(listOf(105), GameMetadataPlatforms.forQuery(query("pce", "1941.sgx")).map { it.screenScraper })
        assertTrue(GameMetadataPlatforms.forQuery(query("unknown-system")).isEmpty())
        assertTrue(GameMetadataPlatforms.libretroCandidates(query("unknown-system")).isEmpty())
    }

    @Test fun combinedNesAndPcEngineArchivesRetainTheirCompatibleNamespaces() {
        for (extension in listOf("zip", "7z", "rar")) {
            val nes = GameMetadataPlatforms.forQuery(query("nes", "Zelda.$extension"))
            assertEquals(listOf(3, 106), nes.map { it.screenScraper })
            val pce = GameMetadataPlatforms.forQuery(query("pce", "1941.$extension"))
            assertEquals(listOf(31, 105), pce.map { it.screenScraper })
            val candidates = GameMetadataPlatforms.libretroCandidates(
                query("nes", "Zelda.$extension").copy(canonicalTitle = "Zelda no Densetsu (Japan)"),
                listOf("Zelda no Densetsu (Japan)"),
            ).filter { it.catalogMatch }
            assertEquals(setOf("Nintendo - Nintendo Entertainment System", "Nintendo - Family Computer Disk System"),
                candidates.map { it.metadata.providerGameId.substringBefore('|') }.toSet())
        }
        for (extension in listOf("nes", "unf", "unif")) {
            assertEquals(listOf(3), GameMetadataPlatforms.forQuery(query("nes", "Game.$extension")).map { it.screenScraper })
        }
        assertEquals(listOf(31), GameMetadataPlatforms.forQuery(query("pce", "Game.pce")).map { it.screenScraper })
    }

    @Test fun everyPlayableSystemHasAnExplicitProviderDecision() {
        // C128/CBM-II intentionally have no guessed Libretro/ScreenScraper identity.
        for (system in GameSystems.all) {
            assertTrue(system.id, GameMetadataPlatforms.forQuery(query(system.id, "rom." + (system.extensions.firstOrNull() ?: "zip"))).isNotEmpty())
        }
        assertNull(GameMetadataPlatforms.forQuery(query("c128")).single().libretro)
        assertNull(GameMetadataPlatforms.forQuery(query("cbm2")).single().screenScraper)
    }

    @Test fun libretroEncodesNamesAsOnePathSegmentAndUsesItsFilenameReplacementRules() {
        val candidate = GameMetadataPlatforms.libretroCandidates(query().copy(title = "Puzzle & Quest: 100% #1?", filename = "")).first()
        val url = candidate.metadata.coverUrl!!.toHttpUrl()
        assertEquals("thumbnails.libretro.com", url.host)
        assertEquals(listOf("Nintendo - Game Boy", "Named_Boxarts", "Puzzle _ Quest_ 100% #1_.png"), url.pathSegments)
        assertNull(url.query)
        assertNull(url.fragment)
    }

    @Test fun libretroBracketedRomNamesPassStrictUriPolicyOnPrimaryAndMirror() {
        val filename = "[Release] Super Mario World (Europe) [!].sfc"
        val candidates = GameMetadataPlatforms.libretroCandidates(query("snes", filename),
            listOf("Super Mario World (Europe)"))
        val candidate = candidates.single { it.metadata.canonicalTitle == filename.substringBeforeLast('.') }
        for (reference in listOf(candidate.metadata.coverUrl, candidate.metadata.backdropUrl,
                candidate.metadata.titleScreenUrl)) {
            val url = reference!!.toHttpUrl()
            assertTrue(url.encodedPath.contains("%5BRelease%5D"))
            assertTrue(url.encodedPath.contains("%5B!%5D"))
            assertEquals(filename.substringBeforeLast('.') + ".png", url.pathSegments.last())
            assertEquals(reference, sanitizedGameArtworkUrl(GameMetadataProviderIds.LIBRETRO, reference))
            val mirror = libretroArtworkMirrorUrl(url)!!
            assertEquals(url.pathSegments.last(), mirror.pathSegments.last())
            assertEquals(mirror.toString(), sanitizedGameArtworkUrl(GameMetadataProviderIds.LIBRETRO, mirror.toString()))
            assertNull(mirror.query)
        }
    }

    @Test fun libretroPinnedIdentitySurvivesDisplayRenameAndCanonicalNameIsPreferred() {
        val original = GameMetadataPlatforms.libretroCandidates(query().copy(canonicalTitle = "Tetris (World)")).first()
        val renamed = GameMetadataPlatforms.libretroCandidates(query().copy(title = "My favourite puzzle",
            providerGameId = original.metadata.providerGameId, canonicalTitle = "Tetris (World)"))
        assertEquals(1, renamed.size)
        assertEquals(original.metadata.coverUrl, renamed.single().metadata.coverUrl)
        assertTrue(renamed.single().exactMatch)
        assertEquals("Tetris (World)", original.metadata.title)
    }

    @Test fun libretroAmbiguousArchiveSearchStaysBoundedAndIsNeverMarkedExact() {
        val choices = GameMetadataPlatforms.libretroCandidates(query("megadrive", "Sonic.zip"))
        assertTrue(choices.size in 1..12)
        assertTrue(choices.none { it.exactMatch })
        assertEquals(4, choices.map { it.metadata.providerGameId.substringBefore('|') }.distinct().size)
    }

    @Test fun libretroCatalogSearchPreservesRegionalNamesWithoutGuessingAnExactRomMatch() {
        val choices = GameMetadataPlatforms.libretroCandidates(query("snes", "short.smc").copy(title = "Super Mario"),
            listOf("Super Mario World (Europe)", "Super Mario World (USA)"))
        val catalog = choices.filter { it.catalogMatch }
        assertEquals(listOf("Super Mario World (Europe)", "Super Mario World (USA)"), catalog.map { it.metadata.title })
        assertTrue(catalog.none { it.exactMatch })
        assertEquals("Super Mario World (Europe).png", catalog.first().metadata.coverUrl!!.toHttpUrl().pathSegments.last())
        assertFalse(choices.last().catalogMatch)
    }

    @Test fun verifiedCatalogNameIsExactAndDistinctFromUnverifiedFilenameGuesses() {
        val choices = GameMetadataPlatforms.libretroCandidates(query("snes", "short.smc").copy(
            canonicalTitle = "Super Mario World (Europe)"), listOf("Super Mario World (Europe)"))
        assertTrue(choices.first().catalogMatch)
        assertTrue(choices.first().exactMatch)
        assertTrue(choices.drop(1).none { it.catalogMatch })
        val many = GameMetadataPlatforms.libretroCandidates(query("megadrive", "Sonic.zip"),
            (1..1000).map { "Sonic $it (Europe)" })
        assertTrue(many.size <= 30)
    }

    @Test fun screenScraperPreservesLocalizedMetadataButStripsAllCredentialsFromMedia() {
        val result = parseScreenScraper(screenScraperFixture, query(), setOf(9), exact = true).single()
        assertEquals("Tetris Europe", result.metadata.title)
        assertEquals("Deutsche Beschreibung", result.metadata.summary)
        assertEquals(1989, result.metadata.year)
        assertEquals(listOf("Puzzle"), result.metadata.genres)
        assertEquals("Nintendo", result.metadata.publisher)
        assertTrue(result.exactMatch)
        val artwork = result.metadata.coverUrl!!.toHttpUrl()
        assertEquals("box-2D(eu)", artwork.queryParameter("media"))
        assertEquals(setOf("jeuid", "media"), artwork.queryParameterNames)
        assertFalse(result.metadata.toString().contains("secret"))
    }

    @Test fun screenScraperArtworkChoicesRetainAllRegionsWithinTheMatchedGame() {
        val choices = parseScreenScraper(screenScraperFixture, query(), setOf(9), true, GameArtworkKind.COVER)
        assertEquals(2, choices.size)
        assertEquals(listOf("box-2D(eu)", "box-2D(us)"), choices.map { it.metadata.coverUrl!!.toHttpUrl().queryParameter("media") })
        assertEquals(setOf("123"), choices.map { it.metadata.providerGameId }.toSet())
        assertTrue(choices.all { it.exactMatch })
    }

    @Test fun screenScraperRejectsCrossSystemAndMissingPlatformIdentities() {
        assertTrue(parseScreenScraper(screenScraperFixture, query(), setOf(10), false).isEmpty())
        val unqualified = payload("""{"response":{"jeu":{"id":"1","noms":[{"region":"eu","text":"Same name"}]}}}""")
        assertTrue(parseScreenScraper(unqualified, query(), setOf(9), false).isEmpty())
    }

    @Test fun providerFailuresNeverLookLikeAnEmptySuccessfulSearch() {
        for (raw in listOf("""{"response":{"error":"service busy"}}""", """{"response":{"jeu":null}}""")) {
            val error = runCatching { parseScreenScraper(payload(raw), query(), setOf(9), false) }.exceptionOrNull()
            assertTrue(error is GameMetadataProviderException)
            assertEquals(GameMetadataFailure.INVALID_RESPONSE, (error as GameMetadataProviderException).kind)
        }
        assertTrue(parseScreenScraper(payload("""{"response":{"jeux":[]}}"""), query(), setOf(9), false).isEmpty())
        assertTrue(runCatching { parseTheGamesDb(payload("""{"data":{"games":null}}"""), null,
            query(), setOf("4"), emptyMap(), emptyMap(), emptyMap(), false) }.isFailure)
    }

    @Test fun screenScraperGuestClosuresAndBothDailyQuotasRemainRetryable() {
        for (code in listOf(401, 423, 429, 430, 431))
            assertEquals(GameMetadataFailure.RATE_LIMIT, gameMetadataHttpFailure(GameMetadataProviderIds.SCREENSCRAPER, code))
        assertEquals(GameMetadataFailure.CREDENTIALS, gameMetadataHttpFailure(GameMetadataProviderIds.SCREENSCRAPER, 403))
        assertEquals(GameMetadataFailure.NETWORK, gameMetadataHttpFailure(GameMetadataProviderIds.SCREENSCRAPER, 503))
    }

    @Test fun urlsRejectPrivateAndForeignHostsAndNeverForwardCredentialsInRedirects() {
        for (url in listOf("https://127.0.0.1/image.png", "https://api.screenscraper.fr.evil.example/image.png",
            "https://user:password@api.screenscraper.fr/image.png", "https://api.screenscraper.fr:444/image.png",
            "file:///sdcard/picture.png")) assertNull(sanitizedGameArtworkUrl(GameMetadataProviderIds.SCREENSCRAPER, url))
        val redirect = "https://media.screenscraper.fr/image.png?DevPassword=secret&SSPassword=secret&APIKEY=secret&size=512#secret".toHttpUrl()
        assertEquals("https://media.screenscraper.fr/image.png?size=512", credentialFreeGameUrl(redirect).toString())
        assertTrue(allowedGameProviderHost(GameMetadataProviderIds.SCREENSCRAPER, credentialFreeGameUrl(redirect)))
        assertEquals("https://media.screenscraper.fr/image.png", sanitizedGameArtworkUrl(GameMetadataProviderIds.SCREENSCRAPER,
            "http://media.screenscraper.fr/image.png"))
        assertNull(sanitizedGameArtworkUrl(GameMetadataProviderIds.SCREENSCRAPER, "http://media.screenscraper.fr:444/image.png"))
    }

    @Test fun theGamesDbResolvesReferenceIdsAndUsesFrontArtWithMultipleAlternatives() {
        val candidates = parseTheGamesDb(gamesDbFixture, gamesDbImages, query(), setOf("4"),
            mapOf("1" to "Puzzle"), mapOf("5" to "Nintendo R&amp;D1"), mapOf("2" to "Nintendo"), true)
        assertEquals(1, candidates.size)
        val game = candidates.single().metadata
        assertEquals("Tetris", game.title)
        assertEquals("Nintendo R&amp;D1", game.developer)
        assertEquals(listOf("Puzzle"), game.genres)
        assertEquals("https://cdn.thegamesdb.net/images/large/boxart/front/42-1.jpg", game.coverUrl)
        assertEquals("https://cdn.thegamesdb.net/images/large/fanart/42-1.jpg", game.backdropUrl)
        assertEquals("https://cdn.thegamesdb.net/images/large/titlescreen/42-1.jpg", game.titleScreenUrl)
        val choices = parseTheGamesDb(gamesDbFixture, gamesDbImages, query(), setOf("4"),
            emptyMap(), emptyMap(), emptyMap(), true, GameArtworkKind.COVER)
        assertEquals(2, choices.size)
        assertEquals(setOf("42"), choices.map { it.metadata.providerGameId }.toSet())
    }

    @Test fun theGamesDbSkipsWrongPlatformsAndUntrustedImagePaths() {
        assertTrue(parseTheGamesDb(gamesDbFixture, gamesDbImages, query(), setOf("18"),
            emptyMap(), emptyMap(), emptyMap(), false).isEmpty())
        val badImages = payload("""{"data":{"base_url":{"large":"https://cdn.thegamesdb.net/images/large/"},
            "images":{"42":[{"type":"boxart","side":"front","filename":"https://private.example/key.png"},
            {"type":"boxart","side":"front","filename":"../../outside.png"}]}}}""")
        val noImages = parseTheGamesDb(gamesDbFixture, badImages, query(), setOf("4"),
            emptyMap(), emptyMap(), emptyMap(), false).single()
        assertNull(noImages.metadata.coverUrl)
    }

    @Test fun providerExceptionsNeverRetainARequestOrResponseCause() {
        val error = GameMetadataProviderException(GameMetadataFailure.CREDENTIALS)
        assertNull(error.cause)
        assertFalse(error.message.orEmpty().contains("http"))
    }

    @Test fun alternateArtworkListsAreBoundedBeforeBuildingLargeCandidateCollections() {
        val images = (1..90).joinToString(",") { index -> """{"type":"boxart","side":"front","filename":"boxart/front/42-$index.jpg"}""" }
        val result = parseTheGamesDb(gamesDbFixture, payload("""{"data":{"base_url":{"large":"https://cdn.thegamesdb.net/images/large/"},
            "images":{"42":[$images]}}}"""), query(), setOf("4"), emptyMap(), emptyMap(), emptyMap(), true, GameArtworkKind.COVER)
        assertEquals(30, result.size)
        assertEquals(30, result.map { it.metadata.coverUrl }.distinct().size)
    }

    private val screenScraperFixture get() = payload("""{"response":{"jeu":{
        "id":"123","systeme":{"id":"9"},"editeur":{"text":"Nintendo"},"developpeur":{"text":"Nintendo"},"joueurs":{"text":"1-2"},
        "noms":[{"region":"us","text":"Tetris US"},{"region":"eu","text":"Tetris Europe"}],
        "synopsis":[{"langue":"en","text":"English description"},{"langue":"de","text":"Deutsche Beschreibung"}],
        "dates":[{"region":"eu","text":"1989-07-31"}],"genres":[{"noms":[{"langue":"de","text":"Puzzle"}]}],
        "medias":[
          {"type":"box-2D","region":"us","url":"https://api.screenscraper.fr/api2/mediaJeu.php?jeuid=123&amp;media=box-2D(us)"},
          {"type":"box-2D","region":"eu","url":"https://api.screenscraper.fr/api2/mediaJeu.php?devid=secret&amp;devpassword=secret&amp;ssid=secret&amp;sspassword=secret&amp;jeuid=123&amp;media=box-2D(eu)"},
          {"type":"ss","url":"https://media.screenscraper.fr/screenshot.png"}
        ]}}}""".replace("&amp;", "&"))
    private val gamesDbFixture get() = payload("""{"code":200,"data":{"games":[{
        "id":42,"game_title":"Tetris","platform":4,"release_date":"1989-07-31","players":2,
        "overview":"Falling blocks","developers":[5],"publishers":[2],"genres":[1]
    }]},"pages":{"next":"https://api.thegamesdb.net/page?apikey=secret"}}""")
    private val gamesDbImages get() = payload("""{"data":{"base_url":{"large":"https://cdn.thegamesdb.net/images/large/"},"images":{"42":[
        {"type":"boxart","side":"back","filename":"boxart/back/42-1.jpg"},
        {"type":"boxart","side":"front","filename":"boxart/front/42-1.jpg"},
        {"type":"boxart","side":"front","filename":"boxart/front/42-2.jpg"},
        {"type":"screenshot","filename":"screenshots/42-1.jpg"},
        {"type":"fanart","filename":"fanart/42-1.jpg"},
        {"type":"titlescreen","filename":"titlescreen/42-1.jpg"}
    ]}}}""")
}

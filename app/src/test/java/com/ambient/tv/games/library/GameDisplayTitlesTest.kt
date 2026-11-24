package com.ambient.tv.games.library

import org.junit.Assert.*
import org.junit.Test

class GameDisplayTitlesTest {
    @Test fun removesRegionsAndLanguagesWhilePreservingTheOtherTrailingAnnotations() {
        val original = "Game (The Lost Levels) (USA, Europe) (En,Fr,De) (Rev 1) (Disc 2)"
        assertEquals("Game (The Lost Levels) (Rev 1) (Disc 2)", gameDisplayTitle(original))
        assertEquals("USA, Europe", gameTitleRegionLabel(original))
        assertEquals("En, Fr, De", gameTitleLanguageLabel(original))
        assertEquals("Game (Prototype) [!r]", gameDisplayTitle("Game (Prototype) [Japan] [!r]"))
    }

    @Test fun supportsCountriesMultipleRegionGroupsAndCommonRomRegionAbbreviations() {
        for (region in listOf("USA", "Europe", "Japan", "World", "Germany", "France", "United Kingdom",
            "Brazil", "Korea", "Taiwan", "Australia", "North America", "JPN", "EU", "UK", "U", "E", "J", "W")) {
            assertEquals(region, "Game", gameDisplayTitle("Game ($region)"))
            assertEquals(region, gameTitleRegionLabel("Game ($region)"))
        }
        assertEquals("Game (Rev A)", gameDisplayTitle("Game [USA] (Europe/Japan+USA) (Rev A)"))
        assertEquals("USA, Europe, Japan", gameTitleRegionLabel("Game [USA] (Europe/Japan+USA) (Rev A)"))
        assertEquals("Game", gameDisplayTitle("Game (eUrOpE)"))
    }

    @Test fun leavesVersionsDiscsAndRealTitleTextUntouched() {
        for (title in listOf("Japan Pro Golf", "USA Racer", "Game (The Lost Levels)",
            "Game (II)", "Game (It Lives)", "Game (Rev 1) (Disc 2)", "Game (En,Unknown)",
            "Game (PAL)", "Game (NTSC-J)", "Game (USA Edition)", "Game (Europe) Adventures",
            "Game (En-Game)", "Game (En-XX)", "Game (USA, Language Pack)")) {
            assertEquals(title, gameDisplayTitle(title))
            assertNull(title, gameTitleRegionLabel(title))
            assertNull(title, gameTitleLanguageLabel(title))
        }
    }

    @Test fun ambiguousRegionAndLanguageCodesStaySeparateInEditionLabels() {
        for (language in listOf("Eu", "Uk", "eu", "uk", "En,Uk")) {
            val title = "Game ($language)"
            assertEquals("Game", gameDisplayTitle(title))
            assertNull(gameTitleRegionLabel(title))
            assertNotNull(gameTitleLanguageLabel(title))
        }
        assertEquals("Game", gameDisplayTitle("Game (EU, UK) (Eu,Uk)"))
        assertEquals("EU, UK", gameTitleRegionLabel("Game (EU, UK) (Eu,Uk)"))
        assertEquals("Eu, Uk", gameTitleLanguageLabel("Game (EU, UK) (Eu,Uk)"))
    }

    @Test fun languageOnlyAnnotationsAreRemovedWithoutRequiringARegionTag() {
        assertEquals("Game", gameDisplayTitle("Game (En,de,fr)"))
        assertEquals("En, De, Fr", gameTitleLanguageLabel("Game (En,de,fr)"))
        assertNull(gameTitleRegionLabel("Game (En,de,fr)"))
        val other = "Game [Sv,No,Da,Fi,Pl,Ru,Uk,Eu,Ja,Ko,Zh]"
        assertEquals("Game", gameDisplayTitle(other))
        assertEquals("Sv, No, Da, Fi, Pl, Ru, Uk, Eu, Ja, Ko, Zh", gameTitleLanguageLabel(other))
        assertEquals("Game", gameDisplayTitle("Game (De)"))
    }

    @Test fun supportsDocumentedLocaleVariantsAndFilenameUnderscoreAliases() {
        val title = "Game (en-US,FR-ca,pt_BR,ZH-hant,Zh-Hans,Es-XL)"
        assertEquals("Game", gameDisplayTitle(title))
        assertEquals("En-US, Fr-CA, Pt-BR, Zh-Hant, Zh-Hans, Es-XL", gameTitleLanguageLabel(title))
        assertEquals("Game", gameDisplayTitle(gameDisplayFileName("Game_(En_US,Fr_CA).sfc")))
        assertEquals("Game", gameDisplayTitle("Game (En-GB,Fr-FR,Es-ES,Es-MX,Pt-PT)"))
    }

    @Test fun mixedCountryLanguageAndMultiGameGroupsRetainSeparateLabels() {
        val title = "Game (USA, En,de) (Es,It+En,Es,It,Sv,Da)"
        assertEquals("Game", gameDisplayTitle(title))
        assertEquals("USA", gameTitleRegionLabel(title))
        assertEquals("En, De, Es, It, Sv, Da", gameTitleLanguageLabel(title))
        assertEquals("Game", gameDisplayTitle("Game (En/de+Fr)"))
        val unknown = "Game (USA, En, Unknown)"
        assertEquals(unknown, gameDisplayTitle(unknown))
        assertNull(gameTitleRegionLabel(unknown))
        assertNull(gameTitleLanguageLabel(unknown))
    }

    @Test fun keepsMalformedAndLocaleOnlyTitlesVisible() {
        for (title in listOf("(USA)", "[Japan] (Europe)", "(En,de,fr)", "(USA) [En,Ja]",
            "Game (USA]", "Game (The (USA))", "Game (En,)", "", "  ")) {
            assertEquals(title, gameDisplayTitle(title))
            assertNull(title, gameTitleRegionLabel(title))
            assertNull(title, gameTitleLanguageLabel(title))
        }
        assertEquals("Game (The (Lost Levels))", gameDisplayTitle("Game (The (Lost Levels)) (Europe)"))
        assertEquals("Game (The (Lost Levels))", gameDisplayTitle("Game (The (Lost Levels)) (En,Ja)"))
    }
}

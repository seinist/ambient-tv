package com.ambient.tv.games.library

import org.junit.Assert.*
import org.junit.Test

class GameDisplayTitlesTest {
    @Test fun removesOnlyExplicitRegionGroupsFromTheTrailingAnnotations() {
        val original = "Game (The Lost Levels) (USA, Europe) (En,Fr,De) (Rev 1) (Disc 2)"
        assertEquals("Game (The Lost Levels) (En,Fr,De) (Rev 1) (Disc 2)", gameDisplayTitle(original))
        assertEquals("USA, Europe", gameTitleRegionLabel(original))
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

    @Test fun leavesLanguageCodesVersionsDiscsAndRealTitleTextUntouched() {
        for (title in listOf("Japan Pro Golf", "USA Racer", "Game (The Lost Levels)",
            "Game (En,Fr,De,It,Es,Pt,Ru)", "Game (Rev 1) (Disc 2)", "Game (USA, En)",
            "Game (PAL)", "Game (NTSC-J)", "Game (USA Edition)", "Game (Europe) Adventures")) {
            assertEquals(title, gameDisplayTitle(title))
            assertNull(title, gameTitleRegionLabel(title))
        }
    }

    @Test fun ambiguousLanguageCodesNeedUppercaseToMeanARegion() {
        for (language in listOf("Eu", "Uk", "eu", "uk", "En,Uk")) {
            val title = "Game ($language)"
            assertEquals(title, gameDisplayTitle(title))
            assertNull(gameTitleRegionLabel(title))
        }
        assertEquals("Game (Eu,Uk)", gameDisplayTitle("Game (EU, UK) (Eu,Uk)"))
        assertEquals("EU, UK", gameTitleRegionLabel("Game (EU, UK) (Eu,Uk)"))
    }

    @Test fun keepsMalformedAndRegionOnlyTitlesVisible() {
        for (title in listOf("(USA)", "[Japan] (Europe)", "Game (USA]", "Game (The (USA))", "", "  ")) {
            assertEquals(title, gameDisplayTitle(title))
            assertNull(title, gameTitleRegionLabel(title))
        }
        assertEquals("Game (The (Lost Levels))", gameDisplayTitle("Game (The (Lost Levels)) (Europe)"))
    }
}

package com.ambient.tv.games.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDiscGroupingTest {
    @Test fun onePlaylistRepresentsTwoDiscsWithoutHidingOtherGames() {
        val paths = setOf("PSX/game.m3u", "PSX/disc1.cue", "PSX/disc2.chd", "PSX/other.chd")
        val refs = GameDiscGrouping.referenced("PSX/game.m3u", "disc1.cue\ndisc2.chd\n")
        val visible = GameDiscGrouping.visiblePaths(paths, mapOf("PSX/game.m3u" to refs))
        assertEquals(setOf("PSX/game.m3u", "PSX/other.chd"), visible)
    }
    @Test fun unsafeReferencesNeverHideCatalogItems() {
        val refs = GameDiscGrouping.referenced("Games/movie.m3u", "../Movies/movie.iso\n/absolute.iso\nSafe.chd")
        assertFalse("Movies/movie.iso" in refs)
        assertFalse("/absolute.iso" in refs)
        assertTrue("Unsafe playlist must hide nothing", refs.isEmpty())
    }
    @Test fun incompletePlaylistNeverHidesItsStillPlayableDiscs() {
        val paths = setOf("PSX/game.m3u", "PSX/disc1.cue", "PSX/other.chd")
        val refs = GameDiscGrouping.referenced("PSX/game.m3u", "disc1.cue\ndisc2.chd\n")
        assertEquals(paths, GameDiscGrouping.visiblePaths(paths, mapOf("PSX/game.m3u" to refs)))
    }

    @Test fun completePlaylistStillHidesEveryReferencedDisc() {
        val paths = setOf("PSX/game.m3u", "PSX/disc1.cue", "PSX/disc2.chd", "PSX/other.chd")
        val refs = GameDiscGrouping.referenced("PSX/game.m3u", "disc1.cue\ndisc2.chd\n")
        assertEquals(setOf("PSX/game.m3u", "PSX/other.chd"),
            GameDiscGrouping.visiblePaths(paths, mapOf("PSX/game.m3u" to refs)))
    }

    @Test fun standaloneCueRemainsAVisiblePlayableGame() {
        val paths = setOf("Dreamcast/game.gdi", "PSX/game.cue")
        val refs = mapOf("PSX/game.cue" to setOf("PSX/game.bin"))
        assertEquals(paths, GameDiscGrouping.visiblePaths(paths, refs))
    }
}

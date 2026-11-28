package com.ambient.tv.games.library

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class GameLibraryQueryTest {
    @Test fun searchFoldingMatchesLiteralIgnoreCaseWithoutAsciiOrWildcardRegressions() {
        val titles = listOf("Ärger", "İSTANBUL", "dotless ı", "ΟΣ ος", "Straße", "100%_done's",
            "\uD801\uDC00\uD801\uDC28", "two words", "A\u0000B")
        val queries = listOf("är", "i", "I", "ı", "σ", "ς", "ss", "%_", "'s", "\uD801\uDC28", " ", "\u0000")
        for (title in titles) for (query in queries) {
            assertEquals("$title / $query", title.contains(query, ignoreCase = true),
                gameSearchKey(title).contains(gameSearchKey(query)))
        }
    }

    @Test fun blobSortKeysGroupSymbolsBeforeLettersAndPreserveRootLowercaseUtf16Order() {
        val values = listOf("9 Games", "~Game", "[Game]", "Zulu", "alpha", "İ", "I", "Ä", "ß", "\uE000", "\uD801\uDC00", "a", "aA")
        val byBlob = values.sortedWith { a, b ->
            val left = gameSortKey(a); val right = gameSortKey(b)
            var result = 0
            for (i in 0 until minOf(left.size, right.size)) {
                result = (left[i].toInt() and 255).compareTo(right[i].toInt() and 255)
                if (result != 0) break
            }
            if (result == 0) left.size.compareTo(right.size) else result
        }
        assertEquals(values.sortedWith(compareBy<String> { gameIndexLetter(it) != '#' }
            .thenBy { it.lowercase(Locale.ROOT) }), byBlob)
        assertEquals(listOf('#', '#', '#', '#', '#'), byBlob.take(5).map(::gameIndexLetter))
    }

    @Test fun indexIncludesNumbersSymbolsWhitespaceAndNonAsciiLetters() {
        assertEquals('#', gameIndexLetter("  1942"))
        assertEquals('#', gameIndexLetter("[Demo]"))
        assertEquals('#', gameIndexLetter("~Title"))
        assertEquals('#', gameIndexLetter(""))
        assertEquals('A', gameIndexLetter(" alpha"))
        assertEquals('Ä', gameIndexLetter("Älpler"))
        assertEquals('Р', gameIndexLetter("Русский"))
    }

    @Test fun arbitrarySearchAndSectionStayBoundParameters() {
        val sql = gameLibrarySql(GameLibraryQuery(section = "snes' OR 1=1 --", search = "%_'") , "auto")
        assertFalse(sql.sql.contains("OR 1=1"))
        assertFalse(sql.sql.contains("%_"))
        assertArrayEquals(arrayOf("snes' OR 1=1 --", "%_'", "%_'"), sql.args)
        assertTrue(sql.sql.contains("instr("))
        assertFalse(sql.sql.contains("LIKE"))
    }

    @Test fun recentSectionAlwaysKeepsNewestFirstAndFullLibraryHasNoImplicitCap() {
        val recent = gameLibrarySql(GameLibraryQuery(section = "recent", descending = true), "auto")
        assertTrue(recent.sql.contains("q.last_played DESC"))
        assertFalse(recent.sql.contains(" LIMIT "))
        assertTrue(gameLibrarySql(GameLibraryQuery(limit = 24), "auto").sql.endsWith(" LIMIT 24"))
        assertTrue(gameLibrarySql(GameLibraryQuery(limit = -1), "auto").sql.endsWith(" LIMIT 0"))
    }

    @Test fun digestSeparatesFieldsAndDoesNotConfuseEmbeddedSeparators() {
        assertNotEquals(gameQueryDigest(listOf("a", "bc")), gameQueryDigest(listOf("ab", "c")))
        assertNotEquals(gameQueryDigest(listOf("a\u0000b", "c")), gameQueryDigest(listOf("a", "b\u0000c")))
        assertEquals(64, gameQueryDigest(emptyList()).length)
    }
}

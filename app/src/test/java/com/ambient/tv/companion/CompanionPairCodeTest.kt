package com.ambient.tv.companion

import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class CompanionPairCodeTest {
    @Test fun generatedCodesAvoidLookAlikeCharacters() {
        val random = SecureRandom()
        repeat(2000) {
            val code = randomPairCode(random)
            assertEquals(PAIR_CODE_LENGTH, code.length)
            assertTrue(code, code.all { it in PAIR_CODE_ALPHABET })
            assertTrue(code, code.none { it in "ILOU01" })
        }
    }

    @Test fun generatedCodesAreNotPredictable() {
        val codes = (1..500).map { randomPairCode() }
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test fun normalizationAcceptsHowPeopleTypeTheCode() {
        assertEquals("ABCD2345", normalizePairCode("abcd-2345"))
        assertEquals("ABCD2345", normalizePairCode(" ABCD 2345 "))
        assertEquals("ABCD2345", normalizePairCode("ABCD2345"))
        assertEquals(formatPairCode("ABCD2345"), "ABCD-2345")
    }

    @Test fun charactersOutsideTheAlphabetShortenInsteadOfMatchingSomethingElse() {
        // A mistyped look-alike must not silently become a different valid code.
        for (typo in listOf("ABCD234O", "ABCD2341", "IBCD2345", "ABCDU345")) {
            assertNotEquals(PAIR_CODE_LENGTH, normalizePairCode(typo).length)
        }
    }

    @Test fun normalizationIsBounded() {
        assertTrue(normalizePairCode("A".repeat(500)).length <= 64)
    }
}

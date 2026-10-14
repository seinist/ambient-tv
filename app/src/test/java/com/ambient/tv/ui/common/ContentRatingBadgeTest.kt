package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRatingBadgeTest {
    @Test
    fun parsesGermanFskRatings() {
        assertEquals(0, parseGermanFskAge("DE/0"))
        assertEquals(6, parseGermanFskAge("de-6"))
        assertEquals(12, parseGermanFskAge("FSK 12"))
        assertEquals(16, parseGermanFskAge("FSK ab 16"))
        assertEquals(18, parseGermanFskAge("18"))
    }

    @Test
    fun leavesNonGermanRatingsUntouched() {
        assertNull(parseGermanFskAge("PG-13"))
        assertNull(parseGermanFskAge("TV-MA"))
        assertNull(parseGermanFskAge("DE/15"))
    }
    @Test
    fun parsesExplicitInternationalAgesWithoutGuessingAmbiguousRatings() {
        assertEquals(13, parseContentAge("PG-13"))
        assertEquals(14, parseContentAge("TV-14"))
        assertEquals(15, parseContentAge("15"))
        assertEquals(18, parseContentAge("PEGI 18"))
        assertNull(parseContentAge("PG"))
        assertNull(parseContentAge("R"))
        assertNull(parseContentAge("TV-MA"))
    }

    @Test
    fun normalizesScoresToFiveStars() {
        assertEquals(4.0, normalizeRatingToFive(8.0, 10.0)!!, 0.0001)
        assertEquals(4.5, normalizeRatingToFive(90.0, 100.0)!!, 0.0001)
        assertEquals(5.0, normalizeRatingToFive(15.0, 10.0)!!, 0.0001)
        assertNull(normalizeRatingToFive(Double.NaN, 10.0))
    }

}

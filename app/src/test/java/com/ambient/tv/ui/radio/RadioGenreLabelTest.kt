package com.ambient.tv.ui.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioGenreLabelTest {
    @Test fun formatsCommonGenreNamesWithoutChangingTheirMeaning() {
        assertEquals("Classic Rock", radioGenreDisplayName("classic rock"))
        assertEquals("R&B", radioGenreDisplayName("r&b"))
        assertEquals("EDM", radioGenreDisplayName("edm"))
        assertEquals("DnB", radioGenreDisplayName("dnb"))
        assertEquals("Drum & Bass", radioGenreDisplayName("drum and bass"))
        assertEquals("Hip-Hop", radioGenreDisplayName("hip hop"))
        assertEquals("Lo-Fi", radioGenreDisplayName("lofi"))
        assertEquals("K-Pop", radioGenreDisplayName("kpop"))
        assertEquals("80s", radioGenreDisplayName("80s"))
    }

    @Test fun trimsAndNormalizesWhitespace() {
        assertEquals("Alternative Rock", radioGenreDisplayName("  alternative   rock  "))
    }
}

package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemNameOverridesCleanupTest {
    @Test fun aCopiedLiveTvLabelIsRemoved() {
        assertEquals(mapOf("plex" to "Kino"), SystemNameOverrides.withoutLegacyLabelCopies(
            mapOf("tvheadend" to "Live-TV", "plex" to "Kino")))
        assertEquals(emptyMap<String, String>(), SystemNameOverrides.withoutLegacyLabelCopies(
            mapOf("tvheadend" to " Live TV ")))
    }

    @Test fun ownNamesStay() {
        val names = mapOf("tvheadend" to "Fernsehen", "iptv" to "Live-TV")
        assertEquals(names, SystemNameOverrides.withoutLegacyLabelCopies(names))
    }
}

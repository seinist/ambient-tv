package com.ambient.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuOrderRenamesTest {
    private val shown = mapOf("tvheadend" to "TVHeadend", "plex" to "Kino")
    private val providers = mapOf("plex" to "Mein Plex")
    private val defaults = mapOf("tvheadend" to "TVHeadend", "plex" to "Plex")

    @Test fun untouchedNamesAreNotSaved() {
        val (renamed, edited) = menuOrderRenames(shown, shown, providers, defaults)
        assertEquals(emptyMap<String, String>(), renamed)
        assertEquals(emptySet<String>(), edited)
    }

    @Test fun onlyTheEditedNameIsSaved() {
        val (renamed, edited) = menuOrderRenames(shown + ("tvheadend" to "Fernsehen"), shown, providers, defaults)
        assertEquals(mapOf("tvheadend" to "Fernsehen"), renamed)
        assertEquals(setOf("tvheadend"), edited)
    }

    @Test fun resettingToTheDefaultRemovesTheOwnName() {
        val (renamed, edited) = menuOrderRenames(shown + ("plex" to "Plex"), shown, providers, defaults)
        assertEquals(emptyMap<String, String>(), renamed)
        assertEquals(setOf("plex"), edited)
    }
}

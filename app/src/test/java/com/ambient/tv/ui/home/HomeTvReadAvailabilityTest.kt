package com.ambient.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTvReadAvailabilityTest {
    @Test fun `repeated cancellation failures do not republish unchanged Home rows`() {
        val availability = HomeTvReadAvailability()
        val states = mutableMapOf("tvheadend" to false, "plex" to false)
        fun unconfirmed(source: String) = states.getValue(source)
        availability.capture(listOf("tvheadend"), ::unconfirmed)

        // Cancelling another NAS service changes its health, not these TV programme claims.
        states["plex"] = true
        repeat(20) { assertFalse(availability.changed(::unconfirmed)) }

        states["tvheadend"] = true
        assertTrue(availability.changed(::unconfirmed))
        availability.capture(listOf("tvheadend"), ::unconfirmed)
        repeat(20) { assertFalse(availability.changed(::unconfirmed)) }

        // Fresh authenticated data clears the saved marker and restores the programme once.
        states["tvheadend"] = false
        assertTrue(availability.changed(::unconfirmed))
        availability.capture(listOf("tvheadend"), ::unconfirmed)
        assertFalse(availability.changed(::unconfirmed))
    }

    @Test fun `channels shared by live recent and return rows read health once per source`() {
        val availability = HomeTvReadAvailability()
        val reads = mutableMapOf<String, Int>()
        val sources = List(40) { "tvheadend" } + List(40) { "plex:second-profile" }
        val snapshot = availability.capture(sources) { source ->
            reads[source] = reads.getOrDefault(source, 0) + 1
            source == "plex:second-profile"
        }

        assertEquals(mapOf("tvheadend" to 1, "plex:second-profile" to 1), reads)
        assertEquals(mapOf("tvheadend" to false, "plex:second-profile" to true), snapshot)
    }

    @Test fun `a captured profile remains independent of a sibling on the same host`() {
        val availability = HomeTvReadAvailability()
        val states = mutableMapOf("plex:first-profile" to false, "plex:second-profile" to false)
        availability.capture(states.keys) { states.getValue(it) }
        states["plex:second-profile"] = true
        assertTrue(availability.changed { states.getValue(it) })

        // Removing its row removes its influence; it can no longer repaint the first profile.
        availability.capture(listOf("plex:first-profile")) { states.getValue(it) }
        states["plex:second-profile"] = false
        assertFalse(availability.changed { states.getValue(it) })
    }

    @Test fun `Home with no TV channels ignores availability until a preview is published`() {
        val availability = HomeTvReadAvailability()
        assertFalse(availability.changed { error("No displayed source needs checking") })
        availability.capture(emptyList()) { error("No displayed source needs checking") }
        assertFalse(availability.changed { error("No displayed source needs checking") })

        availability.capture(listOf("tvheadend")) { true }
        assertTrue(availability.changed { false })
    }
}

package com.ambient.tv.data.browse

import org.junit.Assert.*
import org.junit.Test

class BrowseReconnectPolicyTest {
    @Test fun recoveryOnlyRefreshesTheAffectedSourceOrItsFamily() {
        assertTrue(browseSourceMatches("files:nas-a", setOf("files")))
        assertTrue(browseSourceMatches("files", setOf("files:nas-a")))
        assertTrue(browseSourceMatches("files:nas-a", setOf("files:nas-a")))
        assertFalse(browseSourceMatches("files:nas-b", setOf("files:nas-a")))
        assertFalse(browseSourceMatches("plex", setOf("jellyfin")))
        assertFalse(browseSourceMatches("files", setOf("files-other")))
    }

    @Test fun networkRecoveryReachesEveryViewButAnInactiveViewHasNoSources() {
        assertTrue(browseSourceMatches("*", setOf("youtube")))
        assertTrue(browseSourceMatches("*", setOf("dlna:living-room")))
        assertTrue(browseSourceMatches("spotify", setOf("*")))
        assertFalse(browseSourceMatches("*", emptySet()))
    }

    @Test fun aPausedViewCanCatchUpAfterSeveralRecoveriesWithoutDependingOnTheLastEvent() {
        val revisions = BrowseReconnectRevisions()
        val first = revisions.publish(setOf("plex"))
        val seen = browseReconnectVersion(first, setOf("plex"))
        revisions.publish(setOf("plex"))
        val current = revisions.publish(setOf("spotify"))
        assertTrue(browseReconnectVersion(current, setOf("plex")) > seen)
        assertEquals(2L, browseReconnectVersion(current, setOf("plex")))
        assertEquals(3L, browseReconnectVersion(current, setOf("plex", "spotify")))
    }

    @Test fun coalescedServicesShareOneRevisionAndUnrelatedSuccessDoesNotRetriggerAView() {
        val clock = BrowseReconnectRevisions()
        val first = clock.publish(setOf("plex", "tvheadend"))
        assertEquals(first["plex"], first["tvheadend"])
        val next = clock.publish(setOf("dlna:nas"))
        assertEquals(browseReconnectVersion(first, setOf("plex")), browseReconnectVersion(next, setOf("plex")))
        assertEquals(next, clock.publish(emptySet()))
    }

    @Test fun firstNetworkAndRepeatedCapabilityReportsAreNotReconnects() {
        val recovery = BrowseNetworkRecovery()
        assertTrue(recovery.update("wifi", true).isEmpty())
        repeat(5) { assertTrue(recovery.update("wifi", true).isEmpty()) }
    }

    @Test fun anIsolatedLanRecoversWithoutInternetValidation() {
        val recovery = BrowseNetworkRecovery()
        recovery.update("ethernet", false)
        assertTrue(recovery.update(null, false).isEmpty())
        assertEquals(setOf("*"), recovery.update("ethernet", false))
        assertTrue(recovery.update("ethernet", false).isEmpty())
    }

    @Test fun switchingNetworkPathsRefreshesEvenWithoutAnOfflineCallback() {
        val recovery = BrowseNetworkRecovery()
        recovery.update("wifi", true)
        assertEquals(setOf("*"), recovery.update("ethernet", true))
        // A delayed callback for the previous network still observes the current default.
        assertTrue(recovery.update("ethernet", true).isEmpty())
    }

    @Test fun internetRecoveryAlsoReachesRemoteMediaServersAndIptv() {
        val recovery = BrowseNetworkRecovery()
        recovery.update("wifi", false)
        assertEquals(setOf("*"), recovery.update("wifi", true))
        assertTrue(recovery.update("wifi", true).isEmpty())
        assertTrue(recovery.update("wifi", false).isEmpty())
        assertEquals(setOf("*"), recovery.update("wifi", true))
    }
}

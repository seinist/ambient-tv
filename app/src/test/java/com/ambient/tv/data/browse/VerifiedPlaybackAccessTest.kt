package com.ambient.tv.data.browse

import com.ambient.tv.connectivity.HostHealthStore
import org.junit.Assert.*
import org.junit.Test

class VerifiedPlaybackAccessTest {
    @Test fun unknownServiceHasNoPermission() {
        assertFalse(VerifiedPlaybackAccess().contains("plex", "server:account:network"))
    }

    @Test fun onlyCurrentProtectedReadCreatesPermission() {
        val access = VerifiedPlaybackAccess()
        assertTrue(access.confirm("jellyfin", "server:user:token:network", "server:user:token:network"))
        assertTrue(access.contains("jellyfin", "server:user:token:network"))
        assertFalse(access.contains("emby", "server:user:token:network"))
        assertFalse(access.contains("jellyfin", "server:other-user:token:network"))
    }

    @Test fun lateSuccessAfterProfileOrNetworkChangeCannotGrantPermission() {
        val access = VerifiedPlaybackAccess()
        assertFalse(access.confirm("plex", "old-server:user:network1", "new-server:user:network1"))
        assertFalse(access.confirm("plex", "server:user:network1", "server:user:network2"))
        assertFalse(access.contains("plex", "new-server:user:network1"))
    }

    @Test fun matchingIdOnNewAccountDoesNotReusePermission() {
        val access = VerifiedPlaybackAccess()
        access.confirm("plex", "server:alice:tokenA", "server:alice:tokenA")
        assertFalse(access.contains("plex", "server:bob:tokenB"))
        assertFalse(access.confirm("plex", "server:alice:tokenA", "server:bob:tokenB"))
    }

    @Test fun anOutageRemainsPartOfIdentityAfterAnotherServiceWakesHost() {
        val hosts = HostHealthStore()
        val access = VerifiedPlaybackAccess()
        hosts.online("shared-machine")
        val old = "tvheadend:${hosts.current("shared-machine").outages}"
        access.confirm("tvheadend", old, old)
        hosts.offline(hosts.begin("shared-machine"))
        hosts.online("shared-machine") // Plex answering must not restore TVHeadend's permission.
        val recovered = "tvheadend:${hosts.current("shared-machine").outages}"
        assertFalse(access.contains("tvheadend", recovered))
        assertEquals(1L, hosts.current("shared-machine").outages)
    }

    @Test fun onlineSiblingNeverProvidesRequiredLiveOrDvrPermission() {
        val access = VerifiedPlaybackAccess()
        access.confirm("plex", "shared-host:plex-account", "shared-host:plex-account")
        access.confirm("tvheadend:profile:channel", "tvh:account:network", "tvh:account:network")
        assertFalse(access.contains("tvheadend", "shared-host:plex-account"))
        assertFalse(access.contains("tvheadend:profile:recording", "tvh:account:network"))
    }

    @Test fun failedSourceDoesNotRevokeAnIndependentVerifiedSource() {
        val access = VerifiedPlaybackAccess()
        access.confirm("plex", "plex:a", "plex:a")
        access.confirm("emby", "emby:b", "emby:b")
        access.invalidate("plex")
        assertFalse(access.contains("plex", "plex:a"))
        assertTrue(access.contains("emby", "emby:b"))
    }

    @Test fun repeatedSuccessKeepsTheSamePermissionWithoutAnotherProbe() {
        val access = VerifiedPlaybackAccess()
        repeat(4) { assertTrue(access.confirm("emby", "same-session", "same-session")) }
        assertTrue(access.contains("emby", "same-session"))
    }
}

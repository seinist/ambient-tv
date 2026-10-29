package com.ambient.tv.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostEndpointTest {
    private val active = HostEndpoint("plex", "active.lan", 32400, "active-machine")
    private val saved = HostEndpoint(requireNotNull(mediaServerProfileSourceId("plex", "saved-profile")), "saved.lan", 32400, "saved-machine")

    @Test fun savedProfileRequestUsesItsOwnHostInsteadOfTheActiveProvider() {
        assertEquals(saved, matchingHostEndpoint(listOf(active, saved), "saved.lan", 32400))
        assertEquals(active, matchingHostEndpoint(listOf(active, saved), "active.lan", 32400))
    }

    @Test fun anotherPortOnTheSameMachineMustHaveAnExplicitEndpoint() {
        val tv = HostEndpoint("tvheadend", "active.lan", 9981, "active-machine")
        assertEquals(tv, matchingHostEndpoint(listOf(active, tv), "active.lan", 9981))
        assertNull(matchingHostEndpoint(listOf(active, tv), "active.lan", 8096))
    }

    @Test fun unknownHostsCannotFallBackToAnActiveServer() {
        assertNull(matchingHostEndpoint(listOf(active, saved), "other.lan", 32400))
        assertNull(matchingHostEndpoint(listOf(active, saved), "saved.lan", 443))
    }

    @Test fun explicitProfileCheckRequiresBothIdentityAndCurrentEndpoint() {
        assertEquals(saved, matchingHostEndpoint(listOf(active, saved), "saved.lan", 32400, saved.system))
        assertNull(matchingHostEndpoint(listOf(active, saved), "saved.lan", 32400, "plex"))
        assertNull(matchingHostEndpoint(listOf(active, saved), "active.lan", 32400, saved.system))
    }

    @Test fun equivalentHostNotationStillMatchesTheCorrectEndpoint() {
        assertEquals(saved, matchingHostEndpoint(listOf(active, saved), "HTTPS://SAVED.LAN:32400/path", 32400))
        val ipv6 = HostEndpoint("emby:profile:user", "fd00::a", 8096, "ipv6-machine")
        assertEquals(ipv6, matchingHostEndpoint(listOf(active, ipv6), "[fd00::a]", 8096))
    }

    @Test fun usersOnOneSavedMediaBrowserProfileHaveSeparateServiceIdentities() {
        assertEquals("plex:profile", mediaServerProfileSourceId("plex", "profile"))
        assertEquals("jellyfin:profile:user-a", mediaServerProfileSourceId("jellyfin", "profile", "user-a"))
        assertEquals("emby:profile:user-a", mediaServerProfileSourceId("emby", "profile", "user-a"))
        assertNotEquals(mediaServerProfileSourceId("jellyfin", "profile", "user-a"), mediaServerProfileSourceId("jellyfin", "profile", "user-b"))
        assertNull(mediaServerProfileSourceId("jellyfin", "profile"))
        assertNull(mediaServerProfileSourceId("emby", "profile", ""))
        assertNull(mediaServerProfileSourceId("plex", ""))
    }
}

package com.ambient.tv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveredServerSelectionTest {
    @Test fun plexWithoutTokenStartsLogin() {
        assertEquals(DiscoveredServerAction.StartLogin, plexDiscoveredServerAction(null))
        assertEquals(DiscoveredServerAction.StartLogin, plexDiscoveredServerAction(""))
        assertEquals(DiscoveredServerAction.StartLogin, plexDiscoveredServerAction("   "))
    }

    @Test fun plexWithTokenConnects() {
        assertEquals(DiscoveredServerAction.Connect, plexDiscoveredServerAction("account-token"))
    }

    @Test fun jellyfinOrEmbyWithoutApiKeyStartsLogin() {
        assertEquals(
            DiscoveredServerAction.StartLogin,
            mediaServerDiscoveredServerAction(accessToken = null, userId = "user", authenticated = true),
        )
        assertEquals(
            DiscoveredServerAction.StartLogin,
            mediaServerDiscoveredServerAction(accessToken = "   ", userId = "user", authenticated = true),
        )
    }

    @Test fun jellyfinOrEmbyWithTokenButIncompleteProfileStartsLogin() {
        assertEquals(
            DiscoveredServerAction.StartLogin,
            mediaServerDiscoveredServerAction(accessToken = "key", userId = null, authenticated = true),
        )
        assertEquals(
            DiscoveredServerAction.StartLogin,
            mediaServerDiscoveredServerAction(accessToken = "key", userId = "user", authenticated = false),
        )
    }

    @Test fun jellyfinOrEmbyFullyConfiguredConnects() {
        assertEquals(
            DiscoveredServerAction.Connect,
            mediaServerDiscoveredServerAction(accessToken = "key", userId = "user", authenticated = true),
        )
    }
}

package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOsdIdentityTest {
    @Test fun previousChannelOsdIsNotShownWhileAnotherChannelIsActive() {
        assertFalse(playerOsdIdentityMatches("old-tv-channel", "new-tv-channel"))
        assertFalse(playerOsdIdentityMatches("old-radio-station", "new-radio-station"))
        assertFalse(playerOsdIdentityMatches(null, "new-tv-channel"))
    }

    @Test fun activeSourceAndRegularVideoOsdRemainVisible() {
        assertTrue(playerOsdIdentityMatches("new-radio-station", "new-radio-station"))
        assertTrue(playerOsdIdentityMatches("episode", null))
        assertTrue(playerOsdIdentityMatches(null, null))
    }
}

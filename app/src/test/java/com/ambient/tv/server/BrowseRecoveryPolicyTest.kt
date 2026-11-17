package com.ambient.tv.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseRecoveryPolicyTest {
    @Test fun firstConfirmationKeepsPersistentCacheTtl() {
        assertFalse(shouldRequestBrowseRecovery(ServerConnectionState.NotConfigured, null, "server-a"))
    }

    @Test fun actualOutageAndFailedWakeInvalidateOnRecovery() {
        assertTrue(shouldRequestBrowseRecovery(ServerConnectionState.Offline, null, "server-a"))
        assertTrue(shouldRequestBrowseRecovery(ServerConnectionState.WakeTimedOut, null, "server-a"))
        assertTrue(shouldRequestBrowseRecovery(ServerConnectionState.Error("unreachable"), null, "server-a"))
    }

    @Test fun serverSwitchInvalidatesEvenWhenNoOutageWasReported() {
        assertTrue(shouldRequestBrowseRecovery(ServerConnectionState.NotConfigured, "server-a", "server-b"))
        assertFalse(shouldRequestBrowseRecovery(ServerConnectionState.NotConfigured, "server-a", "server-a"))
    }
}

package com.ambient.tv.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {
    @Test fun permissionRequiresBothRuntimeAndTarget37() {
        assertFalse(requiresLocalNetworkPermission(36, 37))
        assertFalse(requiresLocalNetworkPermission(37, 36))
        assertTrue(requiresLocalNetworkPermission(37, 37))
        assertTrue(requiresLocalNetworkPermission(38, 38))
    }
    @Test fun configuredServiceIntroducesMissingPermission() {
        assertTrue(shouldIntroduceLocalNetworkAccess(true, false, true, false))
    }
    @Test fun freshSetupAndCloudOnlyDoNotShowIntroduction() {
        assertFalse(shouldIntroduceLocalNetworkAccess(true, false, false, false))
    }
    @Test fun oldOsAndGrantedPermissionDoNotShowIntroduction() {
        assertFalse(shouldIntroduceLocalNetworkAccess(false, false, true, false))
        assertFalse(shouldIntroduceLocalNetworkAccess(true, true, true, false))
    }
    @Test fun decliningDoesNotCreateAnIntroductionLoop() {
        assertFalse(shouldIntroduceLocalNetworkAccess(true, false, true, true))
    }

    @Test fun dlnaOnlySetupCountsAsAConfiguredLanService() {
        val preferences = com.ambient.tv.data.local.AppPreferences(com.ambient.tv.data.local.memoryPreferences()).apply {
            plexEnabled = false
            jellyfinEnabled = false
            embyEnabled = false
            tvheadendEnabled = false
            spotifyEnabled = false
            filesEnabled = false
        }

        assertTrue(LocalNetworkAccess.hasConfiguredService(preferences, dlnaConfigured = true))
    }
}

package com.ambient.tv.data.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceReportOrderTest {
    @Test
    fun aFailureStartedBeforeTheLastSuccessIsStale() {
        val order = SourceReportOrder()
        val hung = order.begin()          // request hangs through the wake
        val fresh = order.begin()
        assertTrue(order.accept("plex", fresh, SourceHealth.ONLINE))
        assertFalse(order.accept("plex", hung, SourceHealth.OFFLINE))
        assertTrue(order.accept("plex", order.begin(), SourceHealth.OFFLINE))
    }

    @Test
    fun successesAlwaysApplyAndScopesAreSeparate() {
        val order = SourceReportOrder()
        val old = order.begin()
        assertTrue(order.accept("plex", order.begin(), SourceHealth.ONLINE))
        assertTrue(order.accept("plex", old, SourceHealth.ONLINE))
        assertTrue(order.accept("jellyfin", old, SourceHealth.OFFLINE))
    }

    @Test
    fun anotherReachableNasPortCannotConfirmAFailedServiceRead() {
        val onlineHost = com.ambient.tv.connectivity.HostHealth.ONLINE
        assertTrue(browseSourceUnconfirmed(true, onlineHost, SourceHealth.OFFLINE, false))
        assertTrue(browseSourceUnconfirmed(true, onlineHost, SourceHealth.ONLINE, true))
        assertTrue(browseSourceUnconfirmed(true, onlineHost, SourceHealth.AUTH_REQUIRED, false))
        assertFalse(browseSourceUnconfirmed(true, onlineHost, SourceHealth.ONLINE, false))
    }

    @Test
    fun networkOrHostLossLocksAPreviouslyLoadedViewButUnknownAloneDoesNot() {
        assertTrue(browseSourceUnconfirmed(false, com.ambient.tv.connectivity.HostHealth.ONLINE, SourceHealth.ONLINE, false))
        assertTrue(browseSourceUnconfirmed(true, com.ambient.tv.connectivity.HostHealth.OFFLINE, SourceHealth.ONLINE, false))
        assertFalse(browseSourceUnconfirmed(true, com.ambient.tv.connectivity.HostHealth.UNKNOWN, SourceHealth.UNKNOWN, false))
    }

    @Test
    fun publicIdentityDoesNotRemoveTheSavedViewWarning() {
        assertFalse(browseResponseConfirmsRead("plex", "/identity"))
        assertFalse(browseResponseConfirmsRead("plex", "/"))
        assertFalse(browseResponseConfirmsRead("jellyfin", "/jellyfin/System/Info/Public"))
        assertFalse(browseResponseConfirmsRead("emby", "/emby/System/Info/Public/"))
        assertTrue(browseResponseConfirmsRead("plex", "/library/sections"))
        assertTrue(browseResponseConfirmsRead("jellyfin", "/Users/user/Items"))
        assertTrue(browseResponseConfirmsRead("emby", "/Items/item"))
    }
}

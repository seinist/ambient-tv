package com.ambient.tv.connectivity

import com.ambient.tv.wol.WakeEndpoint
import com.ambient.tv.wol.macsOfGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineWakePolicyTest {
    @Test fun manualWakeDoesNotRequireAutomaticWake() {
        assertTrue(canOfferOfflineWake(HostHealth.OFFLINE, "aa:bb:cc:dd:ee:ff"))
        assertTrue(canOfferOfflineWake(HostHealth.UNKNOWN, "aa-bb-cc-dd-ee-ff"))
        assertFalse(canOfferOfflineWake(HostHealth.ONLINE, "aa:bb:cc:dd:ee:ff"))
        assertFalse(canOfferOfflineWake(HostHealth.OFFLINE, null))
        assertFalse(canOfferOfflineWake(HostHealth.OFFLINE, "not-a-mac"))
    }

    @Test fun tvheadendCanWakeWithPlexMacOnSameMachine() {
        val (_, macs) = macsOfGroup(listOf(
            WakeEndpoint("plex", "192.168.1.10", "AA:BB:CC:DD:EE:FF"),
            WakeEndpoint("jellyfin", "192.168.1.10", null),
            WakeEndpoint("tvheadend", "http://192.168.1.10:9981", null),
        ))
        assertEquals(listOf("aabbccddeeff"), macs)
        assertTrue(canOfferOfflineWake(HostHealth.OFFLINE, macs.firstOrNull()))
    }
}

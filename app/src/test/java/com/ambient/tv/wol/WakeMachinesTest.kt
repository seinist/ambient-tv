package com.ambient.tv.wol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WakeMachinesTest {
    @Test
    fun macSeparatorsAndCaseShareOneKey() {
        assertEquals(wakeGroupKey("AA:BB:CC:DD:EE:FF", null), wakeGroupKey("aa-bb-cc-dd-ee-ff", null))
        assertEquals("mac:aabbccddeeff", wakeGroupKey("aabb.ccdd.eeff", "nas.local"))
    }

    @Test
    fun hostIsTheFallbackWithoutMac() {
        assertEquals("host:192.168.1.10", wakeGroupKey(null, "http://192.168.1.10:32400/"))
        assertEquals(wakeGroupKey("", "NAS.local."), wakeGroupKey("invalid", "nas.local"))
        assertEquals("system:tvheadend", wakeGroupKey(null, " ", "tvheadend"))
    }

    @Test
    fun sameMacOrSameHostMergeTransitively() {
        val keys = resolveWakeGroupKeys(listOf(
            WakeEndpoint("plex", "192.168.1.10", "AA:BB:CC:DD:EE:FF"),
            WakeEndpoint("tvheadend", "http://192.168.1.10:9981", null),
            WakeEndpoint("files", "nas.local", "aa-bb-cc-dd-ee-ff"),
            WakeEndpoint("dlna", "nas.local", null),
            WakeEndpoint("emby", "10.0.0.5", "11:22:33:44:55:66"),
        ))
        assertEquals(List(4) { "mac:aabbccddeeff" }, keys.take(4))
        assertEquals("mac:112233445566", keys[4])
    }

    @Test
    fun hostOnlyMembersShareTheirHostKey() {
        val keys = resolveWakeGroupKeys(listOf(
            WakeEndpoint("jellyfin", "Server.lan", null),
            WakeEndpoint("tvheadend", "server.lan", ""),
            WakeEndpoint("plex", "other.lan", null),
        ))
        assertEquals(keys[0], keys[1])
        assertEquals("host:server.lan", keys[0])
        assertNotEquals(keys[0], keys[2])
    }

    @Test
    fun keyDoesNotDependOnOrder() {
        val a = WakeEndpoint("plex", "h1", "AA:BB:CC:DD:EE:01")
        val b = WakeEndpoint("tvheadend", "h1", "AA:BB:CC:DD:EE:00")
        assertEquals(resolveWakeGroupKeys(listOf(a, b)).first(), resolveWakeGroupKeys(listOf(b, a)).first())
    }

    @Test
    fun cachedIndexResolvesArbitraryTargetsExactlyLikeFullGrouping() {
        val endpoints = listOf(
            WakeEndpoint("plex", "nas.local", "AA:BB:CC:DD:EE:10"),
            WakeEndpoint("tvheadend", "nas.local", null),
            WakeEndpoint("files", "alias.local", "AA:BB:CC:DD:EE:10"),
            WakeEndpoint("jellyfin", "other.local", "AA:BB:CC:DD:EE:20"),
            WakeEndpoint("dlna", "host-only.local", null),
        )
        val index = WakeGroupIndex(endpoints)
        val targets = listOf(
            WakeEndpoint("manual", "https://NAS.local:443/", null),
            WakeEndpoint("manual", "new.local", "aa-bb-cc-dd-ee-10"),
            // Joins two previously distinct groups, preserving the smallest MAC key.
            WakeEndpoint("manual", "other.local", "AA:BB:CC:DD:EE:10"),
            // A new, smaller MAC changes the selected group key.
            WakeEndpoint("manual", "nas.local", "AA:BB:CC:DD:EE:00"),
            WakeEndpoint("manual", "HOST-ONLY.local.", "invalid"),
            WakeEndpoint("manual", "new.local", null),
            WakeEndpoint("manual", null, null),
        )
        for (target in targets) {
            val all = endpoints + target
            assertEquals(resolveWakeGroupKeys(all).last(), index.keyFor(target.system, target.host, target.mac))
            assertEquals(macsOfGroup(all), index.groupFor(target.system, target.host, target.mac))
        }
    }

    @Test
    fun bridgedGroupsKeepRequestedMacFirstAndConfiguredOrderAfterIt() {
        val index = WakeGroupIndex(listOf(
            WakeEndpoint("plex", "first.local", "AA:BB:CC:DD:EE:30"),
            WakeEndpoint("tvheadend", "first.local", "AA:BB:CC:DD:EE:20"),
            WakeEndpoint("files", "second.local", "AA:BB:CC:DD:EE:10"),
        ))
        assertEquals(
            "mac:aabbccddee10" to listOf("aabbccddee10", "aabbccddee30", "aabbccddee20"),
            index.groupFor("manual", "first.local", "AA:BB:CC:DD:EE:10"),
        )
    }
}

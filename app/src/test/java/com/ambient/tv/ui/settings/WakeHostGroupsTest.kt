package com.ambient.tv.ui.settings

import org.junit.Assert.*
import org.junit.Test

class WakeHostGroupsTest {
    private fun server(kind: WakeMemberKind, host: String, mac: String = "", port: Int = 80) =
        WakeMember(kind, kind.linkTarget, "", host, port, mac, autoWake = false)
    private fun file(id: String, host: String, mac: String = "") =
        WakeMember(WakeMemberKind.FILES, id, id, host, 445, mac, autoWake = false, broadcast = "192.168.1.255", wolPort = 7)

    @Test fun servicesOnTheSameHostShareOneGroup() {
        val groups = groupWakeTargets(listOf(
            server(WakeMemberKind.PLEX, "http://192.168.1.10:32400", "aa-bb-cc-dd-ee-f0"),
            server(WakeMemberKind.JELLYFIN, "jf.local"),
            server(WakeMemberKind.TVHEADEND, "192.168.1.10:9981"),
        ))
        assertEquals(2, groups.size)
        assertEquals(listOf(WakeMemberKind.PLEX, WakeMemberKind.TVHEADEND), groups[0].members.map { it.kind })
        assertEquals(listOf("192.168.1.10"), groups[0].hosts)
        assertEquals("AA:BB:CC:DD:EE:F0", groups[0].mac)
        assertFalse(groups[0].macConflict)
    }

    @Test fun hostsAreComparedCaseInsensitivelyAndWithoutTrailingDot() {
        val groups = groupWakeTargets(listOf(server(WakeMemberKind.PLEX, "NAS.local."), file("smb", "nas.local")))
        assertEquals(1, groups.size)
    }

    @Test fun sameMacMergesDifferentAddressesOfOneMachine() {
        val groups = groupWakeTargets(listOf(
            server(WakeMemberKind.PLEX, "nas.local", "02:11:22:33:44:55"),
            file("smb", "192.168.1.20", "02-11-22-33-44-55"),
        ))
        assertEquals(1, groups.size)
        assertEquals(listOf("nas.local", "192.168.1.20"), groups[0].hosts)
    }

    @Test fun conflictingMacsAreReportedAndServerMacWins() {
        val group = groupWakeTargets(listOf(
            server(WakeMemberKind.TVHEADEND, "tv.lan", "02:00:00:00:00:01"),
            file("smb", "tv.lan", "02:00:00:00:00:02"),
        )).single()
        assertTrue(group.macConflict)
        assertEquals("02:00:00:00:00:01", group.mac)
        assertEquals(listOf("smb"), group.membersMissingMac.map { it.id })
    }

    @Test fun nasBroadcastIsUsedWhenItCarriesTheGroupMac() {
        val group = groupWakeTargets(listOf(server(WakeMemberKind.PLEX, "nas"), file("smb", "nas", "02:00:00:00:00:09"))).single()
        assertEquals("192.168.1.255", group.wakeTarget.broadcast)
        assertEquals(7, group.wakeTarget.wolPort)
    }

    @Test fun invalidAndMulticastMacsDoNotCount() {
        assertNull(canonicalWakeMac("01:00:5E:00:00:01"))
        assertNull(canonicalWakeMac("FF:FF:FF:FF:FF:FF"))
        assertNull(canonicalWakeMac("02:00:00"))
        val groups = groupWakeTargets(listOf(server(WakeMemberKind.PLEX, "a", "01:00:5E:00:00:01"), file("smb", "b", "01:00:5E:00:00:01")))
        assertEquals(2, groups.size)
    }

    @Test fun linksTargetMachinesIndependentlyOfSectionAndProfileRows() {
        val groups = groupWakeTargets(listOf(
            server(WakeMemberKind.PLEX, "a"), server(WakeMemberKind.JELLYFIN, "b"), server(WakeMemberKind.TVHEADEND, "a"),
        ))
        assertEquals("wake-group:${groups[0].key}", networkWakeTargetKey(groups, "tvheadend"))
        assertEquals(networkWakeTargetKey(groups, "plex"), networkWakeTargetKey(groups, "tvheadend"))
        assertEquals("wake-group:${groups[1].key}", networkWakeTargetKey(groups, "jellyfin"))
        assertEquals("wake-sources", networkWakeTargetKey(groups, "dlna"))
        assertEquals("wake-sources", networkWakeTargetKey(groups, "files"))
        assertNull(networkWakeTargetKey(groups, "wireguard"))
    }
}

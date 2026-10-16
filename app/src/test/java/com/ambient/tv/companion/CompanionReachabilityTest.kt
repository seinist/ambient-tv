package com.ambient.tv.companion

import com.ambient.tv.R
import com.ambient.tv.data.local.SettingsBackup
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CompanionReachabilityTest {
    private val tv = CompanionPeer("a".repeat(64), "Wohnzimmer", "192.168.1.5", 4000, role = COMPANION_TV)
    private val base = CompanionState(paired = listOf(tv))

    private fun r(state: CompanionState, wakeable: Boolean = false, now: Long = 1_000_000L) =
        companionReachability(tv.fingerprint, state, wakeable, now)

    @Test fun connectedWinsOverEverything() {
        assertEquals(PeerReachability.CONNECTED, r(base.copy(online = true, target = tv)))
        assertEquals(PeerReachability.CONNECTED, r(base.copy(syncPeers = mapOf(tv.fingerprint to emptyList()))))
        assertEquals(PeerReachability.REACHABLE, r(base.copy(online = false, target = tv, devices = listOf(tv))))
    }

    @Test fun discoveredOrRecentlySeenIsReachable() {
        assertEquals(PeerReachability.REACHABLE, r(base.copy(devices = listOf(tv))))
        assertEquals(PeerReachability.REACHABLE, r(base.copy(lastSeen = mapOf(tv.fingerprint to 1_000_000L - 60_000))))
        assertEquals(PeerReachability.UNREACHABLE, r(base.copy(lastSeen = mapOf(tv.fingerprint to 1_000_000L - COMPANION_SEEN_TTL_MS))))
    }

    @Test fun sleepingTvWithWakePathIsWakeableAndActionable() {
        val state = r(base, wakeable = true)
        assertEquals(PeerReachability.WAKEABLE, state)
        assertTrue(state.allowsActions())
    }

    @Test fun searchingAndUnreachableBlockActions() {
        assertEquals(PeerReachability.SEARCHING, r(base.copy(searching = true)))
        assertFalse(PeerReachability.SEARCHING.allowsActions())
        assertEquals(PeerReachability.UNREACHABLE, r(base))
        assertFalse(PeerReachability.UNREACHABLE.allowsActions())
        assertTrue(PeerReachability.CONNECTED.allowsActions()); assertTrue(PeerReachability.REACHABLE.allowsActions())
    }

    @Test fun wakeableNeedsEnabledWakeWithMacOrValidVpn() {
        assertFalse(companionWakeable(null))
        assertFalse(companionWakeable(CompanionConnectionSettings()))
        assertFalse(companionWakeable(CompanionConnectionSettings(mac = "AA:BB:CC:DD:EE:FF")))
        assertTrue(companionWakeable(CompanionConnectionSettings(wake = true, mac = "AA:BB:CC:DD:EE:FF")))
        assertTrue(companionWakeable(CompanionConnectionSettings(wireGuard = true, vpnHost = "10.0.0.5")))
        assertFalse(companionWakeable(CompanionConnectionSettings(wireGuard = true, vpnHost = "")))
    }

    @Test fun displayNamePrefersTxtAndCleansServiceName() {
        assertEquals("Wohnzimmer", companionDisplayName("Ambient TV SHIELD", "Wohnzimmer"))
        assertEquals("SHIELD Android TV", companionDisplayName("Ambient\\032TV\\032SHIELD\\032Android\\032TV", null))
        assertEquals("Küche", companionDisplayName("Ambient TV Küche (2)", ""))
        assertEquals("TV", companionDisplayName("Ambient TV", null))
        assertEquals(40, companionDisplayName("Ambient TV " + "x".repeat(80), null).length)
    }

    @Test fun backupRequestOnlyAcceptedByTelevision() {
        assertFalse(companionCommandAllowed(false, "backup_request"))
        assertTrue(companionCommandAllowed(true, "backup_request"))
    }

    @Test fun timestampHintWarnsOnlyForOlderExports() {
        assertEquals(SyncTimestampHint.OLDER_THAN_LOCAL, syncTimestampHint(100, 200))
        assertEquals(SyncTimestampHint.NONE, syncTimestampHint(300, 200))
        assertEquals(SyncTimestampHint.NONE, syncTimestampHint(100, 0))
        assertEquals(SyncTimestampHint.NONE, syncTimestampHint(0, 200))
    }

    @Test fun tvToPhoneSettingsKeepPhoneHardware() {
        val tvSettings = SettingsBackup(exportedAtEpochMs = 5, uiScale = 0.8f, overscanPercent = 3, subtitlesEnabled = true)
        val phone = SettingsBackup(exportedAtEpochMs = 6, uiScale = 1.3f, overscanPercent = 0, subtitlesEnabled = false)
        val wire = CompanionBackupPolicy.prepare(tvSettings, false, "settings")
        val merged = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(
            CompanionBackupPolicy.json.decodeFromString(wire), phone, "settings", true, false))
        assertEquals(1.3f, merged.uiScale); assertEquals(0, merged.overscanPercent)
        assertTrue(merged.subtitlesEnabled)
    }

    @Test fun lingeringPlaybackErrorNoLongerFailsTransferFrames() {
        assertEquals("", companionReplyError("", "error", "backup_chunk"))
        assertEquals("playback", companionReplyError("", "error", "status"))
        assertEquals("consent_pending", companionReplyError("consent_pending", "error", "backup_begin"))
        assertTrue(companionBackupAckOk("")); assertTrue(companionBackupAckOk("playback"))
        assertFalse(companionBackupAckOk("consent_pending")); assertFalse(companionBackupAckOk("unsupported"))
    }

    @Test fun failureReasonsHaveDistinctMessages() {
        val reasons = listOf(BackupFailure.NOT_CONNECTED, BackupFailure.BUSY, BackupFailure.UNSAVED, BackupFailure.UNSUPPORTED,
            BackupFailure.TOO_LARGE, BackupFailure.TIMEOUT, BackupFailure.RECEIVER_BUSY, BackupFailure.APPLY, BackupFailure.PREPARE)
        assertEquals(reasons.size, reasons.map(::companionBackupFailureMessage).toSet().size)
        assertFalse(reasons.map(::companionBackupFailureMessage).contains(R.string.companion_backup_failed))
    }

    @Test fun transferFramesFitTheWireLimit() {
        val payload = java.util.Base64.getEncoder().encodeToString(ByteArray(4096) { 7 })
        val chunk = CompanionFrame(type = "backup_chunk", transferId = java.util.UUID.randomUUID().toString(), index = 3, payload = payload,
            nonce = "n".repeat(43), sequence = 99, capabilities = listOf("duplex_sync", "settings_sync", "nav_sounds"), syncPort = 45678)
        assertTrue(companionJson.encodeToString(chunk).toByteArray().size < 16_384)
        val reply = CompanionFrame(type = "state", syncReturn = CompanionSyncFrame.from(5, chunk), nonce = "n".repeat(43), sequence = 99)
        assertTrue(companionJson.encodeToString(reply).toByteArray().size < 16_384)
    }
}

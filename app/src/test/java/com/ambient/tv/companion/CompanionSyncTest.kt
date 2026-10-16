package com.ambient.tv.companion

import com.ambient.tv.data.local.*
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.domain.model.*
import com.ambient.tv.ui.sound.NavSoundBank
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CompanionSyncTest {
    private val source = SettingsBackup(exportedAtEpochMs = 1,
        servers = listOf(ServerBackup(ServerProfile("same", "Plex", "plex.test"), "plex-secret")),
        jellyfinServers = listOf(JellyfinServerBackup(JellyfinServerProfile("same", "Jellyfin", "jellyfin.test"), "jf-secret")),
        embyServers = listOf(EmbyServerBackup(EmbyServerProfile("same", "Emby", "emby.test"), "emby-secret")),
        fileSources = listOf(FileSource("nas", "NAS", "smb", host = "nas.test"), FileSource("local", "Local", "local")),
        smbPasswords = mapOf("nas" to "nas-secret", "local" to "local-secret"),
        tvheadendHost = "tvh.test", tvheadendUsername = "tvh-user", tvheadendPassword = "tvh-secret", tmdbApiKey = "tmdb-secret",
        navSoundsEnabled = true, navSoundsVolume = .6f, navSoundBank = NavSoundBank().encode())

    @Test fun selectionSeparatesProvidersWithSameIdAndDropsDeselectedCredentials() {
        val selected = selectSyncAccounts(source, setOf("jellyfin:same", "files:nas"))
        assertTrue(selected.servers.isEmpty()); assertTrue(selected.embyServers.isEmpty())
        assertEquals(source.jellyfinServers, selected.jellyfinServers)
        assertEquals(listOf("nas"), selected.fileSources.map { it.id })
        assertEquals(mapOf("nas" to "nas-secret"), selected.smbPasswords)
        assertEquals("", selected.tvheadendHost); assertEquals("", selected.tvheadendUsername)
        assertNull(selected.tvheadendPassword); assertNull(selected.tmdbApiKey)
        val wire = CompanionBackupPolicy.prepare(selected, true, "accounts")
        assertFalse(wire.contains("plex-secret")); assertFalse(wire.contains("emby-secret"))
        assertFalse(wire.contains("local-secret")); assertFalse(wire.contains("tvh-secret"))
    }

    @Test fun selectNoneSendsNoAccountsAndKeepsReceiverAccounts() {
        val selected = selectSyncAccounts(source, emptySet())
        val wire = CompanionBackupPolicy.prepare(selected, true, "accounts")
        val incoming = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(wire)
        val merged = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(incoming, source, "accounts", true, true))
        assertEquals(source, merged)
    }

    @Test fun choicesExcludeLocalFilesAndSelectAllIncludesEveryPortableAccount() {
        val choices = syncAccountChoices(source)
        assertEquals(setOf("plex:same", "jellyfin:same", "emby:same", "files:nas", "tvheadend", "tmdb"), choices.map { it.key }.toSet())
        val selected = selectSyncAccounts(source, choices.map { it.key }.toSet())
        assertEquals(source.servers, selected.servers); assertEquals(source.tvheadendPassword, selected.tvheadendPassword)
        assertEquals(listOf("nas"), selected.fileSources.map { it.id })
    }

    @Test fun allIncludesSoundsButRetainsLocalIdentityAndOptInHardware() {
        val target = SettingsBackup(exportedAtEpochMs = 2, wireGuardPrivateKey = "private", uiScale = 1.3f)
        val wire = CompanionBackupPolicy.prepare(source.copy(uiScale = .8f), false, "settings")
        assertFalse(wire.contains("plex-secret")); assertFalse(wire.contains("nas-secret"))
        val result = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(
            CompanionBackupPolicy.json.decodeFromString(wire), target, "settings", true, false))
        assertEquals(source.navSoundBank, result.navSoundBank); assertEquals(.6f, result.navSoundsVolume)
        assertTrue(result.navSoundsEnabled); assertEquals(1.3f, result.uiScale); assertEquals("private", result.wireGuardPrivateKey)
    }

    @Test fun legacySettingsDoNotResetSoundDesigns() {
        val result = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(
            SettingsBackup(schemaVersion = 49, exportedAtEpochMs = 0), source, "settings", true, false))
        assertEquals(source.navSoundBank, result.navSoundBank); assertTrue(result.navSoundsEnabled)
    }

    @Test fun phoneReceiverRejectsPlaybackInputAndUnknownCommands() {
        listOf("start", "play", "pause", "stop", "input_claim", "input", "queue_add", "select", "anything").forEach {
            assertFalse(it, companionCommandAllowed(false, it))
        }
        listOf("status", "input_release").forEach {
            assertTrue(it, companionCommandAllowed(false, it))
        }
        assertTrue(companionCommandAllowed(true, "start"))
    }

    @Test fun optionalSyncEndpointDoesNotChangeLegacyFrameDecoding() {
        assertEquals(0, companionJson.decodeFromString<CompanionFrame>("{\"type\":\"status\"}").syncPort)
        val frame = CompanionFrame(type = "status", syncPort = 45678)
        assertEquals(frame, companionJson.decodeFromString<CompanionFrame>(companionJson.encodeToString(frame)))
    }

    @Test fun nasRoutingStaysLocalForEveryImportMode() {
        for (mode in listOf("accounts", "settings")) for (localVpn in listOf(false, true)) {
            val local = FileSource("local", "NAS", "smb", host = "192.168.50.2", share = "media", wireGuard = localVpn)
            val target = SettingsBackup(exportedAtEpochMs = 1, fileSources = listOf(local))
            val incoming = SettingsBackup(exportedAtEpochMs = 2, fileSources = listOf(local.copy(id = "remote", wireGuard = !localVpn),
                local.copy(id = "new", host = "192.168.40.2", wireGuard = true)))
            // Merge also defends against old or crafted senders that include local flags.
            val result = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(incoming, target, mode, true, true))
            assertEquals(localVpn, result.fileSources.first().wireGuard)
            assertEquals("local", result.fileSources.first().id)
            assertFalse(result.fileSources.last().wireGuard)
            val wire = CompanionBackupPolicy.prepare(incoming, true, mode)
            assertFalse(wire.contains("\"wireGuard\""))
        }
    }

    @Test fun changedTvhHostRequiresLocalVpnOptInAgain() {
        val target = source.copy(tvheadendHost = "192.168.50.2", wireGuardTvheadendEnabled = true)
        for (host in listOf("192.168.40.2", "192.168.50.2")) {
            val result = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(
                source.copy(tvheadendHost = host), target, "accounts", true, false))
            assertEquals(host == target.tvheadendHost, result.wireGuardTvheadendEnabled)
        }
    }

    private val streaming = source.copy(
        smartTubeAccounts = listOf(SmartTubeAccountBackup("yt-a", "Anna", "spec-a"), SmartTubeAccountBackup("yt-b", "Ben", "spec-b")),
        spotifyAccount = SpotifyAccountBackup("client", "Spotty", "access-secret", "refresh-secret", 5, "scope"),
        spotifyClientId = "client",
        wireGuardProfile = com.ambient.tv.vpn.WireGuardProfile(address = "10.0.0.2", peerPublicKey = "peer", endpointHost = "vpn.test"),
        wireGuardPrivateKey = "wg-secret", wireGuardPresharedKey = "psk-secret")

    @Test fun streamingAndVpnAccountsAreSelectableAndWireGuardIsOptIn() {
        val choices = syncAccountChoices(streaming)
        assertTrue(choices.map { it.key }.containsAll(listOf("smarttube:yt-a", "smarttube:yt-b", "spotify", WIREGUARD_CHOICE)))
        val defaults = defaultSyncAccounts(choices)
        assertFalse(WIREGUARD_CHOICE in defaults)
        val wire = CompanionBackupPolicy.prepare(selectSyncAccounts(streaming, defaults - "smarttube:yt-b"), true, "accounts")
        assertTrue(wire.contains("spec-a")); assertTrue(wire.contains("refresh-secret"))
        assertFalse(wire.contains("spec-b")); assertFalse(wire.contains("wg-secret")); assertFalse(wire.contains("vpn.test"))
        val withVpn = CompanionBackupPolicy.prepare(selectSyncAccounts(streaming, setOf(WIREGUARD_CHOICE)), true, "accounts")
        assertTrue(withVpn.contains("wg-secret")); assertFalse(withVpn.contains("refresh-secret"))
    }

    @Test fun streamingCredentialsStayHomeWithoutCredentialOption() {
        val wire = CompanionBackupPolicy.prepare(selectSyncAccounts(streaming, syncAccountChoices(streaming).map { it.key }.toSet()), false, "accounts")
        listOf("spec-a", "access-secret", "refresh-secret", "wg-secret", "psk-secret").forEach { assertFalse(it, wire.contains(it)) }
        val target = SettingsBackup(exportedAtEpochMs = 2, smartTubeAccounts = listOf(SmartTubeAccountBackup("yt-a", "Anna", "local-spec")))
        val result = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(
            CompanionBackupPolicy.json.decodeFromString(wire), target, "accounts", true, false))
        // Credential-less entries neither add accounts nor erase the receiver's sign-in.
        assertEquals(target.smartTubeAccounts, result.smartTubeAccounts)
        assertNull(result.spotifyAccount); assertEquals("client", result.spotifyClientId)
        assertEquals("vpn.test", result.wireGuardProfile?.endpointHost); assertNull(result.wireGuardPrivateKey)
    }

    @Test fun mergeAddsStreamingAccountsButKeepsReceiverSignInsUnlessOverwriting() {
        val incoming = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.prepare(
            selectSyncAccounts(streaming, syncAccountChoices(streaming).map { it.key }.toSet()), true, "accounts"))
        val target = SettingsBackup(exportedAtEpochMs = 2, wireGuardEnabled = true, wireGuardPlexEnabled = true,
            smartTubeAccounts = listOf(SmartTubeAccountBackup("yt-a", "Anna", "local-spec")),
            spotifyAccount = SpotifyAccountBackup("other", "Local", null, "local-refresh"), spotifyClientId = "other",
            wireGuardProfile = com.ambient.tv.vpn.WireGuardProfile(endpointHost = "home.test"), wireGuardPrivateKey = "local-wg")
        fun merge(overwrite: Boolean) = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(incoming, target, "accounts", overwrite, false))
        val kept = merge(false)
        assertEquals(listOf("local-spec", "spec-b"), kept.smartTubeAccounts!!.map { it.credential })
        assertEquals("local-refresh", kept.spotifyAccount?.refreshToken); assertEquals("other", kept.spotifyClientId)
        assertEquals("home.test", kept.wireGuardProfile?.endpointHost); assertEquals("local-wg", kept.wireGuardPrivateKey)
        val replaced = merge(true)
        assertEquals(listOf("spec-a", "spec-b"), replaced.smartTubeAccounts!!.map { it.credential })
        assertEquals("refresh-secret", replaced.spotifyAccount?.refreshToken); assertEquals("client", replaced.spotifyClientId)
        assertTrue(replaced.spotifyEnabled)
        assertEquals("vpn.test", replaced.wireGuardProfile?.endpointHost); assertEquals("wg-secret", replaced.wireGuardPrivateKey)
        assertTrue(replaced.wireGuardEnabled); assertTrue(replaced.wireGuardPlexEnabled)
    }

    @Test fun settingsOnlyExcludesEveryAccountAndPreservesReceiverAccountsEvenForUnfilteredInput() {
        val source = streaming.copy(
            iptvSources = listOf(com.ambient.tv.data.iptv.IptvSource("iptv", "IPTV", "https://example.test/secret")),
            openSubtitlesApiKey = "subtitle-key", openSubtitlesUsername = "user", openSubtitlesPassword = "password",
            uiLanguageKey = "de", uiScale = .8f)
        val wire = CompanionBackupPolicy.prepare(source, true, "preferences")
        listOf("servers", "spotifyAccount", "smartTubeAccounts", "wireGuardProfile", "iptvSources", "openSubtitlesApiKey", "tmdbApiKey").forEach {
            assertFalse(it, CompanionBackupPolicy.json.parseToJsonElement(wire).toString().contains("\"$it\""))
        }
        val target = streaming.copy(exportedAtEpochMs = 7, uiLanguageKey = "en", uiScale = 1.3f)
        // Defend the receiving boundary too: a peer cannot smuggle accounts into this mode.
        val merged = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(source, target, "preferences", true, false))
        assertEquals("de", merged.uiLanguageKey)
        assertEquals(1.3f, merged.uiScale)
        assertEquals(target.servers, merged.servers)
        assertEquals(target.spotifyAccount, merged.spotifyAccount)
        assertEquals(target.smartTubeAccounts, merged.smartTubeAccounts)
        assertEquals(target.wireGuardProfile, merged.wireGuardProfile)
        assertEquals(target.iptvSources, merged.iptvSources)
        assertEquals(target.openSubtitlesApiKey, merged.openSubtitlesApiKey)
        assertThrows(IllegalArgumentException::class.java) { CompanionBackupPolicy.merge(source, target, "preferences", false, false) }
    }

    @Test fun deselectedIptvAndSubtitleCredentialsNeverTravelWithOtherAccounts() {
        val source = streaming.copy(
            iptvSources = listOf(com.ambient.tv.data.iptv.IptvSource("iptv", "IPTV", "https://example.test/secret")),
            openSubtitlesApiKey = "subtitle-key", openSubtitlesUsername = "subtitle-user", openSubtitlesPassword = "subtitle-password")
        assertTrue(syncAccountChoices(source).map { it.key }.containsAll(listOf("iptv:iptv", "opensubtitles")))
        val selected = selectSyncAccounts(source, setOf("spotify"))
        assertTrue(selected.iptvSources.orEmpty().isEmpty())
        assertNull(selected.openSubtitlesApiKey)
        val wire = CompanionBackupPolicy.prepare(selected, true, "settings")
        assertFalse(wire.contains("example.test/secret"))
        assertFalse(wire.contains("subtitle-key"))
        assertFalse(wire.contains("subtitle-password"))
        assertTrue(wire.contains("refresh-secret"))
    }

    @Test fun transferUsesConnectedTvAndDoesNotGuessBetweenMultipleOfflineTvs() {
        val first = CompanionPeer(fingerprint = "a".repeat(64), name = "First")
        val second = first.copy(fingerprint = "b".repeat(64), name = "Second")
        val paired = listOf(first, second)
        assertEquals(second.fingerprint, initialSyncTarget(CompanionState(paired = paired, target = second, online = true)))
        assertNull(initialSyncTarget(CompanionState(paired = paired, target = second)))
        assertEquals(first.fingerprint, initialSyncTarget(CompanionState(paired = listOf(first))))
        assertEquals(second.fingerprint, initialSyncTarget(CompanionState(paired = paired, syncPeers = mapOf(second.fingerprint to listOf("settings_sync")))) )
    }

    @Test fun returnChannelIsBoundedSettingsOnlyAndLegacyCompatible() {
        val legacy = companionJson.decodeFromString<CompanionFrame>("{\"type\":\"state\"}")
        assertNull(legacy.syncReturn)
        for (type in listOf("backup_begin", "backup_chunk", "backup_finish", "backup_cancel")) {
            val command = CompanionFrame(type = type, transferId = "id", payload = "payload")
            val response = CompanionFrame(type = "state", syncReturn = CompanionSyncFrame.from(1, command))
            val stream = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(stream).writeFrame(response)
            val decoded = java.io.DataInputStream(java.io.ByteArrayInputStream(stream.toByteArray())).readFrame()
            assertEquals(command, decoded.syncReturn!!.command())
        }
        for (type in listOf("start", "input", "status", "anything")) {
            assertThrows(IllegalArgumentException::class.java) { CompanionSyncFrame.from(1, CompanionFrame(type = type)).command() }
        }
    }
}

package com.ambient.tv.companion

import com.ambient.tv.data.local.*
import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.data.files.FileSource
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CompanionFeaturesTest {
    private val json = CompanionBackupPolicy.json
    private fun decode(value: String) = json.decodeFromString<SettingsBackup>(value)
    private fun rejects(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
    private val target = SettingsBackup(exportedAtEpochMs = 1, uiScale = 1.2f, playbackBackendKey = "mpv", accentColorKey = "blue",
        servers = listOf(ServerBackup(ServerProfile("same-id", "TV NAS", "nas.test"), "tv-token")), activeServerId = "same-id",
        wireGuardPrivateKey = "tv-key", spotifyClientId = "tv-spotify", recentSearches = listOf("private TV search"))

    @Test fun navSoundsTransferIsIsolatedAndRequiresExplicitReplacement() {
        val bank = com.ambient.tv.ui.sound.NavSoundBank().encode()
        val source = target.copy(navSoundsEnabled = true, navSoundsVolume = .65f, navSoundBank = bank,
            accentColorKey = "red", uiScale = .8f)
        val payload = CompanionBackupPolicy.prepare(source, false, "nav_sounds")
        assertEquals(setOf("schemaVersion", "exportedAtEpochMs", "navSoundsEnabled", "navSoundsVolume", "navSoundBank"),
            json.parseToJsonElement(payload).jsonObject.keys)
        rejects { CompanionBackupPolicy.prepare(source, true, "nav_sounds") }
        rejects { CompanionBackupPolicy.merge(decode(payload), target, "nav_sounds", false, false) }
        val merged = decode(CompanionBackupPolicy.merge(decode(payload), target, "nav_sounds", true, true))
        assertEquals(target.copy(navSoundsEnabled = true, navSoundsVolume = .65f, navSoundBank = bank), merged)
        rejects { CompanionBackupPolicy.prepare(source.copy(navSoundBank = "invalid"), false, "nav_sounds") }
        rejects { CompanionBackupPolicy.prepare(source.copy(navSoundsVolume = Float.NaN), false, "nav_sounds") }
    }

    @Test fun accountOnlyPayloadDoesNotSendSettingsHistoryVpnOrUnapprovedCredentials() {
        val payload = CompanionBackupPolicy.prepare(target.copy(tmdbApiKey = "secret", tvheadendPassword = "password"), false, "accounts")
        val fields = json.parseToJsonElement(payload).jsonObject
        assertFalse(fields.containsKey("uiScale")); assertFalse(fields.containsKey("recentSearches")); assertFalse(fields.containsKey("wireGuardPrivateKey"))
        assertFalse(payload.contains("tv-token")); assertFalse(payload.contains("tv-key")); assertFalse(payload.contains("secret"))
        assertNull(decode(payload).servers.single().token)
    }

    @Test fun accountsAreAddedWithoutReplacingExistingCredentialsOrDeviceSettings() {
        val source = SettingsBackup(exportedAtEpochMs = 2, accentColorKey = "red", uiScale = 0.8f,
            servers = listOf(ServerBackup(ServerProfile("phone-id", "Renamed", "nas.test"), "phone-token"),
                ServerBackup(ServerProfile("same-id", "Different host", "other.test"), null)))
        val merged = decode(CompanionBackupPolicy.merge(source, target, "accounts", false, false))
        assertEquals(2, merged.servers.size)
        assertEquals(target.servers.single(), merged.servers.first())
        assertNotEquals("same-id", merged.servers.last().profile.id)
        assertNull(merged.servers.last().token)
        assertEquals(target.uiScale, merged.uiScale)
        assertEquals(target.accentColorKey, merged.accentColorKey)
        assertEquals(target.activeServerId, merged.activeServerId)
    }

    @Test fun freshReceiverAdoptsSendersActiveServersAndEnabledSystems() {
        val source = SettingsBackup(exportedAtEpochMs = 2, plexEnabled = true, jellyfinEnabled = true, youtubeEnabled = true,
            servers = listOf(ServerBackup(ServerProfile("a", "A", "a.test"), "ta"), ServerBackup(ServerProfile("b", "B", "b.test"), "tb")),
            activeServerId = "b",
            jellyfinServers = listOf(JellyfinServerBackup(JellyfinServerProfile("j", "JF", "jf.test", username = "u", userId = "1", authenticated = true), "tj")),
            activeJellyfinServerId = "j")
        val payload = decode(CompanionBackupPolicy.prepare(source, true, "accounts"))
        val merged = decode(CompanionBackupPolicy.merge(payload, SettingsBackup(exportedAtEpochMs = 1), "accounts", false, false))
        assertEquals("b", merged.activeServerId)
        assertEquals("j", merged.activeJellyfinServerId)
        assertTrue(merged.plexEnabled && merged.jellyfinEnabled && merged.youtubeEnabled)
        // A receiver with its own valid selection keeps it.
        assertEquals("same-id", decode(CompanionBackupPolicy.merge(payload, target, "accounts", true, false)).activeServerId)
    }

    @Test fun explicitOverwritePreservesMissingCredentialsOnlyForSameEndpointAndAccount() {
        val source = target.copy(servers = listOf(ServerBackup(target.servers.single().profile.copy(id = "phone", name = "New name"))))
        val merged = decode(CompanionBackupPolicy.merge(source, target, "accounts", true, false))
        assertEquals("New name", merged.servers.single().profile.name)
        assertEquals("same-id", merged.servers.single().profile.id)
        assertEquals("tv-token", merged.servers.single().token)
        val changed = source.copy(servers = listOf(ServerBackup(source.servers.single().profile.copy(useHttps = true))))
        val separate = decode(CompanionBackupPolicy.merge(changed, target, "accounts", true, false))
        assertEquals(2, separate.servers.size); assertNull(separate.servers.last().token)
    }

    @Test fun sameServerDifferentUsersNeverInheritEachOthersTokens() {
        val existing = JellyfinServerBackup(JellyfinServerProfile("one", "TV", "jf.test", username = "alice", userId = "a"), "alice-secret")
        val incoming = JellyfinServerBackup(existing.profile.copy(username = "bob", userId = "b"), null)
        val merged = decode(CompanionBackupPolicy.merge(SettingsBackup(exportedAtEpochMs = 0, jellyfinServers = listOf(incoming)),
            target.copy(jellyfinServers = listOf(existing)), "accounts", true, false))
        assertEquals(2, merged.jellyfinServers.size)
        assertNull(merged.jellyfinServers.last().token)
    }

    @Test fun settingsRequireConfirmationAndHardwareIsOptInWhileVpnRemainsLocal() {
        val source = target.copy(uiScale = 0.75f, accentColorKey = "red", playbackBackendKey = "vlc", wireGuardPrivateKey = "phone-key", spotifyClientId = "phone-spotify")
        rejects { CompanionBackupPolicy.merge(source, target, "settings", false, true) }
        val safe = decode(CompanionBackupPolicy.merge(source, target, "settings", true, false))
        assertEquals("red", safe.accentColorKey); assertEquals(1.2f, safe.uiScale); assertEquals("mpv", safe.playbackBackendKey)
        // A key without a selected WireGuard profile never replaces the receiver's tunnel.
        assertEquals("tv-key", safe.wireGuardPrivateKey)
        // Spotify is a syncable account: a confirmed overwrite adopts the client of a signed-out receiver.
        assertEquals("phone-spotify", safe.spotifyClientId)
        val hardware = decode(CompanionBackupPolicy.merge(source, target, "settings", true, true))
        assertEquals(0.75f, hardware.uiScale); assertEquals("vlc", hardware.playbackBackendKey)
    }

    @Test fun smbPasswordsFollowRemappedSourceAndLocalTreesAreNotSent() {
        val old = FileSource("nas-id", "NAS", "smb", host = "nas.test", share = "media", username = "a")
        val new = old.copy(host = "other.test")
        val t = target.copy(fileSources = listOf(old), smbPasswords = mapOf("nas-id" to "old-secret"))
        val s = target.copy(fileSources = listOf(new), smbPasswords = mapOf("nas-id" to "new-secret"))
        val merged = decode(CompanionBackupPolicy.merge(s, t, "accounts", false, false))
        assertEquals("old-secret", merged.smbPasswords["nas-id"])
        assertEquals("new-secret", merged.smbPasswords[merged.fileSources.last().id])
        val local = old.copy(kind = "local", treeUri = "content://test/tree/one")
        assertTrue(decode(CompanionBackupPolicy.prepare(s.copy(fileSources = listOf(local)), true)).fileSources.isEmpty())
    }

    @Test fun previewHasNoWritesAndInvalidBackupCannotStartAnImport() {
        val preferences = AppPreferences(memoryPreferences())
        val store = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
        val before = store.exportJson()
        store.previewJson("""{"schemaVersion":48,"exportedAtEpochMs":0,"accentColorKey":"red"}""")
        assertEquals("gold", preferences.accentColorKey)
        rejects { store.previewJson("""{"schemaVersion":999,"exportedAtEpochMs":0}""") }
        assertEquals(decode(before).accentColorKey, preferences.accentColorKey)
    }

    @Test fun transfersRequireExactSizeHashOrderedChunksAndKnownMode() {
        val id = "00000000-0000-0000-0000-000000000001"
        val bytes = "example settings".toByteArray()
        val transfer = CompanionTransfer(id, bytes.size, fingerprint(bytes), "accounts", false)
        rejects { transfer.finish() }
        rejects { transfer.append(1, Base64.getEncoder().encodeToString(bytes)) }
        transfer.append(0, Base64.getEncoder().encodeToString(bytes))
        assertEquals("example settings", transfer.finish())
        rejects { transfer.append(0, "YQ==") }
        rejects { CompanionTransfer(id, CompanionBackupPolicy.MAX_BYTES + 1, fingerprint(bytes), "accounts", false) }
        rejects { CompanionTransfer(id, 1, fingerprint(bytes), "unknown", false) }
        val wrong = CompanionTransfer(id, bytes.size, "00".repeat(32), "settings", true)
        wrong.append(0, Base64.getEncoder().encodeToString(bytes)); rejects { wrong.finish() }
    }

    @Test fun controllerLeaseIsExclusiveRevocableAndExpires() {
        val lease = CompanionInputLease()
        assertTrue(lease.claim("a", "Phone", 0))
        assertFalse(lease.claim("b", "Phone", 10))
        assertFalse(lease.release("b")); assertTrue(lease.owns("a", 20))
        assertTrue(lease.release("a")); assertTrue(lease.claim("b", "Phone", 30))
        assertFalse(lease.owns("b", 60_031)); assertTrue(lease.claim("a", "Phone", 60_032))
    }

    @Test fun remoteInputIsBoundedAndCannotInjectSystemKeysOrInvalidCoordinates() {
        CompanionInput("text", text = "Grüße 👋 日本語").validate()
        rejects { CompanionInput("key", key = "power").validate() }
        rejects { CompanionInput("key", key = "home").validate() }
        rejects { CompanionInput("move", x = Float.NaN).validate() }
        rejects { CompanionInput("move", y = 2f).validate() }
        rejects { CompanionInput("text", text = "x".repeat(2049)).validate() }
        rejects { CompanionInput("text", text = "a\u0000b").validate() }
    }
}

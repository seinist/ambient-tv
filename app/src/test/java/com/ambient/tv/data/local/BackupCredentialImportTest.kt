package com.ambient.tv.data.local

import com.ambient.tv.companion.CompanionBackupPolicy
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.vpn.WireGuardProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BackupCredentialImportTest {
    private fun store(): Triple<AppPreferences, TokenStore, SettingsBackupManager> {
        val prefs = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), prefs)
        return Triple(prefs, tokens, SettingsBackupManager(prefs, tokens))
    }

    private val plex = ServerProfile(id = "plex-1", name = "Plex", host = "192.168.1.10")
    private val jellyfin = JellyfinServerProfile(id = "jf-1", name = "JF", host = "192.168.1.11", userId = "u1", username = "me", authenticated = true)
    private val wg = WireGuardProfile(address = "10.0.0.2", peerPublicKey = "A".repeat(43) + "=", endpointHost = "vpn.example.org", allowedIps = listOf("0.0.0.0/0"))

    private fun signedIn(): Triple<AppPreferences, TokenStore, SettingsBackupManager> = store().also { (prefs, tokens, _) ->
        prefs.replaceServerProfiles(listOf(plex), plex.id)
        tokens.setTokenForServer(plex.id, "plex-token")
        prefs.replaceJellyfinServerProfiles(listOf(jellyfin), jellyfin.id)
        tokens.setJellyfinTokenForServer(jellyfin.id, "jf-token")
        prefs.tvheadendHost = "tvh.lan"
        prefs.tvheadendUsername = "tv"
        tokens.tvheadendPassword = "tvh-pass"
        tokens.tmdbApiKey = "tmdb"
        prefs.setWireGuardProfile(wg)
        tokens.wireGuardPrivateKey = "priv"
        tokens.wireGuardPresharedKey = "psk"
    }

    @Test fun importWithoutCredentialsKeepsLocalLogins() {
        val (prefs, tokens, manager) = signedIn()
        val raw = manager.exportJson(includeCredentials = false)
        manager.importJson(raw)
        assertEquals("plex-token", tokens.tokenForServer(plex.id))
        assertEquals("jf-token", tokens.jellyfinTokenForServer(jellyfin.id))
        assertTrue(prefs.jellyfinServerProfiles.single().authenticated)
        assertEquals("u1", prefs.jellyfinServerProfiles.single().userId)
        assertEquals("tvh-pass", tokens.tvheadendPassword)
        assertEquals("tmdb", tokens.tmdbApiKey)
        assertEquals("priv", tokens.wireGuardPrivateKey)
        assertEquals("psk", tokens.wireGuardPresharedKey)
    }

    @Test fun importWithCredentialsReplacesThem() {
        val (sourcePrefs, sourceTokens, exporter) = signedIn()
        sourceTokens.setTokenForServer(plex.id, "new-token")
        sourceTokens.tvheadendPassword = "new-pass"
        val raw = exporter.exportJson(includeCredentials = true)
        val (_, tokens, manager) = signedIn()
        manager.importJson(raw)
        assertEquals("new-token", tokens.tokenForServer(plex.id))
        assertEquals("new-pass", tokens.tvheadendPassword)
        assertNotNull(sourcePrefs)
    }

    @Test fun credentialsAreDroppedWhenTheServerAddressChanges() {
        val (_, tokens, manager) = signedIn()
        val raw = """{"schemaVersion":57,"exportedAtEpochMs":0,
            "servers":[{"profile":{"id":"plex-1","name":"Plex","host":"203.0.113.9"}}],
            "jellyfinServers":[{"profile":{"id":"jf-1","name":"JF","host":"203.0.113.9","authenticated":true,"userId":"u1"}}],
            "tvheadendHost":"other.example","tvheadendUsername":"tv"}"""
        manager.importJson(raw)
        assertNull(tokens.tokenForServer(plex.id))
        assertNull(tokens.jellyfinTokenForServer(jellyfin.id))
        assertNull(tokens.tvheadendPassword)
    }

    @Test fun wireGuardKeysFollowTheProfile() {
        val (prefs, tokens, manager) = signedIn()
        val other = wg.copy(endpointHost = "other.example.org")
        val raw = Json.parseToJsonElement(manager.exportJson(includeCredentials = false)).jsonObject.toMutableMap().apply {
            put("wireGuardProfile", Json.parseToJsonElement(Json.encodeToString(WireGuardProfile.serializer(), other)))
        }.let { kotlinx.serialization.json.JsonObject(it).toString() }
        manager.importJson(raw)
        assertEquals("other.example.org", prefs.wireGuardProfile()?.endpointHost)
        assertNull(tokens.wireGuardPrivateKey)
        assertNull(tokens.wireGuardPresharedKey)
    }

    @Test fun deviceSpecificValuesAreNotImported() {
        val (source, _, exporter) = store()
        source.svpAvailable = true
        source.displayModeWhitelist = setOf("3840x2160@59.940")
        source.preferredDisplayModeKey = "3840x2160@59.940"
        val (target, _, importer) = store()
        importer.importJson(exporter.exportJson())
        assertFalse(target.svpAvailable)
        assertTrue(target.displayModeWhitelist.isEmpty())
        assertEquals("system", target.preferredDisplayModeKey)
        source.preferredDisplayModeKey = AppPreferences.DISPLAY_MODE_AUTO_DISPLAY
        importer.importJson(exporter.exportJson())
        assertEquals(AppPreferences.DISPLAY_MODE_AUTO_DISPLAY, target.preferredDisplayModeKey)
    }

    @Test fun preferredAudioLanguageRoundTrips() {
        val (source, _, exporter) = store()
        source.audioLanguageKey = "de"
        val (target, _, importer) = store()
        importer.importJson(exporter.exportJson())
        assertEquals("de", target.preferredAudioLanguage)
    }

    @Test fun failedImportRestoresPreviousState() {
        var armed = false
        val base = memoryPreferences()
        // Fails the first write of the TVHeadend host, i.e. midway through applying a backup.
        val trap = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader, arrayOf(android.content.SharedPreferences::class.java),
        ) { _, method, args ->
            if (method.name != "edit") return@newProxyInstance method.invoke(base, *(args ?: emptyArray()))
            val editor = base.edit()
            java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences.Editor::class.java.classLoader, arrayOf(android.content.SharedPreferences.Editor::class.java),
            ) { proxy, op, inputs ->
                if (armed && op.name == "putString" && inputs!![0] == AppPreferences.KEY_TVH_HOST) { armed = false; error("disk full") }
                val result = op.invoke(editor, *(inputs ?: emptyArray()))
                if (result === editor) proxy else result
            }
        } as android.content.SharedPreferences
        val prefs = AppPreferences(trap)
        val tokens = TokenStore(memoryPreferences(), prefs)
        val manager = SettingsBackupManager(prefs, tokens)
        prefs.replaceServerProfiles(listOf(plex), plex.id)
        tokens.setTokenForServer(plex.id, "plex-token")
        prefs.tvheadendHost = "tvh.lan"
        prefs.uiScale = 0.8f
        armed = true
        val result = runCatching {
            manager.importJson("""{"schemaVersion":57,"exportedAtEpochMs":0,"uiScale":1.0,"tvheadendHost":"other.lan"}""")
        }
        assertTrue(result.isFailure)
        assertEquals(0.8f, prefs.uiScale, 0.0001f)
        assertEquals(listOf(plex.id), prefs.serverProfiles.map { it.id })
        assertEquals("plex-token", tokens.tokenForServer(plex.id))
        assertEquals("tvh.lan", prefs.tvheadendHost)
    }

    @Test fun companionMergeClearsKeysForNewWireGuardPeerAndSkipsBadEntries() {
        val (_, _, sourceManager) = signedIn()
        val (_, _, targetManager) = signedIn()
        val json = Json { ignoreUnknownKeys = true }
        val source = json.decodeFromString(SettingsBackup.serializer(), sourceManager.exportJson(false)).copy(
            wireGuardProfile = wg.copy(endpointHost = "new.example.org"),
            servers = listOf(ServerBackup(plex.copy(id = "", host = "10.1.1.1")), ServerBackup(plex.copy(id = "p2", host = "10.1.1.2"))),
        )
        val target = json.decodeFromString(SettingsBackup.serializer(), targetManager.exportJson(true))
        val merged = Json.parseToJsonElement(CompanionBackupPolicy.merge(source, target, "accounts", overwrite = true, deviceSettings = false)).jsonObject
        assertEquals(JsonNull, merged["wireGuardPrivateKey"])
        assertEquals(JsonNull, merged["wireGuardPresharedKey"])
        val hosts = merged.getValue("servers").let { it as kotlinx.serialization.json.JsonArray }.map {
            it.jsonObject.getValue("profile").jsonObject.getValue("host").jsonPrimitive.content
        }
        assertTrue("10.1.1.2" in hosts)
        assertFalse("10.1.1.1" in hosts)
    }

    @Test fun companionMergeKeepsTvheadendPasswordForSameHostInDifferentCase() {
        val (_, _, sourceManager) = signedIn()
        val (_, _, targetManager) = signedIn()
        val json = Json { ignoreUnknownKeys = true }
        val source = json.decodeFromString(SettingsBackup.serializer(), sourceManager.exportJson(false)).copy(tvheadendHost = "TVH.lan")
        val target = json.decodeFromString(SettingsBackup.serializer(), targetManager.exportJson(true))
        val merged = Json.parseToJsonElement(CompanionBackupPolicy.merge(source, target, "accounts", overwrite = true, deviceSettings = false)).jsonObject
        assertEquals("tvh-pass", merged.getValue("tvheadendPassword").jsonPrimitive.content)
    }
    @Test fun monogramBackupRestoresSourceSettingsButNeverUserSessions() {
        val (source, keys, exporter) = store()
        val hash = "0123456789abcdef0123456789abcdef"
        keys.setMonogramCredentials(12345678, hash)
        source.telegramEnabled = true
        source.telegramSubmenuKeys = listOf("channels", "youtube", "audio")
        source.homeRows = listOf(HomeRowDefinition(
            system = HomeRowDefinition.SYSTEM_TELEGRAM, contentKey = "youtube"))
        source.setSectionViewMode("monogram.recent", "ROWS")
        source.setSectionViewMode("monogram.youtube", "INFO_LIST")

        val normal = exporter.exportJson(includeCredentials = false)
        assertEquals(JsonNull, Json.parseToJsonElement(normal).jsonObject["monogramApiCredentials"])
        // The backup must never contain native session material or Keystore state.
        assertFalse(normal.contains("account.session"))
        assertFalse(normal.contains("phoneCodeHash"))
        assertFalse(normal.contains(hash))
        val (_, preservedKeys, ordinaryImporter) = store()
        preservedKeys.setMonogramCredentials(2, "a".repeat(32))
        ordinaryImporter.importJson(normal)
        assertEquals(2 to "a".repeat(32), preservedKeys.monogramCredentials())

        val full = exporter.exportJson(includeCredentials = true)
        val fullJson = Json.parseToJsonElement(full).jsonObject
        assertEquals("12345678",
            fullJson.getValue("monogramApiCredentials").jsonObject.getValue("apiId").toString())
        assertFalse(full.contains("account.session"))
        val (restored, restoredKeys, importer) = store()
        importer.importJson(full)
        assertEquals(12345678 to hash, restoredKeys.monogramCredentials())
        assertTrue(restored.telegramEnabled)
        assertEquals(source.telegramSubmenuKeys, restored.telegramSubmenuKeys)
        assertEquals(source.homeRows, restored.homeRows)
        assertEquals("ROWS", restored.sectionViewMode("monogram.recent"))
        assertEquals("INFO_LIST", restored.sectionViewMode("monogram.youtube"))
    }

    @Test fun monogramBackupRejectsMalformedCredentialsBeforeApplyingOtherSettings() {
        val (prefs, keys, importer) = store()
        prefs.telegramEnabled = false
        val bad = """{"schemaVersion":76,"exportedAtEpochMs":0,"telegramEnabled":true,
            "monogramApiCredentials":{"apiId":123,"apiHash":"not-valid"}}"""
        assertTrue(runCatching { importer.importJson(bad) }.isFailure)
        assertNull(keys.monogramCredentials())
        assertFalse(prefs.telegramEnabled)
    }

    @Test fun monogramBackupCannotSilentlyReplaceAnotherApplicationsCredentials() {
        val (prefs, keys, importer) = store()
        keys.setMonogramCredentials(111, "a".repeat(32))
        val backup = """{"schemaVersion":76,"exportedAtEpochMs":0,"telegramEnabled":true,
            "monogramApiCredentials":{"apiId":222,"apiHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}"""
        assertTrue(runCatching { importer.importJson(backup) }.isFailure)
        assertEquals(111 to "a".repeat(32), keys.monogramCredentials())
        assertFalse(prefs.telegramEnabled)
    }

}

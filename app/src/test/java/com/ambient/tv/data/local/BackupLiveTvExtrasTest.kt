package com.ambient.tv.data.local

import com.ambient.tv.companion.CompanionBackupPolicy
import com.ambient.tv.data.iptv.IptvSource
import com.ambient.tv.data.iptv.IptvStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BackupLiveTvExtrasTest {
    private fun store(): Triple<AppPreferences, TokenStore, SettingsBackupManager> {
        val prefs = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), prefs)
        return Triple(prefs, tokens, SettingsBackupManager(prefs, tokens))
    }

    private val playlist = IptvSource(id = "p1", name = "Provider", playlistUrl = "http://iptv.example/get.php?username=me&password=secret-pw")

    private fun configured() = store().also { (prefs, tokens, _) ->
        IptvStore(tokens).save(playlist)
        tokens.openSubtitlesApiKey = "os-key"
        tokens.openSubtitlesUsername = "os-user"
        tokens.openSubtitlesPassword = "os-pass"
        prefs.liveTimeshiftMb = 2048
    }

    @Test fun credentialBackupRestoresPlaylistsAndOpenSubtitles() {
        val (_, _, manager) = configured()
        val (target, targetTokens, targetManager) = store()
        targetManager.importJson(manager.exportJson(includeCredentials = true))
        assertEquals(listOf(playlist), IptvStore(targetTokens).sources())
        assertEquals("os-key", targetTokens.openSubtitlesApiKey)
        assertEquals("os-user", targetTokens.openSubtitlesUsername)
        assertEquals("os-pass", targetTokens.openSubtitlesPassword)
        assertEquals(2048, target.liveTimeshiftMb)
    }

    @Test fun backupWithoutCredentialsLeavesThemOutAndKeepsTheReceivers() {
        val (_, _, manager) = configured()
        val public = manager.exportJson()
        assertFalse(public.contains("secret-pw")); assertFalse(public.contains("os-key")); assertFalse(public.contains("os-pass"))
        val (target, targetTokens, targetManager) = store()
        val own = IptvSource(id = "own", name = "Own", playlistUrl = "https://own.example/list.m3u")
        IptvStore(targetTokens).save(own); targetTokens.openSubtitlesApiKey = "own-key"
        targetManager.importJson(public)
        assertEquals(listOf(own), IptvStore(targetTokens).sources())
        assertEquals("own-key", targetTokens.openSubtitlesApiKey)
        assertEquals(2048, target.liveTimeshiftMb)
    }

    @Test fun olderBackupDoesNotTouchNewFields() {
        val (prefs, tokens, manager) = configured()
        manager.importJson("""{"schemaVersion":57,"exportedAtEpochMs":0}""")
        assertEquals(listOf(playlist), IptvStore(tokens).sources())
        assertEquals("os-key", tokens.openSubtitlesApiKey)
        assertEquals(2048, prefs.liveTimeshiftMb)
    }

    @Test fun invalidPlaylistRejectsTheBackup() {
        val (_, tokens, manager) = configured()
        assertThrows(IllegalArgumentException::class.java) {
            manager.importJson("""{"schemaVersion":58,"exportedAtEpochMs":0,"iptvSources":[{"id":"x","name":"x","playlistUrl":"file:///etc/passwd"}]}""")
        }
        assertEquals(listOf(playlist), IptvStore(tokens).sources())
    }

    @Test fun companionStripsSecretsAndMergesPlaylists() {
        val (_, _, manager) = configured()
        val backup = manager.previewJson(manager.exportJson(includeCredentials = true))
        val plain = Json.parseToJsonElement(CompanionBackupPolicy.prepare(backup, credentials = false)).jsonObject
        assertEquals(JsonNull, plain["iptvSources"]); assertEquals(JsonNull, plain["openSubtitlesApiKey"]); assertEquals(JsonNull, plain["openSubtitlesPassword"])

        val (_, receiverTokens, receiver) = store()
        val own = IptvSource(id = "own", name = "Own", playlistUrl = "https://own.example/list.m3u")
        IptvStore(receiverTokens).save(own)
        val merged = Json.parseToJsonElement(CompanionBackupPolicy.merge(
            backup, receiver.previewJson(receiver.exportJson(includeCredentials = true)), "accounts", overwrite = false, deviceSettings = false,
        )).jsonObject
        assertEquals(setOf("own", "p1"), merged.getValue("iptvSources").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        assertEquals("os-key", merged.getValue("openSubtitlesApiKey").jsonPrimitive.content)
    }
}

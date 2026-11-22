package com.ambient.tv.data.local

import com.ambient.tv.companion.CompanionBackupPolicy
import com.ambient.tv.companion.GAME_METADATA_CHOICE
import com.ambient.tv.companion.selectSyncAccounts
import com.ambient.tv.companion.syncAccountChoices
import com.ambient.tv.games.metadata.GameMetadataCredentials
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.library.GamePortableState
import com.ambient.tv.games.metadata.GameMetadataOptions
import com.ambient.tv.games.metadata.GameMetadataProviderIds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GameMetadataBackupTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val keys = GameMetadataCredentials(screenScraperDevId = "test-app", screenScraperDevPassword = "dev-secret",
        screenScraperUser = "user", screenScraperPassword = "user-secret", theGamesDbApiKey = "tgdb-secret")
    private val options = GameMetadataOptions(GameMetadataProviderIds.SCREENSCRAPER, automatic = false, region = "jp", language = "de")
    private fun store(): Triple<AppPreferences, TokenStore, SettingsBackupManager> {
        val preferences = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), preferences)
        return Triple(preferences, tokens, SettingsBackupManager(preferences, tokens))
    }
    private fun backup(credentials: GameMetadataCredentials? = keys) = SettingsBackup(schemaVersion = 84, exportedAtEpochMs = 1,
        extras = SettingsBackupExtras(portable = PortableSettingsExtras(gameMetadataOptions = options),
            credentials = CredentialSettingsExtras(gameMetadata = credentials)))

    @Test fun ordinaryExportsCarryOptionsButOmitAllGameProviderCredentials() {
        val (preferences, tokens, manager) = store()
        preferences.gameMetadataOptions = options
        tokens.setGameMetadataCredentials(keys)
        val ordinary = manager.exportJson(false)
        assertEquals(options, json.decodeFromString<SettingsBackup>(ordinary).extras?.portable?.gameMetadataOptions)
        assertNull(json.decodeFromString<SettingsBackup>(ordinary).extras?.credentials)
        for (value in listOf("dev-secret", "user-secret", "tgdb-secret", "test-app")) assertFalse(ordinary.contains(value))
        assertEquals(keys, json.decodeFromString<SettingsBackup>(manager.exportJson(true)).extras?.credentials?.gameMetadata)
    }

    @Test fun explicitCredentialExportRestoresKeysAndOptions() {
        val (_, _, exporter) = store().also { (preferences, tokens, _) ->
            preferences.gameMetadataOptions = options; tokens.setGameMetadataCredentials(keys)
        }
        val (target, tokens, importer) = store()
        importer.importJson(exporter.exportJson(true))
        assertEquals(options, target.gameMetadataOptions)
        assertEquals(keys, tokens.gameMetadataCredentials())
    }

    @Test fun oldAndCredentialFreeBackupsPreserveReceiverCredentials() {
        val (preferences, tokens, importer) = store()
        preferences.gameMetadataOptions = options
        tokens.setGameMetadataCredentials(keys)
        importer.importJson("""{"schemaVersion":83,"exportedAtEpochMs":0}""")
        assertEquals(options, preferences.gameMetadataOptions)
        assertEquals(keys, tokens.gameMetadataCredentials())
        importer.importJson(Json.encodeToString(backup(credentials = null)))
        assertEquals(keys, tokens.gameMetadataCredentials())
    }

    @Test fun incompleteImportedPairCannotPartiallyChangeSettingsOrSecrets() {
        val (preferences, tokens, manager) = store()
        preferences.gameMetadataOptions = options
        tokens.setGameMetadataCredentials(keys)
        val invalid = backup(GameMetadataCredentials(screenScraperDevId = "incomplete")).copy(
            extras = SettingsBackupExtras(portable = PortableSettingsExtras(gameMetadataOptions = GameMetadataOptions(provider = "none")),
                credentials = CredentialSettingsExtras(gameMetadata = GameMetadataCredentials(screenScraperDevId = "incomplete"))))
        assertTrue(runCatching { manager.importJson(Json.encodeToString(invalid)) }.isFailure)
        assertEquals(options, preferences.gameMetadataOptions)
        assertEquals(keys, tokens.gameMetadataCredentials())
    }

    @Test fun companionCredentialsRequireConsentAndAccountSelection() {
        for (mode in listOf("accounts", "settings", "preferences")) {
            val omitted = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.prepare(backup(), false, mode))
            assertNull(omitted.extras?.credentials?.gameMetadata)
            val included = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.prepare(backup(), true, mode))
            assertEquals(if (mode == "preferences") null else keys, included.extras?.credentials?.gameMetadata)
            assertEquals(if (mode == "accounts") null else options, included.extras?.portable?.gameMetadataOptions)
        }
        assertTrue(syncAccountChoices(backup()).any { it.key == GAME_METADATA_CHOICE })
        assertNull(selectSyncAccounts(backup(), emptySet()).extras?.credentials?.gameMetadata)
        assertEquals(keys, selectSyncAccounts(backup(), setOf(GAME_METADATA_CHOICE)).extras?.credentials?.gameMetadata)
    }

    @Test fun companionPreservesConfiguredProvidersUnlessOverwriteWasChosen() {
        val existing = GameMetadataCredentials(theGamesDbApiKey = "receiver-key")
        fun merge(overwrite: Boolean, mode: String = "accounts") = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(backup(), backup(existing), mode, overwrite, deviceSettings = false))
        assertEquals(keys.copy(theGamesDbApiKey = "receiver-key"), merge(false).extras?.credentials?.gameMetadata)
        assertEquals(keys, merge(true).extras?.credentials?.gameMetadata)
        assertEquals(existing, merge(true, "preferences").extras?.credentials?.gameMetadata)
        val partial = json.decodeFromString<SettingsBackup>(CompanionBackupPolicy.merge(backup(GameMetadataCredentials()),
            backup(existing), "accounts", overwrite = true, deviceSettings = false))
        assertEquals(existing, partial.extras?.credentials?.gameMetadata)
    }

    @Test fun portableMetadataUrlsAreScrubbedBeforeExportAndBeforeWritingPreferences() {
        val plain = memoryPreferences()
        val preferences = AppPreferences(plain)
        val tokens = TokenStore(memoryPreferences(), preferences)
        val manager = SettingsBackupManager(preferences, tokens)
        val rows = listOf(GamePortableState("nas", "game.gb", metadata = GameMetadata(
            coverUrl = "https://api.screenscraper.fr/image?jeuid=42&DEV%50ASSWORD=url-secret",
            sourceUrl = "https://user:url-secret@example.org/source")))
        // Simulate an old/imported value already present before the catalog is opened.
        plain.edit().putString("game_library_overrides", Json.encodeToString(rows)).apply()
        assertEquals("https://api.screenscraper.fr/image?jeuid=42", preferences.gameLibraryOverrides.single().metadata?.coverUrl)
        assertFalse(manager.exportJson(false).contains("url-secret"))
        preferences.gameLibraryOverrides = rows
        assertFalse(plain.getString("game_library_overrides", "").orEmpty().contains("url-secret"))
    }
}

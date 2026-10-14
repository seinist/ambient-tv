package com.ambient.tv.data.files

import com.ambient.tv.data.local.*
import org.junit.Assert.*
import org.junit.Test

class FileStoreTest {
    private val source = FileSource(id = "nas-1", name = "NAS", kind = "smb", host = "nas.local", share = "media", username = "viewer", wolMac = "02:11:22:33:44:55", wolAuto = true)
    private fun create(): Triple<AppPreferences, TokenStore, FileStore> {
        val regular = memoryPreferences(); val preferences = AppPreferences(regular); val secure = TokenStore(memoryPreferences(), preferences)
        return Triple(preferences, secure, FileStore(preferences, secure, regular))
    }
    @Test fun backupOmitsSecretsUnlessExplicitlyRequested() {
        val (prefs, tokens, store) = create(); store.saveSource(source, "private-fixture")
        val manager = SettingsBackupManager(prefs, tokens)
        val public = manager.exportJson(); assertFalse(public.contains("private-fixture"))
        val (target, targetTokens, _) = create()
        SettingsBackupManager(target, targetTokens).importJson(public)
        assertEquals(listOf(source), target.fileSources); assertTrue(target.filesEnabled)
        assertNull(targetTokens.smbPasswordForSource(source.id))
        SettingsBackupManager(target, targetTokens).importJson(manager.exportJson(true))
        assertEquals("private-fixture", targetTokens.smbPasswordForSource(source.id))
        assertEquals(source.wolMac, target.fileSources.single().wolMac)
    }
    @Test fun importingOldBackupClearsFileSourcesAndCredentials() {
        val (prefs, tokens, store) = create(); store.saveSource(source, "secret")
        SettingsBackupManager(prefs, tokens).importJson("""{"schemaVersion":31,"exportedAtEpochMs":0}""")
        assertFalse(prefs.filesEnabled); assertTrue(prefs.fileSources.isEmpty()); assertNull(tokens.smbPasswordForSource(source.id))
    }
    @Test fun invalidSourceImportDoesNotPartiallyReplaceExistingSources() {
        val (prefs, tokens, store) = create(); store.saveSource(source, "secret")
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupManager(prefs, tokens).importJson("""{"schemaVersion":32,"exportedAtEpochMs":0,"fileSources":[{"id":"bad","name":"bad","kind":"smb","host":"nas","share":"../private"}]}""")
        }
        assertEquals(listOf(source), prefs.fileSources); assertEquals("secret", tokens.smbPasswordForSource(source.id))
    }
    @Test fun historyUpdatesOneStableEntryAndPreservesKnownDuration() {
        val (_, _, store) = create(); store.saveSource(source, null)
        val entry = FileEntry(source.id, "folder/movie.mkv", "movie.mkv", false)
        store.record(entry, 1200, 9000); store.record(entry, 2500, 0)
        assertEquals(1, store.history().size)
        assertEquals(2500L, store.history().single().positionMs)
        assertEquals(9000L, store.history().single().durationMs)
        store.clearProgress(entry.id); assertTrue(store.history().single().completed)
        store.record(entry, 0, completed = false); assertFalse(store.history().single().completed)
    }
    @Test fun removingSourceClearsItsBookmarksHistoryAndSecretOnly() {
        val (_, tokens, store) = create(); store.saveSource(source, "one")
        val other = source.copy(id = "nas-2"); store.saveSource(other, "two")
        for (s in listOf(source, other)) {
            val e = FileEntry(s.id, "movie.mp4", "movie.mp4", false); store.favorite(e); store.record(e, 200)
        }
        store.removeSource(source.id)
        assertEquals(listOf(other), store.sources()); assertNull(tokens.smbPasswordForSource(source.id)); assertEquals("two", tokens.smbPasswordForSource(other.id))
        assertEquals(other.id, store.favorites().single().sourceId); assertEquals(other.id, store.history().single().entry.sourceId)
    }
    @Test fun historyIsBoundedAndMostRecentFirst() {
        val (_, _, store) = create(); store.saveSource(source, null)
        repeat(505) { store.record(FileEntry(source.id, "$it.mp4", "$it.mp4", false)) }
        assertEquals(500, store.history().size); assertEquals("504.mp4", store.history().first().entry.name)
    }

    @Test fun renamedSourceAndWakeOptionsPreserveBookmarksAndProgress() {
        val (_, _, store) = create(); store.saveSource(source, "secret")
        val entry = FileEntry(source.id, "movie.mkv", "movie.mkv", false)
        store.favorite(entry); store.record(entry, 400, 900)
        store.saveSource(source.copy(name = "Renamed", wolWaitSeconds = 120), null)
        assertEquals(entry, store.favorites().single()); assertEquals(400L, store.history().single().positionMs)
    }
    @Test fun anotherHostOrShareMustNotInheritOldResumePoints() {
        for (replacement in listOf(source.copy(host = "other-nas"), source.copy(share = "other-share"), source.copy(rootPath = "new-root"))) {
            val (_, _, store) = create(); store.saveSource(source, "secret")
            val entry = FileEntry(source.id, "movie.mkv", "movie.mkv", false)
            store.favorite(entry); store.record(entry, 400, 900)
            store.saveSource(replacement, "new-secret")
            assertTrue(store.favorites().isEmpty()); assertTrue(store.history().isEmpty())
        }
    }
    @Test fun importCannotRebindHistoryToADifferentNasWithTheSameSourceId() {
        val (prefs, tokens, store) = create(); store.saveSource(source, "secret")
        val entry = FileEntry(source.id, "movie.mkv", "movie.mkv", false)
        store.favorite(entry); store.record(entry, 400, 900)
        val (exportPrefs, exportTokens, exportStore) = create()
        exportStore.saveSource(source.copy(host = "replacement-nas"), null)
        SettingsBackupManager(prefs, tokens).importJson(SettingsBackupManager(exportPrefs, exportTokens).exportJson())
        assertTrue(store.favorites().isEmpty()); assertTrue(store.history().isEmpty())
        assertNull(tokens.smbPasswordForSource(source.id))
    }
    @Test fun rejectedSourceAtLimitDoesNotLeaveAnOrphanPassword() {
        val (_, tokens, store) = create()
        repeat(50) { store.saveSource(source.copy(id = "source-$it"), null) }
        assertThrows(IllegalArgumentException::class.java) { store.saveSource(source, "must-not-persist") }
        assertNull(tokens.smbPasswordForSource(source.id)); assertEquals(50, store.sources().size)
    }

    @Test fun staleRequestCannotUseReplacementNasCredentialsOrRecreateProgress() {
        val (_, _, store) = create(); store.saveSource(source, "old-password")
        val entry = FileEntry(source.id, "movie.mkv", "movie.mkv", false)
        store.record(entry, 400, 900)
        val replacement = source.copy(host = "replacement-nas")
        store.saveSource(replacement, "new-password")
        assertThrows(java.io.IOException::class.java) { store.password(source) }
        assertEquals("new-password", store.password(replacement))
        store.record(entry, 800, 900, expectedSource = source)
        assertTrue(store.history().isEmpty())
    }
    @Test fun staleRequestCannotUseCredentialsAfterImportOrRemoval() {
        val (prefs, tokens, store) = create(); store.saveSource(source, "old-password")
        val (newPrefs, newTokens, newStore) = create()
        val replacement = source.copy(host = "replacement-nas")
        newStore.saveSource(replacement, "replacement-password")
        SettingsBackupManager(prefs, tokens).importJson(SettingsBackupManager(newPrefs, newTokens).exportJson(true))
        assertThrows(java.io.IOException::class.java) { store.password(source) }
        assertEquals("replacement-password", store.password(replacement))
        store.removeSource(source.id)
        assertThrows(java.io.IOException::class.java) { store.password(replacement) }
        store.record(FileEntry(source.id, "movie.mkv", "movie.mkv", false), 800)
        assertTrue(store.history().isEmpty())
    }
    @Test fun sftpKeyFollowsSourceIntoCredentialBackupsAndIsDroppedOtherwise() {
        val sftp = FileSource(id = "sftp-1", name = "Box", kind = "sftp", host = "box.local", port = 22, username = "me", share = "/srv/media")
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "A".repeat(80) + "\n-----END OPENSSH PRIVATE KEY-----"
        val (prefs, tokens, store) = create(); store.saveSource(sftp, "passphrase", key)
        assertEquals(key, store.sshKey(sftp))
        val manager = SettingsBackupManager(prefs, tokens)
        assertFalse(manager.exportJson().contains("OPENSSH"))
        val (target, targetTokens, _) = create()
        SettingsBackupManager(target, targetTokens).importJson(manager.exportJson(true))
        assertEquals(key, targetTokens.sshKeyForSource(sftp.id))
        assertEquals("passphrase", targetTokens.smbPasswordForSource(sftp.id))
        // Turning the source into another kind drops the key; removing it drops everything.
        store.saveSource(sftp.copy(kind = "webdav", port = 443, share = ""), null)
        assertNull(tokens.sshKeyForSource(sftp.id))
        assertEquals("passphrase", tokens.smbPasswordForSource(sftp.id))
        store.removeSource(sftp.id)
        assertNull(tokens.smbPasswordForSource(sftp.id))
    }
    @Test fun wakeUpdateKeepsSecretsAndDoesNotEnableFiles() {
        val (prefs, tokens, store) = create(); store.saveSource(source, "secret")
        prefs.filesEnabled = false
        assertTrue(store.updateWake(source.id, "02:AA:BB:CC:DD:EE", auto = true))
        assertFalse(prefs.filesEnabled)
        assertEquals("02:AA:BB:CC:DD:EE", prefs.fileSources.single().wolMac)
        assertEquals("secret", tokens.smbPasswordForSource(source.id))
        assertFalse(store.updateWake(source.id, "not-a-mac", auto = true))
        assertTrue(store.updateWake(source.id, "", auto = true))
        assertFalse(prefs.fileSources.single().wolAuto)
    }
    @Test fun backupFromNewerVersionSkipsUnknownSourceKinds() {
        val (prefs, tokens, _) = create()
        SettingsBackupManager(prefs, tokens).importJson("""{"schemaVersion":57,"exportedAtEpochMs":0,"filesEnabled":true,"fileSources":[
            {"id":"nas-1","name":"NAS","kind":"smb","host":"nas.local","share":"media"},
            {"id":"future","name":"Future","kind":"s3","host":"bucket"}]}""")
        assertEquals(listOf("nas-1"), prefs.fileSources.map { it.id })
    }
    @Test fun onlyAddingASourceSwitchesFilesOn() {
        val (prefs, _, store) = create(); store.saveSource(source, "secret")
        assertTrue(prefs.filesEnabled)
        prefs.filesEnabled = false
        store.saveSource(source.copy(wolWaitSeconds = 90), null)
        assertFalse(prefs.filesEnabled)
        store.saveSource(source.copy(id = "nas-2"), null)
        assertTrue(prefs.filesEnabled)
    }
}

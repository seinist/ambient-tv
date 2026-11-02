package com.ambient.tv.companion

import com.ambient.tv.data.local.*
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/** Sign-in sessions (tokens) and passwords/keys are separate, independently chosen transfers. */
class CompanionSessionTransferTest {
    private val source = SettingsBackup(exportedAtEpochMs = 1,
        servers = listOf(ServerBackup(ServerProfile("p", "Plex", "plex.test"), "plex-token")),
        jellyfinServers = listOf(JellyfinServerBackup(JellyfinServerProfile("j", "Jellyfin", "jellyfin.test"), "jf-token", "jf-password")),
        fileSources = listOf(FileSource("nas", "NAS", "smb", host = "nas.test")),
        smbPasswords = mapOf("nas" to "nas-password"),
        tvheadendHost = "tvh.test", tvheadendPassword = "tvh-password",
        smartTubeAccounts = listOf(SmartTubeAccountBackup("yt", "Anna", "yt-session")),
        spotifyAccount = SpotifyAccountBackup(clientId = "client", accessToken = "sp-access", refreshToken = "sp-refresh"))

    private fun wire(credentials: Boolean, sessions: Boolean) =
        CompanionBackupPolicy.prepare(source, credentials, "accounts", sessions)

    @Test fun sessionsOnlyCarriesTokensButNoPasswords() {
        val wire = wire(credentials = false, sessions = true)
        listOf("plex-token", "jf-token", "yt-session", "sp-access", "sp-refresh").forEach { assertTrue(it, wire.contains(it)) }
        listOf("jf-password", "nas-password", "tvh-password").forEach { assertFalse(it, wire.contains(it)) }
    }

    @Test fun credentialsOnlyCarriesPasswordsButNoSessions() {
        val wire = wire(credentials = true, sessions = false)
        listOf("jf-password", "nas-password", "tvh-password").forEach { assertTrue(it, wire.contains(it)) }
        listOf("plex-token", "jf-token", "yt-session", "sp-access", "sp-refresh").forEach { assertFalse(it, wire.contains(it)) }
    }

    @Test fun defaultsCarryNeitherAndOlderCallersCarryBoth() {
        val none = wire(credentials = false, sessions = false)
        listOf("plex-token", "jf-token", "yt-session", "sp-access", "jf-password", "nas-password", "tvh-password")
            .forEach { assertFalse(it, none.contains(it)) }
        // A sender that only knows "credentials" meant both, as before.
        val legacy = CompanionBackupPolicy.prepare(source, true, "accounts")
        listOf("plex-token", "jf-token", "yt-session", "jf-password", "nas-password").forEach { assertTrue(it, legacy.contains(it)) }
    }
}

package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionRoleTest {
    @Test fun onlyOppositeExplicitRolesMayAuthenticate() {
        val roles = listOf(COMPANION_TV, COMPANION_HANDHELD, "", "unknown")
        for (server in roles) for (client in roles) {
            assertEquals("$server <- $client", setOf(server, client) == setOf(COMPANION_TV, COMPANION_HANDHELD),
                companionRolesAllowed(server, client))
        }
    }

    @Test fun phonesRejectAllSyncIncludingAccountsAndSources() {
        for (mode in listOf("accounts", "settings", "preferences", "nav_sounds")) {
            assertFalse(mode, companionReceiveModeAllowed(false, mode))
            assertTrue(mode, companionReceiveModeAllowed(true, mode))
        }
        assertTrue(companionReceiveModeAllowed(true, "accounts"))
        assertFalse(companionReceiveModeAllowed(true, "unknown"))
    }

    @Test fun phonesRejectLegacyTransferCommands() {
        for (command in listOf("backup_begin", "backup_chunk", "backup_finish", "backup_cancel")) {
            assertFalse(command, companionCommandAllowed(false, command))
            assertTrue(command, companionCommandAllowed(true, command))
        }
    }

    @Test fun rolesAreBoundToAuthenticationSignature() {
        val valid = authTranscript("nonce", "server", "client", COMPANION_HANDHELD, COMPANION_TV)
        assertFalse(valid.contentEquals(authTranscript("nonce", "server", "client", COMPANION_TV, COMPANION_TV)))
        assertFalse(valid.contentEquals(authTranscript("nonce", "server", "client", COMPANION_HANDHELD, COMPANION_HANDHELD)))
        assertFalse(valid.contentEquals(authTranscript("nonce", "server", "client")))
    }

    @Test fun legacyPairingLoadsWithoutInventingRole() {
        val peer = companionJson.decodeFromString<CompanionPeer>("""{"fingerprint":"pin","name":"Old device"}""")
        assertEquals("", peer.role)
        assertFalse(companionRolesAllowed(COMPANION_TV, peer.role))
    }
}

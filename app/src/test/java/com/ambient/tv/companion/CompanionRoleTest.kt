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

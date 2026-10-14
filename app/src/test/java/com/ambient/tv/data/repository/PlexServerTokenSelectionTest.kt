package com.ambient.tv.data.repository

import com.ambient.tv.data.remote.dto.PlexResourceConnectionDto
import com.ambient.tv.data.remote.dto.PlexResourceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexServerTokenSelectionTest {
    private val owned = PlexResourceDto(
        name = "Home", clientIdentifier = "own-id", provides = "server", owned = true, accessToken = "own-token",
        connections = listOf(PlexResourceConnectionDto("http", "192.168.1.10", 32400, "http://192.168.1.10:32400", local = true)),
    )
    private val shared = PlexResourceDto(
        name = "Friend", clientIdentifier = "friend-id", provides = "server", owned = false, accessToken = "friend-token",
        connections = listOf(
            PlexResourceConnectionDto("https", "203.0.113.7", 32400, "https://203-0-113-7.abc123.plex.direct:32400"),
        ),
    )
    private val player = PlexResourceDto(
        name = "Phone", clientIdentifier = "phone-id", provides = "client,player", accessToken = "player-token",
        connections = listOf(PlexResourceConnectionDto("http", "192.168.1.10", 32400)),
    )

    @Test
    fun `machine identifier selects the shared server token`() {
        assertEquals("friend-token", plexServerAccessToken(listOf(owned, shared), "friend-id", "", 32400))
    }

    @Test
    fun `host and port match address or plex direct uri`() {
        assertEquals("own-token", plexServerAccessToken(listOf(owned, shared, player), null, "192.168.1.10", 32400))
        assertEquals("friend-token", plexServerAccessToken(listOf(owned, shared), null, "203-0-113-7.abc123.plex.direct", 32400))
        assertEquals("friend-token", plexServerAccessToken(listOf(owned, shared), null, "203.0.113.7", 32400))
    }

    @Test
    fun `no or ambiguous match keeps the caller's account token`() {
        assertNull(plexServerAccessToken(listOf(owned), null, "192.168.1.10", 32401))
        assertNull(plexServerAccessToken(listOf(owned, owned.copy(clientIdentifier = "other", accessToken = "x")), null, "192.168.1.10", 32400))
        assertNull(plexServerAccessToken(listOf(owned.copy(accessToken = null)), "own-id", "", 32400))
        assertNull(plexServerAccessToken(listOf(player), "phone-id", "192.168.1.10", 32400))
    }

    @Test
    fun `plex direct address belongs to the address the user typed`() {
        val server = shared.copy(connections = listOf(
            PlexResourceConnectionDto("https", "192.168.1.20", 32400, "https://192-168-1-20.abc123.plex.direct:32400", local = true),
            PlexResourceConnectionDto("https", "203.0.113.7", 32400, "https://203-0-113-7.abc123.plex.direct:32400"),
            PlexResourceConnectionDto("http", "192.168.1.20", 32400, "http://192.168.1.20:32400", local = true),
        ))
        assertEquals("192-168-1-20.abc123.plex.direct" to 32400, plexDirectSecureAddress(server, "192.168.1.20"))
        assertNull(plexDirectSecureAddress(server, "10.0.0.9"))
        assertNull(plexDirectSecureAddress(owned, "192.168.1.10"))
    }
}

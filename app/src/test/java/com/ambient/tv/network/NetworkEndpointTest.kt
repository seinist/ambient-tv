package com.ambient.tv.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkEndpointTest {
    @Test fun normalizesBracketedIpv6ForHttpLibraries() {
        assertEquals("fd12:3456::10", NetworkEndpoint.normalizeHost(" [fd12:3456::10] "))
    }

    @Test fun formatsIpv6ForUrlAuthority() {
        assertEquals("[fd12:3456::10]", NetworkEndpoint.authorityHost("fd12:3456::10"))
        assertEquals("192.168.1.2", NetworkEndpoint.authorityHost("192.168.1.2"))
    }

    @Test fun extractsHostsFromUrlLikeSettings() {
        assertEquals("tvh.local", NetworkEndpoint.extractHost("HTTPS://tvh.local:9981/root"))
        assertEquals("fd12:3456::10", NetworkEndpoint.extractHost("https://[fd12:3456::10]:9981/root"))
        assertEquals("fd12:3456::10", NetworkEndpoint.extractHost("fd12:3456::10"))
    }

    @Test fun dropsCredentialsAndInvisibleCharacters() {
        assertEquals("192.168.178.20", NetworkEndpoint.extractHost("http://user:secret@192.168.178.20:9981/"))
        assertEquals("192.168.178.20", NetworkEndpoint.extractHost("user@192.168.178.20:9981"))
        assertEquals("192.168.178.20", NetworkEndpoint.extractHost("\u200B192.168.178.20\uFEFF"))
    }

    @Test fun rejectsInvalidPorts() {
        assertEquals(32400, NetworkEndpoint.validPortOrDefault(0, 32400))
        assertEquals(32400, NetworkEndpoint.validPortOrDefault(65536, 32400))
        assertEquals(32401, NetworkEndpoint.validPortOrDefault(32401, 32400))
    }

    @Test fun differentiatesServicesOnTheSameHostByPort() {
        assertEquals(true, NetworkEndpoint.sameEndpoint("192.168.178.34", 8096, "192.168.178.34", 8096))
        assertEquals(false, NetworkEndpoint.sameEndpoint("192.168.178.34", 9981, "192.168.178.34", 8096))
    }
}

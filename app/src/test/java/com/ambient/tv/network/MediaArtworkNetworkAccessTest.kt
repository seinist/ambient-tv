package com.ambient.tv.network

import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.data.browse.SourceHealth
import org.junit.Assert.*
import org.junit.Test

class MediaArtworkNetworkAccessTest {
    @Test fun localFileCoverBridgeDoesNotNeedAnAndroidNetwork() {
        for (host in listOf("127.0.0.1", "127.10.20.30", "::1", "localhost", "LOCALHOST"))
            assertTrue(host, artworkLoopbackHost(host))
        for (host in listOf("192.168.1.2", "127.0.0.1.example", "localhost.example", "127.256.0.1", "127.0.0", "::2"))
            assertFalse(host, artworkLoopbackHost(host))
    }

    @Test fun knownOutagesAndExpiredLoginUseCachedBytes() {
        assertTrue(artworkNetworkBlocked(true, SourceHealth.OFFLINE, HostHealth.UNKNOWN))
        assertTrue(artworkNetworkBlocked(true, SourceHealth.OFFLINE, HostHealth.ONLINE)) // A different NAS service is up.
        assertTrue(artworkNetworkBlocked(true, SourceHealth.AUTH_REQUIRED, HostHealth.ONLINE))
        assertTrue(artworkNetworkBlocked(true, SourceHealth.UNKNOWN, HostHealth.OFFLINE))
        assertTrue(artworkNetworkBlocked(false, SourceHealth.ONLINE, HostHealth.ONLINE))
    }

    @Test fun firstLoadAndIsolatedLanCanFetchArtworkWithoutPlaybackProof() {
        assertFalse(artworkNetworkBlocked(true, SourceHealth.UNKNOWN, HostHealth.UNKNOWN))
        assertFalse(artworkNetworkBlocked(true, SourceHealth.ONLINE, HostHealth.ONLINE))
        assertFalse(artworkNetworkBlocked(true, null, null)) // Independent external artwork.
    }

    @Test fun onlyRecoveryRestartsPrimaryCandidateAndUnrelatedReportsAreStable() {
        val initial = ArtworkNetworkState()
        assertEquals(initial, initial.update(false))
        val offline = initial.update(true)
        assertEquals(0L, offline.recovery)
        assertEquals(offline, offline.update(true))
        val recovered = offline.update(false)
        assertEquals(1L, recovered.recovery)
        assertFalse(recovered.blocked)
        assertEquals(recovered, recovered.update(false))
        assertEquals(2L, recovered.update(true).update(false).recovery)
    }
}

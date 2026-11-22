package com.ambient.tv.ui.player

import com.ambient.tv.car.CarAudioIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceRecoveryTest {
    private val track = PlaybackMetadata(
        route = "player/j%3Atrack", title = "Track", source = "jellyfin", mediaId = "j:track",
        hasVideo = false, sourceAccessIdentity = "account-a:endpoint-a",
    )

    private fun owner(metadata: PlaybackMetadata = track) = PlaybackRecoveryOwner(
        7L, metadata.route, metadata.sourceAccessIdentity, Any(),
    )

    @Test fun serviceAudioReconnectResolvesAnOpaqueItemInsteadOfReusingAStreamUrl() {
        val current = owner()
        val item = serviceReconnectItem(track, current, current)
        assertEquals(track.mediaId, item?.let(CarAudioIds::musicItemId))
    }

    @Test fun aMinimizedAppPlayerRetainsItsOwnVersionAndQueueResolver() {
        val current = owner()
        assertNull(serviceReconnectItem(track, null, current))
        assertNull(serviceReconnectItem(track.copy(hasVideo = true), current, current))
    }

    @Test fun anotherRendererWithTheSameRouteCannotInheritServiceOwnership() {
        val original = owner()
        assertNull(serviceReconnectItem(track, original, original.copy(handle = Any())))
        assertNull(serviceReconnectItem(track, original, original.copy(generation = 8L)))
    }

    @Test fun aDifferentAccountOrReplacedSourceCannotReuseTheOldAudioIntent() {
        val original = owner()
        assertNull(serviceReconnectItem(track.copy(sourceAccessIdentity = "account-b:endpoint-a"), original, original))
        val replacing = original.copy(replacingSource = true)
        assertNull(serviceReconnectItem(track, replacing, replacing))
    }

    @Test fun liveRadioReconnectKeepsTheSelectedTvheadendProfile() {
        val radio = track.copy(route = "tvh_player/channel/channel-a?sourceId=tvheadend%3Aprofile-b",
            source = "tvheadend", mediaId = "channel-a", isLive = true,
            liveTvSourceId = "tvheadend:profile-b")
        val current = owner(radio)
        assertEquals("tvheadend:profile-b" to "channel-a",
            serviceReconnectItem(radio, current, current)?.let(CarAudioIds::liveAudioSpec))
    }

    @Test fun recordingsAndMissingIdentityReopenTheirPlayer() {
        val recording = track.copy(route = "tvh_player/recording/recording-a", source = "tvheadend")
        val current = owner(recording)
        assertNull(serviceReconnectItem(recording, current, current))
        assertNull(serviceReconnectItem(track.copy(sourceAccessIdentity = null), current, current))
    }

    @Test fun anOldQueueCannotResolveReusedIdsUnderAnotherLoginOrEndpoint() {
        assertTrue(serviceQueueSourceCurrent(track, track.sourceAccessIdentity))
        assertFalse(serviceQueueSourceCurrent(track, "account-b:endpoint-a"))
        assertFalse(serviceQueueSourceCurrent(track, "account-a:endpoint-b"))
        assertFalse(serviceQueueSourceCurrent(track, null))
        assertFalse(serviceQueueSourceCurrent(track.copy(sourceAccessIdentity = null), null))
    }

    @Test fun queueScopeAlsoCoversLiveProfilesButLeavesIndependentRadioAlone() {
        val live = track.copy(route = "tvh_player/channel/1?sourceId=tvheadend%3Ab", source = "tvheadend")
        assertTrue(serviceQueueSourceCurrent(live, live.sourceAccessIdentity))
        assertFalse(serviceQueueSourceCurrent(live, "account-a:other-tv-profile"))
        val radio = track.copy(route = "radio/station/1", source = "radio", sourceAccessIdentity = null)
        assertTrue(serviceQueueSourceCurrent(radio, null))
    }
}

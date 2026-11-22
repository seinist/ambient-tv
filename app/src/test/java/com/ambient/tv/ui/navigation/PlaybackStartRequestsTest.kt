package com.ambient.tv.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class PlaybackStartRequestsTest {
    @Test fun sameNumericItemFromAnotherAccountIsNotAnExistingSession() {
        assertFalse(mayReusePlaybackSession("player/42", "player/42", true, "plex:alice", "plex:bob"))
        assertFalse(mayReusePlaybackSession("tvh_player/channel/42", "tvh_player/channel/42", true,
            "tvheadend:old-endpoint", "tvheadend:new-endpoint"))
    }

    @Test fun verifiedExistingSessionCanReopenWithoutProbingAgain() {
        assertTrue(mayReusePlaybackSession("player/42", "player/42", true, "emby:alice", "emby:alice"))
        assertFalse(mayReusePlaybackSession("player/42", "player/42", false, "emby:alice", "emby:alice"))
        assertFalse(mayReusePlaybackSession("player/42", "player/42", true, null, "emby:alice"))
    }

    @Test fun repeatedPlayPressesCoalesceWhileCheckingOrOfferingRetry() {
        val requests = PlaybackStartRequests()
        val first = requireNotNull(requests.begin("movie:1", "home", "plex:alice"))
        assertNull(requests.begin("movie:1", "home", "plex:alice"))
        assertTrue(requests.current(first, "home", "plex:alice"))
    }

    @Test fun cancellingPreventsLateSuccessfulCheckFromStarting() {
        val requests = PlaybackStartRequests()
        val first = requireNotNull(requests.begin("movie:1", "home", "plex:alice"))
        requests.cancel()
        assertFalse(requests.current(first, "home", "plex:alice"))
    }

    @Test fun changingTitleInvalidatesOlderRequestEvenOnSamePage() {
        val requests = PlaybackStartRequests()
        val first = requireNotNull(requests.begin("movie:1", "home", "plex:alice"))
        val second = requireNotNull(requests.begin("movie:2", "home", "plex:alice"))
        assertFalse(requests.current(first, "home", "plex:alice"))
        assertTrue(requests.current(second, "home", "plex:alice"))
        requests.finish(first)
        assertTrue(requests.current(second, "home", "plex:alice"))
    }

    @Test fun leavingOriginOrSwitchingAccountPreventsStart() {
        val requests = PlaybackStartRequests()
        val first = requireNotNull(requests.begin("movie:1", "home", "plex:alice"))
        assertFalse(requests.current(first, "search", "plex:alice"))
        assertFalse(requests.current(first, "home", "plex:bob"))
    }

    @Test fun completedRequestCannotFireAgain() {
        val requests = PlaybackStartRequests()
        val first = requireNotNull(requests.begin("channel:1", "guide", "tvheadend:account"))
        requests.finish(first)
        assertFalse(requests.current(first, "guide", "tvheadend:account"))
        assertNotNull(requests.begin("channel:1", "guide", "tvheadend:account"))
    }

    @Test fun retryKeepsTheOriginalIntentWhileConnectionRecovers() {
        val requests = PlaybackStartRequests()
        val first = requireNotNull(requests.begin("episode:8", "series-detail", "jellyfin:alice"))
        // Transient health is deliberately outside source identity; recovering the same
        // authenticated profile can finish the user's original start exactly once.
        assertTrue(requests.current(first, "series-detail", "jellyfin:alice"))
        requests.finish(first)
        assertFalse(requests.current(first, "series-detail", "jellyfin:alice"))
    }
}

package com.ambient.tv.youtube

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTubeFeedHealthTest {
    @Test fun detectsExpiredCredentialsThroughWrappers() {
        assertTrue(smartTubeAuthRejected(IOException("HTTP 401")))
        assertTrue(smartTubeAuthRejected(IOException("Feed incomplete", IOException("invalid_grant"))))
        assertTrue(smartTubeAuthRejected(IllegalStateException("login_required")))
        assertTrue(smartTubeAuthRejected(IllegalStateException("Account authorization expired")))
    }

    @Test fun neverMistakesOrdinaryOutagesOrForbiddenVideosForExpiredLogin() {
        assertFalse(smartTubeAuthRejected(IOException("HTTP 403: region restricted")))
        assertFalse(smartTubeAuthRejected(IOException("HTTP 429")))
        assertFalse(smartTubeAuthRejected(IOException("Connection timeout")))
        assertFalse(smartTubeAuthRejected(SmartTubeSignInRequiredException()))
        assertFalse(smartTubeAuthRejected(IllegalStateException("No rows returned")))
    }

    @Test fun forbiddenPrivateFeedSuggestsCheckingTheAccountWithoutDeclaringItExpired() {
        assertTrue(smartTubePrivateFeedForbidden(IOException("HTTP 403"), "home", signedIn = true))
        assertFalse(smartTubePrivateFeedForbidden(IOException("HTTP 403"), "music", signedIn = true))
        assertFalse(smartTubePrivateFeedForbidden(IOException("HTTP 403"), "home", signedIn = false))
        assertFalse(smartTubePrivateFeedForbidden(IOException("HTTP 429"), "home", signedIn = true))
    }

    @Test fun emptyPersonalizedFeedRequiresEvidenceExceptForHome() {
        assertTrue(smartTubeEmptyFeedNeedsAttention("home", signedIn = true, hadRows = false))
        assertTrue(smartTubeEmptyFeedNeedsAttention("subscriptions", signedIn = false, hadRows = false))
        assertTrue(smartTubeEmptyFeedNeedsAttention("history", signedIn = true, hadRows = true))
        assertFalse(smartTubeEmptyFeedNeedsAttention("playlists", signedIn = true, hadRows = false))
        assertFalse(smartTubeEmptyFeedNeedsAttention("music", signedIn = false, hadRows = true))
    }
}

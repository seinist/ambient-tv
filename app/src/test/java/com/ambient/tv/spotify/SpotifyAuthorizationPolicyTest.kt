package com.ambient.tv.spotify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAuthorizationPolicyTest {
    @Test fun currentRequestedScopesMatchFeaturesActuallyUsedByTheApp() {
        val scopes = SpotifyAuthorizationPolicy.REQUESTED_SCOPES
        assertTrue("user-read-private" in scopes)
        assertTrue("user-read-playback-state" in scopes)
        assertTrue("user-read-currently-playing" in scopes)
        assertTrue("user-modify-playback-state" in scopes)
        assertTrue("user-library-read" in scopes)
        assertTrue("playlist-read-private" in scopes)
        assertTrue("user-follow-read" in scopes)
        assertFalse("user-library-modify" in scopes)
        assertFalse("playlist-read-collaborative" in scopes)
        assertFalse("user-read-recently-played" in scopes)
    }

    @Test fun validLoginDoesNotDependOnEveryOptionalFeatureScope() {
        val all = SpotifyAuthorizationPolicy.REQUIRED_SCOPES.joinToString(" ")
        assertTrue(SpotifyAuthorizationPolicy.isAuthorized("access", null, all))
        assertTrue(SpotifyAuthorizationPolicy.isAuthorized(null, "refresh", all))
        assertFalse(SpotifyAuthorizationPolicy.isAuthorized(null, null, all))
        assertTrue(SpotifyAuthorizationPolicy.isAuthorized("access", null, "user-read-private"))
        assertFalse(SpotifyAuthorizationPolicy.isAuthorized("access", null, "user-library-read"))
    }
}

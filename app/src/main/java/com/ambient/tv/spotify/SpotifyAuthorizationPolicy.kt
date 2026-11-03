package com.ambient.tv.spotify

/** OAuth scopes actually used by the current Web API integration.
 * Keep this list minimal: Spotify may change Development Mode endpoint availability, and a
 * successful token must not be rejected merely because an unused optional scope is absent. */
object SpotifyAuthorizationPolicy {
    val REQUESTED_SCOPES: Set<String> = linkedSetOf(
        "user-read-private",
        "user-read-playback-state",
        "user-read-currently-playing",
        "user-modify-playback-state",
        "user-library-read",
        "playlist-read-private",
        "user-follow-read",
    )

    /**
     * A completed OAuth session must not be classified as signed-out just because Spotify omits
     * a feature-specific scope. The profile scope is the only identity-level requirement; the
     * remaining scopes are requested for library/player features and fail locally if unavailable.
     */
    val REQUIRED_SCOPES: Set<String> = setOf("user-read-private")

    fun isAuthorized(accessToken: String?, refreshToken: String?, scopeValue: String?): Boolean {
        if (accessToken.isNullOrBlank() && refreshToken.isNullOrBlank()) return false
        val granted = scopeValue.orEmpty().split(' ').filter(String::isNotBlank).toSet()
        return REQUIRED_SCOPES.all(granted::contains)
    }
}

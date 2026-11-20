package com.ambient.tv.youtube

import com.ambient.tv.notifications.failureHttpCode

/** A locally missing session, distinct from a rejected HTTP/OAuth credential. */
internal class SmartTubeSignInRequiredException : IllegalStateException("SmartTube sign-in required")

/** These tabs depend on a working Google account. Public topic tabs and search do not. */
internal fun smartTubeFeedNeedsAccount(key: String): Boolean = key in setOf(
    "home", "subscriptions", "channels", "history", "playlists",
)

/**
 * Only explicit credential/session rejections prove that a re-login is necessary. In
 * particular, YouTube's HTTP 403 can also mean a blocked video, quota, region or client;
 * a timeout/empty response must not silently sign the user out.
 *
 * MediaServiceCore can wrap the original response in a feed/reflective exception.
 */
internal fun smartTubeAuthRejected(error: Throwable): Boolean {
    // Missing account is a local state, not proof that a remote credential was rejected.
    if (error is SmartTubeSignInRequiredException) return false
    if (failureHttpCode(error) == 401) return true
    val markers = listOf(
        "invalid_grant", "invalid_token", "token_expired", "login_required",
        "authentication_required", "invalid credentials", "credentials revoked",
        "authorization revoked", "sign-in required", "sign in required",
        "account authorization expired",
    )
    return generateSequence(error) { it.cause }.take(10).any { cause ->
        val message = cause.message.orEmpty().lowercase()
        val name = cause.javaClass.simpleName.lowercase()
        markers.any { marker -> marker in message || marker.replace("_", "") in name }
    }
}

/**
 * An empty personalized Home after its bounded retry is suspicious; an empty playlist,
 * history or subscriptions list can be perfectly legitimate for a new account. Only
 * flag these other feeds when this account had visible content for that feed before.
 */
internal fun smartTubeEmptyFeedNeedsAttention(
    key: String,
    signedIn: Boolean,
    hadRows: Boolean,
): Boolean = smartTubeFeedNeedsAccount(key) && (!signedIn || key == "home" || hadRows)

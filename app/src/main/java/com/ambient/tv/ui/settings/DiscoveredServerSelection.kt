package com.ambient.tv.ui.settings

/**
 * What a click on a LAN-discovered server should do.
 * Connect only when that system's credential is already stored and the profile is complete.
 * Otherwise the existing login flow runs with the discovered address prefilled.
 */
internal enum class DiscoveredServerAction { Connect, StartLogin }

/** Plex is ready to connect when an account token is already present. */
internal fun plexDiscoveredServerAction(token: String?): DiscoveredServerAction =
    if (!token.isNullOrBlank()) DiscoveredServerAction.Connect else DiscoveredServerAction.StartLogin

/**
 * Jellyfin and Emby store an access token (API key) plus a user id after sign-in.
 * Both have to be present on an authenticated profile before a discovered server is connected.
 */
internal fun mediaServerDiscoveredServerAction(
    accessToken: String?,
    userId: String?,
    authenticated: Boolean,
): DiscoveredServerAction =
    if (!accessToken.isNullOrBlank() && !userId.isNullOrBlank() && authenticated) {
        DiscoveredServerAction.Connect
    } else {
        DiscoveredServerAction.StartLogin
    }

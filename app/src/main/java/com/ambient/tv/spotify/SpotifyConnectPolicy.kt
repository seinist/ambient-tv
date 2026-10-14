package com.ambient.tv.spotify

/**
 * Every installation used to announce itself as the bare app name, so two TVs in one household
 * appeared twice under the same name in Spotify Connect. The default now carries the Android
 * device name (Settings → Device name) or the model.
 */
internal fun defaultSpotifyDeviceName(brand: String, deviceLabel: String?): String {
    val label = deviceLabel
        ?.filter { !it.isISOControl() }
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(brand, ignoreCase = true) }
        ?: return brand
    return "$brand · ${label.take(40)}"
}

/**
 * Only a refresh token the server rejected for good ends the session (and with it the Connect
 * receiver). A malformed request or a temporary server problem keeps the account signed in.
 */
internal fun spotifyRefreshFailureEndsSession(httpCode: Int, oauthError: String?): Boolean =
    httpCode in setOf(400, 401) && oauthError in setOf("invalid_grant", "invalid_client", "unauthorized_client")

/**
 * Prefers the receiver's own id. A name match is only trusted when it is unambiguous, so playback
 * never lands on another TV that happens to use the same Connect name.
 */
internal fun pickSpotifyPlaybackDevice(devices: List<Pair<String, String>>, preferredId: String?, preferredName: String): String? {
    devices.firstOrNull { (id, _) -> !preferredId.isNullOrBlank() && id == preferredId }?.let { return it.first }
    val named = devices.filter { (_, name) -> name.equals(preferredName, ignoreCase = true) }
    return named.singleOrNull()?.first
}

/**
 * Identity of the default network for the Connect receiver: the network handle plus its IPv4
 * addresses. IPv6 is left out on purpose, because temporary (privacy) addresses rotate during the
 * day and must not restart a running receiver.
 */
internal fun spotifyNetworkKey(networkHandle: Long, ipv4Addresses: Collection<String>): String =
    "$networkHandle|" + ipv4Addresses.filter(String::isNotBlank).sorted().joinToString(",")

/** Only a real move to another network/address re-announces the receiver; the first sighting does not. */
internal fun spotifyNetworkChangeNeedsRestart(previousKey: String?, currentKey: String, hasIpv4: Boolean): Boolean =
    previousKey != null && hasIpv4 && previousKey != currentKey

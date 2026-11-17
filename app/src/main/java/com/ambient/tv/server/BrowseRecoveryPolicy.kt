package com.ambient.tv.server

/**
 * A first service confirmation after process start is not a reconnect. The existing disk
 * snapshots keep their TTL and Home refreshes only what is stale. A confirmed outage or
 * endpoint switch still invalidates browse caches through BrowseReconnects.
 *
 * Explicit retry, network return and resume revalidation continue to notify separately.
 */
internal fun shouldRequestBrowseRecovery(
    before: ServerConnectionState,
    previousEndpoint: String?,
    currentEndpoint: String,
): Boolean = before is ServerConnectionState.Offline ||
    before is ServerConnectionState.WakeTimedOut ||
    before is ServerConnectionState.Error ||
    (previousEndpoint != null && previousEndpoint != currentEndpoint)

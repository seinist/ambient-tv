package com.ambient.tv.ui.youtube

/** The requested feed, not the Home default, must own focus on the first Compose frame. */
internal fun initialYouTubeBrowseFeed(
    routeFeedKey: String?,
    configuredStartKey: String?,
    enabledFeedKeys: List<String>,
    channelPage: Boolean,
): YouTubeFeed {
    if (channelPage) return YouTubeFeed.HOME
    val enabled = enabledFeedKeys.mapNotNull(YouTubeFeed::fromKey)
    val requested = routeFeedKey?.let(YouTubeFeed::fromKey)
        ?: configuredStartKey?.let(YouTubeFeed::fromKey)
    return requested?.takeIf { it in enabled } ?: enabled.firstOrNull() ?: YouTubeFeed.HOME
}

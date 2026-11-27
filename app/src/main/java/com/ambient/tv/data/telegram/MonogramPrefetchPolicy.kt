package com.ambient.tv.data.telegram

/** Only speculative thumbnails are limited; bandwidth estimates are hints, not measurements. */
internal fun monogramPrefetchNetworkLanes(online: Boolean, metered: Boolean, downstreamKbps: Int): Int =
    when {
        !online -> 0
        metered || downstreamKbps in 1..2_500 -> 1
        else -> 2
    }

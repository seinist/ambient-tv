package com.ambient.tv.data.telegram

/** Only speculative thumbnails are limited; bandwidth estimates are hints, not measurements. */
internal fun monogramPrefetchNetworkLanes(online: Boolean, metered: Boolean, downstreamKbps: Int): Int =
    when {
        !online -> 0
        metered || downstreamKbps in 1..2_500 -> 1
        else -> 2
    }

/** Conservative cap for known large full downloads; single-part reads remain single-part. */
internal fun monogramNativePipelineParts(metered: Boolean, downstreamKbps: Int, lowRam: Boolean): Int =
    when {
        downstreamKbps in 1..2_500 -> 1
        lowRam || metered || downstreamKbps <= 0 || downstreamKbps < 15_000 -> 2
        else -> 4
    }

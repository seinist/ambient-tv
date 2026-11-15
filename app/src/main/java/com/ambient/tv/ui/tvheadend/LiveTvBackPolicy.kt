package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.LiveTvChannelKind

/**
 * Radio can keep playing on a phone before its tracks arrive. A known TV channel remains video
 * even if the renderer has only discovered audio so far. Recordings and unknown channels use
 * confirmed track information instead of assuming that every live stream is audio.
 */
internal fun liveTvAudioOnlyForBack(
    channelKind: LiveTvChannelKind?,
    detectedAudioOnly: Boolean,
): Boolean = when (channelKind) {
    LiveTvChannelKind.RADIO -> true
    LiveTvChannelKind.TV -> false
    LiveTvChannelKind.UNKNOWN, null -> detectedAudioOnly
}

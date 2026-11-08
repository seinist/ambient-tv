@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.car

import androidx.media3.common.MediaMetadata
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.tvheadend.TvheadendPlayerState

/**
 * Keep the station identity while exposing the stream's current title or programme text.
 * Legacy car clients commonly use artist for their second line; modern clients can use subtitle.
 * An ICY string is preserved as supplied, rather than guessed into separate artist/title fields.
 */
internal fun MediaMetadata.Builder.applyCarRadioMetadata(
    playback: PlaybackMetadata?,
    description: String? = null,
    genre: String? = null,
): MediaMetadata.Builder {
    if (playback == null || playback.hasVideo || !playback.isLive ||
        playback.source !in setOf("radio", "tvheadend", "iptv")) return this

    val station = playback.title.cleanRadioText()
    val channelNumber = (playback.resumeState as? TvheadendPlayerState.Ready)?.channel
        ?.takeIf { it.uuid == playback.mediaId }
        ?.numberLabel.cleanRadioText()
    val nowPlaying = playback.subtitle.cleanRadioText()?.takeUnless {
        it.equals(station, ignoreCase = true) || (playback.source != "radio" && it == channelNumber)
    }
    return setTitle(station)
        .setDisplayTitle(station)
        .setStation(station)
        .setSubtitle(nowPlaying)
        .setArtist(nowPlaying)
        .setDescription(description.cleanRadioText())
        .setGenre(genre.cleanRadioText())
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setIsBrowsable(false)
        .setIsPlayable(true)
}

private fun String?.cleanRadioText(): String? = this?.trim()?.takeIf(String::isNotBlank)

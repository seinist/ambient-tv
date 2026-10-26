package com.ambient.tv.ui.player

/**
 * Whether the MPV panel offers video options. mpv reports the codec only once the file is open,
 * which after a renderer switch takes a while: until then a title that is not known to be
 * audio-only keeps its video panel instead of collapsing to the audio-only layout.
 */
internal fun mpvPanelHasVideo(videoCodec: String?, audioOnly: Boolean): Boolean =
    videoCodec?.isNotBlank() == true || !audioOnly

/**
 * A value the panel or Stats for Nerds show before the new engine confirmed it: marked as in
 * transition ("wechselt…") instead of being presented as a fact. Real values replace it.
 */
internal fun rendererTransitionValue(value: String, confirmed: Boolean, switchingLabel: String): String =
    if (confirmed) value else "$value · $switchingLabel"

/**
 * Whether the panel offers video options from a player's track lists. Right after a start, a
 * reload or a new stream both lists are still empty: that is "not known yet", not audio-only,
 * so the video panel stays unless the title is known to be audio-only. Only parsed tracks with
 * audio and without video (radio, music) collapse it.
 */
internal fun playbackPanelHasVideo(videoTracks: Int, audioTracks: Int, audioOnly: Boolean): Boolean = when {
    videoTracks > 0 -> true
    audioOnly -> false
    else -> audioTracks == 0
}

/**
 * The video area to show for the user's [selected] one. While the running engine has not
 * confirmed its capabilities ([confirmed] false, e.g. right after a renderer or output switch)
 * an area that is missing from [available] stays open instead of jumping to another area; the
 * groups come back with the engine. Once confirmed, a missing area returns null: it really does
 * not apply anymore and the panel goes back to the list of areas.
 */
internal fun resolveVideoSection(selected: String, available: List<String>, confirmed: Boolean): String? = when {
    selected in available -> selected
    !confirmed -> selected
    else -> null
}

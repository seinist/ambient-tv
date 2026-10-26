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

package com.ambient.tv.ui.player

/**
 * Identity of the playing title for the "show the OSD when a new title starts" rule.
 * Live TV and radio show their own channel information on a channel change, so they
 * are left out here (null). A renderer switch keeps source and media id, so it does
 * not count as a new title.
 */
internal fun playbackTitleIdentity(source: String, mediaId: String, isLive: Boolean): String? =
    if (isLive || source == "tvheadend" || source == "radio" || mediaId.isBlank()) null
    else "$source:$mediaId"

/**
 * The OSD is shown briefly when the next title is ready: autoplay, Next Up, manual
 * Next/Previous and gapless music. Not on the first start of the player, not for the
 * same title again and not while a panel, menu or dialog is open.
 */
internal fun titleChangeRevealsOsd(
    previousIdentity: String?,
    currentIdentity: String?,
    ready: Boolean,
    blocked: Boolean,
): Boolean = ready && !blocked && channelIdentityChanged(previousIdentity, currentIdentity)

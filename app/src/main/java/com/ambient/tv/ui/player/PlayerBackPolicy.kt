package com.ambient.tv.ui.player

/**
 * What remote Back does in a player, once the OSD is out of the way.
 *
 * On a television Back stops playback — including music and radio. Sending playback to the
 * background is the exit-fullscreen button's job and nothing else's, so "did I stop this or is it
 * still running?" never depends on which kind of item was playing.
 *
 * A handheld keeps the phone convention for audio: leaving the screen is how music goes on
 * playing there, and that platform has no exit-fullscreen chip to do it instead.
 */
internal fun backLeavesPlaybackRunning(audioOnly: Boolean, handheld: Boolean): Boolean =
    audioOnly && handheld

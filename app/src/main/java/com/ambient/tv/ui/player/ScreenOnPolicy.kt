package com.ambient.tv.ui.player

/**
 * Whether the foreground Activity should suppress the Android system screensaver / display timeout.
 *
 * TV: any running local playback, including background audio, should keep the display awake.
 * Phone: only the visible player page (not background audio, browsing or PiP) keeps it awake.
 * The app's own TV screensaver remains able to run without Android interrupting it.
 * An explicit sleep-timer standby request always wins.
 */
internal fun shouldKeepScreenOn(
    handheld: Boolean,
    appScreensaverEnabled: Boolean,
    screensaverOverlayPresent: Boolean,
    photoViewer: Boolean,
    playerPageVisible: Boolean,
    pipVisible: Boolean,
    sessionActive: Boolean,
    isPlaying: Boolean,
    wantsToPlay: Boolean,
    spotifyPlaying: Boolean,
    standbyRequested: Boolean,
): Boolean {
    if (standbyRequested) return false
    if (screensaverOverlayPresent || photoViewer) return true
    if (!handheld && appScreensaverEnabled) return true

    // isPlaying alone misses startup/rebuffering; wantsToPlay alone may briefly lag a
    // native renderer, so use both. Inactive/released sessions must never hold the flag.
    val playbackRunning = (sessionActive && (isPlaying || wantsToPlay)) || spotifyPlaying
    return if (handheld) playerPageVisible && !pipVisible && playbackRunning else playbackRunning
}

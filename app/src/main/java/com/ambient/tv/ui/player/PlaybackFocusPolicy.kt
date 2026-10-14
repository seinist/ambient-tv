package com.ambient.tv.ui.player

/** What the playback service should do about audio focus for the current session state. */
internal enum class AudioFocusAction { REQUEST, KEEP, ABANDON }

/**
 * Media3 asks for focus itself; the native backends get it from the service.
 * [wantsToPlay] includes buffering and output reconfiguration: a stopped frame clock is not
 * a user pause and must not abandon/reacquire focus around every filter change.
 *
 * [waitingForFocus] is set while a request was delayed or refused and playback is only paused
 * until focus arrives. That state must keep the request (and its listener) alive — abandoning it
 * is what left background playback paused for good when another app held focus for a moment.
 */
internal fun audioFocusAction(
    active: Boolean,
    backend: String,
    wantsToPlay: Boolean,
    waitingForFocus: Boolean,
    held: Boolean,
): AudioFocusAction = when {
    !active || backend == "media3" || backend.isBlank() -> AudioFocusAction.ABANDON
    wantsToPlay && !held -> AudioFocusAction.REQUEST
    wantsToPlay || waitingForFocus -> AudioFocusAction.KEEP
    else -> AudioFocusAction.ABANDON
}

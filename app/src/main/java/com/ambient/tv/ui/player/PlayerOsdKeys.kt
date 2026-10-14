package com.ambient.tv.ui.player

import androidx.compose.ui.input.key.Key

/**
 * What is playing decides the few differences in remote handling; the engine never does.
 * VIDEO: films, episodes, files, DLNA, YouTube. RECORDING: a finished live-TV recording.
 * LIVE: a running channel. MUSIC: audio without a picture (Spotify).
 */
internal enum class OsdContent { VIDEO, RECORDING, LIVE, MUSIC }

internal data class OsdKeyState(
    val content: OsdContent,
    val osdVisible: Boolean,
    val panelOpen: Boolean,
    /** The seek bar shown after a jump with the OSD hidden. */
    val seekFeedbackVisible: Boolean,
    /** The OSD only shows a channel change; Up/Down keep changing channels. */
    val zapOsd: Boolean = false,
    /** False while the duration is unknown: Right then only opens the OSD. */
    val canSeekForward: Boolean = true,
)

/** Jump sizes; [held] accelerates a base step while the key is held (repeat count). */
internal data class OsdSeekSteps(
    val jumpMs: Long,
    val backMs: Long = jumpMs,
    val forwardMs: Long = jumpMs,
    val held: (Long) -> Long = { it },
)

/** The player the keys act on. Each engine maps these to its own controller. */
internal interface OsdKeyTarget {
    fun isPlaying(): Boolean
    fun togglePlayback()
    fun play()
    fun pause()
    fun stop()
    /** Relative seek; with [revealOsd] false the seek bar is shown instead of the OSD. */
    fun seek(deltaMs: Long, revealOsd: Boolean)
    fun showOsd()
    fun hideSeekFeedback()
    /** Back to the start of the title. */
    fun restart()
    /** Next title or track; nothing when there is none (the key is still used). */
    fun next()
    /** Previous track (music). */
    fun previousTrack()
    fun zap(delta: Int)
}

private val MEDIA_KEYS = setOf(
    Key.MediaPlayPause, Key.Spacebar, Key.MediaPlay, Key.MediaPause, Key.MediaStop,
    Key.MediaRewind, Key.MediaFastForward, Key.MediaNext, Key.MediaPrevious,
)
private val CONFIRM_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)
/**
 * Never handled and never opening the OSD: Back (and the keyboard keys mapped to it) belongs to
 * the back dispatcher, volume to the system. Showing the OSD first would turn a Back that stops
 * playback into one that only hides the OSD it just opened.
 */
private val PASS_KEYS = setOf(
    Key.Back, Key.Escape, Key.Backspace, Key.VolumeUp, Key.VolumeDown, Key.VolumeMute,
)

/**
 * One remote handling for every player OSD (Media3, VLC and MPV alike). Returns true when the
 * key was used. Back and volume are never handled here (see [PASS_KEYS]).
 *
 * Rules: media keys always act, also with a panel open; every other key goes to an open panel.
 * With the OSD hidden, OK opens it (or toggles playback right after a jump), Left/Right and
 * Up/Down jump (Up/Down change channels in live TV), and any other key opens the OSD. With the
 * OSD visible, navigation keys move the focus inside it.
 */
internal fun handlePlayerOsdKey(
    key: Key,
    keyDown: Boolean,
    state: OsdKeyState,
    steps: OsdSeekSteps,
    target: OsdKeyTarget,
): Boolean {
    if (!keyDown || key in PASS_KEYS) return false
    if (state.panelOpen && key !in MEDIA_KEYS) return false
    val live = state.content == OsdContent.LIVE
    val music = state.content == OsdContent.MUSIC
    when (key) {
        in CONFIRM_KEYS -> {
            if (state.osdVisible) return false
            if (state.seekFeedbackVisible) {
                val wasPlaying = target.isPlaying()
                target.togglePlayback()
                target.hideSeekFeedback()
                if (wasPlaying) target.showOsd()
            } else target.showOsd()
            return true
        }
        Key.DirectionLeft, Key.DirectionRight -> {
            if (state.osdVisible) return false
            val forward = key == Key.DirectionRight
            when {
                live -> target.showOsd()
                forward && !state.canSeekForward -> target.showOsd()
                else -> target.seek(if (forward) steps.held(steps.forwardMs) else -steps.held(steps.backMs), revealOsd = false)
            }
            return true
        }
        Key.DirectionUp, Key.DirectionDown -> {
            val delta = if (key == Key.DirectionUp) 1 else -1
            if (state.osdVisible && !(live && state.zapOsd)) return false
            if (live) target.zap(delta) else target.seek(delta * steps.jumpMs, revealOsd = false)
            return true
        }
        Key.MediaPlayPause, Key.Spacebar -> { target.togglePlayback(); target.showOsd(); return true }
        Key.MediaPlay -> { target.play(); target.showOsd(); return true }
        Key.MediaPause -> { target.pause(); target.showOsd(); return true }
        Key.MediaStop -> { target.stop(); return true }
        Key.MediaRewind, Key.MediaFastForward -> {
            // Live TV: the caller's timeshift handling owns these keys.
            if (live) return false
            val forward = key == Key.MediaFastForward
            if (forward && !state.canSeekForward) return false
            target.seek(if (forward) steps.held(steps.forwardMs) else -steps.held(steps.backMs), revealOsd = true)
            return true
        }
        Key.MediaNext -> {
            if (live) target.zap(1) else target.next()
            return true
        }
        Key.MediaPrevious -> {
            when {
                live -> target.zap(-1)
                music -> target.previousTrack()
                else -> { target.restart(); target.showOsd() }
            }
            return true
        }
        else -> {
            // Colour/info/other keys: show the OSD, and let the key go on to whatever handles it.
            target.showOsd()
            return false
        }
    }
}

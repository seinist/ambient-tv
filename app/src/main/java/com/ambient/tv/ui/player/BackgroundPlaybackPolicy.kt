package com.ambient.tv.ui.player

/** What a running playback does when the app leaves the screen. */
enum class BackgroundPlaybackAction {
    /** Nothing plays. */
    NONE,
    /** Keeps playing: music and audiobooks on a phone. */
    KEEP,
    /** Video: pause now, stop completely after [BACKGROUND_VIDEO_STOP_MS] unless the app returns. */
    PAUSE_THEN_STOP,
    /** Stops completely now: live TV and timeshift, and music on a TV going to standby. */
    STOP,
    /** The app itself opened another screen (file picker, installer, sign-in): decide later. */
    DEFER,
}

/** A video left in the background stays paused this long; returning within it continues there. */
internal const val BACKGROUND_VIDEO_STOP_MS = 30_000L

/** A screen the app opened itself counts as such when the app leaves within this time. */
internal const val EXTERNAL_FLOW_WINDOW_MS = 5_000L

/** Longest stay in a screen the app opened before the normal background rule applies. */
internal const val EXTERNAL_FLOW_LIMIT_MS = 10 * 60_000L

/**
 * The background rule for one playback.
 *
 * [screenOn]: false while the display sleeps (TV standby, phone screen off).
 * [externalFlow]: the app just opened another app's screen and expects to come back.
 */
internal fun backgroundPlaybackAction(
    active: Boolean,
    isLive: Boolean,
    hasVideo: Boolean,
    television: Boolean,
    screenOn: Boolean,
    externalFlow: Boolean,
): BackgroundPlaybackAction = when {
    !active -> BackgroundPlaybackAction.NONE
    // A standby or screen off always ends the wait for the opened screen.
    externalFlow && screenOn -> BackgroundPlaybackAction.DEFER
    // Live TV and timeshift hold a tuner, a server stream and a growing buffer: stop at once.
    isLive -> BackgroundPlaybackAction.STOP
    hasVideo -> BackgroundPlaybackAction.PAUSE_THEN_STOP
    // A TV in standby has nobody listening; a phone keeps music in a pocket.
    television && !screenOn -> BackgroundPlaybackAction.STOP
    else -> BackgroundPlaybackAction.KEEP
}

/** True when the app opened another screen shortly ([EXTERNAL_FLOW_WINDOW_MS]) before leaving. */
internal fun externalFlowActive(markedAtMs: Long?, nowMs: Long): Boolean =
    markedAtMs != null && nowMs - markedAtMs in 0..EXTERNAL_FLOW_WINDOW_MS

/** An Activity start leads to another app's screen (implicit intents included). */
internal fun opensOtherApp(targetPackage: String?, ownPackage: String): Boolean = targetPackage != ownPackage

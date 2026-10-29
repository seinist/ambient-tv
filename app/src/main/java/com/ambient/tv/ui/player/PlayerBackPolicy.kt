package com.ambient.tv.ui.player

/**
 * What remote Back does in a player, once the OSD is out of the way.
 *
 * On a television Back stops playback by default, including music and radio. The viewer can
 * keep playing with the exit-fullscreen button or the minimize-on-Back setting.
 *
 * A handheld keeps audio and live content running when leaving the player. Use the source's
 * live flag so this also works before the renderer has discovered its audio/video tracks.
 *
 * [minimizeOnBack] is the viewer's own choice (Settings › Playback): Back then minimizes everything.
 */
internal fun backLeavesPlaybackRunning(
    audioOnly: Boolean,
    handheld: Boolean,
    minimizeOnBack: Boolean = false,
    isLive: Boolean = false,
): Boolean =
    minimizeOnBack || handheld && (audioOnly || isLive)

/**
 * Settings › Playback "Back in the player": read when Back is pressed, so a change applies to
 * a player that is already open. Minimizing is what the exit-fullscreen button does.
 */
@androidx.compose.runtime.Composable
internal fun rememberBackMinimizesPlayback(): () -> Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    return androidx.compose.runtime.remember(context) { { videoImagePreferences(context).playerBackMinimizes } }
}

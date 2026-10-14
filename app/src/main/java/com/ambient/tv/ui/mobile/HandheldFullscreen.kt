package com.ambient.tv.ui.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ambient.tv.R

/**
 * Fullscreen state of a handheld video page. Landscape is fullscreen, portrait keeps the system
 * bars and the bottom navigation, so turning the device switches between both. [request] is the
 * control-bar override for devices whose auto-rotate is off; it holds that orientation until the
 * button asks for the other one or the video page is left.
 */
class HandheldFullscreen internal constructor(
    val enabled: Boolean,
    val request: (Boolean) -> Unit,
)

/** Null on television and outside video pages: no fullscreen switch exists there. */
val LocalHandheldFullscreen = compositionLocalOf<HandheldFullscreen?> { null }

/**
 * Window contract for handheld video pages: the sensor (or the control-bar override) picks the
 * orientation, browse pages get the unconstrained default back, and the system bars follow the
 * resulting orientation. Television windows keep their manifest configuration untouched.
 */
@Composable
fun rememberHandheldFullscreen(activity: Activity?, onVideoPage: Boolean): HandheldFullscreen {
    val handheld = LocalHandheldUi.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val active = handheld && onVideoPage
    var requested by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(active) { if (!active) requested = null }
    LaunchedEffect(activity, handheld, active, requested, landscape) {
        if (!handheld || activity == null) return@LaunchedEffect
        activity.requestedOrientation = when {
            !active -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            requested == true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            requested == false -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (active && landscape) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val onRequest = remember { { value: Boolean -> requested = value } }
    val fullscreen = active && landscape
    return remember(fullscreen, onRequest) { HandheldFullscreen(fullscreen, onRequest) }
}

/**
 * Fullscreen switch for the shared player control bar. Renders nothing on television or outside
 * video pages, where no orientation contract is installed.
 */
@Composable
fun HandheldFullscreenButton() {
    val fullscreen = LocalHandheldFullscreen.current ?: return
    com.ambient.tv.ui.common.PlayerIconButton(
        icon = if (fullscreen.enabled) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
        label = stringResource(if (fullscreen.enabled) R.string.player_exit_fullscreen else R.string.player_fullscreen),
        onClick = { fullscreen.request(!fullscreen.enabled) },
    )
}

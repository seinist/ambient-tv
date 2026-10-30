package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.ambient.tv.R

/** Motion shared by all centered [AppDialog] cards. Side drawers keep their own slide animation. */
internal const val APP_DIALOG_ENTER_MS = 200
internal const val APP_DIALOG_EXIT_MS = 160
internal const val APP_DIALOG_REDUCED_MS = 120
internal const val APP_DIALOG_ENTER_SCALE = 0.97f
internal const val APP_DIALOG_EXIT_SCALE = 0.98f

internal fun appDialogEnterDurationMs(reducedEffects: Boolean): Int =
    if (reducedEffects) APP_DIALOG_REDUCED_MS else APP_DIALOG_ENTER_MS

/** No overshoot: the dialog remains centered and its layout bounds never change. */
internal fun appDialogEnterScale(progress: Float, reducedEffects: Boolean): Float =
    if (reducedEffects) 1f else APP_DIALOG_ENTER_SCALE +
        (1f - APP_DIALOG_ENTER_SCALE) * progress.coerceIn(0f, 1f)

internal fun appDialogEnterAlpha(progress: Float): Float = progress.coerceIn(0f, 1f)

internal fun appDialogWindowExitStyle(reducedEffects: Boolean): Int =
    if (reducedEffects) R.style.AmbientDialogFadeExit else R.style.AmbientDialogZoomFadeExit

/**
 * Compose's Dialog disappears as soon as its owner closes it. A composable-only exit transition
 * cannot animate custom footer actions (Save, Cancel, Stop, etc.) because their callbacks remove
 * the composable immediately. An Android window-exit animation stays alive across that removal
 * and gives every closing path the same 160 ms fade/scale (120 ms fade in reduced-effects mode).
 *
 * Set this on the dialog's own window, never the activity window or all application dialogs.
 */
@Composable
internal fun AppDialogWindowExitMotion(reducedEffects: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, reducedEffects) {
        (view.parent as? DialogWindowProvider)?.window
            ?.setWindowAnimations(appDialogWindowExitStyle(reducedEffects))
        // Do not restore the previous style here: onDispose runs during the actual window
        // dismissal, and restoring it would discard the closing animation before it can play.
        onDispose { }
    }
}

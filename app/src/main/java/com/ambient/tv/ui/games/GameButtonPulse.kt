package com.ambient.tv.ui.games

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Test seam shared by local gameplay and the Companion phone controller. */
internal val LocalGameButtonHaptic = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun rememberGameButtonHaptic(): () -> Unit {
    val override = LocalGameButtonHaptic.current
    val context = LocalContext.current.applicationContext
    return remember(context, override) { override ?: GameButtonPulse(context)::pulse }
}

/**
 * Game buttons need a perceptible, short pulse, rather than the OEM-dependent text-selection
 * haptic. Only called on opt-in button press edges, after the input has already been published.
 * Resolve the motor lazily and cache the effect; neither motion nor disabled feedback uses it.
 */
internal class GameButtonPulse(private val context: Context) {
    @Suppress("DEPRECATION")
    private val motor by lazy(LazyThreadSafetyMode.NONE) {
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?: context.getSystemService(Vibrator::class.java)
        } else {
            context.getSystemService(Vibrator::class.java)
        })?.takeIf { it.hasVibrator() }
    }
    private val effect by lazy(LazyThreadSafetyMode.NONE) {
        VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    private val audioAttributes by lazy(LazyThreadSafetyMode.NONE) {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    @Suppress("DEPRECATION")
    fun pulse() {
        // Haptics are best-effort; a missing service or a denied vibration must never drop input.
        runCatching {
            val vibrator = motor ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                vibrator.vibrate(effect, audioAttributes)
            }
        }
    }
}

package com.ambient.tv.ui.games

import android.app.Application
import android.os.Vibrator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class GameButtonPulseTest {
    @Test fun vibratorReceivesPulseOnSupportedPhone() {
        val context = RuntimeEnvironment.getApplication()
        val motor = requireNotNull(context.getSystemService(Vibrator::class.java))
        val shadow = shadowOf(motor)
        shadow.setHasVibrator(true)
        GameButtonPulse(context).pulse()
        assertTrue(shadow.isVibrating)
    }

    @Test fun devicesWithoutVibrationMotorAreSilent() {
        val context = RuntimeEnvironment.getApplication()
        val motor = requireNotNull(context.getSystemService(Vibrator::class.java))
        val shadow = shadowOf(motor)
        shadow.setHasVibrator(false)
        GameButtonPulse(context).pulse()
        assertFalse(shadow.isVibrating)
    }
}

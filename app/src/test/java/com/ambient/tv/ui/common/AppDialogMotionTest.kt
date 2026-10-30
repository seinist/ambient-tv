package com.ambient.tv.ui.common

import android.app.Application
import androidx.compose.ui.graphics.TransformOrigin
import com.ambient.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppDialogMotionTest {
    @Test fun centeredDialogEntersWithSmallZoomAndFadeWithoutOvershoot() {
        assertEquals(APP_DIALOG_ENTER_MS, appDialogEnterDurationMs(false))
        assertEquals(APP_DIALOG_ENTER_SCALE, appDialogEnterScale(-1f, false), 0f)
        assertEquals(0.985f, appDialogEnterScale(0.5f, false), 0.0001f)
        assertEquals(1f, appDialogEnterScale(1f, false), 0f)
        assertEquals(1f, appDialogEnterScale(2f, false), 0f)
        assertEquals(0f, appDialogEnterAlpha(-1f), 0f)
        assertEquals(0.5f, appDialogEnterAlpha(0.5f), 0f)
        assertEquals(1f, appDialogEnterAlpha(2f), 0f)
    }

    @Test fun reducedEffectsFadeWithoutScaling() {
        assertEquals(APP_DIALOG_REDUCED_MS, appDialogEnterDurationMs(true))
        for (progress in listOf(-1f, 0f, 0.25f, 0.75f, 1f, 2f)) {
            assertEquals(1f, appDialogEnterScale(progress, true), 0f)
        }
        assertEquals(120, APP_DIALOG_REDUCED_MS)
        assertEquals(160, APP_DIALOG_EXIT_MS)
        assertEquals(0.98f, APP_DIALOG_EXIT_SCALE, 0f)
    }

    @Test fun windowExitAnimationIsPackagedForNormalAndReducedEffects() {
        val context = RuntimeEnvironment.getApplication<Application>()
        fun exitAnimation(style: Int): Int {
            val attributes = context.obtainStyledAttributes(style, intArrayOf(android.R.attr.windowExitAnimation))
            return try { attributes.getResourceId(0, 0) } finally { attributes.recycle() }
        }
        assertNotEquals(appDialogWindowExitStyle(false), appDialogWindowExitStyle(true))
        assertEquals(R.anim.app_dialog_exit, exitAnimation(appDialogWindowExitStyle(false)))
        assertEquals(R.anim.app_dialog_exit_reduced, exitAnimation(appDialogWindowExitStyle(true)))
    }
}

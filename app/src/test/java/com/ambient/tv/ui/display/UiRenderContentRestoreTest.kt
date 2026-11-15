package com.ambient.tv.ui.display

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.ambient.tv.data.local.UiRenderResolutionPolicy
import com.ambient.tv.ui.createUiRenderContentView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the Activity registry and new compositions, rather than reparenting one live View. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], application = Application::class)
class UiRenderContentRestoreTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun activityRecreationRestoresSaveableStateAcrossSystemAndEmbeddedModes() {
        var nextMode = UiRenderResolutionPolicy.SYSTEM
        var contentView: ComposeView? = null
        var savedRoute: MutableState<String>? = null
        var compositionIdentity: Any? = null
        var composedMode: String? = null
        val content: @Composable () -> Unit = {
            val route = rememberSaveable { mutableStateOf("home") }
            val identity = remember { Any() }
            val mode = LocalUiRenderEnvironment.current.modeKey
            SideEffect {
                savedRoute = route
                compositionIdentity = identity
                composedMode = mode
            }
            BasicText(route.value)
        }
        fun install(activity: ComponentActivity) {
            // Like MainActivity, a changed preference is applied only to the next created UI.
            val modeAtCreation = nextMode
            val viewport = when (modeAtCreation) {
                UiRenderResolutionPolicy.FULL_HD -> UiRenderViewport(1920, 1080, 3840, 2160)
                UiRenderResolutionPolicy.PANEL -> UiRenderViewport(3840, 2160, 3840, 2160)
                else -> null
            }
            val view = activity.createUiRenderContentView({ modeAtCreation }, { viewport }, content)
            contentView = view
            // The native attachment keeps this test independent of Robolectric's SCVH support.
            // View identity, composition entry and Activity restoration are the production path.
            activity.setContentView(view)
        }

        val application = rule.activity.application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is ComponentActivity) install(activity)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        rule.runOnIdle {
            application.registerActivityLifecycleCallbacks(callbacks)
            install(rule.activity)
        }
        try {
            rule.waitForIdle()
            rule.runOnIdle { checkNotNull(savedRoute).value = "settings/display/from-library" }
            rule.waitForIdle()
            for (mode in listOf(
                UiRenderResolutionPolicy.FULL_HD,
                UiRenderResolutionPolicy.PANEL,
                UiRenderResolutionPolicy.SYSTEM,
            )) {
                val previousView = contentView
                val previousComposition = compositionIdentity
                rule.runOnIdle {
                    assertEquals(View.NO_ID, checkNotNull(contentView).id)
                    assertNotNull(rule.activity.savedStateRegistry.getSavedStateProvider("SaveableStateRegistry:-1"))
                    nextMode = mode
                }
                rule.activityRule.scenario.recreate()
                rule.waitForIdle()
                rule.runOnIdle {
                    assertNotSame(previousView, contentView)
                    assertNotSame(previousComposition, compositionIdentity)
                    assertEquals(mode, composedMode)
                    assertEquals("settings/display/from-library", checkNotNull(savedRoute).value)
                }
            }
        } finally {
            rule.runOnIdle { application.unregisterActivityLifecycleCallbacks(callbacks) }
        }
    }
}

package com.ambient.tv.ui.display

import android.app.Application
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.ambient.tv.data.local.UiRenderResolutionPolicy
import com.ambient.tv.ui.createUiRenderContentView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], application = Application::class)
class UiRenderConfigurationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Suppress("DEPRECATION") // Update Resources without a native ViewRoot configuration dispatch.
    @Test fun configurationReachesTheRetainedComposeTreeWithUpdatedResources() {
        lateinit var host: UiRenderResolutionHost
        var observedConfiguration: Configuration? = null
        var observedDensity = 0f
        var compositionIdentity: Any? = null
        val resources = rule.activity.resources
        val originalConfiguration = Configuration(resources.configuration)
        val originalMetrics = DisplayMetrics().apply { setTo(resources.displayMetrics) }
        rule.runOnIdle {
            val activity = rule.activity
            val compose = activity.createUiRenderContentView(
                { UiRenderResolutionPolicy.FULL_HD },
                { UiRenderViewport(1920, 1080, 3840, 2160) },
            ) {
                val identity = remember { Any() }
                val configuration = LocalConfiguration.current
                val density = LocalDensity.current.density
                SideEffect {
                    observedConfiguration = Configuration(configuration)
                    observedDensity = density
                    compositionIdentity = identity
                }
                BasicText("Retained UI")
            }
            host = UiRenderResolutionHost(
                activity, UiRenderResolutionPolicy.FULL_HD, compose,
                UiRenderHostCallbacks(onFailure = { throw AssertionError(it) }),
            )
            // Attach the separate content tree directly so real Compose can run without a native
            // SCVH. It is still not a child of host, matching the configuration-forwarding issue.
            activity.setContentView(compose.parent as View)
        }
        try {
            rule.waitForIdle()
            val originalIdentity = checkNotNull(compositionIdentity)
            val updated = Configuration(originalConfiguration).apply {
                densityDpi += 160
                screenWidthDp += 120
                screenHeightDp += 80
            }
            val metrics = DisplayMetrics().apply {
                setTo(originalMetrics)
                densityDpi = updated.densityDpi
                density = densityDpi / 160f
                scaledDensity = density * updated.fontScale
            }
            rule.runOnIdle {
                resources.updateConfiguration(updated, metrics)
                host.dispatchConfigurationChanged(updated)
            }
            rule.waitForIdle()
            rule.runOnIdle {
                assertSame(originalIdentity, compositionIdentity)
                assertEquals(updated.densityDpi, checkNotNull(observedConfiguration).densityDpi)
                assertEquals(updated.screenWidthDp, checkNotNull(observedConfiguration).screenWidthDp)
                assertEquals(updated.screenHeightDp, checkNotNull(observedConfiguration).screenHeightDp)
                assertEquals(metrics.density * .5f, observedDensity, .0001f)
            }
        } finally {
            rule.runOnIdle {
                host.close()
                resources.updateConfiguration(originalConfiguration, originalMetrics)
            }
        }
    }
}

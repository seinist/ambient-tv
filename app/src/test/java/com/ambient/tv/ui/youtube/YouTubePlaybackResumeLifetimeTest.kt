package com.ambient.tv.ui.youtube

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YouTubePlaybackResumeLifetimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun anMpvOrVlcOutputRestartCannotForgetTheUsersSeek() {
        val video = mutableStateOf("first-video")
        val controller = mutableStateOf(Any())
        val externalSeekVersion = mutableIntStateOf(10)
        lateinit var current: YouTubePlaybackResume
        var mountedController: Any? = null
        rule.setContent {
            val owner = controller.value
            val resume = rememberYouTubePlaybackResume(video.value, 0L, externalSeekVersion.intValue)
            SideEffect { current = resume; mountedController = owner }
        }
        lateinit var beforeRestart: YouTubePlaybackResume
        rule.runOnIdle {
            beforeRestart = current
            current.userSeek()
            externalSeekVersion.intValue++
            // Runtime HDR/output changes replace the controller inside the same video screen.
            controller.value = Any()
        }
        rule.runOnIdle {
            assertSame(controller.value, mountedController)
            assertSame(beforeRestart, current)
            assertNull(current.resolve(false, 90_000L, true, externalSeekVersion.intValue))
            video.value = "next-video"
            controller.value = Any()
        }
        rule.runOnIdle {
            assertNotSame(beforeRestart, current)
            assertEquals(25_000L, current.resolve(false, 25_000L, true, externalSeekVersion.intValue))
        }
    }

    @Test fun aCompletedZeroResumeStaysConsumedAfterTheControllerIsReplaced() {
        val controller = mutableStateOf(Any())
        lateinit var current: YouTubePlaybackResume
        rule.setContent {
            val owner = controller.value
            val resume = rememberYouTubePlaybackResume("video", 0L, 10)
            SideEffect { current = resume; check(owner === controller.value) }
        }
        lateinit var beforeRestart: YouTubePlaybackResume
        rule.runOnIdle {
            beforeRestart = current
            assertNull(current.resolve(false, 0L, true, 10))
            controller.value = Any()
        }
        rule.runOnIdle {
            assertSame(beforeRestart, current)
            assertNull(current.resolve(false, 90_000L, true, 10))
        }
    }
}

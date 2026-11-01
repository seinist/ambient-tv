package com.ambient.tv.ui.tvheadend

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class LiveTvChannelRowFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun dpadRightFromLiveChannelActivatesInfoAndNotPlayback() {
        val channel = LiveTvChannel(uuid = "one", name = "Test Channel", number = 1, iconUrl = null)
        val programme = LiveTvProgramme(
            eventId = "programme", channelUuid = channel.uuid, channelName = channel.name,
            title = "Current programme", startEpochSeconds = 60L, stopEpochSeconds = 3_600L,
        )
        var playCount = 0
        var infoCount = 0
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    Box(Modifier.width(1_000.dp)) {
                        ChannelRow(
                            channel = channel, current = programme, next = null, recording = false,
                            imageHeaders = emptyMap(), onWatch = { playCount++ },
                            onEvent = { infoCount++ }, onFocused = {},
                            modifier = Modifier.testTag("channel"),
                        )
                    }
                }
            }
        }
        val row = rule.onNodeWithTag("channel")
        row.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        row.assertIsFocused()
        row.performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionCenter)
        }
        rule.runOnIdle {
            assertEquals("Right then Center should open the programme dialog", 1, infoCount)
            assertEquals("Info must not start channel playback", 0, playCount)
        }
    }
}

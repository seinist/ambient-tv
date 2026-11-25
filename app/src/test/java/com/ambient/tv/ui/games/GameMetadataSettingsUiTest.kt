package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import com.ambient.tv.R
import com.ambient.tv.games.metadata.GameMetadataCredentials
import com.ambient.tv.games.metadata.GameMetadataOptions
import com.ambient.tv.games.metadata.GameMetadataProviderIds
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the real settings dialog, including its width, scroll viewport and compiled-key branch. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-port-mdpi")
class GameMetadataSettingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var savedOptions: GameMetadataOptions? = null
    private var savedCredentials: GameMetadataCredentials? = null
    private var dismissals = 0

    private fun mount(handheld: Boolean = true, builtIn: Boolean = true) {
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    GameMetadataSettingsDialog(
                        initialOptions = GameMetadataOptions(),
                        initialCredentials = GameMetadataCredentials(),
                        builtInScreenScraper = false,
                        builtInGamesDb = builtIn,
                        onDismiss = { dismissals++ },
                        onSave = { options, credentials ->
                            savedOptions = options
                            savedCredentials = credentials
                            true
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun builtInGamesDbRemainsVisibleSelectableAndSaveableOnANarrowPhone() {
        mount()
        rule.onNodeWithText("TheGamesDB").assertIsDisplayed().performTouchInput { click() }
        rule.onNodeWithText("TheGamesDB").assertIsSelected()
        rule.onNodeWithText(rule.activity.getString(R.string.game_metadata_settings_builtin))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.game_metadata_settings_tgdb_key)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.item_edit_save))
            .assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(GameMetadataProviderIds.THEGAMESDB, savedOptions?.provider)
            // Compiled credentials stay out of the editable state and are never re-saved.
            assertEquals(GameMetadataCredentials(), savedCredentials)
            assertEquals(1, dismissals)
        }
    }

    @Test fun providerWithoutACompiledKeyStillOffersManualSetup() {
        mount(builtIn = false)
        rule.onNodeWithText("TheGamesDB").performScrollTo().performTouchInput { click() }
        rule.onNodeWithText(rule.activity.getString(R.string.game_metadata_account_settings))
            .performScrollTo().performTouchInput { click() }
        rule.onNodeWithText(rule.activity.getString(R.string.game_metadata_settings_tgdb_key))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.game_metadata_settings_builtin)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w960dp-h720dp-land-mdpi")
    fun builtInGamesDbCanBeSelectedAndSavedByTvRemote() {
        mount(handheld = false)
        val provider = rule.onNodeWithText("TheGamesDB")
        provider.performScrollTo().assertIsDisplayed().performTouchInput { click() }
        provider.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText(rule.activity.getString(R.string.game_metadata_settings_builtin))
            .performScrollTo().assertIsDisplayed()
        val save = rule.onNodeWithText(rule.activity.getString(R.string.item_edit_save))
        // TV action buttons receive focus from navigation, not from a simulated touch tap.
        // Set up that focus explicitly, then exercise the real keyboard activation below.
        save.assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        save.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle {
            assertEquals(GameMetadataProviderIds.THEGAMESDB, savedOptions?.provider)
            assertEquals(1, dismissals)
        }
    }
}

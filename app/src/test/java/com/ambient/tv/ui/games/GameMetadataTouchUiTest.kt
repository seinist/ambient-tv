package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameMetadataCandidate
import com.ambient.tv.games.metadata.GameMetadataProviderIds
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Use real pointer events: semantic performClick alone masks TV Surface's missing touch path. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-port-mdpi")
class GameMetadataTouchUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var enabled by mutableStateOf(true)
    private val selected = mutableListOf<Int>()
    private var dismissals = 0

    private fun mount(handheld: Boolean = true, imageKind: GameArtworkKind? = null, count: Int = 1,
                      matches: List<GameMetadataCandidate>? = null) {
        val choices = matches ?: (0 until count).map { index -> GameMetadataCandidate(GameMetadata(
            provider = GameMetadataProviderIds.THEGAMESDB,
            providerGameId = index.toString(),
            title = "Matching game $index",
            year = 1994,
            sourceUrl = "https://thegamesdb.net/game.php?id=$index",
        )) }
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    AppDialog(onDismiss = { dismissals++ }, title = "Choose game", scrollable = false) {
                        TvLazyColumn(Modifier.heightIn(max = 360.dp).testTag("matches"),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(choices.indices.toList(), key = { gameMetadataChoiceKey(choices[it], imageKind) }) { index ->
                                GameMetadataMatchRow(
                                    match = choices[index],
                                    imageKind = imageKind,
                                    preview = null,
                                    enabled = enabled,
                                    onClick = { selected += index },
                                    modifier = Modifier.testTag("match-$index"),
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun handheldQueryAcceptsTouchTextAndImeSearch() {
        var query by mutableStateOf("Original game")
        var submitted = ""
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    AppDialog(onDismiss = { dismissals++ }, title = "Identify game",
                        scrollable = false, captureInitialFocus = false) {
                        GameMetadataSearchField(query, { query = it },
                            onSearch = { submitted = query })
                    }
                }
            }
        }
        val editor = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
        editor.performTouchInput { click() }
        editor.performTextReplacement("New title")
        editor.performImeAction()
        rule.runOnIdle {
            assertEquals("New title", query)
            assertEquals("New title", submitted)
            assertEquals(0, dismissals)
        }
    }

    @Test fun tappingAGamesDbMatchSelectsItOnceWithoutDismissingTheDialog() {
        mount()
        rule.onNodeWithTag("match-0").assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(listOf(0), selected)
            assertEquals(0, dismissals)
        }
    }

    @Test fun artworkChoicesUseTheSameTouchPathAndIgnoreTapsWhileSaving() {
        mount(imageKind = GameArtworkKind.COVER)
        rule.runOnIdle { enabled = false }
        rule.onNodeWithTag("match-0").performTouchInput { click() }
        rule.runOnIdle { assertEquals(emptyList<Int>(), selected); enabled = true }
        rule.onNodeWithTag("match-0").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(listOf(0), selected)
            assertEquals(0, dismissals)
        }
    }

    @Test fun scrollingResultsDoesNotApplyTheGameUnderTheFinger() {
        mount(count = 12)
        rule.onNodeWithTag("matches").performTouchInput { swipeUp() }
        rule.runOnIdle {
            assertEquals(emptyList<Int>(), selected)
            assertEquals(0, dismissals)
        }
    }

    @Test fun cleanedRegionalTitlesRemainDistinguishableAndSelectable() {
        val titles = listOf("A game (Europe)", "A game (USA)")
        mount(matches = titles.map { title -> GameMetadataCandidate(GameMetadata(
            provider = GameMetadataProviderIds.LIBRETRO, providerGameId = "snes|$title",
            title = title, canonicalTitle = title, region = "eu",
        )) })
        rule.onNodeWithTag("match-0").assert(hasText("A game"))
            .assert(hasText("Europe", substring = true)).performTouchInput { click() }
        rule.onNodeWithTag("match-1").assert(hasText("A game"))
            .assert(hasText("USA", substring = true)).performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(listOf(0, 1), selected)
            assertEquals(0, dismissals)
        }
    }

    @Test fun cleanedLanguageEditionsRemainDistinguishableAndSelectable() {
        val titles = listOf("A game (Europe) (En,de)", "A game (Europe) (En,fr)")
        mount(matches = titles.map { title -> GameMetadataCandidate(GameMetadata(
            provider = GameMetadataProviderIds.LIBRETRO, providerGameId = "snes|$title",
            title = title, canonicalTitle = title, region = "eu", language = "de",
        )) })
        rule.onNodeWithTag("match-0").assert(hasText("A game"))
            .assert(hasText("En, De", substring = true)).performTouchInput { click() }
        rule.onNodeWithTag("match-1").assert(hasText("A game"))
            .assert(hasText("En, Fr", substring = true)).performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(listOf(0, 1), selected)
            assertEquals(0, dismissals)
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h720dp-land-mdpi")
    fun tvRemoteAndKeyboardEachApplyTheFocusedMatchOnce() {
        mount(handheld = false)
        val match = rule.onNodeWithTag("match-0")
        match.assertIsDisplayed().performTouchInput { click() }
        match.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        match.performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle {
            assertEquals(listOf(0, 0), selected)
            assertEquals(0, dismissals)
        }
    }
}

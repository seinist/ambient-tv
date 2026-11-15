package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.youtube.YouTubeBrowsePlaybackContext
import com.ambient.tv.ui.youtube.YouTubeFilter
import com.ambient.tv.ui.youtube.YouTubeSort
import com.ambient.tv.ui.youtube.prepareYouTubeBrowseReturn
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A retained destination can become current before its flow collectors resume. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
class PlaybackReturnEntryUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Hold subscriber delivery to exercise that ordering without depending on dispatcher luck. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class LaggingStateFlow<T>(
        private val delivered: MutableStateFlow<T>,
    ) : StateFlow<T> by delivered {
        constructor(initial: T) : this(MutableStateFlow(initial))
        private var published = delivered.value
        override val value: T get() = published
        val deliveredValue: T get() = delivered.value
        fun publish(value: T) { published = value }
        fun deliver() { delivered.value = published }
    }

    private data class Page(
        val rows: List<YouTubeRow>,
        val focused: String = "video-0",
        val revision: Int = 0,
    )

    private data class Frame(
        val target: String,
        val firstKey: Any?,
        val firstIndex: Int,
        val visit: Any?,
    )

    private class Fixture(val owner: NavBackStackEntry, val navigation: Boolean = false) {
        val state = LaggingStateFlow(Page(rows(10)))
        val pending = LaggingStateFlow<YouTubeBrowsePlaybackContext.ReturnSnapshot?>(null)
        var active by mutableStateOf(false)
        var phase by mutableStateOf(0)
        val firstFrames = mutableMapOf<Int, Frame>()

        @Composable
        fun Content() {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalActiveNavigationEntryId provides if (active) owner.id else "player",
                LocalHandheldUi provides false,
            ) {
                val stored = state.collectAsCurrentState()
                if (navigation) {
                    val returned = rememberNavigationPlaybackReturn()
                    // Home/Section/Files accept only items already available on this entry.
                    val eligible = remember(returned) {
                        returned?.takeIf { event -> stored.rows.any { row -> row.items.any { it.videoId == event.itemId } } }
                    }
                    Viewport(stored.rows, eligible?.itemId ?: stored.focused,
                        eligible?.revision ?: stored.revision, eligible?.revision)
                } else {
                    val current = pending.collectAsCurrentState()
                    val preview = prepareYouTubeBrowseReturn("feed:home", 3L, current,
                        YouTubeSort.DEFAULT, YouTubeFilter.ALL, descending = false)
                    val returned = rememberPlaybackReturnOnEntry(current.takeIf { preview != null }) { it.revision }
                    val visit = rememberPlaybackReturnVisitToken(returned?.revision)
                    val prepared = preview.takeIf { returned != null }
                    Viewport(prepared?.rows ?: stored.rows, prepared?.target?.itemId ?: stored.focused,
                        prepared?.revision ?: stored.revision, visit)
                }
            }
        }

        @Composable
        private fun Viewport(rows: List<YouTubeRow>, target: String, revision: Any, visit: Any?) {
            val phase = phase
            val keys = rows.flatMap { row -> row.items.map { it.videoId } }
            key(revision) {
                val list = rememberBrowseRowState(initialFirstVisibleItemIndex = keys.indexOf(target).coerceAtLeast(0))
                LazyRow(state = list, modifier = Modifier.size(240.dp, 60.dp).layout { measurable, constraints ->
                    val placed = measurable.measure(constraints)
                    layout(placed.width, placed.height) {
                        firstFrames.putIfAbsent(phase, Frame(target, list.layoutInfo.visibleItemsInfo.firstOrNull()?.key,
                            list.firstVisibleItemIndex, visit))
                        placed.place(0, 0)
                    }
                }) {
                    items(keys, key = { it }) { Box(Modifier.size(80.dp, 60.dp)) }
                }
            }
        }

        fun returnFromPlayer(vararg played: Int) {
            for ((index, item) in played.withIndex()) pending.publish(snapshot(item, index + 1))
            phase++
            active = true
        }

        companion object {
            fun rows(count: Int) = listOf(YouTubeRow("Videos", (0 until count).map { YouTubeItem("video-$it", "Video $it") }, id = "videos"))
            fun snapshot(item: Int, revision: Int) = YouTubeBrowsePlaybackContext.ReturnSnapshot(
                scope = "feed:home", revision = revision, cacheRevision = 3L,
                target = YouTubeBrowsePlaybackContext.ReturnTarget("videos", "video-$item"),
                rows = rows(20),
            )
        }
    }

    @Before fun resetMailbox() = NavigationPlaybackReturns.clear()
    @After fun clearMailbox() = NavigationPlaybackReturns.clear()

    private fun mount(navigation: Boolean = false): Fixture {
        lateinit var owner: NavBackStackEntry
        rule.runOnUiThread {
            val controller = NavHostController(rule.activity).apply {
                setLifecycleOwner(rule.activity)
                setViewModelStore(ViewModelStore())
                setOnBackPressedDispatcher(OnBackPressedDispatcher())
                navigatorProvider.addNavigator(ComposeNavigator())
                setGraph(createGraph(startDestination = "browse") { composable("browse") {} }, null)
            }
            owner = checkNotNull(controller.currentBackStackEntry)
        }
        val fixture = Fixture(owner, navigation)
        rule.setContent { fixture.Content() }
        rule.waitForIdle()
        return fixture
    }

    @Test fun lastNextAndPlayerLoadedPageDetermineTheFirstReturningLayout() {
        val fixture = mount()
        rule.runOnIdle { fixture.returnFromPlayer(2, 7, 14) }
        rule.waitForIdle()
        assertNull(fixture.pending.deliveredValue)
        // This was recorded by placement, before any later focus or scroll effect.
        assertEquals(Frame("video-14", "video-14", 14, 3), fixture.firstFrames[1])
    }

    @Test fun acknowledgingTheReturnCannotExposeTheOldUiStateOrLoseItsVisitToken() {
        val fixture = mount()
        rule.runOnIdle { fixture.returnFromPlayer(2, 7, 14) }
        rule.waitForIdle()
        rule.runOnIdle {
            fixture.state.publish(Page(Fixture.rows(20), "video-14", 3))
            fixture.pending.publish(null)
            fixture.pending.deliver()
            fixture.phase++
        }
        rule.waitForIdle()
        assertEquals("video-0", fixture.state.deliveredValue.focused)
        assertEquals(Frame("video-14", "video-14", 14, 3), fixture.firstFrames[2])
    }

    @Test fun backgroundNextCannotClaimAnAlreadyActivePageButCanBeAcceptedOnReentry() {
        val fixture = mount()
        rule.runOnIdle { fixture.active = true; fixture.phase++ }
        rule.waitForIdle()
        rule.runOnIdle {
            fixture.pending.publish(Fixture.snapshot(14, 4))
            fixture.phase++
        }
        rule.waitForIdle()
        assertEquals(Frame("video-0", "video-0", 0, null), fixture.firstFrames[2])
        rule.runOnIdle { fixture.active = false; fixture.phase++ }
        rule.waitForIdle()
        rule.runOnIdle { fixture.active = true; fixture.phase++ }
        rule.waitForIdle()
        assertEquals(Frame("video-14", "video-14", 14, 4), fixture.firstFrames[4])
    }

    @Test fun aLaterBackgroundRevisionCannotReplaceTheAcceptedVisit() {
        val fixture = mount()
        rule.runOnIdle { fixture.returnFromPlayer(2, 7, 14) }
        rule.waitForIdle()
        rule.runOnIdle {
            fixture.state.publish(Page(Fixture.rows(20), "video-14", 3))
            fixture.pending.publish(Fixture.snapshot(17, 4))
            fixture.phase++
        }
        rule.waitForIdle()
        assertEquals(Frame("video-14", "video-14", 14, 3), fixture.firstFrames[2])
    }

    @Test fun navigationUsesTheLatestPublishedRowsAndLastItemBeforeItsCollectorsResume() {
        val fixture = mount(navigation = true)
        rule.runOnIdle {
            NavigationPlaybackReturns.prepare(fixture.owner.id, "video-2")
            NavigationPlaybackReturns.prepare(fixture.owner.id, "video-7")
            NavigationPlaybackReturns.prepare(fixture.owner.id, "video-14")
            fixture.state.publish(Page(Fixture.rows(20)))
            fixture.phase++
            fixture.active = true
        }
        rule.waitForIdle()
        val revision = checkNotNull(fixture.firstFrames[1]).visit
        assertEquals(10, fixture.state.deliveredValue.rows.single().items.size)
        assertEquals(Frame("video-14", "video-14", 14, revision), fixture.firstFrames[1])
        assertNull(NavigationPlaybackReturns.pending.value[fixture.owner.id])
        rule.runOnIdle { fixture.phase++ }
        rule.waitForIdle()
        assertEquals(fixture.firstFrames[1], fixture.firstFrames[2])
    }

    @Test fun aMissingItemLoadedAfterEntryCannotTriggerALateFocusJump() {
        val fixture = mount(navigation = true)
        rule.runOnIdle {
            NavigationPlaybackReturns.prepare(fixture.owner.id, "video-14")
            fixture.phase++
            fixture.active = true
        }
        rule.waitForIdle()
        assertEquals(Frame("video-0", "video-0", 0, null), fixture.firstFrames[1])
        rule.runOnIdle {
            fixture.state.publish(Page(Fixture.rows(20)))
            fixture.state.deliver()
            fixture.phase++
        }
        rule.waitForIdle()
        assertEquals(Frame("video-0", "video-0", 0, null), fixture.firstFrames[2])
    }

    @Test fun currentValueReadKeepsObservingOrdinaryLaterUpdates() {
        val fixture = mount()
        rule.runOnIdle { fixture.active = true; fixture.phase++ }
        rule.waitForIdle()
        rule.runOnIdle {
            fixture.state.publish(Page(Fixture.rows(20), "video-6", 1))
            fixture.state.deliver()
            fixture.firstFrames.remove(1)
        }
        rule.waitForIdle()
        assertEquals(Frame("video-6", "video-6", 6, null), fixture.firstFrames[1])
    }
}

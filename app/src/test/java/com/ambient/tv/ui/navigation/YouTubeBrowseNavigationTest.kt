package com.ambient.tv.ui.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubeBrowseNavigationTest {
    class ChannelPageState : ViewModel()

    @Test fun channelWithoutPlayerBelowSimplyOpensThePlayerAboveIt() {
        val nav = controller()
        nav.navigate(Destinations.youtubeChannel("UC1", "Kanal"))
        nav.navigateToYouTubeBrowsePlayer(Destinations.youtubePlayer("v1", 1L), closeDetails = true)
        assertEquals(listOf(Destinations.YOUTUBE, Destinations.YOUTUBE_CHANNEL, Destinations.YOUTUBE_PLAYER), nav.routes())
        nav.popBackStack()
        assertEquals(Destinations.YOUTUBE_CHANNEL, nav.currentDestination?.route)
    }

    @Test fun channelOpenedFromThePlayerReplacesThatPlayerBelowIt() {
        val nav = controller()
        nav.navigate(Destinations.youtubePlayer("old"))
        nav.navigate(Destinations.youtubeChannel("UC1", "Kanal", "https://a/b.png"))
        val channel = nav.currentBackStackEntry!!
        val state = ViewModelProvider(channel)[ChannelPageState::class.java]

        nav.navigateToYouTubeBrowsePlayer(Destinations.youtubePlayer("new", 7L), closeDetails = true)

        assertEquals(listOf(Destinations.YOUTUBE, Destinations.YOUTUBE_CHANNEL, Destinations.YOUTUBE_PLAYER), nav.routes())
        assertEquals("new", nav.currentBackStackEntry?.arguments?.getString("videoId"))
        assertEquals(7L, nav.currentBackStackEntry?.arguments?.getLong("browseSession"))
        // Back (or the real end of the queue) lands on the very same channel page: loaded
        // pages, scroll position and the browse session survive, so focus can return.
        nav.popBackStack()
        val restored = nav.currentBackStackEntry!!
        assertEquals(Destinations.YOUTUBE_CHANNEL, restored.destination.route)
        assertEquals(channel.id, restored.id)
        assertEquals("UC1", restored.arguments?.getString("channelId"))
        assertSame(state, ViewModelProvider(restored)[ChannelPageState::class.java])
        nav.popBackStack()
        assertEquals(Destinations.YOUTUBE, nav.currentDestination?.route)
    }

    @Test fun repeatedChannelHopsNeverLeaveAnOldPlayerInHistory() {
        val nav = controller()
        nav.navigate(Destinations.youtubePlayer("a"))
        nav.navigate(Destinations.youtubeChannel("UC1"))
        nav.navigateToYouTubeBrowsePlayer(Destinations.youtubePlayer("b", 1L), closeDetails = true)
        nav.navigate(Destinations.youtubeChannel("UC2"))
        nav.navigateToYouTubeBrowsePlayer(Destinations.youtubePlayer("c", 2L), closeDetails = true)
        assertEquals(
            listOf(Destinations.YOUTUBE, Destinations.YOUTUBE_CHANNEL, Destinations.YOUTUBE_CHANNEL, Destinations.YOUTUBE_PLAYER),
            nav.routes(),
        )
        assertEquals(listOf("UC1", "UC2"), nav.currentBackStack.value
            .filter { it.destination.route == Destinations.YOUTUBE_CHANNEL }
            .map { it.arguments?.getString("channelId") })
    }

    @Test fun detailsFirstClosesTheDetailsWithThePlayerSoBackLandsOnTheChannel() {
        val nav = controller()
        nav.navigate(Destinations.youtubeChannel("UC1"))
        nav.navigate(Destinations.youtubeDetail("v1", 3L))
        nav.navigateToYouTubeBrowsePlayer(Destinations.youtubePlayer("v1", 3L), closeDetails = true)
        assertEquals(listOf(Destinations.YOUTUBE, Destinations.YOUTUBE_CHANNEL, Destinations.YOUTUBE_PLAYER), nav.routes())
        // Next/Previous change only the player's content, never the back stack.
        nav.popBackStack()
        assertEquals(Destinations.YOUTUBE_CHANNEL, nav.currentDestination?.route)
    }

    @Test fun detailsOfAChannelOpenedFromThePlayerAlsoReplaceTheOldPlayer() {
        val nav = controller()
        nav.navigate(Destinations.youtubePlayer("old"))
        nav.navigate(Destinations.youtubeChannel("UC1"))
        val channel = nav.currentBackStackEntry!!
        nav.navigate(Destinations.youtubeDetail("v1", 4L))
        nav.navigateToYouTubeBrowsePlayer(Destinations.youtubePlayer("v1", 4L), closeDetails = true)
        assertEquals(listOf(Destinations.YOUTUBE, Destinations.YOUTUBE_CHANNEL, Destinations.YOUTUBE_PLAYER), nav.routes())
        nav.popBackStack()
        assertEquals(channel.id, nav.currentBackStackEntry?.id)
    }

    @Test fun detailsWithoutABrowseQueueStayInHistory() {
        val nav = controller()
        nav.navigate(Destinations.youtubeDetail("v1"))
        nav.navigateToYouTubeBrowsePlayer(Destinations.youtubePlayer("v1"), closeDetails = false)
        assertEquals(listOf(Destinations.YOUTUBE, Destinations.YOUTUBE_DETAIL, Destinations.YOUTUBE_PLAYER), nav.routes())
    }

    @Test fun feedPlaybackFromHomeKeepsTheFeedBelowThePlayer() {
        val nav = controller()
        nav.navigate(Destinations.youtubeDetail("v1", 5L))
        nav.navigateToYouTubeBrowsePlayer(Destinations.youtubePlayer("v1", 5L), closeDetails = true)
        assertEquals(listOf(Destinations.YOUTUBE, Destinations.YOUTUBE_PLAYER), nav.routes())
    }

    private fun NavHostController.routes() = currentBackStack.value.mapNotNull { it.destination.route }
        .filter { it != graph.route }

    private fun controller(): NavHostController {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        return NavHostController(context).apply {
            setLifecycleOwner(owner)
            setViewModelStore(ViewModelStore())
            setOnBackPressedDispatcher(OnBackPressedDispatcher())
            navigatorProvider.addNavigator(ComposeNavigator())
            setGraph(createGraph(startDestination = Destinations.YOUTUBE) {
                composable(Destinations.YOUTUBE) {}
                composable(
                    Destinations.YOUTUBE_DETAIL,
                    arguments = listOf(
                        navArgument("videoId") { type = NavType.StringType },
                        navArgument("browseSession") { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) {}
                composable(
                    Destinations.YOUTUBE_CHANNEL,
                    arguments = listOf(
                        navArgument("channelId") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        navArgument("avatar") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {}
                composable(
                    Destinations.YOUTUBE_PLAYER,
                    arguments = listOf(
                        navArgument("videoId") { type = NavType.StringType },
                        navArgument("trailer") { type = NavType.BoolType; defaultValue = false },
                        navArgument("browseSession") { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) {}
            }, null)
        }
    }
}

package com.ambient.tv.ui.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.createGraph
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppHomeNavigationTest {
    @Test fun homeReplacesALibrarySectionAboveTheStartDestination() {
        val nav = controller(Destinations.HOME)
        nav.navigate(Destinations.section("movies")) { popUpTo(nav.graph.startDestinationId) }
        assertEquals(Destinations.SECTION, nav.currentDestination?.route)
        nav.openAppHome()
        assertEquals(Destinations.HOME, nav.currentDestination?.route)
    }

    @Test fun homeOpensWhenTheGraphStartedOnAnotherSystem() {
        val nav = controller(Destinations.YOUTUBE)
        nav.navigate(Destinations.PLEX) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        assertEquals(Destinations.PLEX, nav.currentDestination?.route)
        nav.openAppHome()
        assertEquals(Destinations.HOME, nav.currentDestination?.route)
    }

    @Test fun homeStaysPutWhenItIsAlreadyShowing() {
        val nav = controller(Destinations.HOME)
        nav.openAppHome()
        assertEquals(Destinations.HOME, nav.currentDestination?.route)
        assertEquals(1, nav.currentBackStack.value.count { it.destination.route == Destinations.HOME })
    }

    private fun controller(start: String): NavHostController {
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
            setGraph(createGraph(startDestination = start) {
                composable(Destinations.HOME) {}
                composable(Destinations.YOUTUBE) {}
                composable(Destinations.PLEX) {}
                composable(Destinations.SECTION) {}
            }, null)
        }
    }
}

package com.ambient.tv.livetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.ui.tvheadend.*
import dagger.hilt.android.AndroidEntryPoint

/** Private debug fixture: production screen, navigation arguments, view models and player. */
@AndroidEntryPoint
class LiveTvAuditActivity : ComponentActivity() {
    lateinit var navigation: NavHostController
    var television: TvheadendViewModel? = null
    var player: TvheadendPlayerViewModel? = null
    var home: com.ambient.tv.ui.home.HomeViewModel? = null
    var search: com.ambient.tv.ui.search.SearchViewModel? = null
    var sidebar: com.ambient.tv.ui.navigation.SidebarViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val source = intent.getStringExtra("sourceId")!!
        setContent {
            AmbientTvTheme {
                navigation = rememberNavController()
                val sidebarVm: com.ambient.tv.ui.navigation.SidebarViewModel = hiltViewModel()
                SideEffect { sidebar = sidebarVm }
                LaunchedEffect(Unit) { sidebarVm.refresh() }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NavHost(navigation, startDestination = Destinations.liveTvSection("live", source)) {
                        composable("audit_home") {
                            val vm: com.ambient.tv.ui.home.HomeViewModel = hiltViewModel()
                            SideEffect { home = vm }
                            com.ambient.tv.ui.home.HomeScreen(onOpenSettings = {}, onItemClick = {},
                                onWatchLiveChannel = { navigation.navigate(Destinations.tvhPlayer("channel", it.uuid, it.sourceId)) }, viewModel = vm)
                        }
                        composable("audit_search") {
                            val vm: com.ambient.tv.ui.search.SearchViewModel = hiltViewModel()
                            SideEffect { search = vm }
                            com.ambient.tv.ui.search.SearchScreen(onItemClick = {}, onYouTubeClick = {}, onSpotifyClick = {},
                                onTvheadendChannelClick = { navigation.navigate(Destinations.tvhPlayer("channel", it.uuid, it.sourceId)) },
                                onTvheadendProgramClick = {}, onTvheadendRecordingClick = {}, onBack = { navigation.popBackStack() }, viewModel = vm)
                        }
                        composable(Destinations.LIVE_TV_SECTION, arguments = listOf(
                            navArgument("tab") { type = NavType.StringType },
                            navArgument("sourceId") { type = NavType.StringType; nullable = true; defaultValue = null },
                        )) { entry ->
                            val id = entry.arguments?.getString("sourceId")
                            val vm: TvheadendViewModel = hiltViewModel()
                            SideEffect { television = vm }
                            TvheadendScreen(
                                initialTab = TvheadendTab.entries.firstOrNull { it.name.equals(entry.arguments?.getString("tab"), true) } ?: TvheadendTab.LIVE,
                                onOpenSettings = {}, onBack = { navigation.popBackStack() },
                                onSelectTab = { navigation.navigate(Destinations.liveTvSection(it.name.lowercase(), id)) },
                                onWatchChannel = { navigation.navigate(Destinations.tvhPlayer("channel", it, id)) },
                                onPlayRecording = { navigation.navigate(Destinations.tvhPlayer("recording", it, id)) }, viewModel = vm,
                            )
                        }
                        composable(Destinations.TVH_PLAYER, arguments = listOf(
                            navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType },
                            navArgument("sourceId") { type = NavType.StringType; nullable = true; defaultValue = null },
                        )) {
                            val vm: TvheadendPlayerViewModel = hiltViewModel()
                            SideEffect { player = vm }
                            TvheadendPlayerScreen(onBack = { navigation.popBackStack() }, viewModel = vm)
                        }
                    }
                }
            }
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface LiveTvAuditDependencies {
    fun registry(): LiveTvRegistry
    fun preferences(): com.ambient.tv.data.local.AppPreferences
    fun tokens(): com.ambient.tv.data.local.TokenStore
}

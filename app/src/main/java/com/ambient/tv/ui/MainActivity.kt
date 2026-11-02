package com.ambient.tv.ui

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.ambient.tv.R
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

import com.ambient.tv.util.suspendResult
import android.os.Bundle
import android.os.SystemClock
import android.app.Activity
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import com.ambient.tv.ui.sound.SilenceSystemMenuSounds
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.player.PlaybackService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.NavType
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.data.local.MediaSelectionPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.spotify.SpotifyAuthorizationPolicy
import com.ambient.tv.spotify.SpotifyPlaybackTakeoverUiPresence
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.ui.browse.FilteredBrowseScreen
import com.ambient.tv.ui.common.ScreensaverInputGate
import com.ambient.tv.ui.common.ScreensaverInputPhase
import com.ambient.tv.ui.common.ScreensaverInputResult
import com.ambient.tv.ui.common.ScreensaverOverlay
import com.ambient.tv.ui.common.ScreensaverPresentation
import com.ambient.tv.ui.common.AppScreensaver
import com.ambient.tv.ui.common.AppBackground
import com.ambient.tv.ui.common.PlexRefreshBus
import com.ambient.tv.ui.detail.DetailScreen
import com.ambient.tv.ui.detail.episodeDetailAfterPlayback
import com.ambient.tv.ui.detail.seriesDetailIsDirectlyUnder
import com.ambient.tv.ui.home.HomeScreen
import com.ambient.tv.ui.plex.PlexLibraryScreen
import com.ambient.tv.ui.jellyfin.JellyfinLibraryScreen
import com.ambient.tv.ui.emby.EmbyLibraryScreen
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.openAppHome
import com.ambient.tv.ui.navigation.configuredContentRoute
import com.ambient.tv.ui.navigation.isParameterizedSidebarRoute
import com.ambient.tv.ui.navigation.sidebarConcreteRoute
import com.ambient.tv.ui.navigation.sidebarEntryArmed
import com.ambient.tv.ui.navigation.LocalSidebarContentPending
import com.ambient.tv.ui.navigation.LocalSidebarContentReadyReporter
import com.ambient.tv.ui.navigation.Sidebar
import com.ambient.tv.ui.navigation.MediaSelectionDialog
import com.ambient.tv.ui.navigation.MediaSelectionResolver
import com.ambient.tv.ui.keyboard.KodiKeyboardAction
import com.ambient.tv.ui.keyboard.kodiKeyboardAction
import com.ambient.tv.ui.keyboard.kodiSeekTarget
import com.ambient.tv.ui.input.TvMouseButtonAction
import com.ambient.tv.ui.input.shouldStartMiddleMouseDrag
import com.ambient.tv.ui.input.tvMouseButtonAction
import com.ambient.tv.ui.navigation.ResolvedMediaSelection
import com.ambient.tv.ui.navigation.returnsFromFullscreenPlayer
import com.ambient.tv.ui.youtube.YouTubeSelectionDialog
import com.ambient.tv.ui.youtube.YouTubeSelectionRoute
import com.ambient.tv.ui.youtube.youtubeSelectionRoute
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.NextUpCandidate
import com.ambient.tv.ui.player.PlayerScreen
import com.ambient.tv.ui.player.LocalPersistentPlaybackVideoHost
import com.ambient.tv.ui.player.LocalVisibleBackgroundVideo
import com.ambient.tv.ui.player.PersistentPlaybackVideoHost
import com.ambient.tv.ui.search.SearchScreen
import com.ambient.tv.ui.server.ServerSelectScreen
import com.ambient.tv.ui.server.ServerSelectViewModel
import com.ambient.tv.ui.section.SectionScreen
import com.ambient.tv.ui.settings.SettingsScreen
import com.ambient.tv.ui.settings.SettingsExitGuard
import com.ambient.tv.ui.settings.SettingsUiState
import com.ambient.tv.ui.setup.InitialSetupScreen
import com.ambient.tv.ui.tvheadend.TvheadendPlayerScreen
import com.ambient.tv.ui.tvheadend.TvheadendScreen
import com.ambient.tv.ui.youtube.YouTubeScreen
import com.ambient.tv.ui.youtube.YouTubeDetailScreen
import com.ambient.tv.ui.navigation.navigateToYouTubeBrowsePlayer
import com.ambient.tv.ui.navigation.navigateToTelegramPlayer
import com.ambient.tv.ui.youtube.YouTubePlayerScreen
import com.ambient.tv.ui.spotify.SpotifyHomeScreen
import com.ambient.tv.ui.spotify.SpotifyDetailScreen
import com.ambient.tv.ui.spotify.SpotifyPlayerScreen
import com.ambient.tv.ui.spotify.SpotifyPlaybackTakeoverDialog
import com.ambient.tv.ui.theme.AmbientTvTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/** A running service may outlive the browse UI; only its configuration recreation restores focus. */
private val browseUiSessionPolicy = BrowseUiSessionPolicy()

@AndroidEntryPoint
open class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLocaleContext.wrap(newBase))
    }

    @Inject lateinit var wireGuardRoute: com.ambient.tv.vpn.WireGuardRouteController
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var performanceProfileResolver: com.ambient.tv.ui.performance.PerformanceProfileResolver
    @Inject lateinit var plexRefreshBus: PlexRefreshBus
    @Inject lateinit var browseReconnects: com.ambient.tv.data.browse.BrowseReconnects
    @Inject lateinit var browseDiskStore: dagger.Lazy<com.ambient.tv.data.browse.BrowseDiskStore>
    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var mediaRepository: MediaRepository
    @Inject lateinit var radioBrowserRepository: com.ambient.tv.data.radio.RadioBrowserRepository
    @Inject lateinit var telegramRepository: com.ambient.tv.data.telegram.TelegramRepository
    @Inject lateinit var radioPlaybackLauncher: com.ambient.tv.ui.radio.RadioPlaybackLauncher
    @Inject lateinit var radioPlaybackQueue: com.ambient.tv.ui.radio.RadioPlaybackQueue
    @Inject lateinit var playbackAvailability: com.ambient.tv.data.browse.PlaybackAvailability
    @Inject lateinit var dlnaStore: com.ambient.tv.data.dlna.DlnaStore
    @Inject lateinit var liveTvBrowseRegistry: javax.inject.Provider<com.ambient.tv.livetv.LiveTvRegistry>
    @Inject lateinit var photoViewerSession: javax.inject.Provider<com.ambient.tv.data.photos.PhotoViewerSession>
    @Inject lateinit var spotifyRepository: javax.inject.Provider<com.ambient.tv.spotify.SpotifyRepository>
    @Inject lateinit var resumeConnectivityMonitor: ResumeConnectivityMonitor
    @Inject lateinit var connectionNotifications: com.ambient.tv.notifications.ConnectionNotifications
    @Inject lateinit var launcherOpener: com.ambient.tv.launcher.LauncherOpener
    @Inject lateinit var appUpdater: com.ambient.tv.update.AppUpdater
    @Inject lateinit var recordingNotifications: com.ambient.tv.notifications.RecordingNotifications
    @Inject lateinit var companionManager: com.ambient.tv.companion.CompanionManager
    @Inject lateinit var companionSettingsRefresh: com.ambient.tv.companion.CompanionSettingsRefresh
    private var companionIntentPending = true
    @Inject lateinit var uiSoundPlayer: com.ambient.tv.ui.sound.UiSoundPlayer

    private val browseUiOwner = Any()
    private var ownsBrowseUiSession = false

    /** The Android system owns the floating window; the video decoder stays in the app shell. */
    internal var systemPipVisible by mutableStateOf(false)
        private set
    internal var systemPipEntering by mutableStateOf(false)
        private set
    private var pipAutoEntryAllowed = false
    private var pipWasOpen = false
    private var pipParams = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
    private val pipAvailable by lazy {
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    internal fun handheldPipSupported(): Boolean =
        this is com.ambient.tv.ui.mobile.HandheldActivity && pipAvailable

    /** Called only when playback eligibility, geometry, or the user's PiP preference changes. */
    internal fun updateHandheldPip(eligible: Boolean, aspectRatio: Float, sourceRect: Rect?) {
        if (!handheldPipSupported()) return
        pipAutoEntryAllowed = eligible
        val ratio = Rational((aspectRatio * 1000f).toInt().coerceIn(419, 2390), 1000)
        pipParams = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .apply {
                if (sourceRect != null && sourceRect.width() > 0 && sourceRect.height() > 0) {
                    setSourceRectHint(sourceRect)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(eligible)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
        // On Android 10/11 onUserLeaveHint enters explicitly; Android 12+ can auto-enter
        // smoothly via the continuously updated eligibility flag.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { setPictureInPictureParams(pipParams) }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!pipAutoEntryAllowed || !handheldPipSupported() || isInPictureInPictureMode) return
        // Even with Android 12+ automatic entry, this early callback lets the UI prepare
        // the previously hidden mini-player surface before the system shrinks the Activity.
        systemPipEntering = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            !runCatching { enterPictureInPictureMode(pipParams) }.getOrDefault(false)
        ) {
            systemPipEntering = false
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) pipWasOpen = true
        systemPipVisible = isInPictureInPictureMode
        com.ambient.tv.ui.player.BackgroundPlaybackGuard.setPictureInPicture(isInPictureInPictureMode)
        systemPipEntering = false
    }

    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip) {
            // Android 15+: remove app chrome at the beginning of the shrink animation.
            systemPipEntering = true
        }
    }

    private fun stopDismissedPipIfFinishing() {
        if (pipWasOpen && isFinishing && !isChangingConfigurations) {
            // PiP's X removes the Activity. Phone background policy otherwise deliberately
            // keeps the audio running, which would leave a hidden video playing after dismissal.
            BackgroundPlaybackSession.stop()
            pipWasOpen = false
        }
    }

    private val lastInteractionElapsedMs = mutableLongStateOf(SystemClock.elapsedRealtime())
    private val screensaverActive = mutableStateOf(false)
    private val screensaverOverlayPresent = mutableStateOf(false)
    private val screensaverInput = ScreensaverInputGate()
    private val consumedKodiKeyboardKeys = mutableSetOf<Int>()
    private val consumedMouseButtons = mutableSetOf<Int>()
    private var suppressMouseBackKeyUntilMs = 0L
    private var middleMouseDownTimeMs = 0L
    private var middleMouseDownX = 0f
    private var middleMouseDownY = 0f
    private var middleMouseActive = false
    private var middleMouseDragging = false

    private fun setScreensaverActive(active: Boolean) {
        if (active) screensaverOverlayPresent.value = true
        if (!active && screensaverActive.value) registerInteraction()
        screensaverActive.value = active
    }

    override fun onStart() {
        super.onStart()
        appUpdater.onAppStart()
        BackgroundPlaybackSession.retryBlockedPlaybackService(this)
        com.ambient.tv.data.browse.BrowseWorkBudget.foreground = true
        if (com.ambient.tv.ui.mobile.isTelevision(this)) com.ambient.tv.companion.CompanionRemoteInput.register(window) {
            registerUserInteraction(); setScreensaverActive(false)
        }
        if (this is com.ambient.tv.ui.mobile.HandheldActivity || com.ambient.tv.ui.mobile.isTelevision(this)) companionManager.foreground(true, com.ambient.tv.ui.mobile.isTelevision(this))
        if (companionIntentPending && (this is com.ambient.tv.ui.mobile.HandheldActivity || com.ambient.tv.ui.mobile.isTelevision(this))) {
            companionIntentPending = false
            companionManager.handleIntent(intent)
        }
        SpotifyPlaybackTakeoverUiPresence.onActivityStarted()
        // ProcessLifecycle drives the recheck; keep the singleton started if Application lagged.
        if (::resumeConnectivityMonitor.isInitialized) resumeConnectivityMonitor.start()
    }

    override fun onStop() {
        // A stopped Activity outside PiP (or a dismissed PiP window) is real background again.
        if (!isInPictureInPictureMode || isFinishing) com.ambient.tv.ui.player.BackgroundPlaybackGuard.setPictureInPicture(false)
        stopDismissedPipIfFinishing()
        com.ambient.tv.data.browse.BrowseWorkBudget.foreground = false
        com.ambient.tv.data.browse.BrowseWorkBudget.interact()
        // Display snapshots (e.g. a deferred YouTube refresh) must reach disk before Android
        // may reclaim the process, also while playback had kept the writer waiting.
        browseDiskStore.get().flush()
        com.ambient.tv.companion.CompanionRemoteInput.unregister(window)
        if (this is com.ambient.tv.ui.mobile.HandheldActivity || com.ambient.tv.ui.mobile.isTelevision(this)) companionManager.foreground(false, com.ambient.tv.ui.mobile.isTelevision(this))
        SpotifyPlaybackTakeoverUiPresence.onActivityStopped()
        // A phone must not remain an indefinitely advertised Connect receiver while idle in the
        // background. Active Spotify playback keeps its media FGS/receiver; television keeps the
        // intentional always-available Connect behavior.
        if (!com.ambient.tv.ui.mobile.isTelevision(this)) {
            runCatching { spotifyRepository.get() }.getOrNull()?.let { spotify ->
                if (!spotify.shouldKeepReceiverInBackground) spotify.stop()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopDismissedPipIfFinishing()
        if (ownsBrowseUiSession) browseUiSessionPolicy.destroy(browseUiOwner, isChangingConfigurations)
        super.onDestroy()
    }

    /** All browse positions belong to the UI session, including stores held by background services. */
    private fun resetBrowseSessionPositions() {
        appPreferences.clearSessionFocus()
        com.ambient.tv.ui.radio.RadioFocusMemory.clear()
        radioPlaybackQueue.clearReturnFocus()
        com.ambient.tv.ui.detail.DetailFocusSessions.clear()
        liveTvBrowseRegistry.get().clearBrowsePositions()
        com.ambient.tv.livetv.LiveTvReturnFocus.clear()
        photoViewerSession.get().clearReturnFocus()
    }

    private fun consumeScreensaverInput(result: ScreensaverInputResult): Boolean {
        if (result == ScreensaverInputResult.DISMISS) { setScreensaverActive(false); registerUserInteraction() }
        return result != ScreensaverInputResult.PASS
    }

    /**
     * Every start from this Activity passes here: startActivity, result launchers (file picker,
     * settings pages, installer permission). Another app's screen opened from the app is part of
     * the app's own flow, so running playback is not ended by the background rule.
     */
    @Deprecated("Called by the framework and the Activity Result API")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (com.ambient.tv.ui.player.opensOtherApp(intent.component?.packageName, packageName)) {
            com.ambient.tv.ui.player.BackgroundPlaybackGuard.markExternalFlow()
        }
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onPause() {
        setScreensaverActive(false)
        screensaverOverlayPresent.value = false
        screensaverInput.reset()
        resetMiddleMousePan()
        consumedMouseButtons.clear()
        consumedKodiKeyboardKeys.clear()
        super.onPause()
    }
    private val openPlaybackRequest = mutableLongStateOf(0)
    /** Bumped when a launcher start after a root Back must show a fresh Home (see onNewIntent). */
    private val freshUiSessionRequest = mutableLongStateOf(0)
    private var leftFromRoot = false

    /** Handheld root Back: leave the app but keep it warm; the next launcher start is fresh. */
    internal fun leaveFromRoot() {
        leftFromRoot = true
        moveTaskToBack(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ownsBrowseUiSession && launcherStartsFreshUiSession(leftFromRoot, intent.action, intent.categories)) {
            leftFromRoot = false
            resetBrowseSessionPositions()
            freshUiSessionRequest.longValue++
        }
        launcherOpener.open(intent)
        com.ambient.tv.search.ExternalSearch.offer(intent)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) companionManager.handleIntent(intent)
        else companionIntentPending = true
        if (intent.action == PlaybackService.ACTION_OPEN) {
            registerUserInteraction()
            openPlaybackRequest.longValue++
        }
    }

    private fun registerUserInteraction() {
        com.ambient.tv.ui.player.SleepSession.cancelStandbyRequest()
        registerInteraction()
        BackgroundPlaybackSession.registerUserInteraction()
    }

    private fun registerInteraction() {
        lastInteractionElapsedMs.longValue = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        // Back via Recents (no launcher intent) continues the previous session.
        leftFromRoot = false
        if (!isInPictureInPictureMode) {
            pipWasOpen = false // PiP expanded back into the app.
            systemPipEntering = false // Or the platform declined the transition.
        }
        com.ambient.tv.ui.player.SleepSession.cancelStandbyRequest()
        registerInteraction()
        if (::appPreferences.isInitialized && com.ambient.tv.ui.mobile.isTelevision(this) && appPreferences.spotifyEnabled) {
            runCatching { spotifyRepository.get().reconcileReceiverForPlatform() }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) com.ambient.tv.companion.CompanionRemoteInput.hide()
        super.onWindowFocusChanged(hasFocus)
    }

    // Public Activity/Window.Callback override; core's intermediate implementation is restricted.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The start intro covers the UI: a key only skips it, volume keeps working.
        if (com.ambient.tv.ui.intro.IntroPresentation.active && event.keyCode !in setOf(
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) com.ambient.tv.ui.intro.IntroPresentation.skip()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) com.ambient.tv.companion.CompanionRemoteInput.hide()
        com.ambient.tv.data.browse.BrowseWorkBudget.interact()
        if (event.action == KeyEvent.ACTION_DOWN) uiSoundPlayer.navigationInput(event.keyCode, event.eventTime)
        val phase = when (event.action) {
            KeyEvent.ACTION_DOWN -> ScreensaverInputPhase.DOWN
            KeyEvent.ACTION_UP -> ScreensaverInputPhase.UP
            else -> ScreensaverInputPhase.OTHER
        }
        val keyIdentity = (event.deviceId.toLong() shl 32) xor (event.keyCode.toLong() and 0xffffffffL)
        if (consumeScreensaverInput(screensaverInput.key(keyIdentity, phase, event.repeatCount,
                screensaverActive.value, screensaverOverlayPresent.value))) return true
        if (event.action == KeyEvent.ACTION_DOWN) registerUserInteraction()
        // Sleep pre-warning: OK extends by 30 min instead of reaching the focused control.
        if (com.ambient.tv.ui.player.SleepWarningKeyGate.takesOk(BackgroundPlaybackSession.sleepWarningDeadline != null) && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) BackgroundPlaybackSession.extendSleepTimer()
            return true
        }
        // Some Android TV mouse drivers emit both BUTTON_BACK and KEYCODE_BACK for one side-button
        // press. The generic mouse event already navigated; consume the duplicate key pair.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && SystemClock.elapsedRealtime() <= suppressMouseBackKeyUntilMs) {
            return true
        }
        if (BackgroundPlaybackSession.snapshot.value.active && event.keyCode in setOf(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> BackgroundPlaybackSession.userToggle()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> BackgroundPlaybackSession.userPlay()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> BackgroundPlaybackSession.userPause()
                    KeyEvent.KEYCODE_MEDIA_STOP -> BackgroundPlaybackSession.stop()
                }
            }
            return true
        }
        // Focused Compose controls and editors get first refusal. This keeps typing, selection,
        // clipboard shortcuts and Space activation intact while playback hotkeys remain global.
        if (super.dispatchKeyEvent(event)) return true
        if (!com.ambient.tv.ui.mobile.isTelevision(this)) return false

        val action = kodiKeyboardAction(
            keyCode = event.keyCode,
            ctrlPressed = event.isCtrlPressed,
            altPressed = event.isAltPressed,
            shiftPressed = event.isShiftPressed,
            metaPressed = event.isMetaPressed,
        ) ?: return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val playbackActive = BackgroundPlaybackSession.snapshot.value.active
                if (action.requiresPlayback && !playbackActive) return false
                consumedKodiKeyboardKeys += event.keyCode
                if (event.repeatCount == 0 || action.repeatable) performKodiKeyboardAction(action)
                true
            }
            KeyEvent.ACTION_UP -> consumedKodiKeyboardKeys.remove(event.keyCode)
            else -> false
        }
    }

    private fun performKodiKeyboardAction(action: KodiKeyboardAction) {
        when (action) {
            KodiKeyboardAction.BACK -> onBackPressedDispatcher.onBackPressed()
            KodiKeyboardAction.TOGGLE_PLAYBACK -> BackgroundPlaybackSession.userToggle()
            KodiKeyboardAction.PLAY -> BackgroundPlaybackSession.userPlay()
            KodiKeyboardAction.STOP -> BackgroundPlaybackSession.stop()
            KodiKeyboardAction.SEEK_BACKWARD,
            KodiKeyboardAction.SEEK_FORWARD,
            -> {
                val snapshot = BackgroundPlaybackSession.snapshot.value
                if (snapshot.metadata?.isLive == true) return
                val stepMs = if (::appPreferences.isInitialized) {
                    appPreferences.bigSeekJumpSeconds.coerceAtLeast(1) * 1_000L
                } else 10_000L
                val deltaMs = if (action == KodiKeyboardAction.SEEK_FORWARD) stepMs else -stepMs
                BackgroundPlaybackSession.seekTo(
                    kodiSeekTarget(snapshot.status.positionMs, snapshot.status.durationMs, deltaMs),
                )
            }
            KodiKeyboardAction.VOLUME_DOWN -> adjustKeyboardVolume(android.media.AudioManager.ADJUST_LOWER)
            KodiKeyboardAction.VOLUME_UP -> adjustKeyboardVolume(android.media.AudioManager.ADJUST_RAISE)
            KodiKeyboardAction.TOGGLE_MUTE -> adjustKeyboardVolume(android.media.AudioManager.ADJUST_TOGGLE_MUTE)
        }
    }

    private fun adjustKeyboardVolume(direction: Int) {
        runCatching {
            getSystemService(android.media.AudioManager::class.java)?.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                direction,
                android.media.AudioManager.FLAG_SHOW_UI,
            )
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        com.ambient.tv.data.browse.BrowseWorkBudget.interact()
        // Hover/remote pointer noise is not a new press and must not cancel a preview.
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            val phase = if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) ScreensaverInputPhase.DOWN else ScreensaverInputPhase.UP
            if (consumeScreensaverInput(screensaverInput.touch(phase, screensaverActive.value, screensaverOverlayPresent.value))) return true
            if (phase == ScreensaverInputPhase.DOWN) registerUserInteraction()
        } else if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val deliberate = event.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f || event.getAxisValue(MotionEvent.AXIS_HSCROLL) != 0f
            if (screensaverActive.value && deliberate) {
                setScreensaverActive(false)
                registerUserInteraction()
            }
            if (screensaverOverlayPresent.value) return true
            if (deliberate) registerUserInteraction()
        } else if (screensaverOverlayPresent.value) return true

        if (com.ambient.tv.ui.mobile.isTelevision(this) && handleMiddleMousePan(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE && consumedMouseButtons.remove(event.actionButton)) {
            return true
        }
        // Text editors and other Compose controls get the first chance to show a native context
        // menu. Unhandled secondary/back clicks then behave like Android/Kodi Back.
        if (super.dispatchGenericMotionEvent(event)) return true
        if (!com.ambient.tv.ui.mobile.isTelevision(this) || event.actionMasked != MotionEvent.ACTION_BUTTON_PRESS) {
            return false
        }
        return when (tvMouseButtonAction(event.actionButton)) {
            TvMouseButtonAction.BACK -> {
                consumedMouseButtons += event.actionButton
                suppressMouseBackKeyUntilMs = SystemClock.elapsedRealtime() + 300L
                onBackPressedDispatcher.onBackPressed()
                true
            }
            null -> false
        }
    }

    /**
     * Middle-button drag is translated to a regular touch drag. Compose's existing nested scroll
     * then owns direction and bounds, so the same gesture pans vertical lists/grids and horizontal
     * Home/content rows without screen-specific handlers. A stationary middle click does nothing.
     */
    private fun handleMiddleMousePan(event: MotionEvent): Boolean {
        val middlePressed = event.buttonState and MotionEvent.BUTTON_TERTIARY != 0
        when {
            (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && event.actionButton == MotionEvent.BUTTON_TERTIARY) ||
                (event.actionMasked == MotionEvent.ACTION_DOWN && middlePressed) -> {
                middleMouseDownTimeMs = event.eventTime
                middleMouseDownX = event.x
                middleMouseDownY = event.y
                middleMouseActive = true
                middleMouseDragging = false
                consumedMouseButtons += MotionEvent.BUTTON_TERTIARY
                return true
            }
            event.actionMasked in setOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE) && middleMouseActive -> {
                if (!middleMouseDragging && shouldStartMiddleMouseDrag(
                        middleMouseDownX,
                        middleMouseDownY,
                        event.x,
                        event.y,
                        android.view.ViewConfiguration.get(this).scaledTouchSlop,
                    )) {
                    dispatchSyntheticMouseDrag(MotionEvent.ACTION_DOWN, middleMouseDownX, middleMouseDownY, middleMouseDownTimeMs)
                    middleMouseDragging = true
                }
                if (middleMouseDragging) {
                    dispatchSyntheticMouseDrag(MotionEvent.ACTION_MOVE, event.x, event.y, event.eventTime)
                }
                return true
            }
            (event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE && event.actionButton == MotionEvent.BUTTON_TERTIARY) ||
                (event.actionMasked == MotionEvent.ACTION_UP && middleMouseActive) -> {
                if (middleMouseDragging) {
                    dispatchSyntheticMouseDrag(MotionEvent.ACTION_UP, event.x, event.y, event.eventTime)
                }
                resetMiddleMousePan()
                return true
            }
            event.actionMasked == MotionEvent.ACTION_CANCEL && middleMouseActive -> {
                if (middleMouseDragging) {
                    dispatchSyntheticMouseDrag(MotionEvent.ACTION_CANCEL, event.x, event.y, event.eventTime)
                }
                resetMiddleMousePan()
                return true
            }
        }
        return false
    }

    private fun dispatchSyntheticMouseDrag(action: Int, x: Float, y: Float, eventTime: Long) {
        val synthetic = MotionEvent.obtain(
            middleMouseDownTimeMs,
            eventTime,
            action,
            x,
            y,
            0,
        )
        synthetic.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            super.dispatchTouchEvent(synthetic)
        } finally {
            synthetic.recycle()
        }
    }

    private fun resetMiddleMousePan() {
        consumedMouseButtons.remove(MotionEvent.BUTTON_TERTIARY)
        middleMouseDownTimeMs = 0L
        middleMouseActive = false
        middleMouseDragging = false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        com.ambient.tv.data.browse.BrowseWorkBudget.interact()
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> ScreensaverInputPhase.DOWN
            MotionEvent.ACTION_UP -> ScreensaverInputPhase.UP
            MotionEvent.ACTION_CANCEL -> ScreensaverInputPhase.CANCEL
            else -> ScreensaverInputPhase.OTHER
        }
        if (consumeScreensaverInput(screensaverInput.touch(phase, screensaverActive.value, screensaverOverlayPresent.value))) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) registerUserInteraction()
        if (com.ambient.tv.ui.mobile.isTelevision(this) &&
            event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
            handleMiddleMousePan(event)) return true
        return super.dispatchTouchEvent(event)
    }

    /**
     * Android 13+ phones hide playback/companion notifications until the user allows them.
     * Asked once, on the phone UI only; a TV never shows the prompt.
     */
    private fun requestNotificationPermissionOnce() {
        if (this !is com.ambient.tv.ui.mobile.HandheldActivity || android.os.Build.VERSION.SDK_INT < 33) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("ambient_permission_prompts", MODE_PRIVATE)
        if (prefs.getBoolean("post_notifications_asked", false)) return
        prefs.edit().putBoolean("post_notifications_asked", true).apply()
        requestPermissions(arrayOf(permission), 4_713)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("companion.intent.pending", companionIntentPending)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // A redirecting TV/phone Activity does not own the UI session. It may merely forward a
        // launcher intent to an already running Activity, whose focus must remain untouched.
        val television = com.ambient.tv.ui.mobile.isTelevision(this)
        ownsBrowseUiSession = television == (this !is com.ambient.tv.ui.mobile.HandheldActivity)
        val restoreBrowseState = ownsBrowseUiSession && browseUiSessionPolicy.create(browseUiOwner, savedInstanceState != null)
        // Only a configuration recreation resumes saved focus/scroll/back-stack state. A new
        // UI starts fresh even if a background service kept the old process alive.
        val restoredState = savedInstanceState.takeIf { restoreBrowseState }
        super.onCreate(restoredState)
        // After super.onCreate: Hilt injects the preferences there.
        com.ambient.tv.ui.common.PlayerOsdTimeout.seconds = appPreferences.playerOsdSeconds
        browseReconnects.start()
        companionIntentPending = restoredState?.getBoolean("companion.intent.pending", false) ?: true
        if (this is com.ambient.tv.ui.mobile.HandheldActivity && com.ambient.tv.ui.mobile.isTelevision(this)) {
            startActivity(Intent(intent).setClass(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
            return
        }
        if (this !is com.ambient.tv.ui.mobile.HandheldActivity && !com.ambient.tv.ui.mobile.isTelevision(this)) {
            startActivity(Intent(intent).setClass(this, com.ambient.tv.ui.mobile.HandheldActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
            return
        }
        if (restoredState == null) resetBrowseSessionPositions()
        // Set here, not in the manifest: a manifest orientation also applies to the phone launch
        // before the handheld trampoline, which made portrait phones start in landscape.
        if (com.ambient.tv.ui.mobile.isTelevision(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val startupDisplay = if (android.os.Build.VERSION.SDK_INT >= 30) display else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        val startupMode = startupDisplay?.mode
        appPreferences.initializeUiScale(
            isTelevision = com.ambient.tv.ui.mobile.isTelevision(this),
            displayWidth = startupMode?.physicalWidth ?: 0,
            displayHeight = startupMode?.physicalHeight ?: 0,
        )
        if (restoredState == null) requestNotificationPermissionOnce()
        // A launcher row (Watch Next / preview channel) opened the app on a title. After the
        // handheld hand-off, so the activity that stays connects once.
        if (restoredState == null) {
            launcherOpener.open(intent)
            com.ambient.tv.search.ExternalSearch.offer(intent)
        }
        if (intent?.action == PlaybackService.ACTION_OPEN) registerUserInteraction()
        // Launcher cold start only: a launcher row or playback notification goes straight to its target.
        val startIntro = if (restoredState == null && intent?.action == Intent.ACTION_MAIN) {
            appPreferences.introAnimationKey
        } else com.ambient.tv.data.local.IntroAnimation.OFF
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                // The intro draws the mark itself over the same background; no cross-fade needed.
                if (startIntro != com.ambient.tv.data.local.IntroAnimation.OFF) splash.remove() else splash.animate().alpha(0f).setDuration(160L).withEndAction { splash.remove() }.start()
            }
        }
        enableEdgeToEdge()
        disableDefaultFocusHighlight()
        val spotify = spotifyRepository.get()
        connectionNotifications.start()
        recordingNotifications.start()
        setContent {
            com.ambient.tv.notifications.DisplayChangeNotifications(this@MainActivity, appPreferences)
            var uiScale by remember { mutableStateOf(appPreferences.uiScale) }
            var introStyle by remember { mutableStateOf(startIntro) }
            var introRun by remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) {
                com.ambient.tv.ui.intro.IntroPreviewRequests.requests.collect {
                    introStyle = it
                    introRun++
                }
            }
            var accentColorKey by remember { mutableStateOf(appPreferences.accentColorKey) }
            var customAccentHex by remember { mutableStateOf(appPreferences.customAccentHex) }
            var customPlaybackAccentHex by remember { mutableStateOf(appPreferences.customPlaybackAccentHex) }
            var dualAccentEnabled by remember { mutableStateOf(appPreferences.dualAccentEnabled) }
            var themeModeKey by remember { mutableStateOf(appPreferences.themeModeKey) }
            var uiHdrModeKey by remember { mutableStateOf(appPreferences.uiHdrModeKey) }
            var sdrToHdrEnabled by remember { mutableStateOf(appPreferences.sdrToHdrEnabled) }
            var sdrToHdrPeakNits by remember { mutableStateOf(appPreferences.sdrToHdrPeakNits) }
            var sdrToHdrReferenceWhiteNits by remember { mutableStateOf(appPreferences.sdrToHdrReferenceWhiteNits) }
            // Panel writes apply immediately; unrelated preference writes must not erase a settings preview.
            LaunchedEffect(appPreferences) {
                var storedEnabled = appPreferences.sdrToHdrEnabled
                var storedPeak = appPreferences.sdrToHdrPeakNits
                var storedWhite = appPreferences.sdrToHdrReferenceWhiteNits
                appPreferences.changes().collect {
                    val enabled = appPreferences.sdrToHdrEnabled
                    val peak = appPreferences.sdrToHdrPeakNits
                    if (enabled != storedEnabled) sdrToHdrEnabled = enabled
                    if (peak != storedPeak) sdrToHdrPeakNits = peak
                    storedEnabled = enabled
                    storedPeak = peak
                    val white = appPreferences.sdrToHdrReferenceWhiteNits
                    if (white != storedWhite) sdrToHdrReferenceWhiteNits = white
                    storedWhite = white
                }
            }
            val uiHdrState = com.ambient.tv.ui.display.rememberUiHdrState(this, uiHdrModeKey, sdrToHdrEnabled, sdrToHdrPeakNits, sdrToHdrReferenceWhiteNits)
            var uiPerformanceModeKey by remember { mutableStateOf(appPreferences.effectiveUiPerformanceModeKey()) }
            var playbackPerformanceModeKey by remember { mutableStateOf(appPreferences.effectivePlaybackPerformanceModeKey()) }
            var backgroundVideoDimPercent by remember { mutableStateOf(appPreferences.backgroundVideoDimPercent) }
            var visibleBackgroundVideoPlayback by remember {
                mutableStateOf(appPreferences.visibleBackgroundVideoPlayback)
            }
            // Player one-tap switch (Sparsam override notice): persist and apply like the setting.
            LaunchedEffect(Unit) {
                com.ambient.tv.ui.performance.PlaybackPerformanceRequests.requests.collect { key ->
                    appPreferences.playbackPerformanceModeKey = key
                    playbackPerformanceModeKey = key
                }
            }
            val performanceProfile = remember(uiPerformanceModeKey, playbackPerformanceModeKey) {
                performanceProfileResolver.setPreviewModeKeys(uiPerformanceModeKey, playbackPerformanceModeKey)
                com.ambient.tv.ui.performance.PerformanceProfile.resolve(
                    com.ambient.tv.ui.performance.PerformanceMode.fromKey(uiPerformanceModeKey),
                    performanceProfileResolver.isLowEndDevice(),
                    com.ambient.tv.ui.performance.PerformanceMode.fromKey(playbackPerformanceModeKey),
                )
            }
            var filterBarShown by remember { mutableStateOf(appPreferences.filterBarShown) }
            val filterBarSetting = remember(filterBarShown) {
                com.ambient.tv.ui.common.FilterBarSetting(filterBarShown) { appPreferences.filterBarShown = it; filterBarShown = it }
            }
            CompositionLocalProvider(
                com.ambient.tv.ui.display.LocalUiHdrState provides uiHdrState,
                com.ambient.tv.ui.mobile.LocalHandheldUi provides (!com.ambient.tv.ui.mobile.isTelevision(this@MainActivity) && this@MainActivity is com.ambient.tv.ui.mobile.HandheldActivity),
                com.ambient.tv.ui.performance.LocalPerformanceProfile provides performanceProfile,
                com.ambient.tv.ui.sound.LocalUiSoundPlayer provides uiSoundPlayer,
                com.ambient.tv.ui.common.LocalFilterBarSetting provides filterBarSetting,
            ) {
            AmbientTvTheme(
                uiScale = uiScale,
                accentColorKey = accentColorKey,
                customAccentHex = customAccentHex,
                customPlaybackAccentHex = customPlaybackAccentHex,
                complementaryPlayback = dualAccentEnabled,
                themeModeKey = themeModeKey,
            ) {
                com.ambient.tv.ui.common.LocalNetworkPermissionHost(
                    activity = this@MainActivity,
                    hasConfiguredService = com.ambient.tv.util.LocalNetworkAccess.hasConfiguredService(
                        appPreferences,
                        dlnaConfigured = dlnaStore.servers().isNotEmpty(),
                    ),
                    onGranted = {
                        plexRefreshBus.requestRefresh()
                        // A pairing QR or Direct Share may have arrived before Android granted
                        // local-network access. Resume only the stored, user-originated action.
                        companionManager.onLocalNetworkPermissionGranted()
                        if (appPreferences.spotifyEnabled && com.ambient.tv.ui.mobile.isTelevision(this@MainActivity)) {
                            Thread({ runCatching { spotifyRepository.get().reconcileReceiverForPlatform() } }, "SpotifyPermissionInit").start()
                        }
                    },
                ) {
                // Overscan inset wraps the whole UI, playback included, so nothing lands behind the TV bezel.
                androidx.compose.foundation.layout.Box {
                com.ambient.tv.ui.common.OverscanBox(if (com.ambient.tv.ui.mobile.isTelevision(this@MainActivity)) appPreferences.overscanPercent else 0) {
                    AmbientTvApp(
                        openPlaybackRequest = openPlaybackRequest.longValue + if (intent.action == PlaybackService.ACTION_OPEN) 1 else 0,
                        freshUiSessionRequest = freshUiSessionRequest.longValue,
                        appPreferences = appPreferences,
                        tokenStore = tokenStore,
                        spotifyRepository = spotify,
                        plexRefreshBus = plexRefreshBus,
                        mediaRepository = mediaRepository,
                        radioBrowserRepository = radioBrowserRepository,
                        telegramRepository = telegramRepository,
                        radioPlaybackLauncher = radioPlaybackLauncher,
                        playbackAvailability = playbackAvailability,
                        lastInteractionElapsedMs = lastInteractionElapsedMs.longValue,
                        screensaverActive = screensaverActive.value,
                        screensaverOverlayPresent = screensaverOverlayPresent.value,
                        onScreensaverActiveChanged = ::setScreensaverActive,
                        onScreensaverHidden = {
                            if (screensaverActive.value) false else {
                                screensaverOverlayPresent.value = false
                                true
                            }
                        },
                        onRegisterInteraction = ::registerInteraction,
                        onUiPreferencesPreview = { scale, accent, customHex, customPlaybackHex, dualAccent, theme, hdr, convertSdr, peakNits, referenceWhite ->
                            uiScale = scale
                            accentColorKey = accent
                            customAccentHex = customHex
                            customPlaybackAccentHex = customPlaybackHex
                            dualAccentEnabled = dualAccent
                            themeModeKey = theme
                            uiHdrModeKey = hdr
                            sdrToHdrEnabled = convertSdr
                            sdrToHdrPeakNits = peakNits
                            sdrToHdrReferenceWhiteNits = referenceWhite
                        },
                        onPerformanceModePreview = { uiMode, playbackMode ->
                            uiPerformanceModeKey = uiMode
                            playbackPerformanceModeKey = playbackMode
                        },
                        visibleBackgroundVideoPlayback = visibleBackgroundVideoPlayback,
                        backgroundVideoDimPercent = backgroundVideoDimPercent,
                        onVisibleBackgroundVideoPlaybackPreview = { visible, dim ->
                            visibleBackgroundVideoPlayback = visible
                            backgroundVideoDimPercent = dim
                        },
                        onCompanionSettingsApplied = { companionSettingsRefresh.refresh() },
                        onUiLanguageSaved = {
                            UiLocaleContext.applyToResources(applicationContext, appPreferences.uiLanguageKey)
                            recreate()
                        },
                    )
                }
                if (introStyle != com.ambient.tv.data.local.IntroAnimation.OFF) {
                    androidx.compose.runtime.key(introRun) {
                        com.ambient.tv.ui.intro.IntroAnimationOverlay(introStyle, onFinished = { introStyle = com.ambient.tv.data.local.IntroAnimation.OFF })
                    }
                }
                }
                }
            }
        }
        }
        disableDefaultFocusHighlight()
    }

    /** Compose keeps a single View; Android TV's default highlight would frame the whole screen. */
    private fun disableDefaultFocusHighlight() {
        fun View.clearHighlight() {
            defaultFocusHighlightEnabled = false
            if (this is ViewGroup) {
                for (index in 0 until childCount) getChildAt(index).clearHighlight()
            }
        }
        window?.decorView?.clearHighlight()
    }
}

/** Avoid fading an outgoing empty Radio category into the newly requested one on phones. */
internal fun radioSectionTransitionIsInstant(fromRoute: String?, toRoute: String?): Boolean {
    val isRadioBrowse = { route: String? ->
        route == Destinations.RADIO || route == Destinations.RADIO_SECTION
    }
    return isRadioBrowse(fromRoute) && isRadioBrowse(toRoute)
}

internal val SIDEBAR_ROUTES = setOf(Destinations.TRAILERS, Destinations.TRAILER_FEED, Destinations.FILES, Destinations.FILE_SOURCE, Destinations.FILE_FAVORITES, Destinations.FILE_RECENT, Destinations.FILE_CONTINUE, Destinations.DLNA, Destinations.DLNA_SERVER, Destinations.HOME, Destinations.PLEX, Destinations.JELLYFIN, Destinations.EMBY, Destinations.SEARCH, Destinations.YOUTUBE, Destinations.YOUTUBE_FEED, Destinations.TELEGRAM, Destinations.TELEGRAM_SECTION, Destinations.SPOTIFY, Destinations.SPOTIFY_SECTION, Destinations.RADIO, Destinations.RADIO_SECTION, Destinations.LIVE_TV, Destinations.LIVE_TV_SECTION, Destinations.SETTINGS, Destinations.SECTION)
internal val FULLSCREEN_ROUTES = setOf(Destinations.ITEM_TRAILERS, Destinations.TMDB_TRAILERS, Destinations.PHOTO_VIEWER, Destinations.SETUP_COMPLETE, Destinations.INITIAL_SETUP, Destinations.SETUP_SETTINGS, Destinations.PLAYER, Destinations.TVH_PLAYER, Destinations.YOUTUBE_PLAYER, Destinations.TELEGRAM_PLAYER, Destinations.TELEGRAM_DETAIL, Destinations.YOUTUBE_DETAIL, Destinations.YOUTUBE_CHANNEL, Destinations.SPOTIFY_PLAYER, Destinations.RADIO_PLAYER, Destinations.SPOTIFY_DETAIL, Destinations.SERVER_SELECT, Destinations.DETAIL, Destinations.DETAIL_FILTERED_BROWSE)
// Saved on the Settings back stack entry by a sidebar shortcut: the page to open, plus a serial
// that changes with every request.
private const val SETTINGS_PAGE_KEY = "settings_page"
private const val SETTINGS_PAGE_SERIAL = "settings_page_serial"
/** Settings session an entry belongs to; an entry restored from an older session starts fresh. */
private const val SETTINGS_SESSION_KEY = "settings_session"

private data class ScreensaverPreview(
    val modeKey: String,
    val showClock: Boolean,
    val showDate: Boolean,
    val clock: com.ambient.tv.data.local.ClockSettings,
    val useArtwork: Boolean,
    val artworkIntervalSeconds: Int,
    val dimPercent: Int,
    val qualityKey: String,
)

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun AmbientTvApp(
    openPlaybackRequest: Long,
    freshUiSessionRequest: Long,
    appPreferences: AppPreferences,
    tokenStore: TokenStore,
    spotifyRepository: SpotifyRepository,
    plexRefreshBus: PlexRefreshBus,
    mediaRepository: MediaRepository,
    radioBrowserRepository: com.ambient.tv.data.radio.RadioBrowserRepository,
    telegramRepository: com.ambient.tv.data.telegram.TelegramRepository,
    radioPlaybackLauncher: com.ambient.tv.ui.radio.RadioPlaybackLauncher,
    playbackAvailability: com.ambient.tv.data.browse.PlaybackAvailability,
    lastInteractionElapsedMs: Long,
    screensaverActive: Boolean,
    screensaverOverlayPresent: Boolean,
    onScreensaverHidden: () -> Boolean,
    onScreensaverActiveChanged: (Boolean) -> Unit,
    onRegisterInteraction: () -> Unit,
    onUiPreferencesPreview: (Float, String, String, String, Boolean, String, String, Boolean, Int, Int) -> Unit,
    onPerformanceModePreview: (String, String) -> Unit,
    visibleBackgroundVideoPlayback: Boolean,
    backgroundVideoDimPercent: Int,
    onVisibleBackgroundVideoPlaybackPreview: (Boolean, Int) -> Unit,
    onUiLanguageSaved: () -> Unit,
    onCompanionSettingsApplied: suspend () -> Unit,
) {
    val clockSettings by remember(appPreferences) { appPreferences.observeClockSettings() }
        .collectAsState(initial = appPreferences.clockSettings)
    val homeBackground by remember(appPreferences) { appPreferences.observeHomeBackground() }
        .collectAsState(initial = com.ambient.tv.data.local.HomeBackgroundSettings())
    val homeBackgroundArtwork = remember { kotlinx.coroutines.flow.MutableStateFlow<com.ambient.tv.ui.home.HomeHeroContent?>(null) }
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val uiSounds = com.ambient.tv.ui.sound.LocalUiSoundPlayer.current
    val startupViewModel: ServerSelectViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val startupState by startupViewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val companionVm: com.ambient.tv.companion.CompanionViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    LaunchedEffect(companionVm) {
        var revision = companionVm.manager.state.value.settingsRevision
        companionVm.manager.state.collect { state ->
            if (state.settingsRevision != revision) {
                revision = state.settingsRevision
                onCompanionSettingsApplied()
                plexRefreshBus.requestRefresh()
                kotlinx.coroutines.delay(2_500) // Give the sender a status acknowledgement before activity recreation.
                onUiLanguageSaved()
            }
        }
    }
    val playbackGateScope = rememberCoroutineScope()
    var playbackGateJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /** [navigate] replaces the plain local navigation, e.g. to rebuild the back stack below the player. */
    fun playOnSelectedTarget(
        route: String,
        title: String = "",
        navigate: ((String) -> Unit)? = null,
        options: androidx.navigation.NavOptionsBuilder.() -> Unit = {},
    ) {
        val localPlayback = {
            playbackGateJob?.cancel()
            playbackGateJob = playbackGateScope.launch {
                if (BackgroundPlaybackSession.snapshot.value.metadata?.route == route || playbackAvailability.route(route)) {
                    if (navigate != null) navigate(route) else navController.navigate(route, options)
                }
            }
            Unit
        }
        // Opening an existing player is navigation, not a new playback request.
        if (!handheld || route == Destinations.spotifyPlayer()) {
            localPlayback()
        } else {
            companionVm.manager.requestPlayback(
                media = runCatching { companionVm.mapper.fromRoute(route) }.getOrNull(),
                title = title,
                playLocally = localPlayback,
            )
        }
    }
    DisposableEffect(companionVm.manager) {
        onDispose { companionVm.manager.cancelPlaybackRequest() }
    }
    val spotifyTakeover by spotifyRepository.playbackTakeover.collectAsState()
    val mediaSelectionScope = rememberCoroutineScope()
    var pendingMediaSelection by remember { mutableStateOf<ResolvedMediaSelection?>(null) }
    var pendingMediaSelectionCollectionId by remember { mutableStateOf<String?>(null) }
    var pendingMediaSelectionCollectionSort by remember { mutableStateOf<String?>(null) }
    var mediaSelectionBusy by remember { mutableStateOf(false) }
    var pendingYouTubeVideoId by remember { mutableStateOf<String?>(null) }
    var pendingYouTubeBrowseSession by remember { mutableStateOf<Long?>(null) }

    fun navigateToDetails(ratingKey: String, collectionId: String? = null, collectionSort: String? = null) {
        navController.navigate(Destinations.detail(ratingKey, collectionId, collectionSort))
    }

    fun navigateToResolvedPlayback(
        selection: ResolvedMediaSelection,
        restart: Boolean = false,
        collectionId: String? = null,
        collectionSort: String? = null,
    ) {
        val playable = selection.playable
        if (playable == null) {
            navigateToDetails(selection.original.ratingKey, collectionId, collectionSort)
        } else {
            // mediaIndex=0 is intentionally only the caller hint. PlayerViewModel's existing
            // DisplayVersionSelector still replaces it with the optimal display-sized version
            // when automatic version selection is enabled.
            playOnSelectedTarget(
                Destinations.player(
                    ratingKey = playable.ratingKey,
                    mediaIndex = 0,
                    restart = restart,
                    collectionId = collectionId,
                    collectionSort = collectionSort,
                ),
                title = playable.title,
            )
        }
    }

    val photoContext = LocalContext.current
    fun handleHomeRadioSelection(selection: com.ambient.tv.ui.home.HomeRadioPlaybackSelection) {
        if (selection.stationId.isBlank() || mediaSelectionBusy) return
        mediaSelectionBusy = true
        val originEntry = navController.currentBackStackEntry?.id
        mediaSelectionScope.launch {
            try {
                // Usually the Home ViewModel already owns all 24 source stations. When a
                // cached Home row was displayed before its network refresh, hydrate that same
                // category, then restore the *displayed* ordering by station UUID.
                val known = selection.cachedStations.mapTo(HashSet()) { it.id }
                val fetched = if (selection.stationIds.all { it in known }) emptyList() else {
                    val key = selection.rowId.removePrefix("radio.")
                    suspendResult {
                        when {
                            key in setOf("home", HomeRowDefinition.RADIO_POPULAR) ->
                                radioBrowserRepository.popular(24)
                            key == HomeRowDefinition.RADIO_FAVORITES -> appPreferences.radioFavorites
                            key == HomeRowDefinition.RADIO_RECENT -> appPreferences.recentRadioStations
                            key.startsWith("${HomeRowDefinition.RADIO_GENRE}:") ->
                                radioBrowserRepository.search(tag = key.substringAfter(':'), limit = 24).items
                            key.startsWith("${HomeRowDefinition.RADIO_COUNTRY}:") ->
                                radioBrowserRepository.search(countryCode = key.substringAfter(':'), limit = 24).items
                            key.startsWith("${HomeRowDefinition.RADIO_LANGUAGE}:") ->
                                radioBrowserRepository.search(language = key.substringAfter(':'), limit = 24).items
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
                val clicked = (selection.cachedStations + fetched).firstOrNull { it.id == selection.stationId }
                    ?: suspendResult { radioBrowserRepository.station(selection.stationId) }.getOrNull()
                    ?: return@launch
                if (navController.currentBackStackEntry?.id != originEntry) return@launch
                val sourceQueue = com.ambient.tv.ui.home.homeRadioPlaybackQueue(selection, fetched, clicked)
                radioPlaybackLauncher.play(clicked, sourceQueue).onSuccess {
                    if (navController.currentBackStackEntry?.id == originEntry) {
                        navController.navigate(Destinations.RADIO_PLAYER) { launchSingleTop = true }
                    }
                }
            } finally {
                mediaSelectionBusy = false
            }
        }
    }

    fun handleMediaSelection(
        ratingKey: String,
        collectionId: String? = null,
        collectionSort: String? = null,
    ) {
        if (ratingKey.startsWith(com.ambient.tv.ui.telegram.TELEGRAM_CHANNEL_ITEM_PREFIX)) {
            ratingKey.removePrefix(com.ambient.tv.ui.telegram.TELEGRAM_CHANNEL_ITEM_PREFIX)
                .toLongOrNull()?.let { chatId ->
                    navController.navigate(Destinations.telegramSection("channel:$chatId")) {
                        launchSingleTop = true
                    }
                }
            return
        }
        com.ambient.tv.data.telegram.telegramPostIds(ratingKey)?.let { (chatId, messageId) ->
            // A private post has a detail page with full text and every linked YouTube video.
            navController.navigate(Destinations.telegramDetail(chatId, messageId))
            return
        }
        if (ratingKey.startsWith(com.ambient.tv.data.radio.RADIO_HOME_ITEM_PREFIX)) {
            val stationId = ratingKey.removePrefix(com.ambient.tv.data.radio.RADIO_HOME_ITEM_PREFIX)
            if (stationId.isBlank() || mediaSelectionBusy) return
            mediaSelectionBusy = true
            val originEntry = navController.currentBackStackEntry?.id
            mediaSelectionScope.launch {
                val station = try {
                    suspendResult { radioBrowserRepository.station(stationId) }.getOrNull()
                } finally {
                    mediaSelectionBusy = false
                }
                if (station == null || navController.currentBackStackEntry?.id != originEntry) return@launch
                radioPlaybackLauncher.play(station, listOf(station))
                    .onSuccess {
                        if (navController.currentBackStackEntry?.id == originEntry) {
                            navController.navigate(Destinations.RADIO_PLAYER) { launchSingleTop = true }
                        }
                    }
            }
            return
        }

        // Home "recently added photos" items open the photo viewer, not media details.
        if (ratingKey.startsWith("trailer-video:")) {
            playOnSelectedTarget(Destinations.trailerPlayer(ratingKey.substringAfter(':')))
            return
        }
        if (ratingKey.startsWith(com.ambient.tv.ui.photos.PhotoLaunch.HOME_PREFIX)) {
            if (com.ambient.tv.ui.photos.PhotoLaunch.prepareFromHome(photoContext, ratingKey)) navController.navigate(Destinations.PHOTO_VIEWER)
            return
        }
        when (appPreferences.mediaSelectionBehaviorKey) {
            MediaSelectionPreferences.DETAILS -> navigateToDetails(ratingKey, collectionId, collectionSort)
            MediaSelectionPreferences.PLAY, MediaSelectionPreferences.ASK -> {
                if (mediaSelectionBusy) return
                mediaSelectionBusy = true
                val originEntry = navController.currentBackStackEntry?.id
                mediaSelectionScope.launch {
                    val selection = try {
                        suspendResult { MediaSelectionResolver.resolve(mediaRepository, ratingKey) }.getOrNull()
                    } finally {
                        mediaSelectionBusy = false
                    }
                    if (navController.currentBackStackEntry?.id != originEntry) return@launch
                    if (selection == null || selection.playable == null) {
                        navigateToDetails(selection?.original?.ratingKey ?: ratingKey, collectionId, collectionSort)
                    } else if (appPreferences.mediaSelectionBehaviorKey == MediaSelectionPreferences.ASK) {
                        pendingMediaSelectionCollectionId = collectionId
                        pendingMediaSelectionCollectionSort = collectionSort
                        pendingMediaSelection = selection
                    } else {
                        navigateToResolvedPlayback(selection, collectionId = collectionId, collectionSort = collectionSort)
                    }
                }
            }
            else -> navigateToDetails(ratingKey, collectionId, collectionSort)
        }
    }

    fun openYouTubeChannel(channel: com.ambient.tv.youtube.YouTubeChannelInfo) {
        navController.navigate(Destinations.youtubeChannel(channel.channelId, channel.title, channel.avatarUrl))
    }

    /**
     * A YouTube player started from a browse page (channel, feed, Home): Back and the real end of
     * the queue return to that page. A channel opened from a player's OSD replaces that player
     * below it, and a details page of the same queue closes together with the player.
     */
    fun playYouTubeFromBrowse(videoId: String, browseSession: Long?) {
        playOnSelectedTarget(
            Destinations.youtubePlayer(videoId, browseSession),
            navigate = { route -> navController.navigateToYouTubeBrowsePlayer(route, closeDetails = browseSession != null) },
        )
    }

    fun handleYouTubeSelection(videoId: String, browseSession: Long? = null) {
        // Channel tiles open the channel page whatever the selection behavior says.
        com.ambient.tv.youtube.youtubeChannelIdOf(videoId)?.let { channelId ->
            navController.navigate(Destinations.youtubeChannel(channelId))
            return
        }
        when (val route = youtubeSelectionRoute(appPreferences.youtubeMediaSelectionBehaviorKey, videoId)) {
            is YouTubeSelectionRoute.Details -> navController.navigate(Destinations.youtubeDetail(route.videoId, browseSession))
            is YouTubeSelectionRoute.Play -> playYouTubeFromBrowse(route.videoId, browseSession)
            is YouTubeSelectionRoute.Ask -> {
                pendingYouTubeVideoId = route.videoId
                pendingYouTubeBrowseSession = browseSession
            }
        }
    }
    // Parsing and validating the IPTV JSON is not composition work. Cache it until the stored
    // value actually changes; this function is consulted from several navigation branches.
    val configuredIptvSourceId = remember(tokenStore.iptvSourcesJson) {
        com.ambient.tv.data.iptv.IptvStore(tokenStore).sources().firstOrNull { it.enabled }?.let { "iptv:${it.id}" }
    }
    fun configuredStartDestination(): String? = configuredContentRoute(
        plex = appPreferences.isConfigured,
        jellyfin = appPreferences.isJellyfinConfigured,
        emby = appPreferences.isEmbyConfigured,
        tvheadend = appPreferences.isTvheadendConfigured,
        youtube = appPreferences.youtubeEnabled,
        spotify = appPreferences.spotifyEnabled && spotifyRepository.nativeAvailable && spotifyWebAuthorized(tokenStore),
        radio = appPreferences.radioEnabled,
        telegram = appPreferences.telegramEnabled && telegramRepository.nativeAvailable,
        files = appPreferences.filesEnabled,
        trailers = appPreferences.trailersEnabled && !tokenStore.tmdbApiKey.isNullOrBlank(),
        iptvSourceId = configuredIptvSourceId,
    )
    val rootRoute = if (appPreferences.needsInitialSetup) Destinations.INITIAL_SETUP
        else configuredStartDestination() ?: Destinations.INITIAL_SETUP
    val startDestination = if (appPreferences.isConfigured && startupState.shouldChooseAtStartup)
        Destinations.SERVER_SELECT else rootRoute
    val currentBackStackEntry = navController.currentBackStackEntryAsState().value
    val currentRoute = currentBackStackEntry?.destination?.route ?: startDestination
    val companionRoute by rememberUpdatedState(currentRoute)
    var companionServerSwitch by remember {
        mutableStateOf<Pair<com.ambient.tv.companion.CompanionMediaMapper.ServerSwitch, com.ambient.tv.companion.CompanionMedia>?>(null)
    }
    var companionPendingStartId by remember { mutableStateOf("") }
    fun startCompanionMedia(media: com.ambient.tv.companion.CompanionMedia, startId: String = "") {
        if (media.kind.startsWith("spotify:")) {
            require(com.ambient.tv.companion.validSpotifyMedia(media) && appPreferences.spotifyEnabled && spotifyRepository.nativeAvailable && spotifyRepository.webAuthorized)
            companionVm.manager.hidePanel()
            onRegisterInteraction(); onScreensaverActiveChanged(false)
            spotifyRepository.playUri("${media.kind}:${media.id}", media.offsetId.takeIf { it.isNotEmpty() }?.let { "spotify:track:$it" }) { success ->
                companionVm.manager.confirmSpotifyStart(startId, success)
            }
            if (companionRoute != Destinations.SPOTIFY_PLAYER) navController.navigate(Destinations.spotifyPlayer())
            return
        }
        val route = try { companionVm.mapper.toRoute(media) } catch (_: Exception) {
            throw com.ambient.tv.companion.CompanionSourceUnavailableException()
        }
        companionVm.manager.hidePanel()
        onRegisterInteraction()
        onScreensaverActiveChanged(false)
        val uri = android.net.Uri.parse("ambient://play/$route")
        val source = when (media.kind) {
            "library" -> if (media.id.startsWith("j:")) "jellyfin" else if (media.id.startsWith("e:")) "emby" else "plex"
            "file" -> "files"
            "dlna" -> "dlna"
            "youtube" -> "youtube"
            "url" -> "shared"
            else -> uri.getQueryParameter("sourceId")?.substringBefore(':') ?: "tvheadend"
        }
        BackgroundPlaybackSession.beginSourceReplacement(com.ambient.tv.ui.player.PlaybackMetadata(
            route, "", source = source, mediaId = uri.pathSegments.last(), companionMedia = media,
            isLive = media.kind == "live:channel"), playWhenReadyOverride = true)
        if (companionRoute in setOf(Destinations.PLAYER, Destinations.YOUTUBE_PLAYER, Destinations.TVH_PLAYER)) navController.popBackStack()
        navController.navigate(route)
    }
    companionServerSwitch?.let { (switch, media) ->
        com.ambient.tv.ui.common.AppDialog(
            onDismiss = { companionServerSwitch = null },
            title = androidx.compose.ui.res.stringResource(R.string.companion_switch_server_title),
            hint = androidx.compose.ui.res.stringResource(R.string.companion_switch_server_message, switch.name),
            footer = {
                com.ambient.tv.ui.common.TvActionButton(androidx.compose.ui.res.stringResource(R.string.companion_switch_server_confirm), onClick = {
                    companionServerSwitch = null
                    if (companionVm.mapper.activate(switch)) {
                        mediaRepository.invalidateCaches()
                        runCatching { startCompanionMedia(media, companionPendingStartId) }
                            .onFailure { companionVm.manager.notice(R.string.companion_play_failed, error = true) }
                    }
                })
                com.ambient.tv.ui.common.TvActionButton(androidx.compose.ui.res.stringResource(R.string.files_cancel), onClick = { companionServerSwitch = null })
            },
        )
    }
    DisposableEffect(companionVm, navController) {
        companionVm.manager.attachSettings(companionVm.settings) {
            !companionVm.manager.settingsDirty && !BackgroundPlaybackSession.snapshot.value.active && !spotifyRepository.playback.value.playing
        }
        companionVm.manager.attach { request ->
            // Do not navigate away from TV settings and discard unsaved edits.
            check(!handheld && !companionVm.manager.settingsDirty)
            val media = requireNotNull(request.media)
            // A title from another saved server: ask on the TV instead of failing or switching silently.
            companionVm.mapper.serverSwitchFor(media)?.let { switch ->
                companionPendingStartId = request.startRequestId
                companionServerSwitch = switch to media
                return@attach
            }
            startCompanionMedia(media, request.startRequestId)
        }
        onDispose { companionVm.manager.detach() }
    }
    val currentSectionKey = currentBackStackEntry?.arguments?.getString("sectionKey")
    val currentFeedKey = currentBackStackEntry?.arguments?.getString("feedKey")
    val currentTrailerFeedKey = if (currentRoute == Destinations.TRAILER_FEED) currentFeedKey else null
    val currentSpotifySectionKey = if (currentRoute == Destinations.SPOTIFY_SECTION) currentBackStackEntry?.arguments?.getString("sectionKey") else null
    val currentRadioSectionKey = when (currentRoute) {
        Destinations.RADIO -> "popular"
        Destinations.RADIO_SECTION -> currentBackStackEntry?.arguments?.getString("sectionKey")
        else -> null
    }
    val currentTelegramSectionKey = when (currentRoute) {
        Destinations.TELEGRAM -> "recent"
        Destinations.TELEGRAM_SECTION -> currentBackStackEntry?.arguments?.getString("sectionKey")
        else -> null
    }
    val currentLiveTvSourceId = currentBackStackEntry?.arguments?.getString("sourceId")
    val currentTvheadendTab = if (currentRoute == Destinations.LIVE_TV_SECTION) currentBackStackEntry?.arguments?.getString("tab") else null
    val currentFileSourceId = if (currentRoute == Destinations.FILE_SOURCE) currentBackStackEntry?.arguments?.getString("sourceId") else null
    val currentDlnaServerId = if (currentRoute == Destinations.DLNA_SERVER) currentBackStackEntry?.arguments?.getString("serverId") else null
    val waitsForSidebarContent = currentRoute in setOf(Destinations.FILES, Destinations.FILE_SOURCE, Destinations.FILE_FAVORITES, Destinations.FILE_RECENT, Destinations.FILE_CONTINUE) || currentRoute == Destinations.HOME ||
        currentRoute == Destinations.DLNA || currentRoute == Destinations.DLNA_SERVER ||
        currentRoute == Destinations.PLEX ||
        currentRoute == Destinations.JELLYFIN ||
        currentRoute == Destinations.EMBY ||
        currentRoute == Destinations.SECTION ||
        currentRoute == Destinations.YOUTUBE ||
        currentRoute == Destinations.YOUTUBE_FEED ||
        currentRoute == Destinations.TRAILERS ||
        currentRoute == Destinations.TRAILER_FEED ||
        currentRoute == Destinations.SPOTIFY ||
        currentRoute == Destinations.SPOTIFY_SECTION ||
        currentRoute == Destinations.RADIO ||
        currentRoute == Destinations.RADIO_SECTION ||
        currentRoute == Destinations.TELEGRAM ||
        currentRoute == Destinations.TELEGRAM_SECTION ||
        currentRoute == Destinations.LIVE_TV ||
        currentRoute == Destinations.LIVE_TV_SECTION
    val sidebarDestinationKey = sidebarConcreteRoute(
        currentRoute, currentSectionKey, currentFeedKey, currentTrailerFeedKey, currentSpotifySectionKey, currentTvheadendTab,
        currentLiveTvSourceId, currentFileSourceId, currentDlnaServerId, currentRadioSectionKey, currentTelegramSectionKey,
    )
    // Key readiness to the destination itself: a parent effect must not overwrite a child
    // readiness report later in the same composition frame.
    var sidebarContentReady by remember(sidebarDestinationKey) { mutableStateOf(!waitsForSidebarContent) }
    val reportSidebarContentReady = remember(sidebarDestinationKey, waitsForSidebarContent) {
        { ready: Boolean -> if (waitsForSidebarContent) sidebarContentReady = ready }
    }
    var lastTopLevelRoute by rememberSaveable { mutableStateOf(rootRoute) }
    var lastSectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var screensaverPreview by remember { mutableStateOf<ScreensaverPreview?>(null) }

    val screensaverEnabled = appPreferences.screensaverEnabled
    val screensaverTimeoutMs = appPreferences.screensaverTimeoutMinutes.coerceIn(1, 120) * 60_000L
    val screensaverModeKey = appPreferences.screensaverModeKey
    val screensaverShowClock = appPreferences.screensaverShowClock
    val screensaverShowDate = appPreferences.screensaverShowDate
    val screensaverUseArtwork = appPreferences.screensaverUseArtwork
    val screensaverArtworkIntervalSeconds = appPreferences.screensaverArtworkIntervalSeconds
    val screensaverDimPercent = appPreferences.screensaverDimPercent
    val screensaverQualityKey = appPreferences.screensaverQualityKey
    // Only what the shell needs. The full snapshot changes with every position tick (500 ms), which
    // recomposed the whole app shell twice a second during any playback.
    val backgroundPlayback by remember {
        BackgroundPlaybackSession.snapshot
            .map {
                ShellPlaybackState(
                    it.active,
                    it.active && it.metadata?.hasVideo == true,
                    it.playing,
                    it.wantsToPlay,
                    it.metadata?.isTrailer == true,
                    com.ambient.tv.ui.player.handheldPipAspectRatio(
                        it.metadata?.sourceWidth, it.metadata?.sourceHeight, it.metadata?.sourceAspectRatioHint),
                )
            }
            .distinctUntilChanged()
    }.collectAsState(initial = remember { BackgroundPlaybackSession.snapshot.value.let {
        ShellPlaybackState(
            it.active,
            it.active && it.metadata?.hasVideo == true,
            it.playing,
            it.wantsToPlay,
            it.metadata?.isTrailer == true,
            com.ambient.tv.ui.player.handheldPipAspectRatio(
                it.metadata?.sourceWidth, it.metadata?.sourceHeight, it.metadata?.sourceAspectRatioHint),
        )
    } })
    val onPlayerScreen = currentRoute in setOf(Destinations.PLAYER, Destinations.TVH_PLAYER, Destinations.YOUTUBE_PLAYER, Destinations.TELEGRAM_PLAYER)
    val activity = androidx.activity.compose.LocalActivity.current
    val pipPresentation = (activity as? MainActivity)?.let { it.systemPipVisible || it.systemPipEntering } == true
    // Keep one native video plane alive for the complete playback lifetime. Fullscreen, browse
    // and mini-player transitions now only change Compose chrome above it; they never recreate the
    // decoder surface. The preference controls whether browse pages reveal it, not whether the
    // persistent HDR-capable output exists.
    // Reserve the root output before the screen creates its engine. Otherwise the first
    // composition attaches a screen-local Surface, then immediately destroys it when the
    // session becomes active and the root host takes over.
    val persistentVideoHost = onPlayerScreen || backgroundPlayback.activeVideo
    val showVisibleBackgroundVideo = visibleBackgroundVideoPlayback && persistentVideoHost && !onPlayerScreen
    // Minimized playback used to keep decoding and filtering a full-screen video plane behind an
    // opaque background, which is what made browsing choppy while the mini player ran. The video
    // plane only exists while something can see it: the player screen, or the ambient backdrop.
    // Its surface going away detaches the renderer's video output the same way a screen change
    // does (audio and the session keep running) and comes back on the next frame it is shown.
    val videoPlaneVisible = persistentVideoHost && (onPlayerScreen || showVisibleBackgroundVideo || pipPresentation)
    // Sidebar selections target this group directly. Spatial focus search is unreliable while
    // Navigation Compose is replacing one destination tree with another.
    val contentFocusRequester = remember { FocusRequester() }
    val sidebarContentPending = waitsForSidebarContent && !sidebarContentReady
    var initialContentEntryPending by remember(sidebarDestinationKey) { mutableStateOf(waitsForSidebarContent) }
    val focusManager = LocalFocusManager.current
    val focusSoundView = LocalView.current
    val autoPipEnabled by remember(appPreferences) {
        appPreferences.changes().map { appPreferences.autoPictureInPicture }.distinctUntilChanged()
    }.collectAsState(initial = appPreferences.autoPictureInPicture)
    val pipOrientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    LaunchedEffect(handheld, backgroundPlayback, autoPipEnabled, onPlayerScreen, pipOrientation) {
        val pipActivity = activity as? MainActivity ?: return@LaunchedEffect
        val eligible = com.ambient.tv.ui.player.handheldPipEligible(
            handheld = handheld,
            supported = pipActivity.handheldPipSupported(),
            enabled = autoPipEnabled,
            active = backgroundPlayback.active,
            hasVideo = backgroundPlayback.activeVideo,
            wantsToPlay = backgroundPlayback.wantsToPlay,
            isTrailer = backgroundPlayback.isTrailer,
        )
        // The fullscreen video fills the app shell; a minimized audio/artwork strip has no
        // visible video rect and must not pretend that the full screen is its source.
        val sourceRect = android.graphics.Rect().takeIf {
            onPlayerScreen && focusSoundView.getGlobalVisibleRect(it) && it.width() > 0 && it.height() > 0
        }
        pipActivity.updateHandheldPip(eligible, backgroundPlayback.pipAspectRatio, sourceRect)
    }
    SilenceSystemMenuSounds()
    val sidebarFocusRequester = remember { FocusRequester() }
    var sidebarHasFocus by remember { mutableStateOf(false) }
    var appHasFocus by remember { mutableStateOf(false) }
    val contentFocusPermit = remember { mutableStateOf(false) }
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    var contentHasFocus by remember { mutableStateOf(false) }
    var contentContainerFocused by remember { mutableStateOf(false) }
    var previousRoute by remember { mutableStateOf<String?>(currentRoute) }
    // Advances each time Settings is closed (no Settings entry left on the back stack). A Settings
    // entry composed in a later session forgets its page, area and focus and opens at the start,
    // even when Navigation restores it from a saved tab stack. Survives recreation (UI language).
    var settingsSession by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(currentRoute == Destinations.SETTINGS) }
    var restoreFocusAfterNavigation by remember { mutableStateOf(false) }
    fun requestContentFocus(): Boolean {
        if (sidebarContentPending) return false
        contentFocusPermit.value = true
        return try {
            val entry = com.ambient.tv.ui.navigation.SidebarContentEntry.requestFocus()
            // null: the page is still restoring its focus, so the caller retries next frame.
            if (entry == null) return false
            if (entry == true) return contentHasFocus && !contentContainerFocused
            val accepted = runCatching { contentFocusRequester.requestFocus() }.getOrDefault(false)
            if (accepted && contentContainerFocused) {
                runCatching { focusManager.moveFocus(FocusDirection.Enter) }
            }
            // The container itself is not a visible, usable focus destination.
            contentHasFocus && !contentContainerFocused
        } finally {
            contentFocusPermit.value = false
        }
    }
    val sidebarEntryRequested = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (handheld) return@LaunchedEffect
        kotlinx.coroutines.delay(16)
        if (sidebarContentPending) runCatching { sidebarFocusRequester.requestFocus() }
        else requestContentFocus()
    }
    LaunchedEffect(currentRoute, sidebarDestinationKey, sidebarContentReady, appHasFocus,
        contentContainerFocused, sidebarHasFocus, windowInfo.isWindowFocused, screensaverOverlayPresent) {
        if (handheld || currentRoute in FULLSCREEN_ROUTES || screensaverOverlayPresent || !windowInfo.isWindowFocused) return@LaunchedEffect
        if (sidebarContentPending) {
            // Keep a visible, usable navigation target while content is loading.
            if (!appHasFocus) runCatching { sidebarFocusRequester.requestFocus() }
            return@LaunchedEffect
        }
        if (sidebarHasFocus) return@LaunchedEffect
        if (appHasFocus && !contentContainerFocused) return@LaunchedEffect
        // Only repair actual focus loss. Dialogs have their own window; an existing content
        // selection is never re-requested just because metadata or readiness changed.
        kotlinx.coroutines.delay(180)
        var attempts = 0
        while (attempts < 4 || com.ambient.tv.ui.navigation.SidebarContentEntry.pending) {
            // The destination may have restored its own selection meanwhile (e.g. Home's last
            // tile after Back from Detail). Requesting the container would steal that focus and
            // Enter would land on the first tile instead.
            if (sidebarHasFocus || (contentHasFocus && !contentContainerFocused)) return@LaunchedEffect
            if (requestContentFocus()) return@LaunchedEffect
            attempts++
            kotlinx.coroutines.delay(32)
        }
        sidebarEntryRequested.value = true
        runCatching { sidebarFocusRequester.requestFocus() }
    }
    LaunchedEffect(sidebarContentPending) {
        if (!sidebarContentPending && initialContentEntryPending) {
            initialContentEntryPending = false
            androidx.compose.runtime.withFrameNanos { }
            requestContentFocus()
        }
    }
    LaunchedEffect(currentRoute) {
        if (currentRoute == Destinations.SETTINGS) {
            settingsOpen = true
        } else if (settingsOpen && runCatching { navController.getBackStackEntry(Destinations.SETTINGS) }.isFailure) {
            settingsOpen = false
            settingsSession++
        }
        if (returnsFromFullscreenPlayer(previousRoute, currentRoute) ||
            (previousRoute == Destinations.SETTINGS && currentRoute != Destinations.SETTINGS)) {
            restoreFocusAfterNavigation = true
        }
        previousRoute = currentRoute
    }
    LaunchedEffect(
        currentRoute,
        restoreFocusAfterNavigation,
        sidebarContentPending,
        windowInfo.isWindowFocused,
        screensaverOverlayPresent,
    ) {
        if (!restoreFocusAfterNavigation || sidebarContentPending ||
            !windowInfo.isWindowFocused || screensaverOverlayPresent
        ) return@LaunchedEffect

        // The sidebar is composed before NavHost and can receive Android's fallback focus while
        // the old browse/detail destination is being put back. Wait for its first layout, then
        // enter the destination's focusRestorer. It knows the exact tile/action that launched the
        // player, so this applies equally to Home, libraries, Search, YouTube, TVH and Files.
        androidx.compose.runtime.withFrameNanos { }
        sidebarEntryRequested.value = false
        var attempts = 0
        while (attempts < 8 || com.ambient.tv.ui.navigation.SidebarContentEntry.pending) {
            // The destination may already have put focus back on the launching item (it keeps
            // its own saved selection). Re-entering the container would move it to the first child.
            if (contentHasFocus && !contentContainerFocused) {
                restoreFocusAfterNavigation = false
                return@LaunchedEffect
            }
            if (requestContentFocus()) {
                restoreFocusAfterNavigation = false
                return@LaunchedEffect
            }
            attempts++
            kotlinx.coroutines.delay(32)
        }
        // Do not keep stealing focus if a destination has no focusable content (for example an
        // error page). The ordinary focus-repair path remains responsible for its fallback.
        restoreFocusAfterNavigation = false
    }
    val launcherRoute by com.ambient.tv.launcher.LauncherIntents.requests.collectAsState()
    LaunchedEffect(launcherRoute) {
        val route = launcherRoute ?: return@LaunchedEffect
        // Launcher rows and programme reminders open players directly: an unreachable system stops here too.
        if (!playbackAvailability.route(route)) {
            com.ambient.tv.launcher.LauncherIntents.consumed(route)
            return@LaunchedEffect
        }
        // A new player route (launcher, DLNA sender switching media) replaces an open player
        // instead of stacking players on the back stack.
        val replacePlayer = route.startsWith("player/") && navController.currentDestination?.route == Destinations.PLAYER
        runCatching {
            navController.navigate(route) {
                launchSingleTop = true
                if (replacePlayer) popUpTo(Destinations.PLAYER) { inclusive = true }
            }
        }
        com.ambient.tv.launcher.LauncherIntents.consumed(route)
    }
    // System/voice search: open the search page; it runs and consumes the query.
    val externalSearch by com.ambient.tv.search.ExternalSearch.requests.collectAsState()
    LaunchedEffect(externalSearch) {
        if (externalSearch == null || navController.currentDestination?.route == Destinations.SEARCH) return@LaunchedEffect
        runCatching { navController.navigate(Destinations.SEARCH) { launchSingleTop = true } }
    }
    LaunchedEffect(freshUiSessionRequest) {
        // A new start after root Back: a new root entry (new list state and ViewModel) at the top.
        if (freshUiSessionRequest > 0) runCatching {
            navController.navigate(rootRoute) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }
    LaunchedEffect(openPlaybackRequest) {
        if (openPlaybackRequest > 0) BackgroundPlaybackSession.snapshot.value.metadata?.route?.let {
            navController.navigate(it) { launchSingleTop = true }
        }
    }
    // The photo viewer is a presentation too: a running slideshow must not start the screensaver.
    val onPlayerRoute = currentRoute in setOf(Destinations.PLAYER, Destinations.TVH_PLAYER, Destinations.YOUTUBE_PLAYER, Destinations.TELEGRAM_PLAYER, Destinations.SPOTIFY_PLAYER, Destinations.RADIO_PLAYER)
    androidx.compose.runtime.SideEffect { com.ambient.tv.ui.player.SleepWarningKeyGate.playerRoute = onPlayerRoute }
    val playbackActive = currentRoute == Destinations.PHOTO_VIEWER || currentRoute == Destinations.PLAYER || currentRoute == Destinations.TVH_PLAYER || currentRoute == Destinations.YOUTUBE_PLAYER || currentRoute == Destinations.TELEGRAM_PLAYER || currentRoute == Destinations.SPOTIFY_PLAYER || currentRoute == Destinations.RADIO_PLAYER || backgroundPlayback.active

    // Handheld video pages are fullscreen exactly while the window is landscape, so turning the
    // device switches into fullscreen and back. Television keeps its manifest orientation.
    val handheldFullscreen = com.ambient.tv.ui.mobile.rememberHandheldFullscreen(activity, onPlayerScreen)
    // Shell-owned keep-screen-on (LocalView), not window clearFlags and not unconditional
    // SurfaceView flags: clearFlags raced the video surfaces and let the phone sleep while
    // buffering, and surface-level keepScreenOn=true also blocked sleep during browse/mini-player.
    // wantsToPlay covers buffering; isPlaying alone was too narrow.
    val standbyRequested = !handheld && com.ambient.tv.ui.player.SleepSession.standbyRequested
    val keepScreenOn = !standbyRequested && (
        screensaverOverlayPresent ||
        (!handheld && screensaverEnabled) ||
        // The user can lock the phone while PiP is floating; audio will keep playing.
        ((onPlayerScreen || currentRoute == Destinations.RADIO_PLAYER) &&
            backgroundPlayback.wantsToPlay && !(handheld && pipPresentation)) ||
        (!handheld && backgroundPlayback.activeVideo && backgroundPlayback.wantsToPlay))
    DisposableEffect(keepScreenOn) {
        focusSoundView.keepScreenOn = keepScreenOn
        onDispose { focusSoundView.keepScreenOn = false }
    }

    LaunchedEffect(currentRoute) {
        if (screensaverActive) onScreensaverActiveChanged(false)
        // Sleep-stop can navigate away from the player. That is NOT a user interaction:
        // clearing standby here would prevent the TV from switching off.
        if (!com.ambient.tv.ui.player.SleepSession.standbyRequested) onRegisterInteraction()
    }

    LaunchedEffect(playbackActive, screensaverActive, screensaverPreview) {
        // Only automatic idle activation yields to playback. An explicit test remains visible.
        if (playbackActive && screensaverActive && screensaverPreview == null) onScreensaverActiveChanged(false)
    }

    LaunchedEffect(standbyRequested, screensaverActive) {
        if (standbyRequested && screensaverActive) onScreensaverActiveChanged(false)
    }
    LaunchedEffect(screensaverEnabled, screensaverTimeoutMs, playbackActive, lastInteractionElapsedMs, screensaverOverlayPresent, windowInfo.isWindowFocused, standbyRequested) {
        if (standbyRequested || !screensaverEnabled || playbackActive || screensaverOverlayPresent || !windowInfo.isWindowFocused) return@LaunchedEffect
        val remaining = screensaverTimeoutMs - (SystemClock.elapsedRealtime() - lastInteractionElapsedMs)
        if (remaining > 0) kotlinx.coroutines.delay(remaining)
        if (!standbyRequested && !playbackActive && screensaverEnabled && SystemClock.elapsedRealtime() - lastInteractionElapsedMs >= screensaverTimeoutMs) {
            onScreensaverActiveChanged(true)
        }
    }

    LaunchedEffect(currentRoute, currentSectionKey, currentFeedKey, currentTrailerFeedKey, currentSpotifySectionKey, currentRadioSectionKey, currentTelegramSectionKey, currentTvheadendTab, currentLiveTvSourceId, currentFileSourceId, currentDlnaServerId) {
        if (currentRoute in SIDEBAR_ROUTES && currentRoute != Destinations.SETTINGS) {
            lastTopLevelRoute = when (currentRoute) {
                Destinations.YOUTUBE_FEED -> currentFeedKey?.let(Destinations::youtubeFeed) ?: Destinations.YOUTUBE
                Destinations.TRAILER_FEED -> currentTrailerFeedKey?.let(Destinations::trailerFeed) ?: Destinations.TRAILERS
                Destinations.SPOTIFY_SECTION -> currentSpotifySectionKey?.let(Destinations::spotifySection) ?: Destinations.SPOTIFY
                Destinations.RADIO_SECTION -> currentRadioSectionKey?.let(Destinations::radioSection) ?: Destinations.RADIO
                Destinations.TELEGRAM_SECTION -> currentTelegramSectionKey?.let(Destinations::telegramSection) ?: Destinations.TELEGRAM
                Destinations.LIVE_TV_SECTION -> currentTvheadendTab?.let { Destinations.liveTvSection(it, currentLiveTvSourceId) } ?: Destinations.LIVE_TV
                Destinations.FILE_SOURCE -> currentFileSourceId?.let(Destinations::fileSource) ?: Destinations.FILES
                Destinations.DLNA_SERVER -> currentDlnaServerId?.let(Destinations::dlnaServer) ?: Destinations.DLNA
                else -> currentRoute
            }
            lastSectionKey = if (currentRoute == Destinations.SECTION) currentSectionKey else null
        }
    }

    var settingsExitGuard by remember { mutableStateOf<SettingsExitGuard?>(null) }
    fun guardedSettingsExit(action: () -> Unit) {
        val guard = settingsExitGuard
        if (guard != null && currentRoute == Destinations.SETTINGS) guard(action) else action()
    }

    /** Centralized contextual entry; null category opens the general Settings hub.
     * The serial ensures another shortcut can replace an already open Settings view. */
    fun openSettings(category: String? = null, target: String? = null) {
        guardedSettingsExit {
            if (currentRoute != Destinations.SETTINGS) {
                navController.navigate(Destinations.SETTINGS) { launchSingleTop = true }
            }
            val handle = navController.currentBackStackEntry
                ?.takeIf { it.destination.route == Destinations.SETTINGS }
                ?.savedStateHandle ?: return@guardedSettingsExit
            handle[SETTINGS_PAGE_KEY] = category?.let {
                if (target.isNullOrBlank()) it else "${it}:${target}"
            }
            handle[SETTINGS_PAGE_SERIAL] = (handle.get<Int>(SETTINGS_PAGE_SERIAL) ?: 0) + 1
        }
    }

    fun navigateTopLevel(route: String) {
        guardedSettingsExit {
            // Settings never rides along in a saved tab stack: leaving it for a top-level page closes
            // it, so restoring that page later cannot bring back an old Settings view on top.
            if (navController.currentDestination?.route == Destinations.SETTINGS) navController.popBackStack()
            if (navController.currentDestination?.route == route) return@guardedSettingsExit
            // Home is the usual start destination, so Plex/Jellyfin sit on top of it.
            // popBackStack restores that existing entry; navigate()+restoreState can no-op
            // when the target is already the graph start destination.
            // INTENTIONAL TV-tab model: popUpTo(start) collapses
            // prior top-level hubs so the back stack is not a deep tab history. Root Back on
            // hubs uses focusSidebar (Files pattern) instead of popping to Home.
            if (navController.popBackStack(route, inclusive = false)) return@guardedSettingsExit
            navController.navigate(route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Every library section shares the single "section/{sectionKey}" route pattern, so
    // Navigation Compose's save/restore-state mechanism (keyed by route pattern, not by
    // the actual sectionKey argument) can't tell different sections apart: restoreState
    // would try to reapply whichever section's state was last saved under that pattern,
    // regardless of which section is actually being opened. Section navigation therefore
    // deliberately skips saveState/restoreState, unlike the true singleton tabs above.
    fun navigateToSection(route: String) {
        // Parameterized sidebar destinations (library section, YouTube feed, Spotify section)
        // must not use launchSingleTop. Several entries share the same route pattern and only
        // differ by arguments; singleTop can therefore keep the current destination alive and
        // make a click on another child appear to do nothing. popUpTo already prevents stacking.
        guardedSettingsExit {
            navController.navigate(route) {
                popUpTo(navController.graph.startDestinationId)
            }
        }
    }

    val providerNavigationViewModel: com.ambient.tv.ui.navigation.SidebarViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()

    fun navigateSidebar(route: String) {
        // Only selecting SmartTube Start in the menu or the feed chips requests a Home update.
        // Navigation back from playback must not be confused with this explicit action.
        if (route == Destinations.youtubeSection("home", appPreferences.youtubeStartFeedKey)) {
            com.ambient.tv.ui.youtube.YouTubeHomeSelection.request()
        }
        // The handheld Startseite control always means the app home screen. The shared
        // top-level navigation restores a saved stack on top of the graph start, so that
        // tap can leave the current page in place.
        if (handheld && route == Destinations.HOME) {
            guardedSettingsExit {
                if (navController.currentDestination?.route == Destinations.HOME) {
                    com.ambient.tv.ui.common.NavReselect.fire()
                } else {
                    navController.openAppHome()
                }
            }
            return
        }
        val activeSidebarRoute = sidebarConcreteRoute(
            currentRoute, currentSectionKey, currentFeedKey, currentTrailerFeedKey, currentSpotifySectionKey, currentTvheadendTab,
            currentLiveTvSourceId, currentFileSourceId, currentDlnaServerId, currentRadioSectionKey, currentTelegramSectionKey,
        )
        // Selecting the already active destination is only a focus handoff. Re-navigating a
        // singleton destination destroys its focused subtree and is especially visible when
        // Home/Plex are selected repeatedly with a fast remote.
        val parameterizedSidebarRoute = isParameterizedSidebarRoute(route)
        if (route == activeSidebarRoute) {
            // Re-selecting the open page scrolls it back to the top (Plex behaviour).
            com.ambient.tv.ui.common.NavReselect.fire()
            return
        }

        // Settings is always an overlay (no popUpTo start): Back returns to the caller
        // (section, hub, search, detail, …), matching openSettingsPreservingContext.
        // Previously only nested/fullscreen routes got this; sidebar sections used
        // navigateTopLevel and wiped the caller so Back landed on Home.
        if (route == Destinations.SETTINGS) {
            openSettings()
        } else if (parameterizedSidebarRoute) {
            navigateToSection(route)
        } else {
            navigateTopLevel(route)
        }
    }

    fun navigateBack() {
        uiSounds?.playBack()
        guardedSettingsExit {
            if (!navController.popBackStack()) navigateTopLevel(rootRoute)
        }
    }

    /** Files-style root Back: keep the route, move focus to the sidebar. */
    fun focusSidebar() {
        // Handhelds have no sidebar to take focus, so root Back leaves the page instead.
        if (handheld) {
            if (currentRoute == rootRoute) {
                (activity as? MainActivity)?.leaveFromRoot() ?: activity?.moveTaskToBack(true)
            } else navigateBack()
            return
        }
        sidebarEntryRequested.value = true
        runCatching { sidebarFocusRequester.requestFocus() }
    }

    fun exitApp() {
        uiSounds?.playBack()
        BackgroundPlaybackSession.stop()
        spotifyRepository.stop()
        (activity as? MainActivity)?.wireGuardRoute?.disconnect()
        activity?.finishAndRemoveTask()
    }

    fun openSettingsPreservingContext() = openSettings()

    fun youtubeFeedRoute(feedKey: String) = Destinations.youtubeSection(feedKey, appPreferences.youtubeStartFeedKey)

    fun trailerFeedRoute(feed: com.ambient.tv.trailers.TrailerFeed) =
        if (feed == com.ambient.tv.trailers.TrailerFeed.CINEMA) Destinations.TRAILERS else Destinations.trailerFeed(feed.name)

    fun tvheadendTabRoute(tab: com.ambient.tv.ui.tvheadend.TvheadendTab) = when (tab) {
        com.ambient.tv.ui.tvheadend.TvheadendTab.LIVE -> Destinations.LIVE_TV
        com.ambient.tv.ui.tvheadend.TvheadendTab.GUIDE -> Destinations.liveTvSection("guide")
        com.ambient.tv.ui.tvheadend.TvheadendTab.RECORDINGS -> Destinations.liveTvSection("recordings")
        com.ambient.tv.ui.tvheadend.TvheadendTab.TIMERS -> Destinations.liveTvSection("timers")
        com.ambient.tv.ui.tvheadend.TvheadendTab.HBBTV -> Destinations.liveTvSection("hbbtv")
    }


    /** Existing system shortcuts include composite destinations such as "wake:plex". */
    fun openSystemSettings(category: String) = openSettings(category)

    /** Avoid defaulting non-Plex media IDs to the Plex settings page. */
    fun openMediaSettings(mediaId: String) {
        val provider = com.ambient.tv.data.browse.mediaProvider(mediaId)
        val category = provider.takeIf { it in setOf("plex", "jellyfin", "emby", "files", "dlna") } ?: "library"
        openSystemSettings(category)
    }

    fun playNextFromSidebar(candidate: NextUpCandidate) {
        val route = when (candidate.source.lowercase()) {
            "plex", "jellyfin", "emby", "files", "dlna", "shared" -> Destinations.player(candidate.mediaId)
            "youtube" -> Destinations.youtubePlayer(candidate.mediaId)
            else -> return
        }
        guardedSettingsExit {
            if (handheld && companionVm.manager.state.value.paired.isNotEmpty()) {
                playOnSelectedTarget(route)
                return@guardedSettingsExit
            }
            BackgroundPlaybackSession.recordManualNextUp(candidate)
            if (onPlayerScreen && com.ambient.tv.ui.player.PlayerItemNavigation.playNext(candidate)) return@guardedSettingsExit
            // Same route-pattern pitfall as in-player Next: if a matching fullscreen player is
            // already on top, replace it so a fresh ViewModel loads the next media id.
            val replaceTop = when (candidate.source.lowercase()) {
                "plex", "jellyfin", "emby", "files", "dlna", "shared" -> currentRoute == Destinations.PLAYER
                "youtube" -> currentRoute == Destinations.YOUTUBE_PLAYER
                else -> false
            }
            if (replaceTop) navController.popBackStack()
            navController.navigate(route)
        }
    }

    if (handheld && onPlayerScreen) {
        androidx.activity.compose.BackHandler {
            navigateBack()
            plexRefreshBus.requestRefresh()
        }
    }

    com.ambient.tv.companion.PlaybackPresentationProvider(companionVm.manager, companionVm.mapper, spotifyRepository,
        onOpenLocal = { route -> guardedSettingsExit { navController.navigate(route) { launchSingleTop = true } } }) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!pipPresentation && !persistentVideoHost) {
                AppBackground(Modifier.fillMaxSize())
            }
            if (videoPlaneVisible) {
                // HbbTV may have a smaller video window. PiP always uses the entire video plane.
                PersistentPlaybackVideoHost(
                    Modifier.fillMaxSize().then(if (pipPresentation) Modifier else com.ambient.tv.ui.player.PlaybackVideoWindow.modifier),
                    systemPip = pipPresentation,
                )
            }
            if (!pipPresentation && persistentVideoHost && !showVisibleBackgroundVideo && !onPlayerScreen) {
                AppBackground(Modifier.fillMaxSize())
            }
            if (!pipPresentation && persistentVideoHost) {
                com.ambient.tv.ui.player.BackgroundVideoScrim(
                    visible = showVisibleBackgroundVideo,
                    dimPercent = backgroundVideoDimPercent,
                )
            }
            val showHomeBackground = currentRoute != Destinations.HOME && homeBackground.visible(
                reducedEffects = com.ambient.tv.ui.performance.LocalPerformanceProfile.current.reducedEffects,
                foregroundPlayback = onPlayerScreen || currentRoute in setOf(Destinations.SPOTIFY_PLAYER, Destinations.RADIO_PLAYER,
                    Destinations.ITEM_TRAILERS, Destinations.TMDB_TRAILERS, Destinations.PHOTO_VIEWER) || screensaverOverlayPresent,
                backgroundVideo = showVisibleBackgroundVideo,
            )
            if (!pipPresentation && showHomeBackground) {
                com.ambient.tv.ui.home.HomeHeroAmbient(
                    heroContent = homeBackgroundArtwork,
                    heroStyleKey = homeBackground.style,
                    intensityPercent = homeBackground.intensity,
                    spreadKey = homeBackground.spread,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Keep NavHost/players composed (and their native decoders alive) while the Android
            // system shows the video surface alone. Removing the tree would stop playback.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (pipPresentation) 0f else 1f }) {
            CompositionLocalProvider(
                com.ambient.tv.ui.common.LocalHomeBackgroundVisible provides showHomeBackground,
                com.ambient.tv.ui.common.LocalHomeBackgroundArtwork provides homeBackgroundArtwork,
                com.ambient.tv.ui.common.LocalFocusArtworkBackdrop provides homeBackground.focusArtwork,
                com.ambient.tv.ui.common.LocalClockSettings provides clockSettings,
                LocalPersistentPlaybackVideoHost provides persistentVideoHost,
                LocalVisibleBackgroundVideo provides showVisibleBackgroundVideo,
                LocalSidebarContentReadyReporter provides reportSidebarContentReady,
                LocalSidebarContentPending provides sidebarContentPending,
                com.ambient.tv.ui.mobile.LocalHandheldFullscreen provides handheldFullscreen.takeIf { handheld && onPlayerScreen },
            ) {
            com.ambient.tv.ui.mobile.HandheldNavigationHost(
                currentRoute = currentRoute,
                // A windowed video page is browsable chrome again: the bottom navigation returns
                // with the system bars and only the fullscreen orientation hides it.
                showNavigation = currentRoute !in FULLSCREEN_ROUTES || (handheld && onPlayerScreen && !handheldFullscreen.enabled),
                playerVisible = onPlayerScreen || currentRoute == Destinations.SPOTIFY_PLAYER || currentRoute == Destinations.RADIO_PLAYER,
                onNavigate = ::navigateSidebar,
                onOpenPlayback = { route -> guardedSettingsExit { navController.navigate(route) { launchSingleTop = true } } },
                onOpenSystemSettings = ::openSystemSettings,
            ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { appHasFocus = it.hasFocus }
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        if (handheld) return@onPreviewKeyEvent false
                        if (event.key == ComposeKey.Tab) {
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                focusManager.moveFocus(
                                    if (event.nativeKeyEvent.isShiftPressed) FocusDirection.Previous else FocusDirection.Next,
                                )
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (sidebarContentPending && event.type == KeyEventType.KeyDown && event.key in setOf(
                                ComposeKey.DirectionLeft, ComposeKey.DirectionRight, ComposeKey.DirectionUp, ComposeKey.DirectionDown,
                            )) initialContentEntryPending = false
                        if (!contentHasFocus && !sidebarHasFocus && currentRoute !in FULLSCREEN_ROUTES &&
                            event.type == KeyEventType.KeyDown && event.key in setOf(ComposeKey.DirectionLeft, ComposeKey.DirectionRight, ComposeKey.DirectionUp, ComposeKey.DirectionDown)) {
                            sidebarEntryRequested.value = true
                            runCatching { sidebarFocusRequester.requestFocus() }
                            return@onPreviewKeyEvent true
                        }
                        // A released Left disarms entry again, so a stale arm can't open the bar later.
                        if (contentHasFocus && event.type == KeyEventType.KeyUp && event.key == ComposeKey.DirectionLeft) {
                            sidebarEntryRequested.value = false
                        }
                        if (contentHasFocus && event.type == KeyEventType.KeyDown) {
                            // Merely pressing Left must not open the rail: rows first move to
                            // their previous card. This only arms entry; Sidebar expands if
                            // spatial focus actually crosses over from the row's first item.
                            // Held Left (fast scrolling) never arms it: only a fresh press at the real edge.
                            sidebarEntryRequested.value = sidebarEntryArmed(event.key == ComposeKey.DirectionLeft, event.nativeKeyEvent.repeatCount)
                        }
                        false
                    }
                    .onKeyEvent { event ->
                        // Left/Up reach Compose's root key handler, which plays the navigation tick.
                        // Down/Right often do not: rows and dialogs consume them while moving focus,
                        // and the root effect for those two directions is silent on many TV images.
                        // Play the same tick here when this row still sees the key, and consume a
                        // successful move so the root does not click a second time.
                        if (handheld || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val direction = when (event.key) {
                            ComposeKey.DirectionDown -> FocusDirection.Down
                            ComposeKey.DirectionRight -> FocusDirection.Right
                            else -> return@onKeyEvent false
                        }
                        if (!focusManager.moveFocus(direction)) return@onKeyEvent false
                        uiSounds?.playFocus()
                        true
                    },
            ) {
            if (!handheld && currentRoute !in FULLSCREEN_ROUTES) {
                // Nested browse pages keep the menu reachable. Detail pages are fullscreen so
                // focus cannot escape into the sidebar while viewing an item.
                Sidebar(
                    currentRoute = if (currentRoute in SIDEBAR_ROUTES) currentRoute else lastTopLevelRoute,
                    currentSectionKey = if (currentRoute == Destinations.SECTION) currentSectionKey else lastSectionKey,
                    currentYouTubeFeedKey = currentFeedKey,
                    currentTrailerFeedKey = currentTrailerFeedKey,
                    currentSpotifySectionKey = currentSpotifySectionKey,
                    currentRadioSectionKey = currentRadioSectionKey,
                    currentTelegramSectionKey = currentTelegramSectionKey,
                    currentTvheadendTab = currentTvheadendTab,
                    currentLiveTvSourceId = currentLiveTvSourceId,
                    currentFileSourceId = currentFileSourceId,
                    currentDlnaServerId = currentDlnaServerId,
                    onNavigate = ::navigateSidebar,
                    onOpenSystemSettings = ::openSystemSettings,
                    onOpenPlayback = { route -> guardedSettingsExit { navController.navigate(route) { launchSingleTop = true } } },
                    onPlayNext = ::playNextFromSidebar,
                    requestContentFocus = ::requestContentFocus,
                    contentReady = !waitsForSidebarContent || sidebarContentReady,
                    isEntryRequested = { sidebarEntryRequested.value },
                    onEntryRequestConsumed = { sidebarEntryRequested.value = false },
                    onFocusOwnershipChanged = { sidebarHasFocus = it },
                    modifier = Modifier.focusRequester(sidebarFocusRequester),
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(contentFocusRequester)
                    .focusProperties {
                        onEnter = {
                            when {
                                sidebarHasFocus && !contentFocusPermit.value -> cancelFocusChange()
                                sidebarContentPending -> cancelFocusChange()
                            }
                        }
                    }
                    .onFocusChanged {
                        contentHasFocus = it.hasFocus
                        contentContainerFocused = it.isFocused
                    }
                    // Each destination's RestorableSidebarContent restores its own child.
                    // Keep this outer group for ownership only, so its entry guard cannot
                    // override the destination's focusRestorer callback.
                    .focusGroup(),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    // TV destinations change atomically: outgoing pages must not retain focus
                    // or Back ownership while the parent page is already active.
                    enterTransition = {
                        if (!handheld || radioSectionTransitionIsInstant(
                                initialState.destination.route, targetState.destination.route,
                            )) EnterTransition.None
                        else fadeIn(tween(700))
                    },
                    exitTransition = {
                        if (!handheld || radioSectionTransitionIsInstant(
                                initialState.destination.route, targetState.destination.route,
                            )) ExitTransition.None
                        else fadeOut(tween(700))
                    },
                    // Pop and predictive-back pops default to Navigation's own scale-out
                    // animation (page shrinks, parent shows behind); keep TV atomic here too.
                    popEnterTransition = {
                        if (!handheld || radioSectionTransitionIsInstant(
                                initialState.destination.route, targetState.destination.route,
                            )) EnterTransition.None else fadeIn(tween(700))
                    },
                    popExitTransition = {
                        if (!handheld || radioSectionTransitionIsInstant(
                                initialState.destination.route, targetState.destination.route,
                            )) ExitTransition.None else fadeOut(tween(700))
                    },
                    predictivePopEnterTransition = {
                        if (!handheld || radioSectionTransitionIsInstant(
                                initialState.destination.route, targetState.destination.route,
                            )) EnterTransition.None else fadeIn(tween(700))
                    },
                    predictivePopExitTransition = {
                        if (!handheld || radioSectionTransitionIsInstant(
                                initialState.destination.route, targetState.destination.route,
                            )) ExitTransition.None else fadeOut(tween(700))
                    },
                ) {
                    composable(Destinations.INITIAL_SETUP) {
                        fun setupSource(category: String) {
                            // First setup must not enable an unchosen provider through a legacy default.
                            if (!appPreferences.initialSetupCompleted) appPreferences.youtubeEnabled = false
                            navController.navigate(Destinations.setupSettings(category))
                        }
                        InitialSetupScreen(
                            onUseFiles = {
                                if (!appPreferences.initialSetupCompleted) appPreferences.youtubeEnabled = false
                                appPreferences.filesEnabled = true
                                appPreferences.initialSetupCompleted = true
                                navController.navigate(Destinations.setupComplete(Destinations.FILES))
                            },
                            onSetupPlex = { setupSource("network") },
                            onSetupJellyfin = { setupSource("jellyfin") },
                            onSetupEmby = { setupSource("emby") },
                            onSetupDlna = { setupSource("dlna") },
                            onSetupTvheadend = { setupSource("tvheadend") },
                            onSetupIptv = { setupSource("iptv") },
                            onUseYouTubeWithoutLogin = {
                                appPreferences.youtubeEnabled = true
                                appPreferences.initialSetupCompleted = true
                                navController.navigate(Destinations.setupComplete(Destinations.YOUTUBE))
                            },
                            onYouTubeSignedIn = {
                                appPreferences.youtubeEnabled = true
                                appPreferences.initialSetupCompleted = true
                                navController.navigate(Destinations.setupComplete(Destinations.YOUTUBE))
                            },
                            onSetupSpotify = { setupSource("spotify") },
                            onSetupTelegram = { setupSource("telegram") },
                            onTelegramSignedIn = {
                                // Like SmartTube: signing in from the setup step enables the system.
                                if (!appPreferences.initialSetupCompleted) appPreferences.youtubeEnabled = false
                                appPreferences.telegramEnabled = true
                                appPreferences.initialSetupCompleted = true
                                navController.navigate(Destinations.setupComplete(Destinations.TELEGRAM))
                            },
                            onUseRadio = {
                                if (!appPreferences.initialSetupCompleted) appPreferences.youtubeEnabled = false
                                appPreferences.radioEnabled = true
                                appPreferences.initialSetupCompleted = true
                                navController.navigate(Destinations.setupComplete(Destinations.RADIO))
                            },
                            onSetupTrailers = { setupSource("trailers") },
                            onOpenSettings = { setupSource("appearance") },
                            onRestore = { setupSource("backup") },
                            // Skipping with nothing set up would land on an empty home that leads back here.
                            onSkip = configuredStartDestination()?.let { destination -> {
                                if (!appPreferences.initialSetupCompleted) appPreferences.youtubeEnabled = false
                                appPreferences.initialSetupCompleted = true
                                navController.navigate(destination) { popUpTo(Destinations.INITIAL_SETUP) { inclusive = true } }
                            } },
                        )
                    }
                    composable(
                        route = Destinations.SETUP_SETTINGS,
                        arguments = listOf(navArgument("category") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val category = backStackEntry.arguments?.getString("category")
                        fun finishSetupAfterSave() {
                            val destination = configuredStartDestination()
                            if (destination != null) {
                                appPreferences.initialSetupCompleted = true
                                navController.navigate(Destinations.setupComplete(destination)) {
                                    popUpTo(Destinations.INITIAL_SETUP) { inclusive = false }
                                }
                            }
                            // With no usable source remain here; Save must not silently turn a
                            // failed Plex/Jellyfin setup into the default YouTube guest session.
                        }
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onDone = ::finishSetupAfterSave,
                            onExitApp = ::exitApp,
                            initialCategoryKey = category,
                            onUiPreferencesPreview = onUiPreferencesPreview,
                            onPerformanceModePreview = onPerformanceModePreview,
                            onVisibleBackgroundVideoPlaybackPreview = onVisibleBackgroundVideoPlaybackPreview,
                            onUiLanguageSaved = onUiLanguageSaved,
                            onOpenFiles = {
                                appPreferences.filesEnabled = true
                                appPreferences.initialSetupCompleted = true
                                navController.navigate(Destinations.FILES)
                            },
                            onTestScreensaver = { state ->
                                screensaverPreview = state.toScreensaverPreview()
                                onScreensaverActiveChanged(true)
                            },
                        )
                    }
                    composable(Destinations.SETUP_COMPLETE, arguments = listOf(navArgument("destination") { type = NavType.StringType })) { entry ->
                        val destination = entry.arguments?.getString("destination")?.takeIf { it in setOf(Destinations.HOME, Destinations.YOUTUBE, Destinations.TELEGRAM, Destinations.SPOTIFY, Destinations.RADIO, Destinations.FILES) || it == configuredStartDestination() } ?: Destinations.HOME
                        com.ambient.tv.ui.setup.SetupCompleteScreen(
                            onStart = { navController.navigate(destination) { popUpTo(Destinations.INITIAL_SETUP) { inclusive = true } } },
                            onAddSource = { navController.popBackStack(Destinations.INITIAL_SETUP, false) },
                        )
                    }
                    composable(Destinations.SERVER_SELECT) {
                        ServerSelectScreen(
                            onContinue = {
                                navController.navigate(Destinations.HOME) {
                                    popUpTo(Destinations.SERVER_SELECT) { inclusive = true }
                                }
                            },
                            onSettings = { navController.navigate(Destinations.SETTINGS) },
                        )
                    }
                    composable(Destinations.HOME) {
                        RestorableSidebarContent {
                            HomeScreen(
                                onOpenSettings = ::openSettingsPreservingContext,
                                onOpenRelevantSettings = ::openSystemSettings,
                                onAddSystem = { navController.navigate(Destinations.INITIAL_SETUP) { launchSingleTop = true } },
                                onBack = ::focusSidebar,
                                onItemClick = { ratingKey ->
                                    val trailerParts = ratingKey.removePrefix("trailer-title:").split(':')
                                    if (ratingKey.startsWith("trailer-title:") && trailerParts.size == 2) {
                                        trailerParts[1].toIntOrNull()?.let { navController.navigate(Destinations.tmdbTrailers(trailerParts[0], it)) }
                                    } else {
                                        handleMediaSelection(ratingKey)
                                    }
                                },
                                onYouTubeVideoClick = { videoId, session -> handleYouTubeSelection(videoId, session) },
                                onTelegramPostClick = { key, queue ->
                                    com.ambient.tv.data.telegram.telegramPostIds(key)?.let { (chatId, messageId) ->
                                        navController.navigate(Destinations.telegramDetail(chatId, messageId, queue))
                                    } ?: handleMediaSelection(key)
                                },
                                onRadioHomeStationClick = ::handleHomeRadioSelection,
                                onWatchLiveChannel = { channel -> playOnSelectedTarget(Destinations.tvhPlayer("channel", channel.uuid, channel.sourceId)) },
                                onOpenHomeContent = { row ->
                                    when (row.system) {
                                        HomeRowDefinition.SYSTEM_YOUTUBE -> youtubeFeedRoute(row.contentKey).let { route -> if (route == Destinations.YOUTUBE) navigateTopLevel(route) else navigateToSection(route) }
                                        HomeRowDefinition.SYSTEM_SPOTIFY -> if (row.contentKey == "now_playing") playOnSelectedTarget(Destinations.spotifyPlayer()) else if (row.contentKey == "home") navigateTopLevel(Destinations.SPOTIFY) else navigateToSection(Destinations.spotifySection(row.contentKey))
                                        HomeRowDefinition.SYSTEM_TELEGRAM -> {
                                            val route = Destinations.telegramSection(row.contentKey)
                                            if (route == Destinations.TELEGRAM) navigateTopLevel(route)
                                            else navigateToSection(route)
                                        }
                                        HomeRowDefinition.SYSTEM_RADIO -> {
                                            val section = when {
                                                row.contentKey == "home" -> "popular"
                                                row.contentKey in com.ambient.tv.ui.radio.RadioSection.entries.map { it.key } -> row.contentKey
                                                else -> "popular"
                                            }
                                            val route = Destinations.radioSection(section)
                                            if (route == Destinations.RADIO) navigateTopLevel(route) else navigateToSection(route)
                                        }
                                        HomeRowDefinition.SYSTEM_TVHEADEND -> navigateToSection(Destinations.liveTvSection(row.contentKey))
                                        else -> if (row.contentKey in setOf("live", "guide", "recordings", "timers")) {
                                            val sourceId = providerNavigationViewModel.uiState.value.liveTvSources
                                                .firstOrNull { it.source.system == row.system }?.source?.id
                                            if (sourceId != null) navigateToSection(Destinations.liveTvSection(row.contentKey, sourceId))
                                        }
                                    }
                                },
                            )
                        }
                    }
                    composable(Destinations.PLEX) {
                        RestorableSidebarContent {
                          com.ambient.tv.ui.home.OfflineSystemGuard("plex", ::openSystemSettings) {
                            com.ambient.tv.ui.navigation.ProviderBrowseContent("plex", Destinations.PLEX, providerNavigationViewModel, ::navigateSidebar) {
                                PlexLibraryScreen(
                                    onItemClick = { ratingKey -> handleMediaSelection(ratingKey) },
                                    onOpenSettings = { openSystemSettings("plex") },
                                    onOpenRelevantSettings = ::openSystemSettings,
                                    onOpenAppHome = { navigateSidebar(Destinations.HOME) },
                                    onBack = ::focusSidebar,
                                )
                            }
                          }
                        }
                    }
                    composable(Destinations.JELLYFIN) {
                        RestorableSidebarContent {
                          com.ambient.tv.ui.home.OfflineSystemGuard("jellyfin", ::openSystemSettings) {
                            com.ambient.tv.ui.navigation.ProviderBrowseContent("jellyfin", Destinations.JELLYFIN, providerNavigationViewModel, ::navigateSidebar) {
                                JellyfinLibraryScreen(
                                    onItemClick = { ratingKey -> handleMediaSelection(ratingKey) },
                                    onOpenSettings = { openSystemSettings("jellyfin") },
                                    onOpenRelevantSettings = ::openSystemSettings,
                                    onOpenAppHome = { navigateSidebar(Destinations.HOME) },
                                    onBack = ::focusSidebar,
                                )
                            }
                          }
                        }
                    }
                    composable(Destinations.EMBY) {
                        RestorableSidebarContent {
                          com.ambient.tv.ui.home.OfflineSystemGuard("emby", ::openSystemSettings) {
                            com.ambient.tv.ui.navigation.ProviderBrowseContent("emby", Destinations.EMBY, providerNavigationViewModel, ::navigateSidebar) {
                                EmbyLibraryScreen(
                                    onItemClick = { ratingKey -> handleMediaSelection(ratingKey) },
                                    onOpenSettings = { openSystemSettings("emby") },
                                    onOpenRelevantSettings = ::openSystemSettings,
                                    onOpenAppHome = { navigateSidebar(Destinations.HOME) },
                                    onBack = ::focusSidebar,
                                )
                            }
                          }
                        }
                    }
                    composable(Destinations.SEARCH) {
                        RestorableSidebarContent {
                            SearchScreen(
                                onItemClick = { ratingKey -> handleMediaSelection(ratingKey) },
                                onYouTubeClick = { videoId -> handleYouTubeSelection(videoId) },
                                onSpotifyClick = { item -> if (item.type.name == "TRACK") playOnSelectedTarget(Destinations.spotifyPlayer(item.uri)) else navController.navigate(Destinations.spotifyDetail(item.type.name.lowercase(), item.id)) },
                                onTvheadendChannelClick = { channel -> playOnSelectedTarget(Destinations.tvhPlayer("channel", channel.uuid, channel.sourceId)) },
                                onTvheadendProgramClick = { event ->
                                    if (event.isLive(System.currentTimeMillis() / 1000L)) {
                                        playOnSelectedTarget(Destinations.tvhPlayer("channel", event.channelUuid, event.sourceId))
                                    } else {
                                        navigateToSection(Destinations.liveTvSection("guide", event.sourceId))
                                    }
                                },
                                onTvheadendRecordingClick = { recording -> playOnSelectedTarget(Destinations.tvhPlayer("recording", recording.uuid, recording.sourceId)) },
                                onRadioStarted = { navController.navigate(Destinations.RADIO_PLAYER) { launchSingleTop = true } },
                                onBack = ::focusSidebar,
                            )
                        }
                    }
                    composable(Destinations.YOUTUBE) {
                        RestorableSidebarContent {
                            YouTubeScreen(
                                initialFeedKey = appPreferences.youtubeStartFeedKey.takeIf { it != "home" },
                                onVideoClick = { videoId, session -> handleYouTubeSelection(videoId, session) },
                                onBack = ::focusSidebar,
                                onSelectFeed = { feed -> navigateSidebar(youtubeFeedRoute(feed.key)) },
                            )
                        }
                    }
                    composable(Destinations.YOUTUBE_FEED, arguments = listOf(navArgument("feedKey") { type = NavType.StringType })) { entry ->
                        RestorableSidebarContent {
                            YouTubeScreen(
                                initialFeedKey = entry.arguments?.getString("feedKey"),
                                onVideoClick = { videoId, session -> handleYouTubeSelection(videoId, session) },
                                onBack = ::focusSidebar,
                                onSelectFeed = { feed -> navigateSidebar(youtubeFeedRoute(feed.key)) },
                            )
                        }
                    }
                    composable(Destinations.TELEGRAM) {
                        RestorableSidebarContent {
                            com.ambient.tv.ui.telegram.TelegramScreen(
                                sectionKey = "recent",
                                onSelectSection = { key -> navigateSidebar(Destinations.telegramSection(key)) },
                                onOpenSettings = { openSystemSettings("telegram:account") },
                                onOpenChannel = { chatId -> navigateToSection(Destinations.telegramSection("channel:$chatId")) },
                                onOpenPost = { chatId, messageId, queue ->
                                    navController.navigate(Destinations.telegramDetail(chatId, messageId, queue))
                                },
                            )
                        }
                    }
                    composable(Destinations.TELEGRAM_SECTION,
                        arguments = listOf(navArgument("sectionKey") { type = NavType.StringType })) { entry ->
                        RestorableSidebarContent {
                            com.ambient.tv.ui.telegram.TelegramScreen(
                                sectionKey = entry.arguments?.getString("sectionKey") ?: "recent",
                                onSelectSection = { key -> navigateSidebar(Destinations.telegramSection(key)) },
                                onOpenSettings = { openSystemSettings("telegram:account") },
                                onOpenChannel = { chatId -> navigateToSection(Destinations.telegramSection("channel:$chatId")) },
                                onOpenPost = { chatId, messageId, queue ->
                                    navController.navigate(Destinations.telegramDetail(chatId, messageId, queue))
                                },
                            )
                        }
                    }
                    composable(Destinations.TELEGRAM_DETAIL,
                        arguments = listOf(
                            navArgument("chatId") { type = NavType.LongType },
                            navArgument("messageId") { type = NavType.IntType },
                            navArgument("queue") { type = NavType.LongType; defaultValue = -1L },
                        )) { entry ->
                        com.ambient.tv.ui.telegram.TelegramPostDetailScreen(
                            chatId = entry.arguments?.getLong("chatId") ?: 0L,
                            messageId = entry.arguments?.getInt("messageId") ?: 0,
                            repository = telegramRepository,
                            onBack = ::navigateBack,
                            onOpenSettings = { openSystemSettings("telegram:account") },
                            onOpenChannel = { id ->
                                navController.navigate(Destinations.telegramSection("channel:$id"))
                            },
                            onPlayMedia = { chat, message ->
                                // The page's queue only applies to the post it was started with.
                                val queue = entry.arguments?.getLong("queue")?.takeIf {
                                    it >= 0L && chat == entry.arguments?.getLong("chatId") &&
                                        message == entry.arguments?.getInt("messageId")
                                }
                                playOnSelectedTarget(Destinations.telegramPlayer(chat, message, queue),
                                    navigate = { route -> navController.navigateToTelegramPlayer(route) })
                            },
                            onOpenYouTube = { videoId -> handleYouTubeSelection(videoId) },
                        )
                    }
                    composable(Destinations.TELEGRAM_PLAYER,
                        arguments = listOf(
                            navArgument("chatId") { type = NavType.LongType },
                            navArgument("messageId") { type = NavType.IntType },
                            navArgument("queue") { type = NavType.LongType; defaultValue = -1L },
                        )) {
                        com.ambient.tv.ui.telegram.TelegramPlayerScreen(
                            onBack = ::navigateBack,
                            onOpenChannel = { chat ->
                                navController.navigate(Destinations.telegramSection("channel:$chat"))
                            },
                        )
                    }
                    composable(Destinations.RADIO) {
                        RestorableSidebarContent {
                            com.ambient.tv.ui.radio.RadioScreen(
                                initialSectionKey = "popular",
                                onBack = ::focusSidebar,
                                onOpenPlayer = { navController.navigate(Destinations.RADIO_PLAYER) { launchSingleTop = true } },
                                onSelectSection = { section -> navigateSidebar(Destinations.radioSection(section.key)) },
                            )
                        }
                    }
                    composable(
                        Destinations.RADIO_SECTION,
                        arguments = listOf(navArgument("sectionKey") { type = NavType.StringType }),
                    ) { entry ->
                        RestorableSidebarContent {
                            com.ambient.tv.ui.radio.RadioScreen(
                                initialSectionKey = entry.arguments?.getString("sectionKey"),
                                onBack = ::focusSidebar,
                                onOpenPlayer = { navController.navigate(Destinations.RADIO_PLAYER) { launchSingleTop = true } },
                                onSelectSection = { section -> navigateSidebar(Destinations.radioSection(section.key)) },
                            )
                        }
                    }
                    composable(Destinations.RADIO_PLAYER) {
                        com.ambient.tv.ui.radio.RadioPlayerScreen(onBack = ::navigateBack)
                    }
                    composable(Destinations.SPOTIFY) {
                        RestorableSidebarContent {
                            SpotifyHomeScreen(
                                onOpen = { item ->
                                    if (item.type.name == "TRACK") playOnSelectedTarget(Destinations.spotifyPlayer(item.uri))
                                    else navController.navigate(Destinations.spotifyDetail(item.type.name.lowercase(), item.id))
                                },
                                onNowPlaying = { playOnSelectedTarget(Destinations.spotifyPlayer()) },
                                onBack = ::focusSidebar,
                            )
                        }
                    }
                    composable(Destinations.SPOTIFY_SECTION, arguments = listOf(navArgument("sectionKey") { type = NavType.StringType })) { entry ->
                        RestorableSidebarContent {
                            SpotifyHomeScreen(
                                initialSectionKey = entry.arguments?.getString("sectionKey"),
                                onOpen = { item -> if (item.type.name == "TRACK") playOnSelectedTarget(Destinations.spotifyPlayer(item.uri)) else navController.navigate(Destinations.spotifyDetail(item.type.name.lowercase(), item.id)) },
                                onNowPlaying = { playOnSelectedTarget(Destinations.spotifyPlayer()) },
                                onBack = ::focusSidebar,
                            )
                        }
                    }
                    composable(
                        route = Destinations.SPOTIFY_DETAIL,
                        arguments = listOf(navArgument("type") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
                    ) {
                        SpotifyDetailScreen(
                            onBack = ::navigateBack,
                            onOpenSettings = { openSystemSettings("spotify") },
                            onNowPlaying = { playOnSelectedTarget(Destinations.spotifyPlayer()) },
                            onOpenAlbum = { albumId -> navController.navigate(Destinations.spotifyDetail("album", albumId)) },
                        )
                    }
                    composable(route = Destinations.SPOTIFY_PLAYER, arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                        LaunchedEffect(Unit) { BackgroundPlaybackSession.stop() }
                        SpotifyPlayerScreen(onBack = ::navigateBack)
                    }
                    composable(
                        route = Destinations.YOUTUBE_DETAIL,
                        arguments = listOf(
                            navArgument("videoId") { type = NavType.StringType },
                            navArgument("browseSession") { type = NavType.LongType; defaultValue = -1L },
                        ),
                    ) { entry ->
                        YouTubeDetailScreen(
                            onBack = ::navigateBack,
                            onOpenSettings = { openSystemSettings("youtube") },
                            onPlay = { videoId ->
                                playYouTubeFromBrowse(videoId, entry.arguments?.getLong("browseSession")?.takeIf { it >= 0L })
                            },
                            onOpenChannel = ::openYouTubeChannel,
                            onOpenItem = { videoId -> handleYouTubeSelection(videoId) },
                        )
                    }
                    composable(
                        route = Destinations.YOUTUBE_CHANNEL,
                        arguments = listOf(
                            navArgument("channelId") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType; defaultValue = "" },
                            navArgument("avatar") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) {
                        YouTubeScreen(
                            onVideoClick = { videoId, session -> handleYouTubeSelection(videoId, session) },
                            onBack = ::navigateBack,
                        )
                    }
                    composable(Destinations.TRAILERS) {
                        RestorableSidebarContent {
                            com.ambient.tv.trailers.TrailerScreen(onBack = ::focusSidebar,
                                onOpenSettings = { openSystemSettings("trailers") },
                                onSelectFeed = { feed -> navigateSidebar(trailerFeedRoute(feed)) },
                                onOpenTitle = { type, id -> navController.navigate(Destinations.tmdbTrailers(type, id)) },
                                onPlay = { playOnSelectedTarget(Destinations.trailerPlayer(it)) })
                        }
                    }
                    composable(Destinations.TRAILER_FEED, arguments = listOf(navArgument("feedKey") { type = NavType.StringType })) { entry ->
                        RestorableSidebarContent {
                            com.ambient.tv.trailers.TrailerScreen(
                                initialFeedKey = entry.arguments?.getString("feedKey"),
                                onBack = ::focusSidebar,
                                onOpenSettings = { openSystemSettings("trailers") },
                                onSelectFeed = { feed -> navigateSidebar(trailerFeedRoute(feed)) },
                                onOpenTitle = { type, id -> navController.navigate(Destinations.tmdbTrailers(type, id)) },
                                onPlay = { playOnSelectedTarget(Destinations.trailerPlayer(it)) },
                            )
                        }
                    }
                    composable(Destinations.ITEM_TRAILERS, arguments = listOf(navArgument("ratingKey") { type = NavType.StringType })) { entry ->
                        com.ambient.tv.trailers.TrailerScreen(onBack = { navController.popBackStack() },
                            onOpenSettings = { openSystemSettings("trailers") },
                            onOpenTitle = { type, id -> navController.navigate(Destinations.tmdbTrailers(type, id)) },
                            ratingKey = entry.arguments?.getString("ratingKey"),
                            onPlay = { playOnSelectedTarget(Destinations.trailerPlayer(it)) },
                            onPlayServerTrailer = { playOnSelectedTarget(Destinations.player(it, restart = true)) })
                    }
                    composable(
                        Destinations.TMDB_TRAILERS,
                        arguments = listOf(navArgument("mediaType") { type = NavType.StringType },
                            navArgument("tmdbId") { type = NavType.IntType }),
                    ) { entry ->
                        com.ambient.tv.trailers.TrailerScreen(
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { openSystemSettings("trailers") },
                            onOpenTitle = { type, id -> navController.navigate(Destinations.tmdbTrailers(type, id)) },
                            tmdbType = entry.arguments?.getString("mediaType"),
                            tmdbId = entry.arguments?.getInt("tmdbId"),
                            onPlay = { playOnSelectedTarget(Destinations.trailerPlayer(it)) },
                        )
                    }
                    composable(
                        route = Destinations.YOUTUBE_PLAYER,
                        arguments = listOf(navArgument("videoId") { type = NavType.StringType },
                            navArgument("trailer") { type = NavType.BoolType; defaultValue = false },
                            navArgument("browseSession") { type = NavType.LongType; defaultValue = -1L }),
                    ) { entry ->
                        YouTubePlayerScreen(
                            onBack = { if (navController.currentBackStackEntry === entry) navController.popBackStack() },
                            onOpenChannel = { channel -> if (navController.currentBackStackEntry === entry) openYouTubeChannel(channel) },
                        )
                    }
                    composable(Destinations.LIVE_TV) {
                        RestorableSidebarContent {
                            TvheadendScreen(
                                initialTab = com.ambient.tv.ui.tvheadend.TvheadendTab.LIVE,
                                onOpenSettings = ::openSettingsPreservingContext,
                                onOpenSourceSettings = ::openSystemSettings,
                                onBack = ::focusSidebar,
                                onSelectTab = { tab -> navigateSidebar(tvheadendTabRoute(tab)) },
                                onWatchChannel = { channelUuid -> playOnSelectedTarget(Destinations.tvhPlayer("channel", channelUuid)) },
                                onPlayRecording = { recordingUuid -> playOnSelectedTarget(Destinations.tvhPlayer("recording", recordingUuid)) },
                            )
                        }
                    }
                    composable(
                        route = Destinations.LIVE_TV_SECTION,
                        arguments = listOf(navArgument("tab") { type = NavType.StringType }, navArgument("sourceId") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { entry ->
                        val sourceId = entry.arguments?.getString("sourceId")
                        val initialTab = when (entry.arguments?.getString("tab")) {
                            "guide" -> com.ambient.tv.ui.tvheadend.TvheadendTab.GUIDE
                            "recordings" -> com.ambient.tv.ui.tvheadend.TvheadendTab.RECORDINGS
                            "timers" -> com.ambient.tv.ui.tvheadend.TvheadendTab.TIMERS
                            "hbbtv" -> com.ambient.tv.ui.tvheadend.TvheadendTab.HBBTV
                            else -> com.ambient.tv.ui.tvheadend.TvheadendTab.LIVE
                        }
                        val providerState by providerNavigationViewModel.uiState.collectAsState()
                        val providerSystem = providerState.liveTvSources.firstOrNull { it.source.id == sourceId }
                            ?.source?.system?.takeIf { it in setOf("plex", "jellyfin", "emby") }
                        RestorableSidebarContent {
                            com.ambient.tv.ui.navigation.ProviderBrowseContent(
                                providerSystem, Destinations.liveTvSection(initialTab.name.lowercase(), sourceId),
                                providerNavigationViewModel, ::navigateSidebar,
                            ) {
                                TvheadendScreen(
                                    initialTab = initialTab,
                                    onOpenSettings = ::openSettingsPreservingContext,
                                    onOpenSourceSettings = ::openSystemSettings,
                                    onBack = ::focusSidebar,
                                    onSelectTab = { tab -> navigateSidebar(Destinations.liveTvSection(tab.name.lowercase(), sourceId)) },
                                    onWatchChannel = { channelUuid -> playOnSelectedTarget(Destinations.tvhPlayer("channel", channelUuid, sourceId)) },
                                    onPlayRecording = { recordingUuid -> playOnSelectedTarget(Destinations.tvhPlayer("recording", recordingUuid, sourceId)) },
                                )
                            }
                        }
                    }
                    listOf(Destinations.FILES to "browse", Destinations.FILE_FAVORITES to "favorites",
                        Destinations.FILE_RECENT to "recent", Destinations.FILE_CONTINUE to "continue").forEach { (route, tab) ->
                        composable(route, arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = tab })) {
                            RestorableSidebarContent {
                                // Files sub-pages as pill tabs at the top (they left the main menu).
                                com.ambient.tv.ui.navigation.ProviderBrowseContent("files", route, providerNavigationViewModel, ::navigateSidebar) {
                                com.ambient.tv.ui.files.FilesScreen(
                                    onPlay = { id, restart -> playOnSelectedTarget(Destinations.player(id, restart = restart)) },
                                    onBack = ::focusSidebar,
                                    contentActive = !sidebarHasFocus && windowInfo.isWindowFocused && !screensaverOverlayPresent,
                                    onOpenPhotos = { navController.navigate(Destinations.PHOTO_VIEWER) },
                                )
                                }
                            }
                        }
                    }
                    composable(
                        route = Destinations.FILE_SOURCE,
                        arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
                    ) { entry ->
                        RestorableSidebarContent {
                            val sourceRoute = Destinations.fileSource(entry.arguments?.getString("sourceId").orEmpty())
                            com.ambient.tv.ui.navigation.ProviderBrowseContent("files", sourceRoute, providerNavigationViewModel, ::navigateSidebar) {
                            com.ambient.tv.ui.files.FilesScreen(
                                onPlay = { id, restart -> playOnSelectedTarget(Destinations.player(id, restart = restart)) },
                                onBack = ::focusSidebar,
                                contentActive = !sidebarHasFocus && windowInfo.isWindowFocused && !screensaverOverlayPresent,
                                onOpenPhotos = { navController.navigate(Destinations.PHOTO_VIEWER) },
                            )
                            }
                        }
                    }
                    composable(Destinations.DLNA) {
                        RestorableSidebarContent {
                            com.ambient.tv.ui.navigation.ProviderBrowseContent("dlna", Destinations.DLNA, providerNavigationViewModel, ::navigateSidebar) {
                            com.ambient.tv.ui.dlna.DlnaManageScreen(
                                onOpen = { serverId -> navController.navigate(Destinations.dlnaServer(serverId)) },
                                onBack = ::focusSidebar,
                            )
                            }
                        }
                    }
                    composable(
                        route = Destinations.DLNA_SERVER,
                        arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
                    ) {
                        RestorableSidebarContent {
                            com.ambient.tv.ui.dlna.DlnaBrowserScreen(
                                onPlay = { key -> playOnSelectedTarget(Destinations.player(key)) },
                                onManage = { navController.navigate(Destinations.DLNA) },
                                onBack = ::focusSidebar,
                            )
                        }
                    }
                    composable(Destinations.SETTINGS) { entry ->
                        // Restored from an earlier session: drop its page request before it is read.
                        // A new entry has no session yet and keeps a page set by openSystemSettings.
                        val entrySession = entry.savedStateHandle.get<Int>(SETTINGS_SESSION_KEY)
                        if (entrySession != settingsSession) {
                            if (entrySession != null) entry.savedStateHandle.remove<String>(SETTINGS_PAGE_KEY)
                            entry.savedStateHandle[SETTINGS_SESSION_KEY] = settingsSession
                        }
                        val settingsPage by entry.savedStateHandle.getStateFlow<String?>(SETTINGS_PAGE_KEY, null).collectAsState()
                        val settingsPageSerial by entry.savedStateHandle.getStateFlow(SETTINGS_PAGE_SERIAL, 0).collectAsState()
                        RestorableSidebarContent {
                            // A repeated shortcut reopens its page even after Settings moved elsewhere.
                            // The session in the key also drops rememberSaveable state (area, page, search).
                            androidx.compose.runtime.key(settingsPageSerial, settingsSession) {
                                SettingsScreen(
                                    initialCategoryKey = settingsPage,
                                    onExitGuardChanged = { settingsExitGuard = it },
                                    onBack = ::navigateBack,
                                    onFocusMenu = ::focusSidebar,
                                    // Save keeps Settings open; leaving goes through Back or the sidebar.
                                    onDone = {},
                                    onUiPreferencesPreview = onUiPreferencesPreview,
                                    onPerformanceModePreview = onPerformanceModePreview,
                                    onVisibleBackgroundVideoPlaybackPreview = onVisibleBackgroundVideoPlaybackPreview,
                                    onUiLanguageSaved = onUiLanguageSaved,
                                    onOpenFiles = {
                                        appPreferences.filesEnabled = true
                                        appPreferences.initialSetupCompleted = true
                                        navController.navigate(Destinations.FILES)
                                    },
                                    onOpenDlna = { navController.navigate(Destinations.DLNA) },
                                    onTestScreensaver = { state ->
                                        screensaverPreview = state.toScreensaverPreview()
                                        onScreensaverActiveChanged(true)
                                    },
                                    onExitApp = ::exitApp,
                                )
                            }
                        }
                    }
                    composable(
                        route = Destinations.SECTION,
                        arguments = listOf(navArgument("sectionKey") { type = NavType.StringType }),
                    ) { entry ->
                        val sectionKey = entry.arguments?.getString("sectionKey").orEmpty()
                        val photoRouting: com.ambient.tv.ui.photos.PhotoRoutingViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
                        val photoLibrary = photoRouting.photoSections.collectAsState().value[sectionKey]
                        val providerSystem = when {
                            com.ambient.tv.data.jellyfin.JellyfinBackend.owns(sectionKey) -> "jellyfin"
                            com.ambient.tv.data.emby.EmbyBackend.owns(sectionKey) -> "emby"
                            else -> "plex"
                        }
                        RestorableSidebarContent {
                          com.ambient.tv.ui.home.OfflineSystemGuard(providerSystem, ::openSystemSettings) {
                            com.ambient.tv.ui.navigation.ProviderBrowseContent(providerSystem, Destinations.section(sectionKey), providerNavigationViewModel, ::navigateSidebar) {
                                if (photoLibrary != null) {
                                    // Photo libraries of Plex, Jellyfin and Emby keep their sidebar route.
                                    com.ambient.tv.ui.photos.PhotoBrowserScreen(
                                        rootKey = photoLibrary.key,
                                        title = photoLibrary.title,
                                        onOpenViewer = { navController.navigate(Destinations.PHOTO_VIEWER) },
                                        onPlayVideo = { key -> playOnSelectedTarget(Destinations.player(key)) },
                                        onBack = ::focusSidebar,
                                    )
                                } else {
                                    SectionScreen(
                                        onItemClick = { ratingKey -> handleMediaSelection(ratingKey) },
                                        onCollectionClick = { id, title -> navController.navigate(Destinations.filteredBrowse("collection", id, title)) },
                                        onOpenOverview = { system ->
                                            navigateSidebar(when (system) {
                                                com.ambient.tv.ui.common.MediaSystem.JELLYFIN -> Destinations.JELLYFIN
                                                com.ambient.tv.ui.common.MediaSystem.EMBY -> Destinations.EMBY
                                                else -> Destinations.PLEX
                                            })
                                        },
                                        onBack = ::focusSidebar,
                                    )
                                }
                            }
                          }
                        }
                    }
                    composable(Destinations.PHOTO_VIEWER) {
                        com.ambient.tv.ui.photos.PhotoViewerScreen(
                            onClose = { navController.popBackStack() },
                            onPlayVideo = { key -> playOnSelectedTarget(Destinations.player(key)) },
                        )
                    }
                    composable(
                        route = Destinations.DETAIL,
                        arguments = listOf(
                            navArgument("ratingKey") { type = NavType.StringType },
                            navArgument("collectionId") { type = NavType.StringType; defaultValue = "" },
                            navArgument("collectionSort") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { entry ->
                        val collectionId = entry.arguments?.getString("collectionId")?.takeIf { it.isNotBlank() }
                        val collectionSort = entry.arguments?.getString("collectionSort")?.takeIf { it.isNotBlank() }
                        com.ambient.tv.ui.common.HoldHomeBackgroundWhileLeaving {
                        DetailScreen(
                            onBack = ::navigateBack,
                            // Server-stored trailers need no TMDb key; the button still only shows
                            // once a trailer was actually found (rememberTrailerAvailable).
                            onTrailer = if (appPreferences.trailersEnabled)
                                ({ navController.navigate(Destinations.itemTrailers(it.ratingKey)) }) else null,
                            onOpenSettings = {
                                openMediaSettings(entry.arguments?.getString("ratingKey").orEmpty())
                            },
                            onPlay = { item, mediaIndex, restart ->
                                playOnSelectedTarget(
                                    Destinations.player(
                                        item.ratingKey, mediaIndex, restart, collectionId, collectionSort,
                                        detailOrigin = item.type == "episode",
                                    ),
                                    title = item.title,
                                )
                            },
                            onOpenChild = { child -> handleMediaSelection(child.ratingKey, collectionId, collectionSort) },
                            onOpenEpisode = { episode -> navigateToDetails(episode.ratingKey, collectionId, collectionSort) },
                            onOpenSeason = { season ->
                                // Same destination id as this page. popUpTo finds the topmost detail
                                // only, so the series underneath stays and seasons do not stack.
                                val destinationId = navController.currentDestination?.id
                                navController.navigate(Destinations.detail(season.ratingKey, collectionId, collectionSort)) {
                                    if (destinationId != null) popUpTo(destinationId) { inclusive = true }
                                }
                            },
                            onOpenSeries = { seriesKey ->
                                val previous = navController.previousBackStackEntry
                                if (seriesDetailIsDirectlyUnder(
                                        previous?.destination?.route,
                                        previous?.arguments?.getString("ratingKey"),
                                        seriesKey,
                                    )
                                ) {
                                    navController.popBackStack()
                                } else {
                                    navigateToDetails(seriesKey, collectionId, collectionSort)
                                }
                            },
                            onBrowseActor = { id, name -> navController.navigate(Destinations.detailFilteredBrowse("actor", id, name)) },
                            onBrowseCollection = { id, name -> navController.navigate(Destinations.detailFilteredBrowse("collection", id, name)) },
                        )
                        }
                    }
                    composable(
                        route = Destinations.FILTERED_BROWSE,
                        arguments = listOf(
                            navArgument("kind") { type = NavType.StringType },
                            navArgument("filterId") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val kind = entry.arguments?.getString("kind").orEmpty()
                        val filterId = entry.arguments?.getString("filterId").orEmpty()
                        RestorableSidebarContent {
                            FilteredBrowseScreen(
                                onBack = ::focusSidebar,
                                onOpenSettings = { openMediaSettings(filterId) },
                                onItemClick = { ratingKey, sort ->
                                    handleMediaSelection(
                                        ratingKey = ratingKey,
                                        collectionId = filterId.takeIf { kind == "collection" },
                                        collectionSort = sort.name,
                                    )
                                },
                            )
                        }
                    }
                    composable(
                        route = Destinations.DETAIL_FILTERED_BROWSE,
                        arguments = listOf(
                            navArgument("kind") { type = NavType.StringType },
                            navArgument("filterId") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val kind = entry.arguments?.getString("kind").orEmpty()
                        val filterId = entry.arguments?.getString("filterId").orEmpty()
                        com.ambient.tv.ui.common.HoldHomeBackgroundWhileLeaving {
                            FilteredBrowseScreen(
                                onBack = ::navigateBack,
                                onOpenSettings = { openMediaSettings(filterId) },
                                leftNavigatesBack = false,
                                onItemClick = { ratingKey, sort ->
                                    handleMediaSelection(
                                        ratingKey = ratingKey,
                                        collectionId = filterId.takeIf { kind == "collection" },
                                        collectionSort = sort.name,
                                    )
                                },
                            )
                        }
                    }
                    composable(
                        route = Destinations.TVH_PLAYER,
                        arguments = listOf(
                            navArgument("kind") { type = NavType.StringType },
                            navArgument("id") { type = NavType.StringType },
                            navArgument("sourceId") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entry ->
                        TvheadendPlayerScreen(onBack = { if (navController.currentBackStackEntry === entry) navController.popBackStack() })
                    }
                    composable(
                        route = Destinations.PLAYER,
                        arguments = listOf(
                            navArgument("ratingKey") { type = NavType.StringType },
                            navArgument("mediaIndex") { type = NavType.StringType; defaultValue = "0" },
                            navArgument("restart") { type = NavType.BoolType; defaultValue = false },
                            navArgument("collectionId") { type = NavType.StringType; defaultValue = "" },
                            navArgument("collectionSort") { type = NavType.StringType; defaultValue = "" },
                            navArgument("detailOrigin") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) { entry ->
                        val collectionId = entry.arguments?.getString("collectionId")?.takeIf { it.isNotBlank() }
                        val collectionSort = entry.arguments?.getString("collectionSort")?.takeIf { it.isNotBlank() }
                        PlayerScreen(
                            onBack = { playedRatingKey, mediaType ->
                                if (navController.currentBackStackEntry !== entry) return@PlayerScreen
                                val previous = navController.previousBackStackEntry
                                val episodeDetail = episodeDetailAfterPlayback(
                                    detailOrigin = entry.arguments?.getBoolean("detailOrigin") == true,
                                    mediaType = mediaType,
                                    playedRatingKey = playedRatingKey,
                                    underneathRoute = previous?.destination?.route,
                                    underneathRatingKey = previous?.arguments?.getString("ratingKey"),
                                )
                                navController.popBackStack()
                                plexRefreshBus.requestRefresh()
                                if (episodeDetail != null) {
                                    navController.navigate(Destinations.detail(episodeDetail, collectionId, collectionSort))
                                }
                            },
                        )
                    }
                }

                // Detail pages hide the sidebar, which normally owns this indicator.
                // Draw above their backgrounds without adding a new D-pad focus target.
                if (!handheld && currentRoute in setOf(
                        Destinations.DETAIL, Destinations.YOUTUBE_DETAIL, Destinations.YOUTUBE_CHANNEL, Destinations.SPOTIFY_DETAIL,
                    )
                ) {
                    val scale = com.ambient.tv.ui.common.rememberUhdScale()
                    com.ambient.tv.ui.player.BackgroundPlaybackIndicator(
                        Modifier.align(Alignment.BottomStart)
                            .padding(start = 16.dp * scale, bottom = 24.dp * scale),
                    )
                }

                pendingYouTubeVideoId?.let { videoId ->
                    YouTubeSelectionDialog(
                        onDismiss = {
                            pendingYouTubeVideoId = null
                            pendingYouTubeBrowseSession = null
                        },
                        onPlay = {
                            val session = pendingYouTubeBrowseSession
                            pendingYouTubeVideoId = null
                            pendingYouTubeBrowseSession = null
                            playYouTubeFromBrowse(videoId, session)
                        },
                        onDetails = {
                            val session = pendingYouTubeBrowseSession
                            pendingYouTubeVideoId = null
                            pendingYouTubeBrowseSession = null
                            navController.navigate(Destinations.youtubeDetail(videoId, session))
                        },
                    )
                }
                pendingMediaSelection?.let { selection ->
                    MediaSelectionDialog(
                        selection = selection,
                        onDismiss = { pendingMediaSelection = null },
                        onPlay = {
                            pendingMediaSelection = null
                            navigateToResolvedPlayback(
                                selection = selection,
                                restart = false,
                                collectionId = pendingMediaSelectionCollectionId,
                                collectionSort = pendingMediaSelectionCollectionSort,
                            )
                        },
                        onRestart = {
                            pendingMediaSelection = null
                            navigateToResolvedPlayback(
                                selection = selection,
                                restart = true,
                                collectionId = pendingMediaSelectionCollectionId,
                                collectionSort = pendingMediaSelectionCollectionSort,
                            )
                        },
                        onDetails = {
                            pendingMediaSelection = null
                            navigateToDetails(
                                selection.original.ratingKey,
                                pendingMediaSelectionCollectionId,
                                pendingMediaSelectionCollectionSort,
                            )
                        },
                    )
                }
                spotifyTakeover?.let { prompt ->
                    SpotifyPlaybackTakeoverDialog(
                        prompt = prompt,
                        onContinueCurrent = spotifyRepository::continueCurrentPlaybackAfterSpotifyConnect,
                        onSwitchToSpotify = {
                            if (spotifyRepository.acceptSpotifyPlaybackTakeover()) {
                                playOnSelectedTarget(Destinations.spotifyPlayer()) { launchSingleTop = true }
                            }
                        },
                        onDismiss = spotifyRepository::continueCurrentPlaybackAfterSpotifyConnect,
                    )
                }
            }
        }
            } // HandheldNavigationHost
            // Global: the timer can expire on any screen, also while only the mini-player plays.
            com.ambient.tv.ui.player.SleepTimerPromptDialog()
            com.ambient.tv.ui.player.SleepTimerWarningOverlay(touch = !com.ambient.tv.ui.mobile.isTelevision(androidx.compose.ui.platform.LocalContext.current))
            // A DLNA sender that is not trusted yet asks before anything plays.
            com.ambient.tv.renderer.RendererPromptDialog()
            // A programme reminder that is due while the app is open.
            com.ambient.tv.livetv.LiveTvReminderPromptDialog()
            com.ambient.tv.notifications.AppNotificationHost(
                // The intro shows connectivity itself; held cards keep their full reading time.
                hidden = screensaverOverlayPresent || com.ambient.tv.ui.intro.IntroPresentation.active,
                playback = onPlayerScreen,
                // Settings already has an in-place Wake/Cancel toggle. Do not steal its D-pad focus.
                autoFocusWake = currentRoute != Destinations.SETTINGS && currentRoute != Destinations.SETUP_SETTINGS,
                // The page's content group restores its last focused control.
                onWakeFocusReleased = { requestContentFocus() },
            )
            com.ambient.tv.companion.CompanionDialogs(companionVm)
            if (!handheld) com.ambient.tv.companion.CompanionRemoteInput.Overlay(activity?.window)
            ScreensaverOverlay(
                visible = !standbyRequested && ScreensaverPresentation.shouldShow(screensaverActive, screensaverPreview != null, playbackActive),
                onDismiss = { onScreensaverActiveChanged(false) },
                onHidden = {
                    if (!screensaverActive && onScreensaverHidden()) screensaverPreview = null
                },
            ) {
                val preview = screensaverPreview
                AppScreensaver(
                    modeKey = preview?.modeKey ?: screensaverModeKey,
                    showClock = preview?.showClock ?: screensaverShowClock,
                    showDate = preview?.showDate ?: screensaverShowDate,
                    clock = preview?.clock ?: clockSettings,
                    useArtwork = preview?.useArtwork ?: screensaverUseArtwork,
                    artworkIntervalSeconds = preview?.artworkIntervalSeconds ?: screensaverArtworkIntervalSeconds,
                    dimPercent = preview?.dimPercent ?: screensaverDimPercent,
                    qualityKey = preview?.qualityKey ?: screensaverQualityKey,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            }
            } // Keep-composed, invisible phone chrome during PiP.
        }
    }
    } // PlaybackPresentationProvider
}

@Composable
private fun RestorableSidebarContent(content: @Composable () -> Unit) {
    com.ambient.tv.ui.common.BrowseFocusReturnScope {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Re-selecting the open page drops the remembered child once, so focus lands on the
                // top of the freshly scrolled page instead of jumping back down.
                .then(
                    if (com.ambient.tv.ui.common.NavReselect.restoreTop.value) {
                        Modifier.onFocusChanged { if (it.hasFocus) com.ambient.tv.ui.common.NavReselect.restoreTop.value = false }
                    } else Modifier.focusRestorer(),
                )
                .focusGroup(),
        ) {
            content()
        }
    }
}

private fun SettingsUiState.toScreensaverPreview() = ScreensaverPreview(
    modeKey = screensaverModeKey,
    showClock = screensaverShowClock,
    showDate = screensaverShowDate,
    clock = clock,
    useArtwork = screensaverUseArtwork,
    artworkIntervalSeconds = screensaverArtworkIntervalSeconds,
    dimPercent = screensaverDimPercent,
    qualityKey = screensaverQualityKey,
)

private fun spotifyWebAuthorized(tokenStore: TokenStore): Boolean {
    return SpotifyAuthorizationPolicy.isAuthorized(
        tokenStore.spotifyWebAccessToken,
        tokenStore.spotifyWebRefreshToken,
        tokenStore.spotifyWebScopes,
    )
}

/** The playback facts the app shell reacts to; position changes are deliberately not part of it. */
private data class ShellPlaybackState(
    val active: Boolean,
    val activeVideo: Boolean,
    val playing: Boolean,
    val wantsToPlay: Boolean,
    val isTrailer: Boolean,
    val pipAspectRatio: Float,
)


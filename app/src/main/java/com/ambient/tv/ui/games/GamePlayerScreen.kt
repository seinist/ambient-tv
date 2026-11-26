package com.ambient.tv.ui.games

import android.graphics.Bitmap
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.player.AmbientSurfaceBackdropLayer
import com.ambient.tv.ui.player.osdButtonVisible
import com.ambient.tv.ui.player.osdHiddenButtons
import com.ambient.tv.ui.player.PlayerOsdButtonPanelControls
import com.ambient.tv.ui.player.OsdPlayer
import com.ambient.tv.ui.player.OsdButton
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.filled.Settings
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Groups
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import com.ambient.tv.games.GameBiosState
import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import com.ambient.tv.games.GameStateStore
import com.ambient.tv.ui.common.activateOnTvKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.BuildConfig
import com.ambient.tv.R
import com.ambient.tv.games.GameAspect
import com.ambient.tv.games.GameCheat
import com.ambient.tv.games.GameCoreOption
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.GameSettingsState
import com.ambient.tv.games.displayProfile
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.findActivity
import com.ambient.tv.ui.player.rememberAvailableDisplayModes
import com.ambient.tv.ui.player.videoImagePreferences
import com.ambient.tv.games.GamePictureDefaults
import com.ambient.tv.games.GameIntegerScaling
import com.ambient.tv.ui.display.effectiveSdrReferenceWhiteNits
import com.ambient.tv.ui.player.SdrReferenceWhiteCustomSlider
import com.ambient.tv.ui.player.sdrReferenceWhiteChoiceKey
import com.ambient.tv.ui.player.sdrReferenceWhiteChoices
import com.ambient.tv.ui.player.sdrReferenceWhiteForChoice
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.GameHdrToneMap
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.gamepad.GameInputMode
import com.ambient.tv.ui.gamepad.GameInputRouting
import com.ambient.tv.companion.CompanionGamepadInteraction
import com.ambient.tv.companion.CompanionGamepadSession
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.input.requestUiKeyInputMode
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.RetroPadInput
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.ui.gamepad.retro.rememberPlayableDevices
import com.ambient.tv.ui.gamepad.retro.isPhysicalGamepadActivity
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.LocalPlayerChromeState
import com.ambient.tv.ui.player.LocalPlayerControlFocus
import com.ambient.tv.ui.player.PlayerControlsAutoHide
import com.ambient.tv.ui.player.PlayerTechBadgeRow
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerClock
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Fullscreen game player: a libretro core in LibretroDroid's view, with the other players' OSD
 * (header, badges, control bar, right panels). The game pauses while its menu is open.
 */
@Composable
fun GamePlayerScreen(onBack: () -> Unit, model: GamePlayerViewModel = hiltViewModel()) {
    val state by model.state.collectAsState()
    val activity = LocalContext.current.findActivity()
    // One physical-display lease for the route, including core/HDR/renderer restarts.
    val gameDisplay = remember(activity) { GameDisplaySession(activity) }
    DisposableEffect(gameDisplay) { onDispose { gameDisplay.close() } }
    LaunchedEffect(state is GameUiState.Failed) {
        if (state is GameUiState.Failed) gameDisplay.close()
    }
    val preparingInputOwner = remember { Any() }
    DisposableEffect(state is GameUiState.Ready) {
        if (state !is GameUiState.Ready) GameInputRouting.attach(
            preparingInputOwner, GameInputMode.MENU, onMenu = {},
        )
        onDispose { GameInputRouting.detach(preparingInputOwner) }
    }
    // A game and a film or song do not play together.
    LaunchedEffect(Unit) {
        if (BackgroundPlaybackSession.snapshot.value.active) BackgroundPlaybackSession.stop()
    }
    BackHandler(enabled = state !is GameUiState.Ready) { onBack() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val center = Modifier.align(Alignment.Center)
        when (val current = state) {
            is GameUiState.Preparing -> StatePanel(
                title = stringResource(R.string.game_preparing),
                body = current.progress?.let { stringResource(R.string.game_progress, (it * 100).roundToInt()) },
                actionLabel = stringResource(R.string.back), onAction = onBack,
                modifier = center, centered = true, actionLoading = false,
            )
            is GameUiState.NeedsCore -> {
                val core = current.core
                StatePanel(
                    title = stringResource(R.string.game_core_needed, core.name),
                    body = stringResource(
                        if (core.freeLicense) R.string.game_core_needed_body else R.string.game_core_needed_body_noncommercial,
                        current.game.system.name, core.license,
                    ),
                    actionLabel = stringResource(R.string.game_core_load),
                    onAction = model::downloadCore,
                    secondaryActionLabel = stringResource(R.string.back),
                    onSecondaryAction = onBack,
                    icon = Icons.Filled.SportsEsports,
                    modifier = center, centered = true,
                )
            }
            is GameUiState.ConfirmBuildbot -> StatePanel(
                title = stringResource(R.string.game_buildbot_confirm_title),
                body = stringResource(R.string.game_buildbot_confirm_body, current.core.name),
                actionLabel = stringResource(R.string.game_buildbot_confirm_action),
                onAction = model::confirmBuildbotCore,
                secondaryActionLabel = stringResource(R.string.back),
                onSecondaryAction = onBack,
                modifier = center, centered = true,
            )
            is GameUiState.DownloadingCore -> StatePanel(
                title = stringResource(R.string.game_core_loading, current.core.name),
                body = current.progress?.let { stringResource(R.string.game_progress, (it * 100).roundToInt()) },
                actionLabel = stringResource(R.string.back), onAction = onBack,
                modifier = center, centered = true, actionLoading = false,
            )
            is GameUiState.Failed -> StatePanel(
                title = stringResource(current.reason.titleRes()),
                body = when (current.reason) {
                    GameFailure.BIOS_MISSING -> stringResource(if (model.remote) R.string.romm_failed_bios_missing_body
                        else R.string.game_failed_bios_missing_body, current.detail.orEmpty())
                    GameFailure.PREVIOUS_SESSION -> stringResource(R.string.game_failed_previous_session_body)
                    else -> current.detail
                },
                actionLabel = stringResource(when {
                    current.reason == GameFailure.PREVIOUS_SESSION -> R.string.back
                    model.canUseCache -> R.string.game_cache_fallback
                    else -> R.string.game_retry
                }),
                onAction = when {
                    current.reason == GameFailure.PREVIOUS_SESSION -> onBack
                    model.canUseCache -> model::retryWithCache
                    else -> model::retry
                },
                secondaryActionLabel = if (current.reason == GameFailure.PREVIOUS_SESSION) null else stringResource(R.string.back),
                onSecondaryAction = onBack,
                modifier = center, centered = true,
            )
            // A new session (other core, OpenGL ES after Vulkan) is a new player.
            is GameUiState.Ready -> key(current.session) { GameContent(current.session, model, onBack, gameDisplay) }
        }
    }
}

private fun GameFailure.titleRes(): Int = when (this) {
    GameFailure.NOT_FOUND -> R.string.game_failed_not_found
    GameFailure.UNKNOWN_SYSTEM -> R.string.game_failed_unknown_system
    GameFailure.CORE_DOWNLOAD -> R.string.game_failed_core_download
    GameFailure.CORE_INCOMPATIBLE -> R.string.game_failed_core_incompatible
    GameFailure.NO_ABI -> R.string.game_failed_no_abi
    GameFailure.START, GameFailure.DIRECT_ACCESS -> R.string.game_failed_start
    GameFailure.BIOS_MISSING -> R.string.game_failed_bios_missing
    GameFailure.PREVIOUS_SESSION -> R.string.game_failed_previous_session
}

/** SDR white in the PQ signal: the chosen value (Auto 203), never above a plausible reported peak. */
internal fun gameSdrWhiteNits(requested: Int, display: GameHdrDisplayCapabilities): Int =
    effectiveSdrReferenceWhiteNits(requested, display.peakNits?.takeIf { it.isFinite() && it >= 300f }?.toInt() ?: 0)

/** LibretroDroid follows a lifecycle; it mirrors the activity (the menu pauses the emulation itself). */
private class GameLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun move(state: Lifecycle.State) {
        val current = registry.currentState
        if (current == Lifecycle.State.DESTROYED || current == state) return
        registry.currentState = state
    }
}

/** Rewind targets [GAME_REWIND_SECONDS] of history within this total memory budget. */
private const val REWIND_BUDGET_BYTES = 64L * 1024 * 1024
/** The category key of options without one, when others have one. */
private const val OTHER_CATEGORY = "\u0000other"

/** The game's current frame, scaled for save state previews; null when there is none yet. */
private suspend fun GLRetroView.preview(
    input: GameHdrToneMap,
    verifiedHdrSource: Boolean,
): Bitmap? = withContext(Dispatchers.Default) {
    val frame = runCatching { captureRawFrame(maxWidth = 480) }.getOrNull() ?: return@withContext null
    val scaled = if (frame.width <= 480) frame else {
        val height = (frame.height * 480 / frame.width.toFloat()).roundToInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(frame, 480, height, true).also { if (it !== frame) frame.recycle() }
    }
    if (!verifiedHdrSource || input == GameHdrToneMap.OFF) return@withContext scaled
    // CaptureRawFrame is before GLES shaders: do not write a raw PQ frame into
    // a conventional SDR save-state preview when the HDR source is confirmed.
    GameHdrPreviewToneMap.transformSmallBitmap(scaled, input).also {
        if (it !== scaled) scaled.recycle()
    }
}

@Composable
private fun GameContent(session: GameSession, model: GamePlayerViewModel, onBack: () -> Unit, gameDisplay: GameDisplaySession) {
    val context = LocalContext.current
    val uiInputView = LocalView.current
    val physicalActivity = context.findActivity()
    val physicalContext = physicalActivity ?: context
    val displayPreferences = remember(context) { videoImagePreferences(context) }
    val displayModes = rememberAvailableDisplayModes(physicalContext)
    val handheld = LocalHandheldUi.current
    val scope = rememberCoroutineScope()
    val activityLifecycle = LocalLifecycleOwner.current.lifecycle
    val nativeOwner = remember(session) { GameNativeSessionOwner() }
    val nativeClosed = nativeOwner.closed
    if (nativeClosed == null) {
        LaunchedEffect(session) { model.previousSessionUnavailable() }
        return
    }
    nativeOwner.onRetainedAttach = {
        // TV resolution-host replacement retains this composition but ends its GL thread.
        // Preparing waits for native teardown and checkpoint writes before restoring the game.
        if ((model.state.value as? GameUiState.Ready)?.session === session) model.restart()
    }
    val game = session.game
    val system = game.system
    val core = session.core
    LaunchedEffect(system.id, core.id, game.saveKey) {
        com.ambient.tv.ui.gamepad.retro.RetroInputSettings.lastPlayed.value =
            com.ambient.tv.ui.gamepad.retro.RetroInputProfileContext(
                system.id, core.id, game.saveKey,
            )
    }
    val settings = GameSettings.state
    val gameDisplayProfile = settings.displayProfile(core.id, game.saveKey)
    val shader = settings.shader(system, game.saveKey)
    val shaderIntensity = settings.shaderStrength(system, game.saveKey)
    val selectedPack = settings.packShader(system, game.saveKey)
    // The extra effect needs the GLES chain and its single prefix; packs and HDR conversion keep theirs.
    val effect = settings.effect(system, game.saveKey)
    val gamePictureScope = settings.gamePictureScope(game.saveKey)
    var resolvedPack by remember(session) {
        mutableStateOf(GameShaderPackResolution(session.initialPackSelection, session.initialPackConfig))
    }
    val selectedPackPending = selectedPack != null && !resolvedPack.matches(selectedPack)
    val selectedPackConfig = resolvedPack.configFor(selectedPack)
    LaunchedEffect(session, selectedPack) {
        if (resolvedPack.matches(selectedPack)) return@LaunchedEffect
        // Switching to a built-in filter needs no disk work or intermediate wait.
        if (selectedPack == null) {
            resolvedPack = GameShaderPackResolution(null, null)
            return@LaunchedEffect
        }
        val verified = withContext(Dispatchers.IO) { model.shaderPacks.config(selectedPack) }
        // Cancellation alone cannot interrupt blocking disk IO. Check the live selection too.
        if (GameSettings.state.packShader(system, game.saveKey) == selectedPack) {
            resolvedPack = GameShaderPackResolution(selectedPack, verified)
        }
    }
    var pendingPackChoice by remember(session) { mutableStateOf<GameShaderPackChoice?>(null) }
    LaunchedEffect(session, pendingPackChoice) {
        val request = pendingPackChoice ?: return@LaunchedEffect
        val verified = withContext(Dispatchers.IO) { model.shaderPacks.config(request.selection) }
        // Another picture choice, scope change or settings reload wins over stale IO.
        if (pendingPackChoice !== request) return@LaunchedEffect
        pendingPackChoice = null
        if (verified != null && request.stillApplies(GameSettings.state, system, game.saveKey)) {
            resolvedPack = GameShaderPackResolution(request.selection, verified)
            GameSettings.update {
                it.withPackShader(system, game.saveKey.takeIf { request.forGame }, request.selection)
            }
        }
    }
    // A remembered Vulkan input mode must not suppress GLES SDR-as-HDR output
    // after switching the same core back to OpenGL ES.
    val toneMap = if (session.vulkan) settings.hdrToneMap(core) else GameHdrToneMap.OFF
    val nativeTransfer = GameVulkanTransferMode.forInput(toneMap)
    val aspect = settings.aspect(system, game.saveKey)
    val integerScaling = settings.integerScaling(system, game.saveKey)
    val touchControls = settings.touchControls(system)
    val touchMode = settings.touchMode(system)
    val touchStyle = RetroInputSettings.config.touchStyle
    val touchVariant = RetroInputSettings.config.touchVariants[system.id].orEmpty()
    val touchPresentation = remember(system.id, touchVariant) { gameTouchPresentation(system.id, touchVariant) }
    var hidingGamepadId by remember(game.saveKey) { mutableStateOf<Int?>(null) }
    val hideTouchForGamepad by rememberUpdatedState(touchStyle.hideOnGamepadInput)
    // A trusted small Slang preset may opt into the experimental native WSI.
    // Existing GLES filters remain available for all other choices and for HDR.
    // Extract and verify only the selected, eligible built-in preset off the UI thread.
    // Key the result to the request so a previous shader can never be presented as the new one.
    val nativeSlangRequest = shader.takeIf {
        BuildConfig.LIBRASHADER_NATIVE && session.vulkan && effect == null &&
            nativeTransfer == GameVulkanTransferMode.SDR &&
            (!GameRetroShaders.isCustom(shader) || kotlin.math.abs(shaderIntensity - 1f) < 0.0001f) &&
            GameVulkanSlangPresets.supports(shader, selectedPack)
    }
    var nativeSlangPreparation by remember(context, nativeSlangRequest) {
        mutableStateOf<Pair<Boolean, String?>>(false to null)
    }
    LaunchedEffect(context, nativeSlangRequest) {
        val requested = nativeSlangRequest ?: return@LaunchedEffect
        val prepared = withContext(Dispatchers.IO) {
            GameVulkanSlangPresets.prepare(context.applicationContext, requested, null)
        }
        nativeSlangPreparation = true to prepared
    }
    val nativeSlangPath = nativeSlangPreparation.second
    val nativeSlangPending = nativeSlangRequest != null && !nativeSlangPreparation.first
    // SDR as HDR10 is fixed when the view is created (EGL config and PQ surface); a change restarts.
    val sdrToHdrSession = remember(session) {
        settings.sdrToHdr && toneMap == GameHdrToneMap.OFF && GameHdrDisplayProbe.capture(physicalContext).hdr10
    }
    val nativePictureSupported = selectedPack == null && effect == null && !sdrToHdrSession &&
        (GameVulkanPictureCompatibility.rawFullscreenEligible(shader) || nativeSlangPath != null)
    val saveStates by model.saveStates.collectAsState()
    val coverUrl by produceState<String?>(null, game.fileId) { value = model.coverUrl() }

    val chrome = remember { createGamePlayerChrome() }
    val osd = chrome.osd
    var menuOpen by remember { mutableStateOf(false) }
    val gamePanels = rememberGamePanelNavigation(chrome)
    val panel = gamePanels.panel
    // Do not enumerate and verify every installed pack during normal game startup.
    // Keep the previous catalog while the picture panel is closed.
    var installedShaders by remember(model) {
        mutableStateOf<List<com.ambient.tv.games.InstalledGameShaderPreset>>(emptyList())
    }
    LaunchedEffect(model, panel == GamePanel.PICTURE) {
        if (panel == GamePanel.PICTURE) {
            installedShaders = withContext(Dispatchers.IO) { model.shaderPacks.installed() }
        }
    }
    // A display or refresh-mode change can remove HDR while a Vulkan game is
    // running. Never infer active HDR from these platform capabilities.
    var displayHdr by remember(context) {
        mutableStateOf(GameHdrDisplayProbe.capture(physicalContext))
    }
    var actualGameMode by remember(gameDisplay) { mutableStateOf(gameDisplay.actualMode()) }
    val requiredGameHdr = remember(session, sdrToHdrSession, nativeTransfer) {
        when {
            sdrToHdrSession -> android.view.Display.HdrCapabilities.HDR_TYPE_HDR10
            nativeTransfer == GameVulkanTransferMode.PQ_BT2020 && displayHdr.hdr10 ->
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10
            nativeTransfer == GameVulkanTransferMode.HLG_BT2020 && displayHdr.hlg ->
                android.view.Display.HdrCapabilities.HDR_TYPE_HLG
            else -> null
        }
    }
    DisposableEffect(physicalContext) {
        val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                displayHdr = GameHdrDisplayProbe.capture(physicalContext)
                actualGameMode = gameDisplay.actualMode()
            }
            override fun onDisplayRemoved(displayId: Int) {
                displayHdr = GameHdrDisplayProbe.capture(physicalContext)
                actualGameMode = gameDisplay.actualMode()
            }
            override fun onDisplayChanged(displayId: Int) {
                displayHdr = GameHdrDisplayProbe.capture(physicalContext)
                actualGameMode = gameDisplay.actualMode()
            }
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    // Also re-query when opening Info; some vendors do not deliver callbacks
    // for every HDMI EOTF switch.
    LaunchedEffect(panel) {
        if (panel == GamePanel.INFO || panel == GamePanel.DISPLAY) {
            displayHdr = GameHdrDisplayProbe.capture(physicalContext)
            actualGameMode = gameDisplay.actualMode()
        }
    }
    val optionCategory = gamePanels.optionCategory
    /** Core option changes go to this game only (true) or to every game of the core. */
    var gameScope by remember { mutableStateOf(settings.gameCoreOptions(core, game.saveKey).isNotEmpty()) }
    val statesOpen = gamePanels.statesOpen
    var activityResumed by remember { mutableStateOf(activityLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var exiting by remember { mutableStateOf(false) }
    var destroyed by remember { mutableStateOf(false) }
    /** The core has loaded the game (its surface exists); before that nothing may step it. */
    var coreReady by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var displayFormat by remember { mutableStateOf<GameDisplayFormat?>(null) }
    var displayPrepared by remember { mutableStateOf(false) }
    var displaySwitching by remember { mutableStateOf(false) }
    var displayFailed by remember { mutableStateOf(false) }
    // Once armed, continue refreshing the native clock even when the user disables matching.
    var displayTimingArmed by remember { mutableStateOf(gameDisplayProfile.active) }
    val backgroundVideo by BackgroundPlaybackSession.snapshot.collectAsState()
    val videoOwnsDisplay = backgroundVideo.active || backgroundVideo.replacingSource
    /** The native Vulkan image format, not merely the user's requested graphics mode. */
    var vulkanFrameStatus by remember { mutableStateOf(GameVulkanFrameStatus()) }
    /** Native VkImage pixel layout, NOT color transfer or HDR source provenance. */
    var vulkanCoreSourceFormat by remember { mutableStateOf(GameVulkanCoreSourceFormat()) }
    /** Actual libretro SET_PIXEL_FORMAT acceptance, independent of user settings. */
    var coreHdrHandshake by remember { mutableStateOf(GameCoreHdrPixelHandshake()) }
    val nativeSourceContract = GameVulkanHdrSourceEvidence.observe(
        core.id, session.vulkan,
        settings.effectiveCoreOptions(core, game.saveKey) + if (session.vulkan) core.vulkanOptions else core.glOptions,
        vulkanCoreSourceFormat,
        coreHdrHandshake,
    )
    /** Native WSI format pairs on a separate real Android Surface, NOT active HDR. */
    var vulkanSurfaceFormats by remember { mutableStateOf(GameVulkanSurfaceFormats()) }
    var nativeSdrDiagnostic by remember { mutableStateOf(false) }
    var nativeSdrStatus by remember { mutableStateOf(GameVulkanSdrStatus()) }
    /** Explicitly armed per session; never restored from settings or a backup. */
    var nativeSdrFullscreenRequested by remember { mutableStateOf(false) }
    /** Core-reported serialized size must be nonzero and safe for this device's heap. */
    var stateAvailable by remember { mutableStateOf(false) }
    var stateBusy by remember { mutableStateOf(false) }
    var serializationPolicy by remember { mutableStateOf(GameSerializationPolicy()) }
    var message by remember { mutableStateOf<String?>(null) }
    var optionsVersion by remember { mutableIntStateOf(0) }
    var coreOptionsSnapshot by remember(session) { mutableStateOf<List<GameCoreOption>>(emptyList()) }
    var fastForward by remember { mutableStateOf(false) }
    var rewinding by remember { mutableStateOf(false) }
    var companionRewinding by remember { mutableStateOf(false) }
    var menuRewinding by remember { mutableStateOf(false) }
    var rewindHistory by remember { mutableStateOf(GameRewindHistory()) }
    var rewindFps by remember { mutableStateOf(60.0) }
    /** Paused timeline seek: requested and natively shown distance before live (0 = live). */
    var rewindSeekTargetMs by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var rewindSeekShownFrames by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var rewindSeekVersion by remember { mutableIntStateOf(0) }
    val rewindSeekRequests = remember { kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.CONFLATED) }
    var stateDialogOpen by remember { mutableStateOf(false) }
    var resetConfirmOpen by remember { mutableStateOf(false) }
    var removeCoreConfirmOpen by remember { mutableStateOf(false) }
    var discCount by remember { mutableIntStateOf(0) }
    var currentDisc by remember { mutableIntStateOf(0) }
    /** The picture when the menu opened: preview for every state saved from this menu. */
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    val achievements = remember { GameAchievements(model.retroAchievements, scope) }
    var achievementsInitialized by remember { mutableStateOf(false) }
    val netplay = remember {
        // Native captures masks/axes/pressures atomically and retains taps until this tick.
        // One reusable array avoids a JNI array allocation on every lockstep frame.
        val captured = IntArray(7)
        GameNetplay(context.applicationContext, scope) {
            LibretroDroid.getInputPortSnapshot(0, captured)
            NetplayInput(captured[0], captured[1], captured[2], captured[3], captured[4], captured[5], captured[6])
        }
    }
    val displayHold = displaySwitching ||
        (gameDisplayProfile.active && loaded && !displayPrepared && !netplay.busy && !videoOwnsDisplay)
    val availableGameModes = actualGameMode?.let {
        gameDisplayModes(displayModes, it, handheld, displayPreferences.displayModeWhitelist, requiredGameHdr)
    }.orEmpty()
    var cheats by remember { mutableStateOf(model.cheats(game)) }
    var cheatsLoading by remember { mutableStateOf(false) }
    var cheatsDeleting by remember { mutableStateOf(false) }
    /** The cheat being typed in (description, code), or null. */
    var cheatDraft by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** Hardcore was asked for with an account: the game starts fresh, without the "continue" state. */
    val hardcoreRequested = remember {
        !game.direct && core.achievements && settings.achievements && settings.hardcore && model.retroAchievements.account != null
    }
    val playRequester = remember { FocusRequester() }
    val statesRequester = remember { FocusRequester() }
    val settingsRequester = remember { FocusRequester() }
    val legacyCoreStamp by produceState<String?>(null, session) {
        value = withContext(Dispatchers.IO) { GameStateStore.legacyCoreStamp(session.coreFile) }
    }
    val hardcoreRules = achievements.hardcore || (hardcoreRequested && !achievementsInitialized)
    val rewindPermitted = core.rewind && stateAvailable && serializationPolicy.frameExact &&
        settings.rewindForPlayback && !hardcoreRules && !netplay.busy
    val availability = GameOsdAvailability(
        loaded = loaded && coreReady && activityResumed && !exiting && !destroyed,
        busy = stateBusy || displayHold, stateAvailable = stateAvailable, frameExact = serializationPolicy.frameExact,
        hardcore = hardcoreRules, netplay = netplay.busy,
        supportsCheats = core.cheats, supportsNetplay = core.netplay, directContent = game.direct,
        // The core panel also chooses and removes the core, so it is always there.
        supportsCoreOptions = true,
        achievements = achievements.state != GameAchievementState.Off, discCount = discCount,
        states = saveStates, coreId = core.id, coreStamp = session.coreStamp, legacyCoreStamp = legacyCoreStamp,
        rewindAvailable = rewindPermitted && rewindHistory.available,
    )
    val latestAvailability by rememberUpdatedState(availability)

    val owner = remember { GameLifecycleOwner() }
    val retroView = remember {
        GLRetroView(context, GLRetroViewData(context).apply {
            coreFilePath = session.coreFile.absolutePath
            if (game.direct) {
                // The fork owns FDs after its VFS initializes, and seeks the original SAF/NAS ROM.
                gameVirtualFiles = requireNotNull(game.directContent).virtualFiles
            } else {
                gameFilePath = game.file.absolutePath
            }
            systemDirectory = session.systemDirectory.absolutePath
            savesDirectory = session.savesDirectory.absolutePath
            // The graphics interface decides the GPU cores' renderer options.
            variables = (settings.effectiveCoreOptions(core, game.saveKey) + if (session.vulkan) core.vulkanOptions else core.glOptions)
                .map { (key, value) -> Variable(key, value) }.toTypedArray()
            saveRAMState = session.sram
            sdrToHdrReferenceWhiteNits = if (sdrToHdrSession) gameSdrWhiteNits(settings.sdrReferenceWhite, displayHdr) else 0
            this.shader = GameHdrToneMapShader.forConfig(
                selectedPackConfig ?: shader.config(shaderIntensity),
                toneMap, session.vulkan, pixel = selectedPack == null && shader == GameShader.PIXEL,
                effect = effect.takeIf { selectedPackConfig == null },
            )
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
            // A requested cadence must never change the core's native game speed.
            forceSoftwareTiming = gameDisplayProfile.active
            preferVulkan = session.vulkan
            // Before retro_init an HDR-aware Vulkan core may ask for configured PQ output;
            // distinguish capability from the later verified real-surface HDR signal.
            configuredHdrOutputMode = GameVulkanTransferGate.configuredCoreOutputMode(
                toneMap, displayHdr, session.vulkan,
            )
            preferHighPrecisionVulkanFrames = GameHdrToneMapShader.prefersFp16Bridge(toneMap, session.vulkan)
        }).apply {
            // Input reaches the core through RetroPadInput only.
            isFocusable = false
            isFocusableInTouchMode = false
            // Starts paused: the first composition decides whether it runs.
            suppressAutoResume = true
            aspectRatioOverride = aspect.ratio ?: -1f
            this.integerScalingAuto = integerScaling == GameIntegerScaling.AUTO
            this.integerScaling = integerScaling.enabled
        }.also { view ->
            // Install before native creation, so even an abandoned first composition has
            // a cleanup path. The complete player cleanup replaces this once constructed.
            nativeOwner.cleanup = {
                view.pauseEmulation()
                runCatching {
                    view.destroyNative { error ->
                        if (error == null) nativeClosed.complete(Unit)
                        else nativeClosed.completeExceptionally(error)
                    }
                }.onFailure(nativeClosed::completeExceptionally)
                owner.move(Lifecycle.State.DESTROYED)
                game.close()
            }
            view.beforeDetach = nativeOwner::release
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attached: View) {
                    // A physical detach can also be temporary; wait until Compose has finished
                    // the current apply pass before deciding whether this owner is still retained.
                    attached.post {
                        if (attached.isAttachedToWindow) nativeOwner.viewAttached()
                    }
                }
                override fun onViewDetachedFromWindow(detached: View) = Unit
            })
            owner.registry.addObserver(view)
            owner.move(Lifecycle.State.CREATED)
        }
    }

    // Settings are the sole source of truth for live picture changes. A pending
    // signed pack keeps the last verified picture until its selection is resolved.
    LaunchedEffect(retroView, shader, shaderIntensity, selectedPack, selectedPackPending, selectedPackConfig, toneMap, effect) {
        if (!selectedPackPending && !destroyed && !exiting) {
            retroView.shader = GameHdrToneMapShader.forConfig(
                selectedPackConfig ?: shader.config(shaderIntensity),
                toneMap, session.vulkan, pixel = selectedPack == null && shader == GameShader.PIXEL,
                effect = effect.takeIf { selectedPackConfig == null },
            )
        }
    }

    // Maintain the old GLES renderer unchanged. The extra 1×1 real Android
    // surface exists only for Vulkan WSI probing and never displays game frames.
    val vulkanSurfaceHost = remember(retroView, session.vulkan, nativeTransfer) {
        if (session.vulkan) GameVulkanSurfaceHost(
            context, retroView,
            outputTransfer = nativeTransfer,
            onFormats = { vulkanSurfaceFormats = it },
            onNativeStatus = { nativeSdrStatus = it },
            onFullscreenLost = { nativeSdrFullscreenRequested = false },
            onDiagnosticLost = { nativeSdrDiagnostic = false },
        ) else null
    }
    DisposableEffect(vulkanSurfaceHost) {
        onDispose { vulkanSurfaceHost?.dispose() }
    }
    LaunchedEffect(displayHdr, vulkanSurfaceHost) {
        vulkanSurfaceHost?.updateDisplayHdr(displayHdr)
    }
    LaunchedEffect(integerScaling, vulkanSurfaceHost) {
        vulkanSurfaceHost?.updateIntegerScaling(integerScaling)
    }
    LaunchedEffect(nativeSlangPath, nativeSlangPending, vulkanSurfaceHost) {
        // Disarm fullscreen before replacing the active shader. Do not publish
        // a transient empty (raw Pixel) preset while an IO request is pending.
        if (nativeSlangPending) vulkanSurfaceHost?.setFullscreenRequested(false)
        else vulkanSurfaceHost?.updateShaderPreset(nativeSlangPath)
    }
    LaunchedEffect(coreReady, vulkanSurfaceHost) {
        if (coreReady) vulkanSurfaceHost?.coreReady()
    }
    LaunchedEffect(nativeSourceContract, vulkanSurfaceHost) {
        vulkanSurfaceHost?.updateSourceContract(nativeSourceContract.transfer)
    }
    LaunchedEffect(nativeSdrDiagnostic, coreReady, vulkanSurfaceHost) {
        if (coreReady) vulkanSurfaceHost?.setNativeSdrDiagnostic(nativeSdrDiagnostic)
    }
    LaunchedEffect(nativeSdrStatus, vulkanSurfaceHost) {
        vulkanSurfaceHost?.observeNativeStatus(nativeSdrStatus)
    }

    val cheatUpdates = remember(retroView) {
        GameCheatUpdates(
            enqueue = { task -> retroView.queueEvent { runCatching(task) } },
            reset = { LibretroDroid.resetCheat() },
            enable = { index, code -> LibretroDroid.setCheat(index, true, code) },
        )
    }
    DisposableEffect(cheatUpdates) { onDispose { cheatUpdates.close() } }
    /** One queued batch: hardcore/netplay policy changes can supersede an older pending edit. */
    fun applyCheats(list: List<GameCheat>) {
        if (!loaded || destroyed) return
        val allowed = core.cheats && !achievements.hardcore &&
            !(hardcoreRequested && !achievementsInitialized) && !netplay.busy
        runCatching {
            cheatUpdates.apply(if (allowed) list.filter { it.enabled }.map { it.code } else emptyList())
        }
    }

    fun updateCheats(list: List<GameCheat>) {
        cheats = list
        model.saveCheats(game, list)
        applyCheats(list)
    }

    /**
     * Queue a checkpoint between frames, before the queued native pause/destruction. Waiting
     * for serializeSRAM/serializeState here used to hold Android's main thread for up to two
     * GL timeouts, followed by disk/JPEG IO, during Home and TV standby transitions.
     */
    fun persist() {
        if (!loaded || destroyed) return
        val captureState = stateAvailable
        val ticket = model.reserveCheckpoint(session, snapshot)
        runCatching {
            retroView.queueEvent {
                // This task precedes destroyNative() in the same FIFO. Call native capture here:
                // the view's synchronous wrappers can reject work once destruction is requested.
                if (!ticket.pending) return@queueEvent
                val sram = runCatching { LibretroDroid.serializeSRAM() }
                val state = if (captureState) runCatching { LibretroDroid.serializeState() } else null
                if (sram.isFailure && (state == null || state.isFailure)) {
                    ticket.fail(checkNotNull(sram.exceptionOrNull()))
                } else {
                    ticket.complete(sram.getOrNull() to state?.getOrNull())
                }
            }
        }.onFailure(ticket::fail)
    }

    /** Ends the core while its thread still runs; nothing reaches it afterwards. */
    fun destroy() {
        if (destroyed) return
        cheatUpdates.close()
        netplay.release()
        achievements.stop(retroView)
        retroView.pauseEmulation()
        persist()
        destroyed = true
        runCatching {
            retroView.destroyNative { error ->
                if (error == null) nativeClosed.complete(Unit)
                else nativeClosed.completeExceptionally(error)
            }
        }.onFailure(nativeClosed::completeExceptionally)
        game.close()
    }
    nativeOwner.cleanup = { destroy() }

    fun stateTitle(entry: GameStateEntry): String = entry.label?.takeIf { it.isNotBlank() } ?: when (entry.kind) {
        GameStateKind.AUTO -> context.getString(R.string.game_state_auto)
        GameStateKind.UNDO -> context.getString(R.string.game_state_undo)
        GameStateKind.MANUAL -> context.getString(R.string.game_state_number, entry.number)
    }

    val exit: () -> Unit = {
        if (!exiting) {
            exiting = true
            GameInputRouting.update(session, GameInputMode.BLOCKED)
            gamePanels.dismissForResume()
            destroy()
            onBack()
        }
    }
    val resume: () -> Unit = resume@ {
        if (stateBusy || netplay.synchronizing || destroyed || exiting || !activityResumed) return@resume
        menuRewinding = false
        gamePanels.dismissForResume()
        menuOpen = false
        osd.hideImmediately()
    }
    val openMenu: () -> Unit = {
        if (!menuOpen && !destroyed && !exiting) {
            // Pause at once; the preview is the frame the player saw last.
            retroView.pauseEmulation()
            rewinding = false
            companionRewinding = false
            menuRewinding = false
            GameInputRouting.update(session, GameInputMode.MENU)
            menuOpen = true
            message = null
            scope.launch {
                snapshot = if (loaded) retroView.preview(
                    toneMap,
                    nativeTransfer != GameVulkanTransferMode.SDR && nativeSourceContract.supports(nativeTransfer),
                ) else null
            }
        }
    }
    // Raw game input and Companion menu callbacks bypass Android's normal key dispatch.
    // Leave touch mode before the OSD requests its initial focus; touch controls still use
    // openMenu directly and retain their normal touch behavior.
    val openMenuFromKey: () -> Unit = {
        if (!destroyed && !exiting) {
            uiInputView.requestUiKeyInputMode()
            openMenu()
        }
    }
    LaunchedEffect(netplay.synchronizing, activityResumed) {
        if (netplay.synchronizing && activityResumed && !destroyed && !exiting) {
            // A host can wait while playing. State exchange must still expose its Cancel action.
            openMenu()
            if (gamePanels.panel == null) gamePanels.openRoot(GamePanel.NETPLAY)
        }
    }
    /** Loads [entry]; the moment before is kept as "Before loading" so the step can be undone. */
    val loadState: (GameStateEntry) -> Unit = loadState@ { entry ->
        if (stateBusy || destroyed || exiting) return@loadState
        if (!latestAvailability.canLoad(entry)) {
            message = context.getString(
                when {
                    achievements.hardcore -> R.string.game_message_hardcore_blocked
                    netplay.busy -> R.string.game_message_netplay_blocked
                    else -> R.string.game_message_load_failed
                },
            )
        } else {
            stateBusy = true
            GameInputRouting.update(session, GameInputMode.BLOCKED)
            message = context.getString(R.string.game_state_working)
            val preview = snapshot
            scope.launch {
                try {
                    val compatible = latestAvailability.compatible(entry)
                    if (!compatible) {
                        message = context.getString(R.string.game_message_load_other_core)
                        return@launch
                    }
                    // Read first: loading UNDO must not overwrite the bytes being restored.
                    val bytes = model.readState(game, entry)
                    if (bytes == null || destroyed || exiting) {
                        message = context.getString(R.string.game_message_load_failed)
                        return@launch
                    }
                    val previous = withContext(Dispatchers.Default) { runCatching { retroView.serializeState() }.getOrNull() }
                    if (previous != null) model.saveState(session, GameStateKind.UNDO, previous, preview)
                    if (destroyed || exiting || achievements.hardcore || netplay.busy) return@launch
                    val restored = withContext(Dispatchers.Default) {
                        runCatching {
                            retroView.unserializePersistedState(bytes)
                        }.getOrDefault(false)
                    }
                    if (restored) { message = null; stateBusy = false; resume() }
                    else message = context.getString(R.string.game_message_load_failed)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = context.getString(R.string.game_message_load_failed)
                } finally { stateBusy = false }
            }
        }
    }

    val saveManualState: () -> Unit = {
        if (!stateBusy && !destroyed && !exiting && activityResumed && latestAvailability.save) {
            osd.interact()
            stateBusy = true
            GameInputRouting.update(session, GameInputMode.BLOCKED)
            message = context.getString(R.string.game_state_working)
            val preview = snapshot
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.Default) { retroView.serializeState() }
                    val saved = model.saveState(session, GameStateKind.MANUAL, bytes, preview)
                    message = context.getString(R.string.game_message_saved, stateTitle(saved))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = context.getString(R.string.game_message_save_failed)
                } finally { stateBusy = false }
            }
        }
    }

    /** Starts the game over; the moment before is kept as the undo state, like a load. */
    val resetGame: () -> Unit = resetGame@ {
        if (stateBusy || destroyed || exiting || !latestAvailability.reset) return@resetGame
        stateBusy = true
        GameInputRouting.update(session, GameInputMode.BLOCKED)
        val preview = snapshot
        scope.launch {
            var reset = false
            try {
                val previous = withContext(Dispatchers.Default) { runCatching { retroView.serializeState() }.getOrNull() }
                if (previous != null && !destroyed && !exiting) model.saveState(session, GameStateKind.UNDO, previous, preview)
                if (destroyed || exiting) return@launch
                withContext(Dispatchers.Default) {
                    retroView.reset()
                    retroView.clearRewindBuffer()
                }
                rewindHistory = GameRewindHistory()
                model.clearAutoState(game)
                reset = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.game_reset_failed)
            } finally { stateBusy = false }
            if (reset) resume()
        }
    }

    // Initial metadata arrives after the first native frame. Subsequent switches use
    // the same paused lifecycle as the OSD and do not resume a deliberately paused game.
    LaunchedEffect(retroView, displayFormat, gameDisplayProfile, activityResumed,
        netplay.busy, videoOwnsDisplay, destroyed, exiting) {
        if (videoOwnsDisplay) {
            gameDisplay.close()
            displayPrepared = true
            displayFailed = false
            return@LaunchedEffect
        }
        val format = displayFormat ?: return@LaunchedEffect
        if (!activityResumed || netplay.busy || destroyed || exiting) return@LaunchedEffect
        if (!gameDisplayProfile.active && !gameDisplay.hasLease) {
            displayPrepared = true
            displayFailed = false
            return@LaunchedEffect
        }
        displaySwitching = true
        retroView.suppressAutoResume = true
        retroView.pauseEmulation()
        try {
            if (gameDisplayProfile.active) {
                displayTimingArmed = true
                val baseline = gameDisplay.baseline()
                val selected = baseline?.let {
                    selectGameDisplayMode(
                        displayModes, it, format, gameDisplayProfile, handheld,
                        displayPreferences.displayModeWhitelist, requiredGameHdr,
                    )
                }
                displayFailed = if (selected != null) {
                    !gameDisplay.apply(selected, displayPreferences.displaySwitchPauseMs)
                } else {
                    gameDisplay.reset()
                    true
                }
            } else {
                gameDisplay.reset()
                displayFailed = false
            }
        } finally {
            // Queue clock/audio changes before the running effect can release the frame gate.
            if (currentCoroutineContext().isActive) {
                val actual = gameDisplay.actualMode()
                actualGameMode = actual
                if (!destroyed && !exiting && actual != null && displayTimingArmed) {
                    val strict = gameDisplayProfile.active
                    retroView.queueEvent { LibretroDroid.setGameDisplayTiming(actual.refreshRate, strict) }
                }
                displayPrepared = true
            }
            displaySwitching = false
        }
    }
    // Also retime after external Android/HDMI changes. No format observation in the frame loop.
    LaunchedEffect(retroView, loaded, actualGameMode?.key, displayTimingArmed, gameDisplayProfile.active,
        activityResumed, netplay.busy, videoOwnsDisplay, displaySwitching, destroyed, exiting) {
        if (!loaded || !activityResumed || netplay.busy || videoOwnsDisplay || displaySwitching || destroyed || exiting ||
            !displayTimingArmed) return@LaunchedEffect
        val actual = actualGameMode ?: return@LaunchedEffect
        val strict = gameDisplayProfile.active
        retroView.queueEvent { LibretroDroid.setGameDisplayTiming(actual.refreshRate, strict) }
    }
    val running = coreReady && activityResumed && !menuOpen && panel == null &&
        !exiting && !destroyed && !stateBusy && !netplay.synchronizing && !displayHold
    val rewindPreview = menuOpen && panel == null && !statesOpen && menuRewinding &&
        availability.rewind && activityResumed && !displayHold
    val activeRewinding = rewindPermitted && (rewindPreview ||
        (running && rewindHistory.available && (rewinding || companionRewinding)))
    val emulating = running || rewindPreview
    LaunchedEffect(emulating, activeRewinding, fastForward, netplay.synchronizing) {
        // Set rewind before releasing the frame gate; an OSD hold must never advance one frame.
        if (!emulating) retroView.pauseEmulation()
        retroView.isRewinding = activeRewinding
        retroView.frameSpeed = if (fastForward && !activeRewinding) 2 else 1
        retroView.suppressAutoResume = !emulating
        if (emulating) retroView.resumeEmulation()
    }
    LaunchedEffect(running, menuOpen, panel, statesOpen, availability.rewind) {
        if (!running || !availability.rewind) {
            rewinding = false
            companionRewinding = false
        }
        if (!menuOpen || panel != null || statesOpen || !availability.rewind) menuRewinding = false
    }
    // Reconfigure only on real capability/settings changes, never on OSD/panel navigation.
    // Leaving netplay starts a fresh history rather than exposing pre-netplay checkpoints.
    LaunchedEffect(retroView, loaded, destroyed, rewindPermitted) {
        if (!loaded || destroyed) return@LaunchedEffect
        retroView.rewindEnabled = false
        rewindHistory = GameRewindHistory()
        if (rewindPermitted) {
            val fps = withContext(Dispatchers.Default) {
                runCatching {
                    val rate = LibretroDroid.getContentFps().takeIf { it.isFinite() && it in 1.0..240.0 } ?: 60.0
                    retroView.initRewindBuffer((GAME_REWIND_SECONDS * rate).roundToInt(), REWIND_BUDGET_BYTES)
                    rate
                }.getOrNull()
            }
            if (fps != null && !destroyed && !exiting) {
                rewindFps = fps
                retroView.rewindEnabled = true
            }
        } else withContext(Dispatchers.Default) { runCatching { retroView.destroyRewindBuffer() } }
    }
    // One cheap atomic read four times per second, including paused OSD previews. No GL wait.
    LaunchedEffect(retroView, loaded, activityResumed, destroyed, rewindPermitted) {
        if (!loaded || !activityResumed || destroyed || !rewindPermitted) {
            rewindHistory = GameRewindHistory()
            return@LaunchedEffect
        }
        while (true) {
            rewindHistory = GameRewindHistory(
                runCatching { retroView.getRewindBufferHistoryFrames() }.getOrDefault(0L), rewindFps,
            )
            kotlinx.coroutines.delay(250)
        }
    }
    // Paused timeline seek like a timeshift: native shows the chosen stored state without
    // consuming history; continuing play keeps that point and drops the newer history.
    // Requests are conflated, so fast scrubbing renders only the latest target.
    val rewindSeekAllowed by rememberUpdatedState(
        menuOpen && panel == null && !statesOpen && !menuRewinding && !stateBusy && !running &&
            availability.rewind && !destroyed && !exiting,
    )
    LaunchedEffect(retroView, loaded, destroyed) {
        if (!loaded || destroyed) return@LaunchedEffect
        for (target in rewindSeekRequests) {
            if (!rewindSeekAllowed) continue
            val shown = withContext(Dispatchers.Default) {
                runCatching { retroView.previewRewind(target) }.getOrDefault(-1L)
            }
            if (shown >= 0L) {
                rewindSeekShownFrames = shown
                rewindSeekVersion++
            }
        }
    }
    // Leaving the menu or holding Rewind continues from the shown point (native commits it).
    LaunchedEffect(menuOpen, menuRewinding) {
        if (!menuOpen || menuRewinding) {
            rewindSeekTargetMs = 0L
            rewindSeekShownFrames = 0L
        }
    }
    // Saves made from the menu preview the picture that is actually shown.
    LaunchedEffect(rewindSeekVersion) {
        if (rewindSeekVersion == 0 || !menuOpen || !loaded || destroyed || exiting) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        snapshot = retroView.preview(toneMap,
            nativeTransfer != GameVulkanTransferMode.SDR && nativeSourceContract.supports(nativeTransfer))
    }
    val setMenuRewind: (Boolean) -> Unit = { held ->
        if (held && latestAvailability.rewind && menuOpen && panel == null && !statesOpen) {
            // Capture at the new position when the hold finishes, not on every rewind frame.
            menuRewinding = true
        } else if (menuRewinding) {
            menuRewinding = false
            retroView.pauseEmulation()
            if (menuOpen && loaded && !destroyed && !exiting) scope.launch {
                snapshot = retroView.preview(toneMap,
                    nativeTransfer != GameVulkanTransferMode.SDR && nativeSourceContract.supports(nativeTransfer))
            }
        }
    }
    LaunchedEffect(nativePictureSupported, nativeSlangPending, nativeSdrFullscreenRequested, vulkanSurfaceHost) {
        if (!nativePictureSupported && nativeSdrFullscreenRequested) {
            // A pending asynchronous preparation is not a failed/unsupported preset.
            if (!nativeSlangPending) nativeSdrFullscreenRequested = false
            vulkanSurfaceHost?.setFullscreenRequested(false)
        }
    }
    LaunchedEffect(nativeSdrFullscreenRequested, running, nativePictureSupported, nativeSlangPending, vulkanSurfaceHost) {
        if (!nativeSdrFullscreenRequested || !running || !nativePictureSupported || nativeSlangPending) {
            vulkanSurfaceHost?.setFullscreenRequested(false)
        } else if (running) {
            // Arming from the paused Info panel does not cover the game with a
            // blank Surface; promotion waits until normal playback resumes.
            vulkanSurfaceHost?.setFullscreenRequested(true)
        }
    }

    DisposableEffect(activityLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (!destroyed) {
                        // The patched lifecycle queues native resume and re-enables drawing.
                        // Calling GLSurfaceView.onResume() here waits for GL on the UI thread.
                        owner.move(Lifecycle.State.RESUMED)
                    }
                    activityResumed = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    activityResumed = false
                    GameInputRouting.update(session, GameInputMode.BLOCKED)
                    // Gate frames immediately. Capture, native pause and resume then run FIFO
                    // on GL, with disk writes on IO; this callback performs no latch or IO wait.
                    retroView.suppressAutoResume = true
                    retroView.pauseEmulation()
                    if (!menuOpen && !exiting) menuOpen = true
                    persist()
                    if (!destroyed) {
                        owner.move(Lifecycle.State.STARTED)
                    }
                    // Stop rendering before a fullscreen Vulkan resize/detach. Never retain
                    // that test surface through TV standby, rotation or app backgrounding.
                    nativeSdrFullscreenRequested = false
                    vulkanSurfaceHost?.setFullscreenRequested(false)
                }
                else -> Unit
            }
        }
        activityLifecycle.addObserver(observer)
        onDispose { activityLifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner) {
        onDispose {
            // beforeDetach uses the same idempotent cleanup if AndroidView is removed first.
            nativeOwner.release()
            owner.move(Lifecycle.State.DESTROYED)
        }
    }

    // First frame: the game is loaded; continue where the player left off, then keep rewind ready.
    LaunchedEffect(retroView) {
        var lastStatusPollMs = -2_000L
        var lastInitializationPollMs = -1_000L
        var pendingAuto = session.autoState.takeUnless { hardcoreRequested }
        suspend fun refreshStateSupport() {
            serializationPolicy = GameSerializationPolicy(runCatching { LibretroDroid.getSerializationQuirks() }.getOrDefault(0L))
            stateAvailable = core.saveStates && serializationPolicy.persistentStates && withContext(Dispatchers.Default) {
                runCatching {
                    retroView.getSerializeSize() in 1L..GameStateStore.MAX_STATE_BYTES &&
                        (!serializationPolicy.mustInitialize || retroView.serializeState().isNotEmpty())
                }.getOrDefault(false)
            }
            if (!serializationPolicy.persistentStates) pendingAuto = null
            if (!stateAvailable) return
            pendingAuto?.let { bytes ->
                pendingAuto = null
                val restored = withContext(Dispatchers.Default) {
                    runCatching { retroView.unserializePersistedState(bytes) }.getOrDefault(false)
                }
                if (restored) message = context.getString(R.string.game_message_continued)
            }
        }
        retroView.getGLRetroEvents().collect { event ->
            val now = android.os.SystemClock.elapsedRealtime()
            val statusIntervalMs = if (nativeSdrFullscreenRequested) 500L else 2_000L
            if (event == GLRetroView.GLRetroEvents.FrameRendered &&
                session.vulkan && now - lastStatusPollMs >= statusIntervalMs) {
                lastStatusPollMs = now
                // Frame notifications are conflated; keep status reads on a wall-clock cadence.
                // A driver may switch to
                // RGBA8 on resize, so a startup-only snapshot would become inaccurate.
                val status = GameVulkanFrameStatus(runCatching {
                    LibretroDroid.getVulkanFrameStatus()
                }.getOrDefault(0))
                if (vulkanFrameStatus != status) vulkanFrameStatus = status
                val source = GameVulkanCoreSourceFormat(runCatching {
                    LibretroDroid.getVulkanCoreSourceFormat()
                }.getOrDefault(0))
                if (vulkanCoreSourceFormat != source) vulkanCoreSourceFormat = source
                val handshake = GameCoreHdrPixelHandshake(runCatching {
                    LibretroDroid.getCoreHdrPixelFormatStatus()
                }.getOrDefault(0))
                if (coreHdrHandshake != handshake) coreHdrHandshake = handshake
                if (nativeSdrDiagnostic) {
                    val direct = GameVulkanSdrStatus(runCatching {
                        LibretroDroid.getNativeSdrVulkanStatus()
                    }.getOrDefault(0))
                    if (nativeSdrStatus != direct) nativeSdrStatus = direct
                }
            }
            // Readiness and sampled frame notifications are independent. A completed
            // frame also proves that native initialization succeeded.
            if (event == GLRetroView.GLRetroEvents.SurfaceCreated ||
                event == GLRetroView.GLRetroEvents.FrameRendered) coreReady = true
            if (event == GLRetroView.GLRetroEvents.FrameRendered && !loaded) {
                loaded = true
                // Do not park the renderer before the core has delivered its first frame.
                retroView.enablePausedPresentationOnDemand()
                lastInitializationPollMs = now
                model.firstFrameRendered(session)
                refreshStateSupport()
                // Read the nominal core cadence, never the frame counter or display Hz.
                displayFormat = GameDisplayFormat(
                    runCatching { LibretroDroid.getContentFps().toFloat() }.getOrNull()
                        ?.takeIf { it.isFinite() && it in 1f..240f },
                    runCatching { GameContentFrame.unpack(LibretroDroid.getContentFrameSize()) }.getOrNull(),
                )
                withContext(Dispatchers.Default) {
                    runCatching {
                        val variables = retroView.getVariables()
                        variables.mapNotNull { v -> v.key?.let { it to v.description } } to
                            variables.mapNotNull { v -> v.key?.let { GameCoreOption.parse(it, v.description, v.value) } }
                                .filter { it.visible }
                    }
                }.onSuccess { (declared, parsed) ->
                    model.saveDeclaredOptions(core, declared)
                    coreOptionsSnapshot = parsed
                }
                (0 until RetroPorts.COUNT).forEach { port ->
                    GameSettings.state.controllerType(core, port)?.let { type -> runCatching { retroView.setControllerType(port, type) } }
                }
                applyCheats(cheats)
                achievements.start(retroView, game, !game.direct && core.achievements && GameSettings.state.achievements, hardcoreRequested)
                achievementsInitialized = true
            } else if (event == GLRetroView.GLRetroEvents.FrameRendered && loaded &&
                !stateAvailable && core.saveStates && serializationPolicy.persistentStates &&
                serializationPolicy.mustInitialize && now - lastInitializationPollMs >= 1_000L) {
                // A slow-starting core gets another probe after a second of real elapsed time,
                // independent of notification sampling or the emulated system's frame rate.
                lastInitializationPollMs = now
                refreshStateSupport()
            }
        }
    }
    LaunchedEffect(retroView) {
        retroView.getGLRetroErrors().collect { code ->
            // Vulkan not available after all: once more with OpenGL ES.
            if (session.vulkan && !loaded && model.retryWithoutVulkan()) return@collect
            // A broken core update is restored from the previous installed version, with
            // its own state stamp. Only if no rollback exists is the bad library removed.
            if (code == GLRetroView.ERROR_LOAD_LIBRARY) {
                if (model.rollbackCore(core)) {
                    destroy()
                    model.restart()
                    return@collect
                }
                model.removeCore(core)
            }
            model.failed(context.getString(R.string.game_error_code, code))
        }
    }

    // Controllers, keyboards and remotes play through RetroPadInput while the game runs.
    val latestOpenMenu by rememberUpdatedState(openMenuFromKey)
    val input = remember(system.id, core.id, game.saveKey) {
        val handler = Handler(Looper.getMainLooper())
        RetroPadInput(
            profileContext = com.ambient.tv.ui.gamepad.retro.RetroInputProfileContext(
                systemId = system.id, coreId = core.id, gameId = game.saveKey,
            ),
            schedule = { delay, task ->
                val runnable = Runnable(task)
                handler.postDelayed(runnable, delay)
                ({ handler.removeCallbacks(runnable) })
            },
            onMenu = { latestOpenMenu() },
            onRewind = { held -> rewinding = held && latestAvailability.rewind },
            onFastForward = { if (latestAvailability.fastForward) fastForward = !fastForward },
        )
    }
    var coreInputDescriptions by remember(retroView) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val coreInputActions = remember(coreInputDescriptions) {
        List(RetroPorts.COUNT) { com.ambient.tv.games.GameInputDescriptions.forPort(coreInputDescriptions, it) }
    }
    var contentAspectRatio by remember(retroView) { mutableFloatStateOf(aspect.ratio ?: (4f / 3f)) }
    // Drawn source size for whole-number scaling. The phone surface then matches the exact
    // integer picture, so the ambient fill reaches the picture edge instead of a black border.
    var contentFrame by remember(retroView) { mutableStateOf<GameContentFrame?>(null) }
    // UI-safe atomic geometry snapshots: they follow core/option/rotation changes without
    // capturing frames or waiting for the render thread. Keep the last geometry while paused.
    LaunchedEffect(retroView, handheld, loaded, activityResumed, destroyed) {
        if (!handheld || !loaded || !activityResumed || destroyed) return@LaunchedEffect
        val frames = GameContentFrameFilter(contentFrame)
        while (true) {
            val ratio = LibretroDroid.getContentAspectRatio()
            if (ratio.isFinite() && ratio > 0f) contentAspectRatio = ratio
            frames.observe(GameContentFrame.unpack(LibretroDroid.getContentFrameSize()))
                ?.let { contentFrame = it }
            kotlinx.coroutines.delay(250L)
        }
    }
    // A single atomic revision check per second; descriptors are copied/parsed only when
    // they change. This also catches core-option/controller changes while the OSD is open.
    var descriptorRevision by remember(retroView) { mutableStateOf(-1L) }
    LaunchedEffect(retroView, loaded, activityResumed, destroyed) {
        if (!loaded || !activityResumed || destroyed) return@LaunchedEffect
        while (true) {
            val next = LibretroDroid.getInputDescriptorRevision()
            if (next != descriptorRevision) {
                coreInputDescriptions = withContext(Dispatchers.Default) {
                    com.ambient.tv.games.GameInputDescriptions.fromNative(LibretroDroid.getInputDescriptors())
                }
                descriptorRevision = next
            }
            kotlinx.coroutines.delay(1_000L)
        }
    }
    LaunchedEffect(retroView, loaded, activityResumed, destroyed, menuOpen, optionsVersion) {
        if (!loaded || !activityResumed || destroyed || !menuOpen) return@LaunchedEffect
        coreOptionsSnapshot = withContext(Dispatchers.Default) {
            runCatching {
                retroView.getVariables()
                    .mapNotNull { GameCoreOption.parse(it.key ?: return@mapNotNull null, it.description, it.value) }
                    .filter { it.visible }
            }.getOrDefault(emptyList())
        }
    }
    // Only changed pointer contacts go through JNI; the other device types do not receive
    // redundant six-call resets on each 60Hz phone position update.
    val companionPointerRelay = remember(input) {
        com.ambient.tv.companion.CompanionNativePointerRelay { port, kind, index, x, y, flags ->
            LibretroDroid.onCompanionPointer(port, kind, index, x, y, flags)
        }
    }
    val companionInputKinds = remember(input) { mutableMapOf<Int, String>() }
    val companionNetplayBusy by rememberUpdatedState(netplay.busy)
    // The Companion gamepad uses the same RetroPadInput as real controllers, without injecting
    // synthetic Android keys into the activity. This is a TV-only, full game-session registration.
    val latestCompanionMenu by rememberUpdatedState<() -> Unit>({
        if (coreReady && !stateBusy && !destroyed && !exiting) {
            if (menuOpen) resume() else openMenuFromKey()
        }
    })
    DisposableEffect(input, handheld, system.id, core.id, game.saveKey) {
        if (!handheld) com.ambient.tv.companion.CompanionGamepadSession.attach(
            input, system.id, core.id, game.file.nameWithoutExtension, system.pointerInput,
            onMenu = { latestCompanionMenu() },
            onRewind = { held -> companionRewinding = held && latestAvailability.rewind },
            onPointer = { port, pointer ->
                val kind = pointer?.kind
                val previous = companionInputKinds[port]
                if (kind != previous && !companionNetplayBusy && coreReady && !destroyed &&
                    (kind == null || port !in input.localUsedPorts())) {
                    // Some cores advertise distinct Libretro controller device types for
                    // mouse/lightgun/pointer. Choose one ONLY when the core declares it; else
                    // preserve its original device type (many cores accept POINTER in addition
                    // to an ordinary JOYPAD without advertising a selectable pointer device).
                    val types = runCatching { retroView.getControllers().getOrNull(port).orEmpty() }
                        .getOrDefault(emptyArray())
                    val requested = com.ambient.tv.companion.companionCoreDeviceType(kind)
                    val selection = if (kind == null) {
                        GameSettings.state.controllerType(core, port)
                            ?.let { wanted -> types.firstOrNull { it.id == wanted } }
                            ?: types.firstOrNull { it.id >= 0 && (it.id and 255) == requested }
                    } else types.firstOrNull { it.id >= 0 && (it.id and 255) == requested }
                    selection?.let { chosen ->
                        runCatching { retroView.setControllerType(port, chosen.id) }
                    }
                    if (kind == null) companionInputKinds.remove(port)
                    else companionInputKinds[port] = kind
                }
                companionPointerRelay.update(port, pointer)
            },
            onMotion = { port, motion ->
                LibretroDroid.setCompanionSensorReadings(port, motion?.mask ?: 0,
                    motion?.gx ?: 0f, motion?.gy ?: 0f, motion?.gz ?: 0f,
                    motion?.ax ?: 0f, motion?.ay ?: 0f, motion?.az ?: 0f)
            },
        )
        onDispose {
            if (!handheld) {
                com.ambient.tv.companion.CompanionGamepadSession.detach(input)
                companionPointerRelay.clear()
            }
        }
    }
    val gameDialogOpen = stateDialogOpen || resetConfirmOpen || removeCoreConfirmOpen ||
        gamePanels.navigation.dialogFocusOwner != null
    val gameInputPage = Triple(gamePanels.page, statesOpen,
        (gamePanels.navigation.dialogFocusOwner ?: (stateDialogOpen || resetConfirmOpen || removeCoreConfirmOpen)) to
            netplay.synchronizing)
    val companionInteraction = when {
        !activityResumed || exiting || destroyed -> CompanionGamepadInteraction.BACKGROUND
        stateBusy || displayHold -> CompanionGamepadInteraction.BUSY
        !coreReady || !loaded -> CompanionGamepadInteraction.LOADING
        gameDialogOpen -> CompanionGamepadInteraction.DIALOG
        menuOpen || panel != null -> CompanionGamepadInteraction.MENU
        netplay.synchronizing -> CompanionGamepadInteraction.BUSY
        else -> CompanionGamepadInteraction.PLAYING
    }
    val gameInputMode = when {
        !activityResumed || exiting || destroyed || stateBusy || !coreReady || !loaded || (displayHold && !menuOpen) -> GameInputMode.BLOCKED
        gameDialogOpen -> GameInputMode.DIALOG
        menuOpen || panel != null -> GameInputMode.MENU
        netplay.synchronizing -> GameInputMode.BLOCKED
        else -> GameInputMode.RUNNING
    }
    val showGamePointer = gamePointerVisible(
        menuVisible = gameInputMode == GameInputMode.MENU || gameInputMode == GameInputMode.DIALOG,
        directPointer = system.pointerInput,
        controllerTypes = List(RetroPorts.COUNT) { settings.controllerType(core, it) },
    )
    val hiddenPointer = remember(context) {
        android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL)
    }
    val hiddenComposePointer = remember(hiddenPointer) {
        androidx.compose.ui.input.pointer.PointerIcon(hiddenPointer)
    }
    val latestGameMenu by rememberUpdatedState<() -> Unit>({
        if (!stateBusy && coreReady && !destroyed && !exiting) {
            if (menuOpen) resume() else openMenuFromKey()
        }
    })
    val latestGameSettings by rememberUpdatedState<() -> Unit>({
        if (activityResumed && loaded && coreReady && !stateBusy && !destroyed && !exiting) {
            openMenuFromKey(); gamePanels.openRoot(GamePanel.SETTINGS)
        }
    })
    val latestGameInfo by rememberUpdatedState<() -> Unit>({
        if (activityResumed && loaded && coreReady && !stateBusy && !destroyed && !exiting) {
            openMenuFromKey(); gamePanels.openRoot(GamePanel.INFO)
        }
    })
    DisposableEffect(session) {
        GameInputRouting.attach(session, gameInputMode,
            onMenu = { latestGameMenu() }, onSettings = { latestGameSettings() }, onInfo = { latestGameInfo() },
            context = gameInputPage)
        onDispose {
            GameInputRouting.detach(session)
            com.ambient.tv.companion.CompanionRemoteInput.releaseGamePointerPolicy(session)
        }
    }
    SideEffect {
        GameInputRouting.update(session, gameInputMode, gameInputPage)
        com.ambient.tv.companion.CompanionRemoteInput.setGamePointerAllowed(
            session, showGamePointer,
            inMenu = gameInputMode == GameInputMode.MENU || gameInputMode == GameInputMode.DIALOG,
        )
        // Update the native surface as well as Compose, including Vulkan's hosting view.
        // A null icon restores Android's normal menu/device pointer resolution.
        retroView.pointerIcon = if (showGamePointer) null else hiddenPointer
        vulkanSurfaceHost?.pointerIcon = if (showGamePointer) null else hiddenPointer
        if (!handheld) {
            CompanionGamepadSession.setInteraction(input, companionInteraction, gameInputPage,
                canResume = !netplay.synchronizing)
            CompanionGamepadSession.setRewind(input, availability.rewind, rewindHistory.seconds)
        }
    }
    LaunchedEffect(input, handheld, coreInputDescriptions) {
        if (!handheld) com.ambient.tv.companion.CompanionGamepadSession.setInputDescriptions(input, coreInputDescriptions)
    }
    // Vibration goes to the controllers of the player the core addresses.
    val runningNow by rememberUpdatedState(running)
    LaunchedEffect(Unit) {
        if (core.hardware && settings.vulkan(core) && !session.vulkan) message = context.getString(R.string.game_vulkan_fallback)
    }
    LaunchedEffect(retroView) {
        retroView.getRumbleEvents().collect { event ->
            if (GameSettings.state.rumble && runningNow) {
                input.rumbleDeviceIds(event.port).forEach { GameRumble.apply(it, event.strengthWeak, event.strengthStrong) }
            }
        }
    }
    LaunchedEffect(running, settings.rumble) {
        if (!running || !settings.rumble) GameRumble.stopAll()
    }
    // Hardcore (once the session confirms it) plays without cheats and rewind.
    LaunchedEffect(achievements.state, achievementsInitialized) {
        applyCheats(cheats)
    }
    // Playing together: the menu closes on both sides and the game runs.
    LaunchedEffect(netplay.state) {
        if (netplay.busy) fastForward = false
        if (netplay.state is NetplayState.Connected) {
            rewinding = false
            applyCheats(cheats)
            resume()
        } else if (loaded && !destroyed) {
            // A netplay session temporarily disables rewind and cheats. Restore both on
            // disconnect, rejected connection, timeout or cancel, without restarting a game.
            applyCheats(cheats)
            // Host negotiation pauses the core before exchanging its state. A failed
            // handshake must not leave an otherwise running single-player game frozen.
            if (running) runCatching { retroView.resumeEmulation() }
        }
    }
    DisposableEffect(input, retroView) {
        // The aggregate input already reports exactly which port changed. Publish it
        // before optional UI observers, without scanning 28 atomic values per event.
        input.onPortChanged = changed@{ port ->
            if (destroyed) return@changed
            val range = RetroPadInput.ANALOG_MAX.toFloat()
            LibretroDroid.onInputSnapshot(port, input.buttons(port),
                input.analog(port, 0, 0) / range, input.analog(port, 0, 1) / range,
                input.analog(port, 1, 0) / range, input.analog(port, 1, 1) / range,
                input.trigger(port, 0) / range, input.trigger(port, 1) / range)
        }
        val onRemoved: (Int) -> Unit = { id ->
            input.deviceRemoved(id)
            if (hidingGamepadId == id) hidingGamepadId = null
        }
        GamepadInput.deviceRemovedSink = onRemoved
        onDispose {
            input.onPortChanged = null
            if (GamepadInput.deviceRemovedSink === onRemoved) GamepadInput.deviceRemovedSink = null
            GameRumble.stopAll()
        }
    }
    DisposableEffect(running, input) {
        val sink: (android.view.InputEvent) -> Boolean = { event ->
            // Perfetto can separate OS event delivery from the actual native hand-off.
            // This is not a button-to-photon metric, and tracing is off during normal play.
            val tracing = android.os.Trace.isEnabled()
            if (tracing) {
                android.os.Trace.setCounter("AmbientGame input age ms",
                    (android.os.SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L))
                android.os.Trace.beginSection("AmbientGame input to core")
            }
            val handled = try { input.onEvent(event) } finally {
                if (tracing) android.os.Trace.endSection()
            }
            if (handled && hideTouchForGamepad && isPhysicalGamepadActivity(event, input)) hidingGamepadId = event.deviceId
            handled
        }
        if (running) GamepadInput.rawSink = sink else input.releaseAll()
        onDispose {
            if (GamepadInput.rawSink === sink) GamepadInput.rawSink = null
        }
    }

    // The menu is the player OSD; closing it (Back, Continue) resumes the game.
    LaunchedEffect(menuOpen) {
        if (!menuOpen) return@LaunchedEffect
        osd.expand()
        if (loaded && !destroyed) {
            discCount = withContext(Dispatchers.Default) { runCatching { retroView.getAvailableDisks() }.getOrDefault(0) }
            currentDisc = withContext(Dispatchers.Default) { runCatching { retroView.getCurrentDisk() }.getOrDefault(0) }
        }
    }
    LaunchedEffect(osd.visible.value) { if (!osd.visible.value && panel == null) menuOpen = false }
    RequestGameOsdFocus(menuOpen && activityResumed, gamePanels, playRequester, settingsRequester)
    // Back while playing (a device whose Back does not play) opens the menu; in the menu it continues.
    BackHandler(enabled = panel == null) {
        when {
            statesOpen -> gamePanels.closeStates()
            menuOpen -> resume()
            else -> openMenu()
        }
    }

    val closePanel: () -> Unit = gamePanels::close

    CompositionLocalProvider(
        LocalPlayerChromeState provides chrome,
        LocalPlayerControlFocus provides chrome.focus,
        LocalPlayerPanelNavigation provides gamePanels.navigation,
    ) {
        // The game is paused while its menu shows, so the menu never hides on its own.
        PlayerControlsAutoHide(
            visible = osd.visible.value,
            playing = false,
            panelOpen = panel != null,
            onVisibilityChanged = { osd.visible.value = it },
        )
        Box(Modifier.fillMaxSize().background(Color.Black)
            .then(if (showGamePointer) Modifier else
                Modifier.pointerHoverIcon(hiddenComposePointer, overrideDescendants = true))) {
            if (handheld && settings.performanceProfile.handheldBackdrop) AmbientSurfaceBackdropLayer(
                surface = vulkanSurfaceHost?.presentationSurface() ?: retroView,
                enabled = true,
                landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                modifier = Modifier.fillMaxSize(),
                sampling = running,
                reducedEffects = settings.performanceProfile.reducedBackdropEffects ||
                    LocalPerformanceProfile.current.reducedEffects ||
                    LocalPerformanceProfile.current.reducedPlayback,
            )
            GamePlayerTouchLayout(
                handheld = handheld,
                directPointer = system.pointerInput,
                touchEnabled = touchControls,
                touchMode = touchMode,
                touchStyle = touchStyle,
                presentation = touchPresentation,
                contentAspectRatio = contentAspectRatio,
                // Native whole-pixel scaling uses the source resolution, which may differ
                // from the display aspect. The known source size gives its exact picture.
                fitSurface = integerScaling == com.ambient.tv.games.GameIntegerScaling.OFF,
                integerFrame = contentFrame,
                integerScalingAuto = integerScaling == com.ambient.tv.games.GameIntegerScaling.AUTO,
                coreActions = coreInputActions[0],
                hiddenForGamepad = touchStyle.hideOnGamepadInput && hidingGamepadId != null,
                onShowTouch = { hidingGamepadId = null },
                playing = running,
                menuVisible = menuOpen,
                onMenu = openMenu,
                onButton = { button, pressed -> input.touchButton(button, pressed) },
                onStick = { index, x, y -> input.touchStick(index, x, y) },
                surface = { surfaceModifier ->
                    AndroidView(factory = { vulkanSurfaceHost ?: retroView }, modifier = surfaceModifier)
                },
            )
            if ((displayHold || (gameDisplayProfile.active && !displayPrepared && !videoOwnsDisplay)) && !menuOpen) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.game_display_switching), color = Color.White)
                }
            }
            if (resetConfirmOpen) com.ambient.tv.ui.common.AppDialog(
                onDismiss = { resetConfirmOpen = false },
                title = stringResource(R.string.game_reset_confirm_title),
                hint = stringResource(R.string.game_reset_confirm_hint),
                footer = {
                    com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.files_cancel), onClick = { resetConfirmOpen = false })
                    com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.game_reset), onClick = {
                        resetConfirmOpen = false
                        resetGame()
                    })
                }, content = null,
            )
            if (removeCoreConfirmOpen) com.ambient.tv.ui.common.AppDialog(
                onDismiss = { removeCoreConfirmOpen = false },
                title = stringResource(R.string.games_remove_confirm, core.name),
                hint = stringResource(R.string.game_core_remove_hint),
                footer = {
                    com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.files_cancel), onClick = { removeCoreConfirmOpen = false })
                    com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.saved_profiles_delete), onClick = {
                        removeCoreConfirmOpen = false
                        model.removeCore(core)
                        message = context.getString(R.string.game_message_core_removed)
                        closePanel()
                    })
                }, content = null,
            )
            GameAchievementBanner(achievements.banner, Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
            if (netplay.waiting && !menuOpen) GameOverlayNote(
                stringResource(R.string.game_netplay_waiting),
                Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            )

            GamePlayerMenuOverlay(menuOpen = menuOpen, panelOpen = panel != null) {
                ReferencePlayerOverlayBackground()
                ReferencePlayerClock(modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 48.dp))
                ReferencePlayerBottomContent(onTogglePlayback = resume) {
                    ReferencePlayerHeader(
                        title = game.title,
                        artworkUrl = coverUrl,
                        contextLine = system.name,
                        subtitle = message ?: stringResource(if (running) R.string.game_running else R.string.game_paused),
                        showPaused = !running,
                    )
                    if (availability.rewind && rewindHistory.available) {
                        // The rewind history as a timeline: oldest stored moment on the left, now
                        // on the right. Seeking shows that moment; Continue plays on from there.
                        val historyMs = (rewindHistory.seconds * 1000f).roundToInt().toLong().coerceAtLeast(1L)
                        val shownMs = (rewindSeekShownFrames * 1000.0 / rewindFps).roundToInt().toLong()
                            .coerceIn(0L, historyMs)
                        com.ambient.tv.ui.player.TvPlayerSeekBar(
                            positionMs = historyMs - shownMs,
                            durationMs = historyMs,
                            interactive = !stateBusy && !menuRewinding,
                            onSeekBy = { delta ->
                                val target = gameRewindSeekTarget(rewindSeekTargetMs, delta, historyMs)
                                rewindSeekTargetMs = target
                                rewindSeekRequests.trySend(gameRewindSeekFrames(target, rewindFps))
                            },
                            onInteraction = osd::interact,
                            onTogglePlayback = resume,
                            onPlay = resume,
                            paused = true,
                            showEndTime = false,
                            timelineKey = "game-rewind",
                            timelineContext = { previewPosition ->
                                val backMs = (historyMs - previewPosition).coerceAtLeast(0L)
                                Text(
                                    if (backMs < GAME_REWIND_LIVE_TOLERANCE_MS) stringResource(R.string.game_rewind_timeline_live)
                                    else stringResource(R.string.game_rewind_timeline_back, backMs / 1000f),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.testTag("game-rewind-timeline-position"),
                                )
                            },
                        )
                    }
                    PlayerTechBadgeRow {
                        MetaBadge("libretro", emphasized = true)
                        MetaBadge(core.name)
                        if (game.direct) MetaBadge(stringResource(R.string.game_direct_source))
                        if (core.hardware) MetaBadge(vulkanFrameStatus.badge(session.vulkan))
                        if (sdrToHdrSession && retroView.sdrToHdrActive) MetaBadge(stringResource(R.string.game_badge_sdr_to_hdr))
                        if (nativeSdrFullscreenRequested &&
                            GameVulkanTransferGate.frameConfirmed(nativeTransfer, nativeSdrStatus)) {
                            MetaBadge(stringResource(when (nativeTransfer) {
                                GameVulkanTransferMode.SDR -> R.string.game_badge_native_sdr_test
                                GameVulkanTransferMode.PQ_BT2020 -> R.string.game_badge_native_pq_test
                                GameVulkanTransferMode.HLG_BT2020 -> R.string.game_badge_native_hlg_test
                            }))
                        }
                        if (session.vulkan && vulkanFrameStatus.active && toneMap != GameHdrToneMap.OFF &&
                            nativeSourceContract.supports(nativeTransfer) &&
                            !(nativeSdrFullscreenRequested &&
                                GameVulkanTransferGate.frameConfirmed(nativeTransfer, nativeSdrStatus))) {
                            MetaBadge(stringResource(R.string.game_badge_hdr_to_sdr))
                        } else {
                            MetaBadge(stringResource(shader.titleRes))
                        }
                        if (aspect != GameAspect.CORE) MetaBadge(aspect.key)
                        if (fastForward) MetaBadge(stringResource(R.string.game_badge_fast_forward))
                        (achievements.state as? GameAchievementState.Active)?.let { active ->
                            MetaBadge(stringResource(
                                if (active.hardcore) R.string.game_achievements_badge_hardcore else R.string.game_achievements_badge,
                                achievements.unlocked.size, active.game.achievements.size,
                            ))
                        }
                        (netplay.state as? NetplayState.Connected)?.let { MetaBadge(stringResource(R.string.game_netplay_badge, it.localPlayer)) }
                        if (discCount > 1) MetaBadge(stringResource(R.string.game_disc_number, currentDisc + 1, discCount))
                        MetaBadge(game.sourceName)
                    }
                    PlayerControlBar(
                        player = OsdPlayer.GAMES,
                        // Save states sit below the actions like chapters, and the same Down opens them.
                        belowContent = {
                        GameStateStrip(
                            open = statesOpen,
                            canLoad = availability::canLoad,
                            compatible = availability::compatible,
                            onDialogVisibilityChanged = { stateDialogOpen = it },
                            states = saveStates,
                            coreStamp = session.coreStamp,
                            title = ::stateTitle,
                            screenshot = { model.screenshot(game, it) },
                            onLoad = loadState,
                            onDelete = { model.deleteState(game, it) },
                            onRename = { entry, label ->
                                scope.launch {
                                    try { model.renameState(game, entry, label) }
                                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (_: Exception) { message = context.getString(R.string.game_message_save_failed) }
                                }
                            },
                            enabled = !stateBusy,
                            onClose = { gamePanels.closeStates() },
                        )
                        },
                        onDown = {
                            if (availability.manageStates && !statesOpen) {
                                osd.interact()
                                gamePanels.toggleStates()
                                true
                            } else false
                        },
                        leftContent = {
                            GamePlayerTransportControls(
                                availability = availability,
                                canResume = !stateBusy && !netplay.synchronizing,
                                fastForward = fastForward, rewinding = menuRewinding, statesOpen = statesOpen,
                                onResume = resume, onExit = exit, onSave = saveManualState,
                                onLoad = {
                                    latestAvailability.latestLoadableManual?.let(loadState)
                                },
                                onStates = {
                                    osd.interact()
                                    gamePanels.toggleStates()
                                },
                                onReset = {
                                    if (!stateBusy && !destroyed && !exiting && latestAvailability.reset) {
                                        osd.interact()
                                        resetConfirmOpen = true
                                    }
                                },
                                onFastForward = { if (latestAvailability.fastForward) fastForward = !fastForward },
                                onRewind = setMenuRewind,
                                resumeModifier = Modifier.focusRequester(playRequester),
                                statesModifier = Modifier.focusRequester(statesRequester),
                            )
                        },
                        rightContent = {
                            // Playback actions stay left; picture/options, information and settings stay right.
                            PlayerIconButton(Icons.Filled.Wallpaper, stringResource(R.string.game_panel_picture), {
                                gamePanels.open(GamePanel.PICTURE)
                            }, selected = panel == GamePanel.PICTURE, osd = OsdButton.GAME_PICTURE)
                            if (availability.coreOptions) PlayerIconButton(Icons.Filled.Tune, stringResource(R.string.game_panel_core), {
                                optionsVersion++
                                gamePanels.open(GamePanel.CORE)
                            }, selected = panel == GamePanel.CORE, osd = OsdButton.GAME_CORE)
                            PlayerIconButton(Icons.Filled.SportsEsports, stringResource(R.string.game_panel_controls), {
                                gamePanels.open(GamePanel.CONTROLS)
                            }, selected = panel == GamePanel.CONTROLS, osd = OsdButton.GAME_CONTROLS)
                            if (achievements.state != GameAchievementState.Off) PlayerIconButton(
                                Icons.Filled.EmojiEvents, stringResource(R.string.game_panel_achievements), {
                                    gamePanels.open(GamePanel.ACHIEVEMENTS)
                                }, selected = panel == GamePanel.ACHIEVEMENTS, osd = OsdButton.GAME_ACHIEVEMENTS)
                            if (availability.cheats) PlayerIconButton(Icons.Filled.Code, stringResource(R.string.game_panel_cheats), {
                                gamePanels.open(GamePanel.CHEATS)
                            }, selected = panel == GamePanel.CHEATS, osd = OsdButton.GAME_CHEATS)
                            if (availability.netplaySetup) PlayerIconButton(Icons.Filled.Groups, stringResource(R.string.game_panel_netplay), {
                                gamePanels.open(GamePanel.NETPLAY)
                            }, selected = panel == GamePanel.NETPLAY, osd = OsdButton.GAME_NETPLAY)
                            if (availability.discs) PlayerIconButton(Icons.Filled.Album, stringResource(R.string.game_panel_disc), {
                                gamePanels.open(GamePanel.DISC)
                            }, selected = panel == GamePanel.DISC, osd = OsdButton.GAME_DISC)
                            PlayerIconButton(Icons.Filled.Info, stringResource(R.string.game_panel_info), {
                                gamePanels.open(GamePanel.INFO)
                            }, selected = panel == GamePanel.INFO, osd = OsdButton.GAME_INFO)
                            // Never hide this recovery path: every optional panel remains reachable here.
                            PlayerIconButton(Icons.Filled.Settings, stringResource(R.string.player_playback_settings), {
                                gamePanels.open(GamePanel.SETTINGS)
                            }, modifier = Modifier.focusRequester(settingsRequester), selected = panel != null)
                        },
                    )
                }
            }

            val open = panel
            if (open != null) GamePanelOverlay(
                gamePanels.page,
                onOutsideTap = closePanel,
            ) {
                // Renderer keys follow the graphics choice at every start; offering them would be overridden.
                val rendererKeys = core.vulkanOptions.keys + core.glOptions.keys
                val options = if (open == GamePanel.CORE) coreOptionsSnapshot.filterNot { it.key in rendererKeys } else emptyList()
                val categorized = options.any { it.category != null }
                fun categoryOf(item: GameCoreOption) = item.category?.key ?: OTHER_CATEGORY
                val category = optionCategory
                val categoryTitle = category?.let { key ->
                    options.firstOrNull { it.category?.key == key }?.category?.title ?: stringResource(R.string.game_core_other)
                }
                /** Applies [values] (key → value, null = the core's default) to the running core. */
                fun applyOptions(values: Map<String, String?>) {
                    val changes = options.mapNotNull { item ->
                        if (!values.containsKey(item.key)) return@mapNotNull null
                        val value = values[item.key] ?: item.default
                        Variable(item.key, value).takeIf { value != item.value }
                    }
                    if (changes.isNotEmpty()) runCatching { retroView.updateVariables(*changes.toTypedArray()) }
                    optionsVersion++
                }
                fun changeDisc(index: Int) {
                    if (runCatching { retroView.changeDisk(index) }.isSuccess) {
                        currentDisc = index
                        message = context.getString(R.string.game_disc, index + 1)
                    }
                }
                GameSidePanel(
                    panels = gamePanels,
                    title = when {
                        open == GamePanel.SETTINGS -> stringResource(R.string.player_playback_settings)
                        open == GamePanel.OSD_BUTTONS -> stringResource(R.string.osd_buttons)
                        open == GamePanel.CORE && category != null -> categoryTitle.orEmpty()
                        open == GamePanel.PICTURE -> stringResource(R.string.game_panel_picture)
                        open == GamePanel.DISPLAY -> stringResource(R.string.game_display_title)
                        open == GamePanel.CORE -> stringResource(R.string.game_panel_core)
                        open == GamePanel.DISC -> stringResource(R.string.game_panel_disc)
                        open == GamePanel.CONTROLS -> stringResource(R.string.game_panel_controls)
                        open == GamePanel.ACHIEVEMENTS -> stringResource(R.string.game_panel_achievements)
                        open == GamePanel.CHEATS -> stringResource(R.string.game_panel_cheats)
                        open == GamePanel.NETPLAY -> stringResource(R.string.game_panel_netplay)
                        gamePanels.diagnosticsOpen -> stringResource(R.string.game_info_diagnostics)
                        else -> stringResource(R.string.game_panel_info)
                    },
                    subtitle = when {
                        open == GamePanel.PICTURE && gamePictureScope -> stringResource(R.string.game_core_scope_game)
                        open == GamePanel.PICTURE -> stringResource(R.string.game_picture_scope, system.name)
                        open == GamePanel.CORE -> if (gameScope) {
                            stringResource(R.string.game_core_scope_game)
                        } else stringResource(R.string.game_core_scope_core, core.name)
                        else -> null
                    },
                ) {
                    when {
                        open == GamePanel.SETTINGS -> {
                            val offered = availability.buttons()
                            val hidden = osdHiddenButtons()
                            fun openSettingsPanel(target: GamePanel) {
                                if (target == GamePanel.CORE) optionsVersion++
                                gamePanels.open(target)
                            }
                            GamePanelChoice(
                                text = stringResource(R.string.osd_buttons),
                                subtitle = stringResource(R.string.osd_buttons_summary,
                                    offered.count { osdButtonVisible(it, OsdPlayer.GAMES, hidden) }, offered.size),
                                icon = Icons.Filled.ViewWeek,
                                selected = false,
                                onClick = { openSettingsPanel(GamePanel.OSD_BUTTONS) },
                            )
                            GamePanelChoice(stringResource(R.string.game_panel_picture), selected = false,
                                icon = Icons.Filled.Wallpaper, onClick = { openSettingsPanel(GamePanel.PICTURE) })
                            if (availableGameModes.size > 1 || gameDisplayProfile.active) GamePanelChoice(
                                stringResource(R.string.game_display_title), selected = false,
                                onClick = { openSettingsPanel(GamePanel.DISPLAY) })
                            if (availability.coreOptions) GamePanelChoice(stringResource(R.string.game_panel_core), selected = false,
                                icon = Icons.Filled.Tune,
                                onClick = { openSettingsPanel(GamePanel.CORE) })
                            GamePanelChoice(stringResource(R.string.game_panel_controls), selected = false,
                                icon = Icons.Filled.SportsEsports, onClick = { openSettingsPanel(GamePanel.CONTROLS) })
                            if (achievements.state != GameAchievementState.Off) GamePanelChoice(
                                stringResource(R.string.game_panel_achievements), selected = false,
                                icon = Icons.Filled.EmojiEvents, onClick = { openSettingsPanel(GamePanel.ACHIEVEMENTS) })
                            if (availability.cheats) GamePanelChoice(stringResource(R.string.game_panel_cheats), selected = false,
                                icon = Icons.Filled.Code,
                                onClick = { openSettingsPanel(GamePanel.CHEATS) })
                            if (availability.netplaySetup) GamePanelChoice(stringResource(R.string.game_panel_netplay), selected = false,
                                icon = Icons.Filled.Groups,
                                onClick = { openSettingsPanel(GamePanel.NETPLAY) })
                            if (availability.discs) GameDiscSelector(discCount, currentDisc, ::changeDisc)
                            GamePanelChoice(stringResource(R.string.game_panel_info), selected = false,
                                icon = Icons.Filled.Info, onClick = { openSettingsPanel(GamePanel.INFO) })
                            GamePanelChoice(stringResource(R.string.game_exit), selected = false,
                                icon = Icons.Filled.Stop, onClick = exit)
                        }
                        open == GamePanel.OSD_BUTTONS -> PlayerOsdButtonPanelControls(
                            hasVideo = true,
                            player = OsdPlayer.GAMES,
                            offered = availability.buttons(),
                        )
                        open == GamePanel.DISPLAY -> GameDisplayPanel(
                            core.id, game.saveKey, availableGameModes, handheld, displayFormat, actualGameMode,
                            displaySwitching, displayFailed, netplay.busy,
                        )
                        open == GamePanel.PICTURE -> {
                            if (session.vulkan && (nativeSdrDiagnostic || nativeSdrFullscreenRequested) && !nativePictureSupported) {
                                GamePanelText(stringResource(R.string.game_picture_native_limit))
                            }
                            if (session.vulkan && toneMap != GameHdrToneMap.OFF) {
                                GamePanelText(stringResource(R.string.game_picture_hdr_filter_note))
                                if (loaded && !nativeSourceContract.supports(nativeTransfer)) {
                                    GamePanelText(stringResource(R.string.game_picture_hdr_unconfirmed))
                                }
                            }
                            if (selectedPack != null && !selectedPackPending && selectedPackConfig == null) {
                                GamePanelText(stringResource(R.string.games_shader_packs_missing))
                            }
                            /** Shows what [state] chooses for this game, after the scope changed. */
                            fun applyPicture(state: GameSettingsState) {
                                retroView.aspectRatioOverride = state.aspect(system, game.saveKey).ratio ?: -1f
                                val scaling = state.integerScaling(system, game.saveKey)
                                retroView.integerScalingAuto = scaling == GameIntegerScaling.AUTO
                                retroView.integerScaling = scaling.enabled
                            }
                            // One scope for filter, strength, shape and scaling, like the core options.
                            GamePanelChoice(
                                text = stringResource(R.string.game_shader_scope_game),
                                subtitle = if (gamePictureScope) stringResource(R.string.game_picture_scope_game_hint)
                                    else stringResource(R.string.game_picture_scope, system.name),
                                selected = gamePictureScope,
                                onClick = {
                                    pendingPackChoice = null
                                    if (gamePictureScope) {
                                        GameSettings.update { it.withoutGamePicture(game.saveKey) }
                                        applyPicture(GameSettings.state)
                                    } else {
                                        GameSettings.update { it.withGamePicture(system, game.saveKey) }
                                    }
                                },
                            )
                            GamePictureFilterSelector(
                                system = system,
                                shader = shader,
                                selectedPack = selectedPack,
                                installedPresets = installedShaders,
                                onShaderSelected = { choice ->
                                    pendingPackChoice = null
                                    GameSettings.update {
                                        if (gamePictureScope) it.withGameShader(game.saveKey, choice)
                                        else it.withShader(system, choice)
                                    }
                                },
                                onPackSelected = { preset ->
                                    pendingPackChoice = GameShaderPackChoice(
                                        preset.selection, gamePictureScope, GameSettings.state,
                                    )
                                },
                            )
                            if (selectedPack == null && GameRetroShaders.isCustom(shader)) {
                                val percent = (shaderIntensity * 100).roundToInt()
                                com.ambient.tv.ui.common.PlayerValueSlider(
                                    label = stringResource(R.string.game_shader_strength),
                                    value = percent,
                                    valueRange = 50..150,
                                    step = 25,
                                    valueLabel = stringResource(R.string.game_shader_strength_value, percent),
                                    onValueChange = { value ->
                                        val next = value / 100f
                                        pendingPackChoice = null
                                        GameSettings.update {
                                            it.withShaderStrength(system, game.saveKey.takeIf { gamePictureScope }, next)
                                        }
                                    },
                                )
                            }
                            // Second stage: an effect on the original frame in front of the filter.
                            if (selectedPack == null && !(session.vulkan && toneMap != GameHdrToneMap.OFF)) {
                                val noEffect = stringResource(R.string.game_picture_effect_none)
                                val effectOptions = listOf("" to noEffect) +
                                    GamePictureDefaults.effects.map { it.key to stringResource(it.titleRes) }
                                com.ambient.tv.ui.common.PlayerDropUpSelector(
                                    label = stringResource(R.string.game_picture_effect),
                                    value = effect?.let { stringResource(it.titleRes) } ?: noEffect,
                                    options = effectOptions,
                                    selectedKey = effect?.key.orEmpty(),
                                    // The same look twice adds nothing.
                                    disabledKeys = setOfNotNull(shader.key.takeIf { shader in GamePictureDefaults.effects }),
                                    dropUp = false,
                                    onSelected = { key ->
                                        val chosen = GameShader.fromKey(key)?.takeIf { it in GamePictureDefaults.effects }
                                        pendingPackChoice = null
                                        GameSettings.update {
                                            if (gamePictureScope) it.withGameEffect(game.saveKey, chosen)
                                            else it.withEffect(system, chosen)
                                        }
                                    },
                                )
                                GamePanelText(stringResource(R.string.game_picture_effect_hint))
                            }
                            GamePanelHeading(stringResource(R.string.game_picture_shape))
                            GameAspect.entries.forEach { choice ->
                                GamePanelChoice(
                                    text = stringResource(choice.titleRes),
                                    selected = choice == aspect,
                                    onClick = {
                                        retroView.aspectRatioOverride = choice.ratio ?: -1f
                                        GameSettings.update {
                                            if (gamePictureScope) it.withGameAspect(game.saveKey, choice)
                                            else it.withAspect(system, choice)
                                        }
                                    },
                                )
                            }
                            // Off / On / Auto: a value choice like the other picture values, not a toggle row.
                            val scalingOptions = GameIntegerScaling.entries.map { it.key to stringResource(it.titleRes) }
                            com.ambient.tv.ui.common.PlayerDropUpSelector(
                                label = stringResource(R.string.game_picture_integer),
                                value = stringResource(integerScaling.titleRes),
                                options = scalingOptions,
                                selectedKey = integerScaling.key,
                                dropUp = false,
                                onSelected = { key ->
                                    val chosen = GameIntegerScaling.fromKey(key) ?: return@PlayerDropUpSelector
                                    retroView.integerScalingAuto = chosen == GameIntegerScaling.AUTO
                                    retroView.integerScaling = chosen.enabled
                                    GameSettings.update {
                                        if (gamePictureScope) it.withGameIntegerScaling(game.saveKey, chosen)
                                        else it.withIntegerScaling(system, chosen)
                                    }
                                },
                            )
                            GamePanelText(stringResource(when (integerScaling) {
                                GameIntegerScaling.AUTO -> R.string.game_picture_integer_auto_hint
                                else -> R.string.game_picture_integer_hint
                            }))
                            // HDR is offered only where a core is known to output it (or to switch it off again).
                            val hdrOption = GameVulkanHdrSourceEvidence.hdrOption(core.id)
                            if (session.vulkan && core.hardware && core.supportsVulkan && !netplay.busy && !exiting &&
                                (hdrOption != null || toneMap != GameHdrToneMap.OFF)) {
                                GamePanelHeading(stringResource(R.string.game_picture_hdr))
                                if (hdrOption != null) GamePanelText(stringResource(R.string.game_picture_hdr_steps))
                                GameHdrToneMap.entries.filter { it != GameHdrToneMap.HLG || toneMap == GameHdrToneMap.HLG }.forEach { choice ->
                                    GamePanelChoice(
                                        text = stringResource(choice.titleRes),
                                        subtitle = if (choice == GameHdrToneMap.OFF) null
                                            else stringResource(R.string.game_picture_hdr_hint),
                                        selected = toneMap == choice,
                                        enabled = !netplay.busy && !exiting,
                                        onClick = {
                                            if (choice != toneMap && !exiting && !netplay.busy) {
                                                GameSettings.update { state ->
                                                    GameHdrInputSettings.select(state, core, game.saveKey, choice)
                                                }
                                                // EGL/AHB precision is negotiated at core start, not on a
                                                // live shader toggle; restart to choose FP16 or release it.
                                                exiting = true
                                                destroy()
                                                model.restart()
                                            }
                                        },
                                    )
                                }
                            }
                            // SDR as HDR10: for every game on an HDR TV, while no HDR source is being converted.
                            if ((displayHdr.hdr10 || settings.sdrToHdr) && toneMap == GameHdrToneMap.OFF) {
                                GamePanelHeading(stringResource(R.string.game_picture_sdr_to_hdr))
                                GamePanelChoice(
                                    text = stringResource(R.string.game_picture_sdr_to_hdr_switch),
                                    subtitle = stringResource(R.string.game_picture_sdr_to_hdr_hint),
                                    selected = settings.sdrToHdr,
                                    enabled = !netplay.busy && !exiting,
                                    onClick = {
                                        GameSettings.update { it.withSdrToHdr(!settings.sdrToHdr) }
                                        // The EGL config and window surface are chosen at start.
                                        exiting = true
                                        destroy()
                                        model.restart()
                                    },
                                )
                                if (settings.sdrToHdr) {
                                    val white = settings.sdrReferenceWhite
                                    com.ambient.tv.ui.common.PlayerDropUpSelector(
                                        label = stringResource(R.string.sdr_reference_white),
                                        value = sdrReferenceWhiteChoices().firstOrNull { it.first == sdrReferenceWhiteChoiceKey(white) }?.second.orEmpty(),
                                        options = sdrReferenceWhiteChoices(),
                                        selectedKey = sdrReferenceWhiteChoiceKey(white),
                                        dropUp = false,
                                        onSelected = { key ->
                                            val next = sdrReferenceWhiteForChoice(key, white)
                                            GameSettings.update { it.withSdrReferenceWhite(next) }
                                            retroView.sdrToHdrReferenceWhiteNits = gameSdrWhiteNits(next, displayHdr)
                                        },
                                    )
                                    SdrReferenceWhiteCustomSlider(white) { next ->
                                        GameSettings.update { it.withSdrReferenceWhite(next) }
                                        retroView.sdrToHdrReferenceWhiteNits = gameSdrWhiteNits(next, displayHdr)
                                    }
                                    if (sdrToHdrSession && !retroView.sdrToHdrActive) {
                                        GamePanelText(stringResource(R.string.game_picture_sdr_to_hdr_unavailable))
                                    }
                                }
                            }
                            if (core.hardware && core.supportsVulkan && !core.requiresVulkan && !netplay.busy && !exiting) {
                                GamePanelHeading(stringResource(R.string.game_picture_graphics))
                                listOf(false, true).forEach { vulkan ->
                                    GamePanelChoice(
                                        text = stringResource(if (vulkan) R.string.game_picture_graphics_vulkan else R.string.game_picture_graphics_gles),
                                        subtitle = stringResource(R.string.game_picture_graphics_hint, core.name),
                                        selected = settings.vulkan(core) == vulkan,
                                        onClick = {
                                            GameSettings.update { it.withVulkan(core, vulkan) }
                                            // The renderer is chosen when the core starts: start again with it.
                                            if (vulkan != session.vulkan && !exiting && !netplay.busy) {
                                                exiting = true
                                                destroy()
                                                model.restart(retryVulkan = vulkan)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        open == GamePanel.CORE -> {
                            if (category == null && system.alternatives.isNotEmpty()) {
                                // Which core plays this game; switching saves and starts it again.
                                GamePanelHeading(stringResource(R.string.game_info_core_choice))
                                val own = settings.ownGameCore(system, game.saveKey)
                                system.cores.forEach { choice ->
                                    GamePanelChoice(
                                        text = choice.name,
                                        subtitle = listOfNotNull(
                                            choice.license,
                                            stringResource(R.string.game_info_core_system_default).takeIf {
                                                choice.id == settings.systemCore(system).id
                                            },
                                            stringResource(R.string.game_info_core_needs_bios).takeIf { choice.needsBios },
                                        ).joinToString(" · "),
                                        selected = choice.id == core.id,
                                        enabled = !netplay.busy,
                                        focusKey = "game-core-choice:${choice.id}",
                                        onClick = {
                                            if (choice.id != core.id || own != null) {
                                                GameSettings.update {
                                                    it.withGameCore(system, game.saveKey, choice.takeIf { c -> c.id != it.systemCore(system).id })
                                                }
                                                if (choice.id != core.id && !exiting) {
                                                    // Saves, ends this core and starts the game with the other one.
                                                    exiting = true
                                                    destroy()
                                                    model.restart()
                                                }
                                            }
                                        },
                                    )
                                }
                                GamePanelHeading(stringResource(R.string.game_core_options_heading))
                            }
                            if (options.isEmpty()) GamePanelText(stringResource(R.string.game_core_no_options))
                            if (category == null) {
                                if (options.isNotEmpty()) GamePanelChoice(
                                    text = stringResource(R.string.game_shader_scope_game),
                                    subtitle = if (gameScope) stringResource(R.string.game_core_scope_game)
                                    else stringResource(R.string.game_core_scope_core, core.name),
                                    selected = gameScope,
                                    onClick = { gameScope = !gameScope },
                                )
                                if (categorized) {
                                    options.groupBy(::categoryOf).forEach { (key, items) ->
                                        val first = items.first().category
                                        GamePanelChoice(
                                            text = first?.title ?: stringResource(R.string.game_core_other),
                                            subtitle = first?.info?.takeIf { it.isNotBlank() }
                                                ?: stringResource(R.string.game_core_category_count, items.size),
                                            selected = false,
                                            trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            focusKey = "game-category:$key",
                                            onClick = { gamePanels.openCategory(key) },
                                        )
                                    }
                                }
                            }
                            val shown = when {
                                category != null -> options.filter { categoryOf(it) == category }
                                categorized -> emptyList()
                                else -> options
                            }
                            shown.forEach { item -> key(item.key) {
                                GameCoreOptionSelector(
                                    option = item,
                                    origin = settings.coreOptionOrigin(core, game.saveKey, item.key),
                                    onSelected = { value ->
                                        runCatching { retroView.updateVariables(Variable(item.key, value)) }
                                        // Choosing the inherited value clears this scope's override.
                                        val inherited = if (gameScope) settings.coreOptions(core)[item.key] ?: item.default
                                            else item.default
                                        val stored = value.takeIf { it != inherited }
                                        GameSettings.update {
                                            if (gameScope) it.withGameCoreOption(core, game.saveKey, item.key, stored)
                                            else it.withCoreOption(core, item.key, stored, gameKey = game.saveKey)
                                        }
                                        optionsVersion++
                                    },
                                )
                            } }
                            if (category == null) {
                                val own = settings.gameCoreOptions(core, game.saveKey)
                                if (own.isNotEmpty()) GamePanelChoice(
                                    text = stringResource(R.string.game_core_game_reset),
                                    subtitle = stringResource(R.string.game_core_game_reset_hint),
                                    selected = false,
                                    onClick = {
                                        val forAll = settings.coreOptions(core)
                                        GameSettings.update { it.withoutGameCoreOptions(core, game.saveKey) }
                                        applyOptions(own.keys.associateWith { forAll[it] })
                                    },
                                )
                                val forAll = settings.coreOptions(core)
                                if (forAll.isNotEmpty()) GamePanelChoice(
                                    text = stringResource(R.string.game_core_options_reset),
                                    subtitle = stringResource(R.string.game_core_options_reset_hint),
                                    selected = false,
                                    onClick = {
                                        val gameOwn = settings.gameCoreOptions(core, game.saveKey)
                                        GameSettings.update { it.withoutCoreOptions(core) }
                                        applyOptions((forAll.keys - gameOwn.keys).associateWith { null })
                                    },
                                )
                                GamePanelChoice(
                                    text = stringResource(R.string.game_core_remove),
                                    subtitle = stringResource(R.string.game_core_remove_hint),
                                    selected = false,
                                    onClick = { removeCoreConfirmOpen = true },
                                )
                            }
                        }
                        open == GamePanel.DISC -> GameDiscSelector(discCount, currentDisc, ::changeDisc)
                        open == GamePanel.CONTROLS -> {
                            // Direct touch controls only exist on a phone or tablet; a TV starts with players.
                            if (handheld) {
                                GamePanelHeading(stringResource(R.string.game_touch_layout))
                                GamePanelChoice(
                                    text = stringResource(R.string.game_touch_controls),
                                    subtitle = stringResource(R.string.game_touch_controls_hint),
                                    selected = touchControls,
                                    onClick = { GameSettings.update { it.withTouchControls(system, !touchControls) } },
                                )
                                GameTouchMode.entries.forEach { choice ->
                                    GamePanelChoice(
                                        text = stringResource(choice.titleRes),
                                        selected = choice == touchMode,
                                        enabled = touchControls,
                                        onClick = { GameSettings.update { it.withTouchMode(system, choice) } },
                                    )
                                }
                                GameTouchOptions(system.id)
                            }
                            // The panel is already called "Players"; the heading only separates it from touch.
                            if (handheld) GamePanelHeading(stringResource(R.string.game_panel_controls))
                            GameControlsPanel()
                            GameDeviceTypesPanel(
                                controllers = remember(open) { runCatching { retroView.getControllers() }.getOrDefault(emptyArray()) },
                                current = { port -> settings.controllerType(core, port) },
                                enabled = !netplay.busy,
                                onChoose = { port, type ->
                                    runCatching { retroView.setControllerType(port, type) }
                                    GameSettings.update { it.withControllerType(core, port, type) }
                                },
                            )
                            coreInputActions.forEachIndexed { port, actions ->
                                GameCoreInputActions(actions, touchPresentation,
                                    stringResource(R.string.game_core_input_actions_player, port + 1))
                            }
                            if (!handheld) {
                                // On a TV these shape the touch pad of the companion phone.
                                GamePanelHeading(stringResource(R.string.game_touch_companion))
                                GameTouchOptions(system.id)
                            }
                        }
                        open == GamePanel.ACHIEVEMENTS -> GameAchievementsPanel(achievements)
                        open == GamePanel.CHEATS -> GameCheatsPanel(
                            cheats = cheats,
                            loading = cheatsLoading,
                            deleting = cheatsDeleting,
                            draft = cheatDraft,
                            blocked = !availability.cheats,
                            onToggle = { index -> updateCheats(cheats.mapIndexed { i, c -> if (i == index) c.copy(enabled = !c.enabled) else c }) },
                            onDelete = { index -> updateCheats(cheats.filterIndexed { i, _ -> i != index }) },
                            onDeleting = { cheatsDeleting = it },
                            onDraft = { cheatDraft = it },
                            onAdd = { description, code ->
                                updateCheats(cheats + GameCheat(description.ifBlank { code }, code.trim(), enabled = true))
                                cheatDraft = null
                            },
                            onDatabase = {
                                cheatsLoading = true
                                scope.launch {
                                    val found = runCatching { model.databaseCheats(game) }
                                    cheatsLoading = false
                                    found.onSuccess { list ->
                                        val known = cheats.map { it.code }.toSet()
                                        val added = list.filter { it.code !in known }.map { it.copy(enabled = false) }
                                        message = context.getString(
                                            if (list.isEmpty()) R.string.game_cheats_none_found else R.string.game_cheats_found, added.size,
                                        )
                                        if (added.isNotEmpty()) updateCheats(cheats + added)
                                    }.onFailure { message = context.getString(R.string.game_cheats_failed) }
                                }
                            },
                        )
                        open == GamePanel.NETPLAY -> GameNetplayPanel(
                            netplay, retroView, game, session.coreStamp, session.vulkan,
                            settings.effectiveCoreOptions(core, game.saveKey) +
                                (if (session.vulkan) core.vulkanOptions else core.glOptions),
                            session.systemDirectory,
                        )
                        gamePanels.diagnosticsOpen -> {
                            GamePanelInfo(stringResource(R.string.game_info_hdr_display_capabilities, displayHdr.supportedTypesLabel()))
                            if (session.vulkan) {
                                GamePanelInfo(stringResource(R.string.game_info_vulkan_surface_pairs, vulkanSurfaceFormats.candidateTypesLabel()))
                                GamePanelInfo(stringResource(R.string.game_info_vulkan_core_source_format, vulkanCoreSourceFormat.label))
                                GamePanelInfo(stringResource(when {
                                    coreHdrHandshake.hdr10Accepted -> R.string.game_info_hdr_core_accepts_pq
                                    coreHdrHandshake.hdr10Requested -> R.string.game_info_hdr_core_rejects_pq
                                    coreHdrHandshake.scrgbRequested -> R.string.game_info_hdr_core_rejects_scrgb
                                    else -> R.string.game_info_hdr_core_not_negotiated
                                }))
                                if (nativeTransfer != GameVulkanTransferMode.SDR) {
                                    GamePanelInfo(stringResource(if (nativeSourceContract.supports(nativeTransfer))
                                        R.string.game_info_vulkan_hdr_core_declared
                                    else R.string.game_info_vulkan_hdr_source_unverified))
                                    GamePanelInfo(stringResource(R.string.game_info_vulkan_hdr_mastering_unknown))
                                } else {
                                    GamePanelInfo(stringResource(R.string.game_info_vulkan_source_transfer_unknown))
                                }
                                val transferSupported = nativeSourceContract.supports(nativeTransfer) &&
                                    GameVulkanTransferGate.supported(
                                        nativeTransfer, displayHdr, vulkanSurfaceFormats,
                                    )
                                if (transferSupported) {
                                    val nativePhase = when (nativeSdrStatus.phase) {
                                        GameVulkanSdrStatus.Phase.OFF -> R.string.game_info_native_sdr_off
                                        GameVulkanSdrStatus.Phase.REQUESTED -> R.string.game_info_native_sdr_requested
                                        GameVulkanSdrStatus.Phase.SWAPCHAIN -> R.string.game_info_native_sdr_swapchain
                                        GameVulkanSdrStatus.Phase.PRESENTING -> R.string.game_info_native_sdr_presenting
                                        GameVulkanSdrStatus.Phase.GLES_FALLBACK -> R.string.game_info_native_sdr_fallback
                                    }
                                    GamePanelInfo(stringResource(nativePhase))
                                    GamePanelChoice(
                                        text = stringResource(if (nativeTransfer == GameVulkanTransferMode.SDR)
                                            R.string.game_info_native_sdr_test else R.string.game_info_native_hdr_test),
                                        subtitle = stringResource(if (nativeTransfer == GameVulkanTransferMode.SDR)
                                            R.string.game_info_native_sdr_test_hint else R.string.game_info_native_hdr_test_hint),
                                        selected = nativeSdrDiagnostic,
                                        enabled = !netplay.busy && !exiting,
                                        onClick = {
                                            nativeSdrDiagnostic = !nativeSdrDiagnostic
                                            if (!nativeSdrDiagnostic) nativeSdrFullscreenRequested = false
                                        },
                                    )
                                    val fullscreenAvailable = nativePictureSupported &&
                                        (nativeSlangPath == null || nativeSdrStatus.shaderPresenting) &&
                                        GameVulkanFullscreenPolicy.canEnable(
                                            session.vulkan, toneMap, nativeSdrDiagnostic,
                                            nativeSdrStatus, netplay.busy, displayHdr, vulkanSurfaceFormats,
                                        )
                                    if (nativeSlangPath != null) {
                                        GamePanelInfo(stringResource(if (nativeSdrStatus.shaderPresenting)
                                            R.string.games_vulkan_shader_presenting else R.string.games_vulkan_shader_pending))
                                    }
                                    if (!nativePictureSupported) {
                                        GamePanelInfo(stringResource(R.string.game_info_native_picture_requires_gles))
                                    }
                                    GamePanelChoice(
                                        text = stringResource(if (nativeTransfer == GameVulkanTransferMode.SDR)
                                            R.string.game_info_native_sdr_fullscreen else R.string.game_info_native_hdr_fullscreen),
                                        subtitle = stringResource(if (nativeTransfer == GameVulkanTransferMode.SDR)
                                            R.string.game_info_native_sdr_fullscreen_hint else R.string.game_info_native_hdr_fullscreen_hint),
                                        selected = nativeSdrFullscreenRequested,
                                        enabled = !exiting && (nativeSdrFullscreenRequested || fullscreenAvailable),
                                        onClick = {
                                            nativeSdrFullscreenRequested = !nativeSdrFullscreenRequested
                                        },
                                    )
                                } else if (nativeTransfer != GameVulkanTransferMode.SDR) {
                                    GamePanelInfo(stringResource(R.string.game_info_native_hdr_unavailable))
                                }
                            }
                            if (nativeSdrFullscreenRequested &&
                                nativeTransfer != GameVulkanTransferMode.SDR &&
                                GameVulkanTransferGate.frameConfirmed(nativeTransfer, nativeSdrStatus)) {
                                GamePanelInfo(stringResource(R.string.game_info_hdr_wsi_unverified))
                            } else {
                                GamePanelInfo(stringResource(R.string.game_info_hdr_output_inactive))
                            }
                        }
                        else -> {
                            GamePanelInfo(stringResource(R.string.game_info_system, system.name))
                            GamePanelInfo(stringResource(R.string.game_info_core, core.name, core.license))
                            GamePanelInfo(stringResource(R.string.game_info_file, game.file.name, game.sourceName))
                            if (game.remote != null) {
                                val remoteSync by model.remoteSync.collectAsState()
                                GamePanelInfo(stringResource(R.string.romm_info_data, stringResource(
                                    com.ambient.tv.ui.romm.rommSyncLabel(remoteSync[game.saveKey]))))
                            }
                            val bios = remember { model.biosStatus(game) }
                            bios.forEach { status ->
                                GamePanelInfo(stringResource(R.string.game_info_bios, status.bios.file, stringResource(status.state.titleRes())))
                            }
                            GamePanelInfo(stringResource(R.string.game_info_core_source))
                            // Renderer and HDR details are opt-in, like the video "Stats for nerds".
                            if (core.hardware && core.supportsVulkan) GamePanelChoice(
                                text = stringResource(R.string.game_info_diagnostics),
                                subtitle = stringResource(R.string.game_info_diagnostics_hint),
                                selected = false,
                                trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                onClick = gamePanels::openDiagnostics,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun GameBiosState.titleRes(): Int = when (this) {
    GameBiosState.PRESENT -> R.string.game_bios_present
    GameBiosState.OTHER_VERSION -> R.string.game_bios_other_version
    GameBiosState.MISSING -> R.string.game_bios_missing
}

/** Supporting text in a game panel: the shared hint, without a D-pad stop of its own. */
@Composable
internal fun GamePanelText(text: String) {
    com.ambient.tv.ui.common.SettingsHint(text)
}

/** A section heading, as the video panels use it. */
@Composable
internal fun GamePanelHeading(text: String) {
    com.ambient.tv.ui.common.RightPanelSectionTitle(text)
}

/** A read-only line the D-pad can land on, so long information panels scroll. */
@Composable
internal fun GamePanelInfo(text: String) {
    com.ambient.tv.ui.common.RightPanelInfoRow(text)
}

/** Who plays as which player: each device opens the shared select (automatic, players 1–4, off). */
@Composable
private fun GameControlsPanel() {
    val devices = rememberPlayableDevices()
    val config = RetroInputSettings.config
    GamePanelText(stringResource(R.string.game_controls_panel_hint))
    val choices = (listOf(RetroPorts.AUTO) + (0 until RetroPorts.COUNT) + RetroPorts.OFF).map { port ->
        port.toString() to when (port) {
            RetroPorts.AUTO -> stringResource(R.string.game_controls_port_auto)
            RetroPorts.OFF -> stringResource(R.string.game_controls_port_off)
            else -> stringResource(R.string.game_controls_port_player, port + 1)
        }
    }
    devices.forEach { device -> key(device.descriptor) {
        val current = config.ports[device.descriptor] ?: RetroPorts.AUTO
        com.ambient.tv.ui.common.PlayerDropUpSelector(
            label = device.name.ifBlank { stringResource(device.kind.titleRes) },
            value = choices.firstOrNull { it.first == current.toString() }?.second.orEmpty(),
            options = choices,
            selectedKey = current.toString(),
            dropUp = false,
            onSelected = { chosen ->
                chosen.toIntOrNull()?.let { port -> RetroInputSettings.update { it.withPort(device.descriptor, port) } }
            },
        )
    } }
}

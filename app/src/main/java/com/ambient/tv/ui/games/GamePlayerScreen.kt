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
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FastForward
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
import androidx.compose.ui.semantics.Role
import com.ambient.tv.games.GameBiosState
import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import com.ambient.tv.games.GameStateStore
import com.ambient.tv.ui.common.activateOnTvKey
import kotlinx.coroutines.launch
import java.io.File
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsBackupRestore
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.ambient.tv.games.GameOptionOrigin
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.GameShaderGroup
import com.ambient.tv.games.GameHdrToneMap
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.gamepad.GamepadInput
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
import com.ambient.tv.ui.player.PlayerChromeState
import com.ambient.tv.ui.player.PlayerControlsAutoHide
import com.ambient.tv.ui.player.PlayerOverlayVisibility
import com.ambient.tv.ui.player.PlayerTechBadgeRow
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerClock
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
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
                    GameFailure.BIOS_MISSING -> stringResource(R.string.game_failed_bios_missing_body, current.detail.orEmpty())
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
            is GameUiState.Ready -> key(current.session) { GameContent(current.session, model, onBack) }
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

internal fun GameShader.config(intensity: Float = 1f): ShaderConfig = when (this) {
    GameShader.SHARP -> ShaderConfig.Sharp
    GameShader.PIXEL -> ShaderConfig.Pixel
    GameShader.SMOOTH -> ShaderConfig.CUT3()
    GameShader.CUT2 -> ShaderConfig.CUT2()
    GameShader.BICUBIC -> GamePictureShaders.bicubic()
    GameShader.ADAPTIVE -> GamePictureShaders.adaptiveSharpen()
    GameShader.CRT -> ShaderConfig.CRT
    GameShader.LCD -> ShaderConfig.LCD
    else -> GameRetroShaders.config(this, intensity)
}

/** The Android key LibretroDroid reads as each RetroPad button (it maps by letter, not position). */
internal fun RetroPadButton.libretroDroidKey(): Int = when (this) {
    RetroPadButton.A -> KeyEvent.KEYCODE_BUTTON_A
    RetroPadButton.B -> KeyEvent.KEYCODE_BUTTON_B
    RetroPadButton.X -> KeyEvent.KEYCODE_BUTTON_X
    RetroPadButton.Y -> KeyEvent.KEYCODE_BUTTON_Y
    RetroPadButton.L -> KeyEvent.KEYCODE_BUTTON_L1
    RetroPadButton.R -> KeyEvent.KEYCODE_BUTTON_R1
    RetroPadButton.L2 -> KeyEvent.KEYCODE_BUTTON_L2
    RetroPadButton.R2 -> KeyEvent.KEYCODE_BUTTON_R2
    RetroPadButton.L3 -> KeyEvent.KEYCODE_BUTTON_THUMBL
    RetroPadButton.R3 -> KeyEvent.KEYCODE_BUTTON_THUMBR
    RetroPadButton.START -> KeyEvent.KEYCODE_BUTTON_START
    RetroPadButton.SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
    RetroPadButton.UP -> KeyEvent.KEYCODE_DPAD_UP
    RetroPadButton.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
    RetroPadButton.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
    RetroPadButton.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
}

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

private enum class GamePanel { SETTINGS, OSD_BUTTONS, PICTURE, CORE, DISC, CONTROLS, INFO, ACHIEVEMENTS, CHEATS, NETPLAY }

/** Rewind keeps this many seconds, within [REWIND_BUDGET_BYTES] of memory. */
private const val REWIND_SECONDS = 20
private const val REWIND_BUDGET_BYTES = 64L * 1024 * 1024
/** The category key of options without one, when others have one. */
private const val OTHER_CATEGORY = "\u0000other"

/** The game's current frame, scaled for save state previews; null when there is none yet. */
private suspend fun GLRetroView.preview(
    input: GameHdrToneMap,
    verifiedHdrSource: Boolean,
): Bitmap? = withContext(Dispatchers.Default) {
    val frame = runCatching { captureRawFrame() }.getOrNull() ?: return@withContext null
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
private fun GameContent(session: GameSession, model: GamePlayerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
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
    val shader = settings.shader(system, game.saveKey)
    val shaderIntensity = settings.shaderStrength(system, game.saveKey)
    val selectedPack = settings.packShader(system, game.saveKey)
    val gameShaderScope = settings.values.containsKey("shader.game." + game.saveKey)
    val installedShaders = remember(model) { model.shaderPacks.installed() }
    val selectedPackConfig = remember(selectedPack, model) { model.shaderPacks.config(selectedPack) }
    val toneMap = settings.hdrToneMap(core)
    val nativeTransfer = GameVulkanTransferMode.forInput(toneMap)
    val aspect = settings.aspect(system)
    val integerScaling = settings.integerScaling(system)
    val touchControls = settings.touchControls(system)
    val touchMode = settings.touchMode(system)
    val touchStyle = RetroInputSettings.config.touchStyle
    val touchVariant = RetroInputSettings.config.touchVariants[system.id].orEmpty()
    val touchPresentation = remember(system.id, touchVariant) { gameTouchPresentation(system.id, touchVariant) }
    var hidingGamepadId by remember(game.saveKey) { mutableStateOf<Int?>(null) }
    val hideTouchForGamepad by rememberUpdatedState(touchStyle.hideOnGamepadInput)
    // A trusted small Slang preset may opt into the experimental native WSI.
    // Existing GLES filters remain available for all other choices and for HDR.
    val nativeSlangPath = remember(context, shader, shaderIntensity, selectedPack, nativeTransfer, session.vulkan) {
        // Bundled native presets currently use their author's default parameter values.
        // A user-adjusted intensity must keep using GLES until native per-parameter
        // parity is implemented; never silently display the wrong chosen appearance.
        if (BuildConfig.LIBRASHADER_NATIVE && session.vulkan &&
            nativeTransfer == GameVulkanTransferMode.SDR &&
            kotlin.math.abs(shaderIntensity - 1f) < 0.0001f)
            GameVulkanSlangPresets.prepare(context, shader, selectedPack)
        else null
    }
    val nativePictureSupported = selectedPack == null &&
        (GameVulkanPictureCompatibility.rawFullscreenEligible(shader) || nativeSlangPath != null)
    val saveStates by model.saveStates.collectAsState()

    val chrome = remember { PlayerChromeState() }
    val osd = chrome.osd
    var menuOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<GamePanel?>(null) }
    var panelFromSettings by remember { mutableStateOf(false) }
    // A display or refresh-mode change can remove HDR while a Vulkan game is
    // running. Never infer active HDR from these platform capabilities.
    var displayHdr by remember(context) {
        mutableStateOf(GameHdrDisplayProbe.capture(context))
    }
    DisposableEffect(context) {
        val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                displayHdr = GameHdrDisplayProbe.capture(context)
            }
            override fun onDisplayRemoved(displayId: Int) {
                displayHdr = GameHdrDisplayProbe.capture(context)
            }
            override fun onDisplayChanged(displayId: Int) {
                displayHdr = GameHdrDisplayProbe.capture(context)
            }
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    // Also re-query when opening Info; some vendors do not deliver callbacks
    // for every HDMI EOTF switch.
    LaunchedEffect(panel) {
        if (panel == GamePanel.INFO) displayHdr = GameHdrDisplayProbe.capture(context)
    }
    var optionCategory by remember { mutableStateOf<String?>(null) }
    var shaderGroup by remember { mutableStateOf<GameShaderGroup?>(null) }
    var shaderPacksOpen by remember { mutableStateOf(false) }
    var optionKey by remember { mutableStateOf<String?>(null) }
    /** Core option changes go to this game only (true) or to every game of the core. */
    var gameScope by remember { mutableStateOf(settings.gameCoreOptions(core, game.saveKey).isNotEmpty()) }
    var statesOpen by remember { mutableStateOf(false) }
    var activityResumed by remember { mutableStateOf(activityLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var exiting by remember { mutableStateOf(false) }
    var destroyed by remember { mutableStateOf(false) }
    /** The core has loaded the game (its surface exists); before that nothing may step it. */
    var coreReady by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
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
    var fastForward by remember { mutableStateOf(false) }
    var rewinding by remember { mutableStateOf(false) }
    var discCount by remember { mutableIntStateOf(0) }
    var currentDisc by remember { mutableIntStateOf(0) }
    /** The picture when the menu opened: preview for every state saved from this menu. */
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    val achievements = remember { GameAchievements(model.retroAchievements, scope) }
    /** The input layer (created below); netplay reads the local player's buttons from it. */
    val inputRef = remember { java.util.concurrent.atomic.AtomicReference<RetroPadInput?>(null) }
    val netplay = remember {
        GameNetplay(context.applicationContext, scope) {
            inputRef.get()?.let { pad ->
                NetplayInput(pad.buttons(0), pad.analog(0, 0, 0), pad.analog(0, 0, 1), pad.analog(0, 1, 0), pad.analog(0, 1, 1))
            } ?: NetplayInput.NONE
        }
    }
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
            this.shader = GameHdrToneMapShader.forConfig(
                selectedPackConfig ?: shader.config(shaderIntensity),
                toneMap, session.vulkan, pixel = selectedPack == null && shader == GameShader.PIXEL,
            )
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
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
            this.integerScaling = integerScaling
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
    LaunchedEffect(nativeSlangPath, vulkanSurfaceHost) {
        vulkanSurfaceHost?.updateShaderPreset(nativeSlangPath)
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

    /** Cheats on (enabled ones) or all off: hardcore and netplay play without. */
    fun applyCheats(list: List<GameCheat>) {
        if (!loaded || destroyed) return
        runCatching {
            retroView.resetCheat()
            if (core.cheats && !achievements.hardcore && !netplay.active) {
                list.filter { it.enabled }.forEachIndexed { index, cheat -> retroView.setCheat(index, true, cheat.code) }
            }
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
            destroy()
            onBack()
        }
    }
    val resume: () -> Unit = {
        panel = null
        panelFromSettings = false
        optionKey = null
        optionCategory = null
        shaderGroup = null
        shaderPacksOpen = false
        statesOpen = false
        menuOpen = false
        osd.close()
    }
    val openMenu: () -> Unit = {
        if (!menuOpen) {
            // Pause at once; the preview is the frame the player saw last.
            retroView.pauseEmulation()
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
    /** Loads [entry]; the moment before is kept as "Before loading" so the step can be undone. */
    val loadState: (GameStateEntry) -> Unit = loadState@ { entry ->
        if (stateBusy || destroyed || exiting) return@loadState
        if (!stateAvailable || achievements.hardcore || netplay.active) {
            message = context.getString(
                when {
                    achievements.hardcore -> R.string.game_message_hardcore_blocked
                    netplay.active -> R.string.game_message_netplay_blocked
                    else -> R.string.game_message_load_failed
                },
            )
        } else {
            stateBusy = true
            message = context.getString(R.string.game_state_working)
            val preview = snapshot
            scope.launch {
                try {
                    val compatible = withContext(Dispatchers.IO) {
                        GameStateStore.compatible(entry, core.id, session.coreStamp,
                            GameStateStore.legacyCoreStamp(session.coreFile))
                    }
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
                    if (destroyed || exiting || achievements.hardcore || netplay.active) return@launch
                    val restored = withContext(Dispatchers.Default) {
                        runCatching {
                            retroView.unserializePersistedState(bytes).also { if (it) retroView.clearRewindBuffer() }
                        }.getOrDefault(false)
                    }
                    if (restored) { message = null; resume() }
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
        if (!stateBusy && !destroyed && !exiting) {
            osd.interact()
            stateBusy = true
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

    val running = coreReady && activityResumed && !menuOpen && panel == null && !exiting && !destroyed && !stateBusy
    LaunchedEffect(running) {
        retroView.suppressAutoResume = !running
        if (running) retroView.resumeEmulation() else retroView.pauseEmulation()
    }
    LaunchedEffect(nativePictureSupported, nativeSdrFullscreenRequested, vulkanSurfaceHost) {
        if (!nativePictureSupported && nativeSdrFullscreenRequested) {
            nativeSdrFullscreenRequested = false
            vulkanSurfaceHost?.setFullscreenRequested(false)
        }
    }
    LaunchedEffect(nativeSdrFullscreenRequested, running, vulkanSurfaceHost) {
        if (!nativeSdrFullscreenRequested) {
            vulkanSurfaceHost?.setFullscreenRequested(false)
        } else if (running) {
            // Arming from the paused Info panel does not cover the game with a
            // blank Surface; promotion waits until normal playback resumes.
            vulkanSurfaceHost?.setFullscreenRequested(true)
        }
    }
    LaunchedEffect(fastForward) { retroView.frameSpeed = if (fastForward) 2 else 1 }
    LaunchedEffect(rewinding, running) {
        retroView.isRewinding = core.rewind && serializationPolicy.frameExact && rewinding && running && !achievements.hardcore && !netplay.active
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
        var renderedFrames = 0
        var initializationFrames = 0
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
            if (core.rewind && serializationPolicy.frameExact && GameSettings.state.rewind && !hardcoreRequested) {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val fps = LibretroDroid.getContentFps().takeIf { it in 1.0..240.0 } ?: 60.0
                        retroView.initRewindBuffer((REWIND_SECONDS * fps).roundToInt(), REWIND_BUDGET_BYTES)
                        retroView.rewindEnabled = true
                    }
                }
            }
        }
        retroView.getGLRetroEvents().collect { event ->
            val statusInterval = if (nativeSdrFullscreenRequested) 30 else 120
            if (event == GLRetroView.GLRetroEvents.FrameRendered &&
                session.vulkan && renderedFrames++ % statusInterval == 0) {
                // One atomic native status read per ~2 seconds at 60 FPS. A driver may switch to
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
            if (event == GLRetroView.GLRetroEvents.SurfaceCreated) coreReady = true
            if (event == GLRetroView.GLRetroEvents.FrameRendered && !loaded) {
                loaded = true
                model.firstFrameRendered(session)
                refreshStateSupport()
                withContext(Dispatchers.Default) { runCatching { retroView.getVariables().mapNotNull { v -> v.key?.let { it to v.description } } } }
                    .onSuccess { model.saveDeclaredOptions(core, it) }
                (0 until RetroPorts.COUNT).forEach { port ->
                    GameSettings.state.controllerType(core, port)?.let { type -> runCatching { retroView.setControllerType(port, type) } }
                }
                applyCheats(cheats)
                achievements.start(retroView, game, !game.direct && core.achievements && GameSettings.state.achievements, hardcoreRequested)
            } else if (event == GLRetroView.GLRetroEvents.FrameRendered && loaded &&
                !stateAvailable && core.saveStates && serializationPolicy.persistentStates &&
                serializationPolicy.mustInitialize && ++initializationFrames % 60 == 0) {
                // Some cores cannot serialize on frame one. Probe at most once per 60 rendered
                // frames, off main, rather than permanently disabling saves after the first miss.
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
    val latestOpenMenu by rememberUpdatedState(openMenu)
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
            onRewind = { held -> rewinding = held && core.rewind && stateAvailable && serializationPolicy.frameExact },
            onFastForward = { if (!netplay.active) fastForward = !fastForward },
        ).also(inputRef::set)
    }
    // Only changed pointer contacts go through JNI; the other device types do not receive
    // redundant six-call resets on each 60Hz phone position update.
    val companionPointerRelay = remember(input) {
        com.ambient.tv.companion.CompanionNativePointerRelay { port, kind, index, x, y, flags ->
            LibretroDroid.onCompanionPointer(port, kind, index, x, y, flags)
        }
    }
    val companionInputKinds = remember(input) { mutableMapOf<Int, String>() }
    val companionNetplayActive by rememberUpdatedState(netplay.active)
    // The Companion gamepad uses the same RetroPadInput as real controllers, without injecting
    // synthetic Android keys into the activity. This is a TV-only, full game-session registration.
    val latestCompanionMenu by rememberUpdatedState<() -> Unit>({
        if (coreReady && !destroyed && !exiting) {
            if (menuOpen) resume() else openMenu()
        }
    })
    DisposableEffect(input, handheld, system.id, core.id, game.saveKey) {
        if (!handheld) com.ambient.tv.companion.CompanionGamepadSession.attach(
            input, system.id, core.id, game.file.nameWithoutExtension, system.pointerInput,
            onMenu = { latestCompanionMenu() },
            onPointer = { port, pointer ->
                val kind = pointer?.kind
                val previous = companionInputKinds[port]
                if (kind != previous && !companionNetplayActive && coreReady && !destroyed &&
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
    LaunchedEffect(input, handheld, running) {
        if (!handheld) com.ambient.tv.companion.CompanionGamepadSession.setReady(input, running)
    }
    // Vibration goes to the controllers of the player the core addresses.
    val runningNow by rememberUpdatedState(running)
    LaunchedEffect(Unit) {
        if (core.hardware && settings.vulkan(core) && !session.vulkan) message = context.getString(R.string.game_vulkan_fallback)
    }
    LaunchedEffect(retroView) {
        retroView.getRumbleEvents().collect { event ->
            if (GameSettings.state.rumble && runningNow) {
                input.deviceIds(event.port).forEach { GameRumble.apply(it, event.strengthWeak, event.strengthStrong) }
            }
        }
    }
    LaunchedEffect(running) {
        if (!running) GameRumble.stopAll()
    }
    // Hardcore (once the session confirms it) plays without cheats and rewind.
    LaunchedEffect(achievements.state) {
        applyCheats(cheats)
        if (achievements.hardcore) runCatching { retroView.rewindEnabled = false }
    }
    // Playing together: the menu closes on both sides and the game runs.
    LaunchedEffect(netplay.state) {
        if (netplay.state is NetplayState.Connected) {
            fastForward = false
            rewinding = false
            applyCheats(cheats)
            resume()
        } else if (loaded && !destroyed) {
            // A netplay session temporarily disables rewind and cheats. Restore both on
            // disconnect, rejected connection, timeout or cancel, without restarting a game.
            applyCheats(cheats)
            runCatching {
                retroView.rewindEnabled =
                    core.rewind && stateAvailable && serializationPolicy.frameExact && settings.rewind && !achievements.hardcore
            }
            // Host negotiation pauses the core before exchanging its state. A failed
            // handshake must not leave an otherwise running single-player game frozen.
            if (running) runCatching { retroView.resumeEmulation() }
        }
    }
    DisposableEffect(input, retroView) {
        val sentButtons = IntArray(RetroPorts.COUNT)
        val sentAnalog = IntArray(RetroPorts.COUNT * 4)
        input.onChanged = {
            for (port in 0 until RetroPorts.COUNT) {
                val buttons = input.buttons(port)
                val changed = buttons xor sentButtons[port]
                if (changed != 0) {
                    RetroPadButton.entries.forEach { button ->
                        if (changed and button.mask != 0) {
                            val action = if (buttons and button.mask != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                            retroView.sendKeyEvent(action, button.libretroDroidKey(), port)
                        }
                    }
                    sentButtons[port] = buttons
                }
                for (index in 0..1) {
                    val x = input.analog(port, index, 0)
                    val y = input.analog(port, index, 1)
                    val at = port * 4 + index * 2
                    if (x != sentAnalog[at] || y != sentAnalog[at + 1]) {
                        sentAnalog[at] = x
                        sentAnalog[at + 1] = y
                        retroView.sendMotionEvent(
                            if (index == 0) GLRetroView.MOTION_SOURCE_ANALOG_LEFT else GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                            x / RetroPadInput.ANALOG_MAX.toFloat(), y / RetroPadInput.ANALOG_MAX.toFloat(), port,
                        )
                    }
                }
            }
        }
        val onRemoved: (Int) -> Unit = { id ->
            input.deviceRemoved(id)
            if (hidingGamepadId == id) hidingGamepadId = null
        }
        GamepadInput.deviceRemovedSink = onRemoved
        onDispose {
            input.onChanged = null
            if (GamepadInput.deviceRemovedSink === onRemoved) GamepadInput.deviceRemovedSink = null
            GameRumble.stopAll()
        }
    }
    DisposableEffect(running, input) {
        val sink: (android.view.InputEvent) -> Boolean = { event ->
            val handled = input.onEvent(event)
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
    LaunchedEffect(menuOpen, panel, statesOpen) {
        if (menuOpen && panel == null && !statesOpen) {
            withFrameNanos { }
            runCatching { playRequester.requestFocus() }
        }
    }
    // Back while playing (a device whose Back does not play) opens the menu; in the menu it continues.
    BackHandler(enabled = panel == null && !statesOpen) { if (menuOpen) resume() else openMenu() }

    val closePanel: () -> Unit = {
        panel = null
        panelFromSettings = false
        optionKey = null
        optionCategory = null
        shaderGroup = null
        shaderPacksOpen = false
    }

    CompositionLocalProvider(
        LocalPlayerChromeState provides chrome,
        LocalPlayerControlFocus provides chrome.focus,
    ) {
        // The game is paused while its menu shows, so the menu never hides on its own.
        PlayerControlsAutoHide(
            visible = osd.visible.value,
            playing = false,
            panelOpen = panel != null,
            onVisibilityChanged = { osd.visible.value = it },
        )
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (handheld) AmbientSurfaceBackdropLayer(
                surface = vulkanSurfaceHost?.presentationSurface() ?: retroView,
                enabled = true,
                landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                modifier = Modifier.fillMaxSize(),
                sampling = running,
                reducedEffects = LocalPerformanceProfile.current.reducedEffects ||
                    LocalPerformanceProfile.current.reducedPlayback,
            )
            GamePlayerTouchLayout(
                handheld = handheld,
                directPointer = system.pointerInput,
                touchEnabled = touchControls,
                touchMode = touchMode,
                touchStyle = touchStyle,
                presentation = touchPresentation,
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
            GameAchievementBanner(achievements.banner, Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
            if (netplay.waiting && !menuOpen) GameOverlayNote(
                stringResource(R.string.game_netplay_waiting),
                Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            )

            PlayerOverlayVisibility(visible = osd.visible.value && panel == null) {
                ReferencePlayerOverlayBackground()
                ReferencePlayerClock(modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 48.dp))
                ReferencePlayerBottomContent(onTogglePlayback = resume) {
                    ReferencePlayerHeader(
                        title = game.title,
                        artworkUrl = null,
                        contextLine = system.name,
                        subtitle = message ?: stringResource(if (running) R.string.game_running else R.string.game_paused),
                        showPaused = !running,
                    )
                    PlayerTechBadgeRow {
                        MetaBadge("libretro", emphasized = true)
                        MetaBadge(core.name)
                        if (game.direct) MetaBadge(stringResource(R.string.game_direct_source))
                        if (core.hardware) MetaBadge(vulkanFrameStatus.badge(session.vulkan))
                        if (nativeSdrFullscreenRequested &&
                            GameVulkanTransferGate.frameConfirmed(nativeTransfer, nativeSdrStatus)) {
                            MetaBadge(when (nativeTransfer) {
                                GameVulkanTransferMode.SDR -> "Vulkan SDR · Test"
                                GameVulkanTransferMode.PQ_BT2020 -> "Vulkan PQ WSI · Test"
                                GameVulkanTransferMode.HLG_BT2020 -> "Vulkan HLG WSI · Test"
                            })
                        }
                        if (session.vulkan && vulkanFrameStatus.active && toneMap != GameHdrToneMap.OFF &&
                            !(nativeSdrFullscreenRequested &&
                                GameVulkanTransferGate.frameConfirmed(nativeTransfer, nativeSdrStatus))) {
                            MetaBadge("HDR → SDR")
                        } else {
                            MetaBadge(stringResource(shader.titleRes))
                        }
                        if (aspect != GameAspect.CORE) MetaBadge(aspect.key)
                        if (fastForward) MetaBadge("2×")
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
                    GameStateStrip(
                        open = statesOpen,
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
                        onClose = {
                            statesOpen = false
                            runCatching { statesRequester.requestFocus() }
                        },
                    )
                    PlayerControlBar(
                        player = OsdPlayer.GAMES,
                        leftContent = {
                            PlayerIconButton(Icons.Filled.PlayArrow, stringResource(R.string.game_continue), resume,
                                modifier = Modifier.focusRequester(playRequester), primary = true)
                            PlayerIconButton(Icons.Filled.Save, stringResource(R.string.game_save_state), saveManualState,
                                enabled = loaded && stateAvailable && !stateBusy, osd = OsdButton.GAME_SAVE)
                            PlayerIconButton(Icons.Filled.SettingsBackupRestore, stringResource(R.string.game_quick_load), {
                                model.latestManualState(game)?.let(loadState)
                            }, enabled = loaded && stateAvailable && !stateBusy && !achievements.hardcore && !netplay.active && saveStates.any { it.kind == GameStateKind.MANUAL },
                                osd = OsdButton.GAME_LOAD)
                            PlayerIconButton(Icons.Filled.Bookmarks, stringResource(R.string.game_states), {
                                osd.interact()
                                statesOpen = !statesOpen
                            }, modifier = Modifier.focusRequester(statesRequester),
                                selected = statesOpen, enabled = loaded && stateAvailable && saveStates.isNotEmpty(),
                                osd = OsdButton.GAME_STATES)
                            PlayerIconButton(Icons.Filled.RestartAlt, stringResource(R.string.game_reset), {
                                runCatching { retroView.reset() }
                                runCatching { retroView.clearRewindBuffer() }
                                model.clearAutoState(game)
                                resume()
                            }, enabled = loaded && !netplay.active && !stateBusy, osd = OsdButton.GAME_RESET)
                            PlayerIconButton(Icons.Filled.FastForward, stringResource(R.string.game_fast_forward), {
                                fastForward = !fastForward
                            }, selected = fastForward, enabled = loaded && !netplay.active,
                                osd = OsdButton.GAME_FAST_FORWARD)
                        },
                        rightContent = {
                            // Same order as video: exit, playback options, information, settings.
                            PlayerExitButton(exit)
                            PlayerIconButton(Icons.Filled.Wallpaper, stringResource(R.string.game_panel_picture), {
                                panel = GamePanel.PICTURE
                            }, selected = panel == GamePanel.PICTURE, osd = OsdButton.GAME_PICTURE)
                            PlayerIconButton(Icons.Filled.Tune, stringResource(R.string.game_panel_core), {
                                optionsVersion++
                                panel = GamePanel.CORE
                            }, selected = panel == GamePanel.CORE, enabled = loaded && !netplay.active,
                                osd = OsdButton.GAME_CORE)
                            PlayerIconButton(Icons.Filled.SportsEsports, stringResource(R.string.game_panel_controls), {
                                panel = GamePanel.CONTROLS
                            }, selected = panel == GamePanel.CONTROLS, osd = OsdButton.GAME_CONTROLS)
                            if (achievements.state != GameAchievementState.Off) PlayerIconButton(
                                Icons.Filled.EmojiEvents, stringResource(R.string.game_panel_achievements), {
                                    panel = GamePanel.ACHIEVEMENTS
                                }, selected = panel == GamePanel.ACHIEVEMENTS, osd = OsdButton.GAME_ACHIEVEMENTS)
                            PlayerIconButton(Icons.Filled.Code, stringResource(R.string.game_panel_cheats), {
                                panel = GamePanel.CHEATS
                            }, selected = panel == GamePanel.CHEATS, enabled = loaded && core.cheats && !achievements.hardcore,
                                osd = OsdButton.GAME_CHEATS)
                            PlayerIconButton(Icons.Filled.Groups, stringResource(R.string.game_panel_netplay), {
                                panel = GamePanel.NETPLAY
                            }, selected = panel == GamePanel.NETPLAY, enabled = loaded && stateAvailable && serializationPolicy.frameExact && core.netplay && !game.direct && !stateBusy,
                                osd = OsdButton.GAME_NETPLAY)
                            if (discCount > 1) PlayerIconButton(Icons.Filled.Album, stringResource(R.string.game_panel_disc), {
                                panel = GamePanel.DISC
                            }, selected = panel == GamePanel.DISC, enabled = !netplay.active,
                                osd = OsdButton.GAME_DISC)
                            PlayerIconButton(Icons.Filled.Info, stringResource(R.string.game_panel_info), {
                                panel = GamePanel.INFO
                            }, selected = panel == GamePanel.INFO, osd = OsdButton.GAME_INFO)
                            // Never hide this recovery path: every optional panel remains reachable here.
                            PlayerIconButton(Icons.Filled.Settings, stringResource(R.string.player_playback_settings), {
                                panelFromSettings = false
                                panel = GamePanel.SETTINGS
                            }, selected = panel != null)
                        },
                    )
                }
            }

            val open = panel
            if (open != null) GamePanelOverlay(
                open, optionKey, optionCategory, shaderGroup, shaderPacksOpen,
                onOutsideTap = closePanel,
            ) {
                val options = remember(optionsVersion, open) {
                    if (open == GamePanel.CORE && loaded && !destroyed) runCatching {
                        retroView.getVariables()
                            .mapNotNull { GameCoreOption.parse(it.key ?: return@mapNotNull null, it.description, it.value) }
                            .filter { it.visible }
                    }.getOrDefault(emptyList()) else emptyList()
                }
                val categorized = options.any { it.category != null }
                fun categoryOf(item: GameCoreOption) = item.category?.key ?: OTHER_CATEGORY
                val option = options.firstOrNull { it.key == optionKey }
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
                @Composable
                fun originLabel(item: GameCoreOption): String = when (settings.coreOptionOrigin(core, game.saveKey, item.key)) {
                    GameOptionOrigin.GAME -> stringResource(R.string.game_core_origin_game, item.label)
                    GameOptionOrigin.CORE -> stringResource(R.string.game_core_origin_core, item.label)
                    GameOptionOrigin.DEFAULT -> item.label
                }
                RightSidePanel(
                    title = when {
                        option != null -> option.title
                        open == GamePanel.SETTINGS -> stringResource(R.string.player_playback_settings)
                        open == GamePanel.OSD_BUTTONS -> stringResource(R.string.osd_buttons)
                        open == GamePanel.CORE && category != null -> categoryTitle.orEmpty()
                        open == GamePanel.PICTURE && shaderPacksOpen -> stringResource(R.string.games_shader_packs_title)
                        open == GamePanel.PICTURE && shaderGroup != null -> stringResource(shaderGroup!!.titleRes)
                        open == GamePanel.PICTURE -> stringResource(R.string.game_panel_picture)
                        open == GamePanel.CORE -> stringResource(R.string.game_panel_core)
                        open == GamePanel.DISC -> stringResource(R.string.game_panel_disc)
                        open == GamePanel.CONTROLS -> stringResource(R.string.game_panel_controls)
                        open == GamePanel.ACHIEVEMENTS -> stringResource(R.string.game_panel_achievements)
                        open == GamePanel.CHEATS -> stringResource(R.string.game_panel_cheats)
                        open == GamePanel.NETPLAY -> stringResource(R.string.game_panel_netplay)
                        else -> stringResource(R.string.game_panel_info)
                    },
                    subtitle = when {
                        open == GamePanel.PICTURE -> stringResource(R.string.game_picture_scope, system.name)
                        open == GamePanel.CORE && option == null -> if (gameScope) {
                            stringResource(R.string.game_core_scope_game)
                        } else stringResource(R.string.game_core_scope_core, core.name)
                        else -> null
                    },
                    onBack = {
                        when {
                            option != null -> optionKey = null
                            category != null -> optionCategory = null
                            shaderGroup != null -> shaderGroup = null
                            shaderPacksOpen -> shaderPacksOpen = false
                            panelFromSettings -> { panelFromSettings = false; panel = GamePanel.SETTINGS }
                            else -> closePanel()
                        }
                    },
                    closeInsteadOfBack = option == null && category == null && shaderGroup == null && !shaderPacksOpen && !panelFromSettings,
                ) {
                    when {
                        open == GamePanel.SETTINGS -> {
                            val offered = gameOsdButtons(achievements.state != GameAchievementState.Off, discCount > 1)
                            val hidden = osdHiddenButtons()
                            fun openSettingsPanel(target: GamePanel) {
                                if (target == GamePanel.CORE) optionsVersion++
                                panelFromSettings = true
                                panel = target
                            }
                            RightPanelChoice(
                                text = stringResource(R.string.osd_buttons),
                                subtitle = stringResource(R.string.osd_buttons_summary,
                                    offered.count { osdButtonVisible(it, OsdPlayer.GAMES, hidden) }, offered.size),
                                icon = Icons.Filled.ViewWeek,
                                selected = false,
                                onClick = { openSettingsPanel(GamePanel.OSD_BUTTONS) },
                            )
                            RightPanelChoice(stringResource(R.string.game_panel_picture), selected = false,
                                icon = Icons.Filled.Wallpaper, onClick = { openSettingsPanel(GamePanel.PICTURE) })
                            RightPanelChoice(stringResource(R.string.game_panel_core), selected = false,
                                icon = Icons.Filled.Tune, enabled = loaded && !netplay.active,
                                onClick = { openSettingsPanel(GamePanel.CORE) })
                            RightPanelChoice(stringResource(R.string.game_panel_controls), selected = false,
                                icon = Icons.Filled.SportsEsports, onClick = { openSettingsPanel(GamePanel.CONTROLS) })
                            if (achievements.state != GameAchievementState.Off) RightPanelChoice(
                                stringResource(R.string.game_panel_achievements), selected = false,
                                icon = Icons.Filled.EmojiEvents, onClick = { openSettingsPanel(GamePanel.ACHIEVEMENTS) })
                            RightPanelChoice(stringResource(R.string.game_panel_cheats), selected = false,
                                icon = Icons.Filled.Code, enabled = loaded && core.cheats && !achievements.hardcore,
                                onClick = { openSettingsPanel(GamePanel.CHEATS) })
                            RightPanelChoice(stringResource(R.string.game_panel_netplay), selected = false,
                                icon = Icons.Filled.Groups, enabled = loaded && stateAvailable && serializationPolicy.frameExact && core.netplay && !game.direct && !stateBusy,
                                onClick = { openSettingsPanel(GamePanel.NETPLAY) })
                            if (discCount > 1) RightPanelChoice(stringResource(R.string.game_panel_disc), selected = false,
                                icon = Icons.Filled.Album, enabled = !netplay.active,
                                onClick = { openSettingsPanel(GamePanel.DISC) })
                            RightPanelChoice(stringResource(R.string.game_panel_info), selected = false,
                                icon = Icons.Filled.Info, onClick = { openSettingsPanel(GamePanel.INFO) })
                        }
                        open == GamePanel.OSD_BUTTONS -> PlayerOsdButtonPanelControls(
                            hasVideo = true,
                            player = OsdPlayer.GAMES,
                            offered = gameOsdButtons(achievements.state != GameAchievementState.Off, discCount > 1),
                        )
                        option != null -> {
                            if (option.info.isNotBlank()) GamePanelText(option.info)
                            option.values.forEach { choice ->
                                RightPanelChoice(
                                    text = choice.label,
                                    subtitle = if (choice.value == option.default) stringResource(R.string.game_core_value_default) else null,
                                    selected = choice.value == option.value,
                                    onClick = {
                                        runCatching { retroView.updateVariables(Variable(option.key, choice.value)) }
                                        GameSettings.update {
                                            if (gameScope) it.withGameCoreOption(core, game.saveKey, option.key, choice.value)
                                            else it.withCoreOption(core, option.key, choice.value, gameKey = game.saveKey)
                                        }
                                        optionsVersion++
                                        optionKey = null
                                    },
                                )
                            }
                        }
                        open == GamePanel.PICTURE && shaderPacksOpen -> {
                            if (installedShaders.isEmpty()) GamePanelText(stringResource(R.string.games_shader_packs_empty))
                            installedShaders.forEach { preset ->
                                RightPanelChoice(
                                    text = preset.title,
                                    subtitle = preset.packTitle + " · " + preset.license,
                                    selected = selectedPack == preset.selection,
                                    onClick = {
                                        val verified = model.shaderPacks.config(preset.selection)
                                        if (verified != null) {
                                            retroView.shader = GameHdrToneMapShader.forConfig(
                                                verified, toneMap, session.vulkan,
                                            )
                                            GameSettings.update {
                                                it.withPackShader(system, game.saveKey.takeIf { gameShaderScope }, preset.selection)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        open == GamePanel.PICTURE && shaderGroup != null -> {
                            GameShader.entries.filter { it.group == shaderGroup }.forEach { choice ->
                                RightPanelChoice(
                                    text = stringResource(choice.titleRes),
                                    subtitle = stringResource(choice.hintRes),
                                    selected = selectedPack == null && choice == shader,
                                    onClick = {
                                        retroView.shader = GameHdrToneMapShader.forGame(choice, toneMap, session.vulkan, shaderIntensity)
                                        GameSettings.update {
                                            if (gameShaderScope) it.withGameShader(game.saveKey, choice)
                                            else it.withShader(system, choice)
                                        }
                                    },
                                )
                            }
                        }
                        open == GamePanel.PICTURE -> {
                            if (session.vulkan && (nativeSdrDiagnostic || nativeSdrFullscreenRequested) && !nativePictureSupported) {
                                GamePanelText(stringResource(R.string.game_picture_native_limit))
                            }
                            if (session.vulkan && toneMap != GameHdrToneMap.OFF) {
                                GamePanelText(stringResource(R.string.game_picture_hdr_filter_note))
                            }
                            if (selectedPack != null && selectedPackConfig == null) {
                                GamePanelText(stringResource(R.string.games_shader_packs_missing))
                            }
                            GamePanelText(stringResource(R.string.game_picture_filter))
                            RightPanelChoice(
                                text = stringResource(R.string.game_shader_scope_game),
                                subtitle = stringResource(R.string.game_shader_scope_system),
                                selected = gameShaderScope,
                                onClick = {
                                    if (gameShaderScope) {
                                        val systemShader = settings.shader(system)
                                        val systemStrength = settings.shaderStrength(system)
                                        val systemPack = settings.packShader(system)
                                        GameSettings.update {
                                            it.withGameShader(game.saveKey, null)
                                                .withShaderStrength(system, game.saveKey, 1f)
                                        }
                                        retroView.shader = GameHdrToneMapShader.forConfig(
                                            model.shaderPacks.config(systemPack) ?: systemShader.config(systemStrength),
                                            toneMap, session.vulkan, pixel = systemPack == null && systemShader == GameShader.PIXEL,
                                        )
                                    } else {
                                        GameSettings.update {
                                            if (selectedPack != null) it.withPackShader(system, game.saveKey, selectedPack)
                                            else it.withGameShader(game.saveKey, shader)
                                        }
                                    }
                                },
                            )
                            if (installedShaders.isNotEmpty()) RightPanelChoice(
                                text = stringResource(R.string.games_shader_packs_title),
                                subtitle = installedShaders.firstOrNull { it.selection == selectedPack }?.title,
                                selected = selectedPack != null,
                                onClick = { shaderPacksOpen = true },
                            )
                            GameShaderGroup.entries.forEach { group ->
                                RightPanelChoice(
                                    text = stringResource(group.titleRes),
                                    subtitle = GameShader.entries.firstOrNull { it == shader && it.group == group }
                                        ?.let { stringResource(it.titleRes) },
                                    selected = selectedPack == null && shader.group == group,
                                    onClick = { shaderGroup = group },
                                )
                            }
                            if (selectedPack == null && GameRetroShaders.isCustom(shader)) RightPanelChoice(
                                text = stringResource(R.string.game_shader_strength),
                                subtitle = stringResource(R.string.game_shader_strength_value, (shaderIntensity * 100).toInt()),
                                selected = false,
                                onClick = {
                                    val levels = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)
                                    val index = levels.indexOfFirst { kotlin.math.abs(it - shaderIntensity) < 0.01f }
                                    val next = levels[(index + 1).coerceAtLeast(0) % levels.size]
                                    retroView.shader = GameHdrToneMapShader.forGame(shader, toneMap, session.vulkan, next)
                                    GameSettings.update {
                                        it.withShaderStrength(system, game.saveKey.takeIf { gameShaderScope }, next)
                                    }
                                },
                            )
                            GamePanelText(stringResource(R.string.game_picture_shape))
                            GameAspect.entries.forEach { choice ->
                                RightPanelChoice(
                                    text = stringResource(choice.titleRes),
                                    selected = choice == aspect,
                                    onClick = {
                                        retroView.aspectRatioOverride = choice.ratio ?: -1f
                                        GameSettings.update { it.withAspect(system, choice) }
                                    },
                                )
                            }
                            RightPanelChoice(
                                text = stringResource(R.string.game_picture_integer),
                                subtitle = stringResource(R.string.game_picture_integer_hint),
                                selected = integerScaling,
                                onClick = {
                                    retroView.integerScaling = !integerScaling
                                    GameSettings.update { it.withIntegerScaling(system, !integerScaling) }
                                },
                            )
                            if (session.vulkan && core.hardware && core.supportsVulkan) {
                                GamePanelText(stringResource(R.string.game_picture_hdr))
                                GameHdrToneMap.entries.forEach { choice ->
                                    RightPanelChoice(
                                        text = stringResource(choice.titleRes),
                                        subtitle = if (choice == GameHdrToneMap.OFF) null
                                            else stringResource(R.string.game_picture_hdr_hint),
                                        selected = toneMap == choice,
                                        enabled = !netplay.active && !exiting,
                                        onClick = {
                                            if (choice != toneMap && !exiting && !netplay.active) {
                                                GameSettings.update { it.withHdrToneMap(core, choice) }
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
                            if (core.hardware && core.supportsVulkan && !core.requiresVulkan) {
                                GamePanelText(stringResource(R.string.game_picture_graphics))
                                listOf(false, true).forEach { vulkan ->
                                    RightPanelChoice(
                                        text = stringResource(if (vulkan) R.string.game_picture_graphics_vulkan else R.string.game_picture_graphics_gles),
                                        subtitle = stringResource(R.string.game_picture_graphics_hint, core.name),
                                        selected = settings.vulkan(core) == vulkan,
                                        onClick = {
                                            GameSettings.update { it.withVulkan(core, vulkan) }
                                            // The renderer is chosen when the core starts: start again with it.
                                            if (vulkan != session.vulkan && !exiting && !netplay.active) {
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
                            if (options.isEmpty()) GamePanelText(stringResource(R.string.game_core_no_options))
                            if (category == null) {
                                if (options.isNotEmpty()) RightPanelChoice(
                                    text = stringResource(R.string.game_core_scope),
                                    subtitle = if (gameScope) stringResource(R.string.game_core_scope_game)
                                    else stringResource(R.string.game_core_scope_core, core.name),
                                    selected = false,
                                    onClick = { gameScope = !gameScope },
                                )
                                if (categorized) {
                                    options.groupBy(::categoryOf).forEach { (key, items) ->
                                        val first = items.first().category
                                        RightPanelChoice(
                                            text = first?.title ?: stringResource(R.string.game_core_other),
                                            subtitle = first?.info?.takeIf { it.isNotBlank() }
                                                ?: stringResource(R.string.game_core_category_count, items.size),
                                            selected = false,
                                            trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            onClick = { optionCategory = key },
                                        )
                                    }
                                }
                            }
                            val shown = when {
                                category != null -> options.filter { categoryOf(it) == category }
                                categorized -> emptyList()
                                else -> options
                            }
                            shown.forEach { item ->
                                RightPanelChoice(
                                    text = item.title,
                                    subtitle = originLabel(item),
                                    selected = false,
                                    trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    onClick = { optionKey = item.key },
                                )
                            }
                            if (category == null) {
                                val own = settings.gameCoreOptions(core, game.saveKey)
                                if (own.isNotEmpty()) RightPanelChoice(
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
                                if (forAll.isNotEmpty()) RightPanelChoice(
                                    text = stringResource(R.string.game_core_options_reset),
                                    subtitle = stringResource(R.string.game_core_options_reset_hint),
                                    selected = false,
                                    onClick = {
                                        val gameOwn = settings.gameCoreOptions(core, game.saveKey)
                                        GameSettings.update { it.withoutCoreOptions(core) }
                                        applyOptions((forAll.keys - gameOwn.keys).associateWith { null })
                                    },
                                )
                            }
                        }
                        open == GamePanel.DISC -> (0 until discCount).forEach { index ->
                            RightPanelChoice(
                                text = stringResource(R.string.game_disc, index + 1),
                                selected = index == currentDisc,
                                onClick = {
                                    if (runCatching { retroView.changeDisk(index) }.isSuccess) {
                                        currentDisc = index
                                        message = context.getString(R.string.game_disc, index + 1)
                                    }
                                    closePanel()
                                },
                            )
                        }
                        open == GamePanel.CONTROLS -> {
                            GamePanelText(stringResource(R.string.game_touch_layout))
                            RightPanelChoice(
                                text = stringResource(R.string.game_touch_controls),
                                subtitle = stringResource(R.string.game_touch_controls_hint),
                                selected = touchControls,
                                onClick = { GameSettings.update { it.withTouchControls(system, !touchControls) } },
                            )
                            GameTouchMode.entries.forEach { choice ->
                                RightPanelChoice(
                                    text = stringResource(choice.titleRes),
                                    selected = choice == touchMode,
                                    enabled = touchControls,
                                    onClick = { GameSettings.update { it.withTouchMode(system, choice) } },
                                )
                            }
                            GameTouchOptions(system.id)
                            GameControlsPanel()
                            GameDeviceTypesPanel(
                                controllers = remember(open) { runCatching { retroView.getControllers() }.getOrDefault(emptyArray()) },
                                current = { port -> settings.controllerType(core, port) },
                                enabled = !netplay.active,
                                onChoose = { port, type ->
                                    runCatching { retroView.setControllerType(port, type) }
                                    GameSettings.update { it.withControllerType(core, port, type) }
                                },
                            )
                        }
                        open == GamePanel.ACHIEVEMENTS -> GameAchievementsPanel(achievements)
                        open == GamePanel.CHEATS -> GameCheatsPanel(
                            cheats = cheats,
                            loading = cheatsLoading,
                            deleting = cheatsDeleting,
                            draft = cheatDraft,
                            blocked = netplay.active,
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
                        else -> {
                            GamePanelText(stringResource(R.string.game_info_system, system.name))
                            GamePanelText(stringResource(R.string.game_info_core, core.name, core.license))
                            if (core.hardware && core.supportsVulkan) {
                                GamePanelText(stringResource(R.string.game_info_hdr_display_capabilities, displayHdr.supportedTypesLabel()))
                                if (session.vulkan) {
                                    GamePanelText(stringResource(R.string.game_info_vulkan_surface_pairs, vulkanSurfaceFormats.candidateTypesLabel()))
                                    GamePanelText(stringResource(R.string.game_info_vulkan_core_source_format, vulkanCoreSourceFormat.label))
                                    GamePanelText(stringResource(when {
                                        coreHdrHandshake.hdr10Accepted -> R.string.game_info_hdr_core_accepts_pq
                                        coreHdrHandshake.hdr10Requested -> R.string.game_info_hdr_core_rejects_pq
                                        coreHdrHandshake.scrgbRequested -> R.string.game_info_hdr_core_rejects_scrgb
                                        else -> R.string.game_info_hdr_core_not_negotiated
                                    }))
                                    if (nativeTransfer != GameVulkanTransferMode.SDR) {
                                        GamePanelText(stringResource(if (nativeSourceContract.supports(nativeTransfer))
                                            R.string.game_info_vulkan_hdr_core_declared
                                        else R.string.game_info_vulkan_hdr_source_unverified))
                                        GamePanelText(stringResource(R.string.game_info_vulkan_hdr_mastering_unknown))
                                    } else {
                                        GamePanelText(stringResource(R.string.game_info_vulkan_source_transfer_unknown))
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
                                        GamePanelText(stringResource(nativePhase))
                                        RightPanelChoice(
                                            text = stringResource(if (nativeTransfer == GameVulkanTransferMode.SDR)
                                                R.string.game_info_native_sdr_test else R.string.game_info_native_hdr_test),
                                            subtitle = stringResource(if (nativeTransfer == GameVulkanTransferMode.SDR)
                                                R.string.game_info_native_sdr_test_hint else R.string.game_info_native_hdr_test_hint),
                                            selected = nativeSdrDiagnostic,
                                            enabled = !netplay.active && !exiting,
                                            onClick = {
                                                nativeSdrDiagnostic = !nativeSdrDiagnostic
                                                if (!nativeSdrDiagnostic) nativeSdrFullscreenRequested = false
                                            },
                                        )
                                        val fullscreenAvailable = nativePictureSupported &&
                                            (nativeSlangPath == null || nativeSdrStatus.shaderPresenting) &&
                                            GameVulkanFullscreenPolicy.canEnable(
                                                session.vulkan, toneMap, nativeSdrDiagnostic,
                                                nativeSdrStatus, netplay.active, displayHdr, vulkanSurfaceFormats,
                                            )
                                        if (nativeSlangPath != null) {
                                            GamePanelText(stringResource(if (nativeSdrStatus.shaderPresenting)
                                                R.string.games_vulkan_shader_presenting else R.string.games_vulkan_shader_pending))
                                        }
                                        if (!nativePictureSupported) {
                                            GamePanelText(stringResource(R.string.game_info_native_picture_requires_gles))
                                        }
                                        RightPanelChoice(
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
                                        GamePanelText(stringResource(R.string.game_info_native_hdr_unavailable))
                                    }
                                }
                                if (nativeSdrFullscreenRequested &&
                                    nativeTransfer != GameVulkanTransferMode.SDR &&
                                    GameVulkanTransferGate.frameConfirmed(nativeTransfer, nativeSdrStatus)) {
                                    GamePanelText(stringResource(R.string.game_info_hdr_wsi_unverified))
                                } else {
                                    GamePanelText(stringResource(R.string.game_info_hdr_output_inactive))
                                }
                            }
                            if (system.alternatives.isNotEmpty()) {
                                GamePanelText(stringResource(R.string.game_info_core_choice))
                                val own = settings.ownGameCore(system, game.saveKey)
                                system.cores.forEach { choice ->
                                    RightPanelChoice(
                                        text = choice.name,
                                        subtitle = listOfNotNull(
                                            choice.license,
                                            stringResource(R.string.game_info_core_system_default).takeIf {
                                                choice.id == settings.systemCore(system).id
                                            },
                                            stringResource(R.string.game_info_core_needs_bios).takeIf { choice.needsBios },
                                        ).joinToString(" · "),
                                        selected = choice.id == core.id,
                                        enabled = !netplay.active,
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
                            }
                            GamePanelText(stringResource(R.string.game_info_file, game.file.name, game.sourceName))
                            val bios = remember { model.biosStatus(game) }
                            bios.forEach { status ->
                                GamePanelText(stringResource(R.string.game_info_bios, status.bios.file, stringResource(status.state.titleRes())))
                            }
                            GamePanelText(stringResource(R.string.game_info_core_source))
                            RightPanelChoice(
                                text = stringResource(R.string.game_core_remove),
                                subtitle = stringResource(R.string.game_core_remove_hint),
                                selected = false,
                                onClick = {
                                    model.removeCore(core)
                                    message = context.getString(R.string.game_message_core_removed)
                                    closePanel()
                                },
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

@Composable
internal fun GamePanelText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
}

/** Who plays as which player; choosing a device steps through automatic, players 1–4 and off. */
@Composable
private fun GameControlsPanel() {
    val devices = rememberPlayableDevices()
    val config = RetroInputSettings.config
    GamePanelText(stringResource(R.string.game_controls_panel_hint))
    devices.forEach { device -> key(device.descriptor) {
        val current = config.ports[device.descriptor] ?: RetroPorts.AUTO
        val sequence = listOf(RetroPorts.AUTO) + (0 until RetroPorts.COUNT) + RetroPorts.OFF
        RightPanelChoice(
            text = device.name.ifBlank { stringResource(device.kind.titleRes) },
            subtitle = when (current) {
                RetroPorts.AUTO -> stringResource(R.string.game_controls_port_auto)
                RetroPorts.OFF -> stringResource(R.string.game_controls_port_off)
                else -> stringResource(R.string.game_controls_port_player, current + 1)
            },
            selected = false,
            onClick = {
                val next = sequence[(sequence.indexOf(current) + 1) % sequence.size]
                RetroInputSettings.update { it.withPort(device.descriptor, next) }
            },
        )
    } }
}

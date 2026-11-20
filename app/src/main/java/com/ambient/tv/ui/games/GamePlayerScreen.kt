package com.ambient.tv.ui.games

import android.graphics.Bitmap
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
import com.ambient.tv.ui.common.activateOnTvKey
import kotlinx.coroutines.launch
import java.io.File
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
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
import com.ambient.tv.R
import com.ambient.tv.games.GameAspect
import com.ambient.tv.games.GameCheat
import com.ambient.tv.games.GameCoreOption
import com.ambient.tv.games.GameOptionOrigin
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.GameShader
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlayerControlBar
import com.ambient.tv.ui.common.PlayerExitButton
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.RightPanelOverlay
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.RetroPadInput
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.ui.gamepad.retro.playableInputDevices
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
            is GameUiState.DownloadingCore -> StatePanel(
                title = stringResource(R.string.game_core_loading, current.core.name),
                body = current.progress?.let { stringResource(R.string.game_progress, (it * 100).roundToInt()) },
                actionLabel = stringResource(R.string.back), onAction = onBack,
                modifier = center, centered = true, actionLoading = false,
            )
            is GameUiState.Failed -> StatePanel(
                title = stringResource(current.reason.titleRes()),
                body = if (current.reason == GameFailure.BIOS_MISSING) {
                    stringResource(R.string.game_failed_bios_missing_body, current.detail.orEmpty())
                } else current.detail,
                actionLabel = stringResource(if (model.canUseCache) R.string.game_cache_fallback else R.string.game_retry),
                onAction = if (model.canUseCache) model::retryWithCache else model::retry,
                secondaryActionLabel = stringResource(R.string.back), onSecondaryAction = onBack,
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
}

internal fun GameShader.config(): ShaderConfig = when (this) {
    GameShader.SHARP -> ShaderConfig.Sharp
    GameShader.PIXEL -> ShaderConfig.Default
    GameShader.SMOOTH -> ShaderConfig.CUT3()
    GameShader.CRT -> ShaderConfig.CRT
    GameShader.LCD -> ShaderConfig.LCD
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

private enum class GamePanel { PICTURE, CORE, DISC, CONTROLS, INFO, ACHIEVEMENTS, CHEATS, NETPLAY }

/** Rewind keeps this many seconds, within [REWIND_BUDGET_BYTES] of memory. */
private const val REWIND_SECONDS = 20
private const val REWIND_BUDGET_BYTES = 64L * 1024 * 1024
/** The category key of options without one, when others have one. */
private const val OTHER_CATEGORY = "\u0000other"

/** The game's current frame, scaled for save state previews; null when there is none yet. */
private suspend fun GLRetroView.preview(): Bitmap? = withContext(Dispatchers.Default) {
    val frame = runCatching { captureRawFrame() }.getOrNull() ?: return@withContext null
    if (frame.width <= 480) return@withContext frame
    val height = (frame.height * 480 / frame.width.toFloat()).roundToInt().coerceAtLeast(1)
    Bitmap.createScaledBitmap(frame, 480, height, true).also { if (it !== frame) frame.recycle() }
}

@Composable
private fun GameContent(session: GameSession, model: GamePlayerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val handheld = LocalHandheldUi.current
    val scope = rememberCoroutineScope()
    val activityLifecycle = LocalLifecycleOwner.current.lifecycle
    val game = session.game
    val system = game.system
    val core = session.core
    val settings = GameSettings.state
    val shader = settings.shader(system)
    val aspect = settings.aspect(system)
    val integerScaling = settings.integerScaling(system)
    val saveStates by model.saveStates.collectAsState()

    val chrome = remember { PlayerChromeState() }
    val osd = chrome.osd
    var menuOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<GamePanel?>(null) }
    var optionCategory by remember { mutableStateOf<String?>(null) }
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
    /** Core-reported serialized size must be nonzero and safe for this device's heap. */
    var stateAvailable by remember { mutableStateOf(false) }
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
            this.shader = shader.config()
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
            preferVulkan = session.vulkan
        }).apply {
            // Input reaches the core through RetroPadInput only.
            isFocusable = false
            isFocusableInTouchMode = false
            // Starts paused: the first composition decides whether it runs.
            suppressAutoResume = true
            aspectRatioOverride = aspect.ratio ?: -1f
            this.integerScaling = integerScaling
        }.also { view ->
            owner.registry.addObserver(view)
            owner.move(Lifecycle.State.CREATED)
        }
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

    /** Battery save and the "continue" state. Runs on the core's thread between frames. */
    fun persist() {
        if (!loaded || destroyed) return
        runCatching { model.saveSram(game, retroView.serializeSRAM()) }
        if (stateAvailable) runCatching { model.saveState(session, GameStateKind.AUTO, retroView.serializeState(), snapshot) }
    }

    /** Ends the core while its thread still runs; nothing reaches it afterwards. */
    fun destroy() {
        if (destroyed) return
        netplay.release()
        achievements.stop(retroView)
        retroView.pauseEmulation()
        persist()
        destroyed = true
        runCatching { retroView.destroyNative() }
        game.close()
    }

    fun stateTitle(entry: GameStateEntry): String = when (entry.kind) {
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
        optionKey = null
        optionCategory = null
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
            scope.launch { snapshot = if (loaded) retroView.preview() else null }
        }
    }
    /** Loads [entry]; the moment before is kept as "Before loading" so the step can be undone. */
    val loadState: (GameStateEntry) -> Unit = { entry ->
        val bytes = model.readState(game, entry)
        if (!stateAvailable || achievements.hardcore || netplay.active) {
            message = context.getString(
                if (achievements.hardcore) R.string.game_message_hardcore_blocked else R.string.game_message_netplay_blocked,
            )
        } else if (bytes == null) {
            message = context.getString(R.string.game_message_load_failed)
        } else {
            runCatching { model.saveState(session, GameStateKind.UNDO, retroView.serializeState(), snapshot) }
            val restored = runCatching { retroView.unserializeState(bytes) }.getOrDefault(false)
            if (restored) {
                runCatching { retroView.clearRewindBuffer() }
                resume()
            } else message = context.getString(
                if (entry.coreStamp != session.coreStamp) R.string.game_message_load_other_core else R.string.game_message_load_failed,
            )
        }
    }

    val running = coreReady && activityResumed && !menuOpen && panel == null && !exiting && !destroyed
    LaunchedEffect(running) {
        retroView.suppressAutoResume = !running
        if (running) retroView.resumeEmulation() else retroView.pauseEmulation()
    }
    LaunchedEffect(fastForward) { retroView.frameSpeed = if (fastForward) 2 else 1 }
    LaunchedEffect(rewinding, running) {
        retroView.isRewinding = core.rewind && rewinding && running && !achievements.hardcore && !netplay.active
    }

    DisposableEffect(activityLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (!destroyed) {
                        retroView.onResume()
                        owner.move(Lifecycle.State.RESUMED)
                    }
                    activityResumed = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    activityResumed = false
                    // Pause and save at once, not after the next composition: the app may stop right after.
                    retroView.suppressAutoResume = true
                    retroView.pauseEmulation()
                    if (!menuOpen && !exiting) menuOpen = true
                    persist()
                    if (!destroyed) {
                        owner.move(Lifecycle.State.STARTED)
                        retroView.onPause()
                    }
                }
                else -> Unit
            }
        }
        activityLifecycle.addObserver(observer)
        onDispose { activityLifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner) {
        onDispose {
            // Leaving without Exit: only while the view (and with it the core's thread) still lives.
            if (retroView.isAttachedToWindow) destroy()
            owner.move(Lifecycle.State.DESTROYED)
        }
    }

    // First frame: the game is loaded; continue where the player left off, then keep rewind ready.
    LaunchedEffect(retroView) {
        retroView.getGLRetroEvents().collect { event ->
            if (event == GLRetroView.GLRetroEvents.SurfaceCreated) coreReady = true
            if (event == GLRetroView.GLRetroEvents.FrameRendered && !loaded) {
                loaded = true
                stateAvailable = core.saveStates && runCatching {
                    retroView.getSerializeSize() in 1L..com.ambient.tv.games.GameStateStore.MAX_STATE_BYTES
                }.getOrDefault(false)
                runCatching { retroView.getVariables().mapNotNull { v -> v.key?.let { it to v.description } } }
                    .onSuccess { model.saveDeclaredOptions(core, it) }
                // Hardcore starts the game fresh: a continued state would not count.
                if (!hardcoreRequested) session.autoState?.let { bytes ->
                    val restored = withContext(Dispatchers.Default) { runCatching { retroView.unserializeState(bytes) }.getOrDefault(false) }
                    if (restored) message = context.getString(R.string.game_message_continued)
                }
                (0 until RetroPorts.COUNT).forEach { port ->
                    GameSettings.state.controllerType(core, port)?.let { type -> runCatching { retroView.setControllerType(port, type) } }
                }
                applyCheats(cheats)
                achievements.start(retroView, game, !game.direct && core.achievements && GameSettings.state.achievements, hardcoreRequested)
                if (core.rewind && stateAvailable && GameSettings.state.rewind && !hardcoreRequested) withContext(Dispatchers.Default) {
                    runCatching {
                        if (retroView.getSerializeSize() <= 0) return@runCatching
                        val fps = LibretroDroid.getContentFps().takeIf { it in 1.0..240.0 } ?: 60.0
                        retroView.initRewindBuffer((REWIND_SECONDS * fps).roundToInt(), REWIND_BUDGET_BYTES)
                        retroView.rewindEnabled = true
                    }
                }
            }
        }
    }
    LaunchedEffect(retroView) {
        retroView.getGLRetroErrors().collect { code ->
            // Vulkan not available after all: once more with OpenGL ES.
            if (session.vulkan && !loaded && model.retryWithoutVulkan()) return@collect
            // A core that does not load is removed, so trying again loads it fresh.
            if (code == GLRetroView.ERROR_LOAD_LIBRARY) model.removeCore(core)
            model.failed(context.getString(R.string.game_error_code, code))
        }
    }

    // Controllers, keyboards and remotes play through RetroPadInput while the game runs.
    val latestOpenMenu by rememberUpdatedState(openMenu)
    val input = remember {
        val handler = Handler(Looper.getMainLooper())
        RetroPadInput(
            schedule = { delay, task ->
                val runnable = Runnable(task)
                handler.postDelayed(runnable, delay)
                ({ handler.removeCallbacks(runnable) })
            },
            onMenu = { latestOpenMenu() },
            onRewind = { held -> rewinding = held && core.rewind && stateAvailable },
            onFastForward = { if (!netplay.active) fastForward = !fastForward },
        ).also(inputRef::set)
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
        if (!running) (0 until RetroPorts.COUNT).forEach { GameRumble.stop(input.deviceIds(it)) }
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
        onDispose { input.onChanged = null }
    }
    DisposableEffect(running, input) {
        val sink: (android.view.InputEvent) -> Boolean = { event -> input.onEvent(event) }
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
        optionKey = null
        optionCategory = null
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
            AndroidView(
                factory = { retroView },
                modifier = Modifier.fillMaxSize()
                    .then(if (handheld) Modifier.clickable { if (!menuOpen) openMenu() } else Modifier),
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
                        subtitle = message ?: stringResource(R.string.game_paused),
                        showPaused = true,
                    )
                    PlayerTechBadgeRow {
                        MetaBadge("libretro", emphasized = true)
                        MetaBadge(core.name)
                        if (game.direct) MetaBadge(stringResource(R.string.game_direct_source))
                        if (core.hardware) MetaBadge(if (session.vulkan) "Vulkan" else "OpenGL ES")
                        MetaBadge(stringResource(shader.titleRes))
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
                        onClose = {
                            statesOpen = false
                            runCatching { statesRequester.requestFocus() }
                        },
                    )
                    PlayerControlBar(
                        leftContent = {
                            PlayerIconButton(Icons.Filled.PlayArrow, stringResource(R.string.game_continue), resume,
                                modifier = Modifier.focusRequester(playRequester), primary = true)
                            PlayerIconButton(Icons.Filled.Save, stringResource(R.string.game_save_state), {
                                osd.interact()
                                runCatching { model.saveState(session, GameStateKind.MANUAL, retroView.serializeState(), snapshot) }
                                    .onSuccess { message = context.getString(R.string.game_message_saved, stateTitle(it)) }
                                    .onFailure { message = context.getString(R.string.game_message_save_failed) }
                            }, enabled = loaded && stateAvailable)
                            PlayerIconButton(Icons.Filled.SettingsBackupRestore, stringResource(R.string.game_quick_load), {
                                model.latestManualState(game)?.let(loadState)
                            }, enabled = loaded && stateAvailable && !achievements.hardcore && !netplay.active && saveStates.any { it.kind == GameStateKind.MANUAL })
                            PlayerIconButton(Icons.Filled.Bookmarks, stringResource(R.string.game_states), {
                                osd.interact()
                                statesOpen = !statesOpen
                            }, modifier = Modifier.focusRequester(statesRequester),
                                selected = statesOpen, enabled = loaded && stateAvailable && saveStates.isNotEmpty())
                            PlayerIconButton(Icons.Filled.RestartAlt, stringResource(R.string.game_reset), {
                                runCatching { retroView.reset() }
                                runCatching { retroView.clearRewindBuffer() }
                                model.clearAutoState(game)
                                resume()
                            }, enabled = loaded && !netplay.active)
                        },
                        rightContent = {
                            PlayerIconButton(Icons.Filled.FastForward, stringResource(R.string.game_fast_forward), {
                                fastForward = !fastForward
                            }, selected = fastForward, enabled = loaded && !netplay.active)
                            if (achievements.state != GameAchievementState.Off) PlayerIconButton(
                                Icons.Filled.EmojiEvents, stringResource(R.string.game_panel_achievements), {
                                    panel = GamePanel.ACHIEVEMENTS
                                }, selected = panel == GamePanel.ACHIEVEMENTS)
                            PlayerIconButton(Icons.Filled.Code, stringResource(R.string.game_panel_cheats), {
                                panel = GamePanel.CHEATS
                            }, selected = panel == GamePanel.CHEATS, enabled = loaded && core.cheats && !achievements.hardcore)
                            PlayerIconButton(Icons.Filled.Groups, stringResource(R.string.game_panel_netplay), {
                                panel = GamePanel.NETPLAY
                            }, selected = panel == GamePanel.NETPLAY, enabled = loaded && stateAvailable && core.netplay && !game.direct)
                            if (discCount > 1) PlayerIconButton(Icons.Filled.Album, stringResource(R.string.game_panel_disc), {
                                panel = GamePanel.DISC
                            }, selected = panel == GamePanel.DISC, enabled = !netplay.active)
                            PlayerIconButton(Icons.Filled.Wallpaper, stringResource(R.string.game_panel_picture), {
                                panel = GamePanel.PICTURE
                            }, selected = panel == GamePanel.PICTURE)
                            PlayerIconButton(Icons.Filled.Tune, stringResource(R.string.game_panel_core), {
                                optionsVersion++
                                panel = GamePanel.CORE
                            }, selected = panel == GamePanel.CORE, enabled = loaded && !netplay.active)
                            PlayerIconButton(Icons.Filled.SportsEsports, stringResource(R.string.game_panel_controls), {
                                panel = GamePanel.CONTROLS
                            }, selected = panel == GamePanel.CONTROLS)
                            PlayerIconButton(Icons.Filled.Info, stringResource(R.string.game_panel_info), {
                                panel = GamePanel.INFO
                            }, selected = panel == GamePanel.INFO)
                            PlayerExitButton(exit)
                        },
                    )
                }
            }

            val open = panel
            if (open != null) RightPanelOverlay(onOutsideTap = closePanel) {
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
                        open == GamePanel.CORE && category != null -> categoryTitle.orEmpty()
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
                            else -> closePanel()
                        }
                    },
                    closeInsteadOfBack = option == null && category == null,
                ) {
                    when {
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
                        open == GamePanel.PICTURE -> {
                            GamePanelText(stringResource(R.string.game_picture_filter))
                            GameShader.entries.forEach { choice ->
                                RightPanelChoice(
                                    text = stringResource(choice.titleRes),
                                    subtitle = stringResource(choice.hintRes),
                                    selected = choice == shader,
                                    onClick = {
                                        retroView.shader = choice.config()
                                        GameSettings.update { it.withShader(system, choice) }
                                    },
                                )
                            }
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
                        open == GamePanel.NETPLAY -> GameNetplayPanel(netplay, retroView, game, session.coreStamp)
                        else -> {
                            GamePanelText(stringResource(R.string.game_info_system, system.name))
                            GamePanelText(stringResource(R.string.game_info_core, core.name, core.license))
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

/**
 * Save states as cards, styled like the video players' chapter row: the preview, the name and
 * when it was saved. OK loads; holding OK (or Menu/Info) twice deletes.
 */
@Composable
private fun GameStateStrip(
    open: Boolean,
    states: List<GameStateEntry>,
    coreStamp: String,
    title: (GameStateEntry) -> String,
    screenshot: (GameStateEntry) -> File?,
    onLoad: (GameStateEntry) -> Unit,
    onDelete: (GameStateEntry) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open, states.isEmpty()) {
        pendingDelete = null
        if (open && states.isNotEmpty()) {
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
        }
    }
    AnimatedVisibility(
        visible = open && states.isNotEmpty(),
        enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(350, easing = FastOutSlowInEasing)) + fadeOut(tween(200)),
    ) {
        val formatter = LocalContext.current.let { remember(it) { android.text.format.DateFormat.getMediumDateFormat(it) to android.text.format.DateFormat.getTimeFormat(it) } }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.Back, Key.Escape -> { if (event.type == KeyEventType.KeyUp) onClose(); true }
                    Key.DirectionUp -> { if (event.type == KeyEventType.KeyDown) onClose(); true }
                    Key.DirectionDown -> true
                    else -> false
                }
            },
        ) {
            itemsIndexed(states, key = { _, entry -> entry.id + entry.createdAt }) { index, entry ->
                var focused by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "stateScale")
                val shape = RoundedCornerShape(10.dp)
                val image = remember(entry.id, entry.createdAt) {
                    screenshot(entry)?.let { file -> runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
                }
                val deleting = pendingDelete == entry.id
                Column(
                    Modifier.width(200.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .onFocusChanged { focused = it.isFocused; if (!it.isFocused && deleting) pendingDelete = null }
                        .clip(shape).background(Color.Black.copy(alpha = if (focused) 0.72f else 0.5f))
                        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.12f), shape)
                        .activateOnTvKey(onClick = { onLoad(entry) }, onLongClick = {
                            if (deleting) { pendingDelete = null; onDelete(entry) } else pendingDelete = entry.id
                        })
                        .clickable(role = Role.Button) { onLoad(entry) },
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black)) {
                        if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title(entry), color = Color.White, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        val date = java.util.Date(entry.createdAt)
                        Text(
                            when {
                                deleting -> stringResource(R.string.game_state_delete_confirm)
                                entry.coreStamp != coreStamp -> stringResource(R.string.game_state_other_core)
                                else -> formatter.first.format(date) + " " + formatter.second.format(date)
                            },
                            color = Color.White.copy(alpha = 0.72f), maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun GamePanelText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
}

/** Who plays as which player; choosing a device steps through automatic, players 1–4 and off. */
@Composable
private fun GameControlsPanel() {
    val devices = remember { playableInputDevices() }
    val config = RetroInputSettings.config
    GamePanelText(stringResource(R.string.game_controls_panel_hint))
    devices.forEach { device ->
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
    }
}

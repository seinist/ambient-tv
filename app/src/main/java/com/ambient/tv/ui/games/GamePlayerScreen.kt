package com.ambient.tv.ui.games

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
import com.ambient.tv.games.GameCoreOption
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.GameStateSlot
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
                val core = current.game.system.core
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
                title = stringResource(R.string.game_core_loading, current.game.system.core.name),
                body = current.progress?.let { stringResource(R.string.game_progress, (it * 100).roundToInt()) },
                actionLabel = stringResource(R.string.back), onAction = onBack,
                modifier = center, centered = true, actionLoading = false,
            )
            is GameUiState.Failed -> StatePanel(
                title = stringResource(current.reason.titleRes()),
                body = current.detail,
                actionLabel = stringResource(R.string.game_retry), onAction = model::retry,
                secondaryActionLabel = stringResource(R.string.back), onSecondaryAction = onBack,
                modifier = center, centered = true,
            )
            is GameUiState.Ready -> GameContent(current.session, model, onBack)
        }
    }
}

private fun GameFailure.titleRes(): Int = when (this) {
    GameFailure.NOT_FOUND -> R.string.game_failed_not_found
    GameFailure.UNKNOWN_SYSTEM -> R.string.game_failed_unknown_system
    GameFailure.CORE_DOWNLOAD -> R.string.game_failed_core_download
    GameFailure.CORE_INCOMPATIBLE -> R.string.game_failed_core_incompatible
    GameFailure.NO_ABI -> R.string.game_failed_no_abi
    GameFailure.START -> R.string.game_failed_start
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

/** LibretroDroid follows a lifecycle; the game's own one pauses it while the menu is open. */
private class GameLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun move(state: Lifecycle.State) {
        val current = registry.currentState
        if (current == Lifecycle.State.DESTROYED || current == state) return
        registry.currentState = state
    }

    val running: Boolean get() = registry.currentState == Lifecycle.State.RESUMED
}

private enum class GamePanel { PICTURE, CORE, CONTROLS, INFO }

@Composable
private fun GameContent(session: GameSession, model: GamePlayerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val handheld = LocalHandheldUi.current
    val activityLifecycle = LocalLifecycleOwner.current.lifecycle
    val game = session.game
    val system = game.system
    val core = system.core
    val settings = GameSettings.state
    val shader = settings.shader(system)
    val hasQuickState by model.hasQuickState.collectAsState()

    val chrome = remember { PlayerChromeState() }
    val osd = chrome.osd
    var menuOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<GamePanel?>(null) }
    var optionKey by remember { mutableStateOf<String?>(null) }
    var activityResumed by remember { mutableStateOf(activityLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var exiting by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var optionsVersion by remember { mutableIntStateOf(0) }
    val playRequester = remember { FocusRequester() }

    val owner = remember { GameLifecycleOwner() }
    val retroView = remember {
        GLRetroView(context, GLRetroViewData(context).apply {
            coreFilePath = session.coreFile.absolutePath
            gameFilePath = game.file.absolutePath
            systemDirectory = session.systemDirectory.absolutePath
            savesDirectory = session.savesDirectory.absolutePath
            variables = settings.coreOptions(core).map { (key, value) -> Variable(key, value) }.toTypedArray()
            saveRAMState = session.sram
            this.shader = shader.config()
            rumbleEventsEnabled = false
            preferLowLatencyAudio = true
        }).apply {
            // Input reaches the core through RetroPadInput only.
            isFocusable = false
            isFocusableInTouchMode = false
        }.also { view ->
            owner.registry.addObserver(view)
            owner.move(Lifecycle.State.CREATED)
        }
    }

    /** Only while the game is paused: the emulation thread is idle then. */
    fun persist() {
        if (!loaded || owner.running) return
        runCatching { model.saveSram(game, retroView.serializeSRAM(false)) }
        runCatching { model.saveState(game, GameStateSlot.AUTO, retroView.serializeState(false)) }
    }

    val exit: () -> Unit = {
        if (!exiting) {
            exiting = true
            owner.move(Lifecycle.State.STARTED)
            persist()
            onBack()
        }
    }
    val resume: () -> Unit = {
        panel = null
        optionKey = null
        menuOpen = false
        osd.close()
    }
    val openMenu: () -> Unit = {
        message = null
        menuOpen = true
    }

    val running = activityResumed && !menuOpen && panel == null && !exiting
    LaunchedEffect(running) { owner.move(if (running) Lifecycle.State.RESUMED else Lifecycle.State.STARTED) }

    DisposableEffect(activityLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activityResumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    activityResumed = false
                    // Pause at once, not after the next composition: the app may stop right after.
                    owner.move(Lifecycle.State.STARTED)
                    menuOpen = true
                }
                Lifecycle.Event.ON_STOP -> persist()
                else -> Unit
            }
        }
        activityLifecycle.addObserver(observer)
        onDispose { activityLifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner) {
        onDispose {
            if (!exiting) {
                owner.move(Lifecycle.State.STARTED)
                persist()
            }
            owner.move(Lifecycle.State.DESTROYED)
        }
    }

    // First frame: the game is loaded; continue where the player left off.
    LaunchedEffect(retroView) {
        retroView.getGLRetroEvents().collect { event ->
            if (event == GLRetroView.GLRetroEvents.FrameRendered && !loaded) {
                loaded = true
                session.autoState?.let { bytes ->
                    val restored = withContext(Dispatchers.Default) { runCatching { retroView.unserializeState(bytes) }.getOrDefault(false) }
                    if (restored) message = R.string.game_message_continued
                }
            }
        }
    }
    LaunchedEffect(retroView) {
        retroView.getGLRetroErrors().collect { code ->
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
        )
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
    LaunchedEffect(menuOpen) { if (menuOpen) osd.expand() }
    LaunchedEffect(osd.visible.value) { if (!osd.visible.value && panel == null) menuOpen = false }
    LaunchedEffect(menuOpen, panel) {
        if (menuOpen && panel == null) {
            withFrameNanos { }
            runCatching { playRequester.requestFocus() }
        }
    }
    // Back while playing (a device whose Back does not play) opens the menu; in the menu it continues.
    BackHandler(enabled = panel == null) { if (menuOpen) resume() else openMenu() }

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

            PlayerOverlayVisibility(visible = osd.visible.value && panel == null) {
                ReferencePlayerOverlayBackground()
                ReferencePlayerClock(modifier = Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 48.dp))
                ReferencePlayerBottomContent(onTogglePlayback = resume) {
                    ReferencePlayerHeader(
                        title = game.title,
                        artworkUrl = null,
                        contextLine = system.name,
                        subtitle = stringResource(message ?: R.string.game_paused),
                        showPaused = true,
                    )
                    PlayerTechBadgeRow {
                        MetaBadge("libretro", emphasized = true)
                        MetaBadge(core.name)
                        MetaBadge(stringResource(shader.titleRes))
                        MetaBadge(game.sourceName)
                    }
                    PlayerControlBar(
                        leftContent = {
                            PlayerIconButton(Icons.Filled.PlayArrow, stringResource(R.string.game_continue), resume,
                                modifier = Modifier.focusRequester(playRequester), primary = true)
                            PlayerIconButton(Icons.Filled.Save, stringResource(R.string.game_quick_save), {
                                osd.interact()
                                runCatching { model.saveState(game, GameStateSlot.QUICK, retroView.serializeState(false)) }
                                    .onSuccess { message = R.string.game_message_saved }
                                    .onFailure { message = R.string.game_message_save_failed }
                            }, enabled = loaded)
                            PlayerIconButton(Icons.Filled.SettingsBackupRestore, stringResource(R.string.game_quick_load), {
                                val bytes = model.readState(game, GameStateSlot.QUICK)
                                if (bytes != null && runCatching { retroView.unserializeState(bytes, false) }.getOrDefault(false)) resume()
                                else message = R.string.game_message_load_failed
                            }, enabled = loaded && hasQuickState)
                            PlayerIconButton(Icons.Filled.RestartAlt, stringResource(R.string.game_reset), {
                                runCatching { retroView.reset(false) }
                                model.clearAutoState(game)
                                resume()
                            }, enabled = loaded)
                        },
                        rightContent = {
                            PlayerIconButton(Icons.Filled.Wallpaper, stringResource(R.string.game_panel_picture), {
                                panel = GamePanel.PICTURE
                            }, selected = panel == GamePanel.PICTURE)
                            PlayerIconButton(Icons.Filled.Tune, stringResource(R.string.game_panel_core), {
                                optionsVersion++
                                panel = GamePanel.CORE
                            }, selected = panel == GamePanel.CORE, enabled = loaded)
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
            if (open != null) RightPanelOverlay(onOutsideTap = { panel = null; optionKey = null }) {
                val options = remember(optionsVersion, open) {
                    if (open == GamePanel.CORE && loaded) runCatching {
                        retroView.getVariables().mapNotNull { GameCoreOption.parse(it.key ?: return@mapNotNull null, it.description, it.value) }
                    }.getOrDefault(emptyList()) else emptyList()
                }
                val option = options.firstOrNull { it.key == optionKey }
                RightSidePanel(
                    title = when {
                        option != null -> option.title
                        open == GamePanel.PICTURE -> stringResource(R.string.game_panel_picture)
                        open == GamePanel.CORE -> stringResource(R.string.game_panel_core)
                        open == GamePanel.CONTROLS -> stringResource(R.string.game_panel_controls)
                        else -> stringResource(R.string.game_panel_info)
                    },
                    subtitle = when (open) {
                        GamePanel.PICTURE -> stringResource(R.string.game_picture_scope, system.name)
                        GamePanel.CORE -> if (option == null) core.name else null
                        else -> null
                    },
                    onBack = { if (option != null) optionKey = null else panel = null },
                    closeInsteadOfBack = option == null,
                ) {
                    when {
                        option != null -> option.values.forEach { value ->
                            RightPanelChoice(text = value, selected = value == option.value, onClick = {
                                retroView.updateVariables(Variable(option.key, value))
                                GameSettings.update { it.withCoreOption(core, option.key, value) }
                                optionsVersion++
                                optionKey = null
                            })
                        }
                        open == GamePanel.PICTURE -> GameShader.entries.forEach { choice ->
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
                        open == GamePanel.CORE -> {
                            if (options.isEmpty()) GamePanelText(stringResource(R.string.game_core_no_options))
                            options.forEach { item ->
                                RightPanelChoice(
                                    text = item.title,
                                    subtitle = item.value,
                                    selected = false,
                                    trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    onClick = { optionKey = item.key },
                                )
                            }
                            if (settings.coreOptions(core).isNotEmpty()) RightPanelChoice(
                                text = stringResource(R.string.game_core_options_reset),
                                subtitle = stringResource(R.string.game_core_options_reset_hint),
                                selected = false,
                                onClick = { GameSettings.update { it.withoutCoreOptions(core) } },
                            )
                        }
                        open == GamePanel.CONTROLS -> GameControlsPanel()
                        else -> {
                            GamePanelText(stringResource(R.string.game_info_system, system.name))
                            GamePanelText(stringResource(R.string.game_info_core, core.name, core.license))
                            GamePanelText(stringResource(R.string.game_info_file, game.file.name, game.sourceName))
                            GamePanelText(stringResource(R.string.game_info_core_source))
                            RightPanelChoice(
                                text = stringResource(R.string.game_core_remove),
                                subtitle = stringResource(R.string.game_core_remove_hint),
                                selected = false,
                                onClick = { model.removeCore(core); message = R.string.game_message_core_removed; panel = null },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GamePanelText(text: String) {
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

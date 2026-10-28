@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime as BedtimeOutlined
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.theme.menuFocusMotion
import kotlinx.coroutines.delay
import kotlin.reflect.KProperty

enum class UnifiedPlayerPanel {
    NONE, SETTINGS, CONFIGURATION, RENDERER, VIDEO,
    /** One area of the video panel (Player, Scaling, Filters, ...), opened from its row. */
    VIDEO_SECTION,
    /** SVP motion smoothing below Motion, and its advanced level. */
    VIDEO_SVP, VIDEO_SVP_ADVANCED,
    AUDIO, AUDIO_SECTION, AUDIO_CODECS, SUBTITLES, SLEEP, VISUALIZER, VISUALIZER_SETTINGS,
    /** Which action-bar buttons the current player shows. */
    OSD_BUTTONS,
    /** A-B repeat, frame step and screenshot. */
    TOOLS;

    val isAudio: Boolean get() = this == AUDIO || this == AUDIO_SECTION || this == AUDIO_CODECS
    val isVisualizer: Boolean get() = this == VISUALIZER || this == VISUALIZER_SETTINGS

    /** The video list or one of its areas: the action bar's video button stays selected. */
    val isVideo: Boolean get() = this == VIDEO || this == VIDEO_SECTION || this == VIDEO_SVP || this == VIDEO_SVP_ADVANCED
}

/**
 * Click path through the player's option panels. Back leaves one level at a time and hands focus
 * to the control that opened the level being left: a settings row, or at the root the action-bar
 * button. Players delegate their `panel` variable to it, so `panel = X` opens a level and
 * `panel = NONE` closes the whole path.
 */
@Stable
class PlayerPanelNavigation {
    private class Level(val panel: UnifiedPlayerPanel, val originKey: String?) {
        /** Last focused keyed row of this level; focus returns there when the level's content is rebuilt. */
        var focusedKey: String? = null
        var hadFocus = false
    }

    internal val videoState = VideoPanelState()
    internal val audioState = VideoPanelState().apply { section = "output" }

    private val path = mutableStateListOf<Level>()
    private val requesters = HashMap<String, FocusRequester>()
    private var pendingOrigin: String? = null
    private var pendingRoot = false

    /** Control to focus once the level it opened is gone; null when nothing is pending. */
    internal var restoreKey by mutableStateOf<String?>(null)
    internal var restoreGeneration by mutableIntStateOf(0)
    internal var restoreFailed by mutableStateOf(false)

    val panel: UnifiedPlayerPanel get() = path.lastOrNull()?.panel ?: UnifiedPlayerPanel.NONE

    operator fun getValue(thisRef: Any?, property: KProperty<*>): UnifiedPlayerPanel = panel
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: UnifiedPlayerPanel) = open(value)

    fun open(target: UnifiedPlayerPanel) {
        when {
            target == UnifiedPlayerPanel.NONE -> close()
            target == panel -> Unit
            else -> {
                videoState.leave()
                audioState.leave()
                // Opening a panel invalidates any delayed hand-back to the player.
                restoreKey = null
                restoreFailed = false
                restoreGeneration++
                // The action bar starts a fresh path; rows inside a panel go one level deeper.
                if (pendingRoot) path.clear()
                path.add(Level(target, pendingOrigin))
            }
        }
    }

    /**
     * Opens one area of the video panel above its list, so Back returns to the list with
     * [entryKey] (the area's row) focused. Called from an action-bar button, the list level
     * keeps that button as its origin; the area level must not start a new path itself.
     */
    fun openVideoSection(section: String, entryKey: String?) {
        open(UnifiedPlayerPanel.VIDEO)
        videoState.section = section
        val root = pendingRoot
        val origin = pendingOrigin
        pendingRoot = false
        pendingOrigin = entryKey
        try {
            open(UnifiedPlayerPanel.VIDEO_SECTION)
        } finally {
            pendingRoot = root
            pendingOrigin = origin
        }
    }

    /**
     * "Adjust" from a dialog outside the panels (render overload): shows the video settings.
     * Already inside them it stays on the current level instead of stacking a second video
     * level whose Back would lead to a duplicate; from anywhere else it starts a fresh path.
     */
    fun openVideoSettings() {
        if (!videoAdjustOpensPanel(panel)) return
        path.clear()
        pendingOrigin = null
        pendingRoot = false
        open(UnifiedPlayerPanel.VIDEO)
    }

    fun back(expectedPanel: UnifiedPlayerPanel = panel) {
        if (panel != expectedPanel) return
        val left = path.removeLastOrNull() ?: return
        videoState.leave()
        audioState.leave()
        restore(left.originKey)
    }

    fun close() {
        val root = path.firstOrNull() ?: return
        path.clear()
        videoState.leave()
        audioState.leave()
        restore(root.originKey)
    }

    /** Runs [action] with the calling control recorded as the origin of any level it opens. */
    fun fromOrigin(key: String, root: Boolean, action: () -> Unit) {
        pendingOrigin = key
        pendingRoot = root
        try {
            action()
        } finally {
            pendingOrigin = null
            pendingRoot = false
        }
    }

    /** Hiding the OSD invalidates any pending hand-back to one of its buttons. */
    fun cancelFocusReturn() {
        if (restoreKey == null) return
        restoreKey = null
        restoreFailed = false
        restoreGeneration++
    }

    fun focusRequester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** The open level has not held focus yet. */
    internal val levelFresh: Boolean get() = path.lastOrNull()?.hadFocus != true

    internal val focusedKey: String? get() = path.lastOrNull()?.focusedKey

    /** A keyed row ([focusRequester]) of the open level took focus. */
    internal fun noteFocused(key: String) {
        val level = path.lastOrNull() ?: return
        level.focusedKey = rememberedPanelFocusKey(level.focusedKey, key, parking)
        level.hadFocus = true
    }

    /**
     * A rescue holds focus on a stand-in row; that row must not replace the one to return to.
     * Stays set until the rescue ends or the user presses a key ([endParking]), so it also covers
     * a focus event Compose delivers after the request returned.
     */
    private var parking = false

    /** Runs [focus] (a stand-in request) without overwriting the level's remembered row. */
    internal fun <T> parked(focus: () -> T): T {
        parking = true
        return focus()
    }

    internal fun endParking() {
        parking = false
    }

    /** True while a player dialog is shown or puts focus back afterwards ([DialogFocusReturn]). */
    internal var dialogFocusOwner by mutableStateOf<Any?>(null)

    internal fun notePanelFocused() {
        path.lastOrNull()?.hadFocus = true
    }

    internal fun requestFocus(key: String): Boolean =
        requesters[key]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false

    internal fun focusRescue(): PanelFocusRescue = PanelFocusRescue(
        levelFresh = levelFresh,
        target = { panelFocusRescueTarget(restoreKey, focusedKey) },
        request = ::requestFocus,
        onFocused = ::notePanelFocused,
        park = { focus -> parked(focus) },
        parkingDone = ::endParking,
        dialogOwnsFocus = { dialogFocusOwner != null },
    )

    private fun restore(key: String?) {
        restoreKey = key
        restoreFailed = false
        restoreGeneration++
    }
}

/** Whether "adjust video" has to open the video panel: not while one of its levels is shown. */
internal fun videoAdjustOpensPanel(current: UnifiedPlayerPanel): Boolean =
    !current.isVideo && current != UnifiedPlayerPanel.RENDERER

val LocalPlayerPanelNavigation = compositionLocalOf<PlayerPanelNavigation?> { null }

/** Player-scoped [PlayerPanelNavigation] that also performs its focus hand-backs. */
@Composable
fun rememberPlayerPanelNavigation(vararg keys: Any?): PlayerPanelNavigation {
    val navigation = com.ambient.tv.ui.player.LocalPlayerChromeState.current?.panels
        ?: remember(*keys) { PlayerPanelNavigation() }
    LaunchedEffect(navigation, navigation.restoreGeneration) {
        val key = navigation.restoreKey ?: return@LaunchedEffect
        val generation = navigation.restoreGeneration
        val destination = navigation.panel
        val requester = navigation.focusRequester(key)
        // The origin re-enters composition with the parent level, or slides back in with the bar.
        var placed = false
        val finished = retryFocusFor {
            if (navigation.restoreGeneration != generation || navigation.panel != destination) return@retryFocusFor true
            placed = runCatching { requester.requestFocus() }.getOrDefault(false)
            placed
        }
        if (finished && !placed) return@LaunchedEffect
        navigation.restoreKey = null
        if (placed) return@LaunchedEffect
        navigation.restoreFailed = true
    }
    return navigation
}

/** How long the player OSD stays up without input; Settings › Playback, applied right away. */
object PlayerOsdTimeout {
    val SECONDS = listOf(3, 5, 8, 15)
    const val DEFAULT_SECONDS = 5
    var seconds by androidx.compose.runtime.mutableIntStateOf(DEFAULT_SECONDS)
}

val PLAYER_CONTROLS_TIMEOUT_MS: Long get() = PlayerOsdTimeout.seconds * 1_000L

/** OSD play/pause control. Seek-bar Down moves here, and the OSD opens on it. */
val LocalPlayerPlayPauseFocus = compositionLocalOf<FocusRequester?> { null }

/**
 * Shared player action bar: transport/source actions stay left, while playback options and
 * navigation stay right. Renderer-specific players only provide the actions they support.
 *
 * Handheld fullscreen uses the same split as television. A windowed handheld page has no room
 * for it, so there the bar packs to the start edge and scrolls.
 */
@Composable
fun PlayerControlBar(
    modifier: Modifier = Modifier,
    player: com.ambient.tv.ui.player.OsdPlayer? = null,
    leftContent: @Composable RowScope.() -> Unit,
    rightContent: @Composable RowScope.() -> Unit,
) {
    val presence = remember { com.ambient.tv.ui.player.OsdPresence() }
    if (player != null) {
        androidx.compose.runtime.SideEffect { com.ambient.tv.ui.player.activeOsdPlayer = player }
        // Publishes what the bar offers; an empty set (bar leaving) keeps the last one.
        LaunchedEffect(presence) {
            androidx.compose.runtime.snapshotFlow { presence.counts.keys.toSet() }.collect {
                if (it.isNotEmpty()) com.ambient.tv.ui.player.activeOsdButtons = it
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.ambient.tv.ui.player.LocalOsdPlayer provides player,
        com.ambient.tv.ui.player.LocalOsdPresence provides presence.takeIf { player != null },
    ) {
        PlayerControlBarContent(modifier, leftContent, rightContent)
    }
}

@Composable
private fun PlayerControlBarContent(
    modifier: Modifier,
    leftContent: @Composable RowScope.() -> Unit,
    rightContent: @Composable RowScope.() -> Unit,
) {
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val handheldFullscreen = com.ambient.tv.ui.mobile.LocalHandheldFullscreen.current?.enabled == true
    if (handheld && !handheldFullscreen) {
        Row(modifier.fillMaxWidth().height(58.dp).horizontalScroll(rememberSessionBarScroll("windowed")),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            leftContent()
            Spacer(Modifier.width(12.dp))
            com.ambient.tv.ui.mobile.HandheldFullscreenButton()
            com.ambient.tv.ui.player.ChapterButton()
            rightContent()
        }
        return
    }
    if (handheld) {
        HandheldFullscreenControlBar(modifier, leftContent, rightContent)
        return
    }
    val focusManager = LocalFocusManager.current
    val controlFocus = com.ambient.tv.ui.player.LocalPlayerControlFocus.current
    val osdStow = com.ambient.tv.ui.player.LocalPlayerOsdStow.current
    val stowed = controlFocus?.seekFocused == true || osdStow?.closing == true
    // Slides down while the seek bar holds focus and when the OSD closes; the block above follows
    // only for the seek bar, a closing OSD fades it in place.
    // Stays in the layout so D-pad down still finds it.
    // The OSD opening straight onto the seek bar snaps instead of visibly sliding away.
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    val stow = remember { androidx.compose.animation.core.Animatable(if (stowed) 1f else 0f) }
    LaunchedEffect(stowed) {
        val target = if (stowed) 1f else 0f
        if (android.os.SystemClock.uptimeMillis() - openedAt < 300L) stow.snapTo(target)
        // Closing gets a longer, eased slide so the bar is visibly stowed before the OSD fades.
        else stow.animateTo(target, if (osdStow?.closing == true) androidx.compose.animation.core.tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing) else androidx.compose.animation.core.tween(200))
    }
    if (osdStow != null) LaunchedEffect(osdStow) {
        androidx.compose.runtime.snapshotFlow { stow.value }.collect { osdStow.fraction = it }
    }
    val navigation = com.ambient.tv.ui.player.LocalPlaybackNavigation.current
    Column(
        modifier.fillMaxWidth().graphicsLayer {
            translationY = stow.value * 72.dp.toPx()
            alpha = 1f - stow.value
        },
    ) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        controlFocus?.seekTarget()?.let { runCatching { it.requestFocus() } }
                        true // No seek bar: keep focus in the controls.
                    }
                    // Second Down below the seek bar: the chapter row.
                    Key.DirectionDown -> {
                        if (navigation != null && navigation.timeline.chapters.isNotEmpty()) {
                            navigation.interact(); navigation.chaptersOpen = true; true
                        } else false
                    }
                    Key.DirectionLeft -> wrapBarFocus(focusManager, FocusDirection.Left, FocusDirection.Right)
                    Key.DirectionRight -> wrapBarFocus(focusManager, FocusDirection.Right, FocusDirection.Left)
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leftContent()
        Spacer(Modifier.weight(1f))
        com.ambient.tv.ui.player.ChapterButton()
        rightContent()
    }
    com.ambient.tv.ui.player.ChapterStrip()
    }
}

/** Scroll state of a handheld action bar that comes back where it was within the playback session. */
@Composable
private fun rememberSessionBarScroll(bar: String): androidx.compose.foundation.ScrollState {
    val metadata = com.ambient.tv.ui.player.LocalPlaybackMetadata.current
    val session = metadata?.let { it.source to it.mediaId }
    val state = remember(session, bar) { androidx.compose.foundation.ScrollState(PlayerControlBarScroll.offset(session, bar)) }
    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.value }.collect { PlayerControlBarScroll.save(session, bar, it) }
    }
    return state
}

/**
 * Moves within the action bar and wraps at its ends: past the first action lands on the last
 * and vice versa. Nothing focusable sits beside the bar, so a failed move means an edge.
 */
private fun wrapBarFocus(focusManager: FocusManager, direction: FocusDirection, back: FocusDirection): Boolean {
    if (!focusManager.moveFocus(direction)) {
        repeat(MAX_BAR_ACTIONS) { if (!focusManager.moveFocus(back)) return true }
    }
    return true
}

private const val MAX_BAR_ACTIONS = 32

/**
 * Television's two-sided action bar for handheld fullscreen. A weighted spacer cannot do this
 * here: the narrowest landscape windows are not wide enough for every action, and those must
 * stay reachable by scrolling instead of being pushed off the screen edge. The bar therefore
 * grows to the window width, pinning both groups to their edges, and past that to its own
 * content width, where it scrolls exactly like the windowed bar.
 */
@Composable
private fun HandheldFullscreenControlBar(
    modifier: Modifier,
    leftContent: @Composable RowScope.() -> Unit,
    rightContent: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(58.dp)) {
        // Only a bounded parent can pin the groups to the window edges; otherwise the bar keeps
        // its own content width, which is the packed layout.
        val windowWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        Row(Modifier.fillMaxHeight().horizontalScroll(rememberSessionBarScroll("fullscreen"))) {
            Layout(
                modifier = Modifier.fillMaxHeight(),
                content = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        leftContent()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        com.ambient.tv.ui.mobile.HandheldFullscreenButton()
                        com.ambient.tv.ui.player.ChapterButton()
                        rightContent()
                    }
                },
            ) { measurables, barConstraints ->
                val groupConstraints = barConstraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
                val start = measurables[0].measure(groupConstraints)
                val end = measurables[1].measure(groupConstraints)
                val width = maxOf(windowWidth, start.width + end.width + 12.dp.roundToPx())
                val height = barConstraints.constrainHeight(maxOf(start.height, end.height))
                layout(width, height) {
                    start.placeRelative(0, (height - start.height) / 2)
                    end.placeRelative(width - end.width, (height - end.height) / 2)
                }
            }
        }
    }
}

@Composable
fun UnifiedPlayerSidePanel(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A level re-entered by Back focuses the row that opened the child instead of its first row.
    val navigation = LocalPlayerPanelNavigation.current
    // A level that already held focus (its content was rebuilt, e.g. by a renderer switch) puts
    // focus back on its last row instead of starting over.
    val levelFresh = remember { navigation?.levelFresh != false }
    val requestInitialFocus = remember {
        levelFresh && navigation?.restoreKey == null &&
            (navigation?.panel !in setOf(UnifiedPlayerPanel.VIDEO, UnifiedPlayerPanel.VIDEO_SECTION, UnifiedPlayerPanel.VIDEO_SVP, UnifiedPlayerPanel.VIDEO_SVP_ADVANCED, UnifiedPlayerPanel.RENDERER) ||
                navigation?.videoState?.focusedSelector == null) &&
            (navigation?.panel?.isAudio != true || navigation.audioState.focusedSelector == null)
    }
    RightSidePanel(
        title = title,
        onBack = onBack,
        // Handheld: a tap beside the panel closes it entirely, not just one level.
        onOutsideTap = navigation?.let { nav -> { nav.close() } } ?: onBack,
        modifier = modifier,
        // Television: 38 % of the screen; labels that do not fit scroll while focused.
        width = if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
            if (navigation?.panel in setOf(UnifiedPlayerPanel.VIDEO, UnifiedPlayerPanel.VIDEO_SECTION, UnifiedPlayerPanel.VIDEO_SVP, UnifiedPlayerPanel.VIDEO_SVP_ADVANCED, UnifiedPlayerPanel.RENDERER)) 420.dp else 500.dp
        } else (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.38f).dp,
        requestHeaderFocus = requestInitialFocus || navigation?.restoreFailed == true,
        scrollable = true,
        retainFocus = true,
        focusRescue = navigation?.focusRescue(),
        header = header,
        content = content,
    )
}

@Composable
fun PlayerPanelEntry(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigation = LocalPlayerPanelNavigation.current
    val originKey = "entry:$title"
    RightPanelChoice(
        text = title,
        subtitle = value,
        icon = icon,
        selected = false,
        onClick = { navigation?.fromOrigin(originKey, root = false, onClick) ?: onClick() },
        modifier = modifier
            .playerPanelFocusKey(originKey)
            .fillMaxWidth(),
    )
}

/** Gives a panel row a stable key: it is focused again after its level is rebuilt or a child level closes. */
@Composable
fun Modifier.playerPanelFocusKey(key: String): Modifier {
    val navigation = LocalPlayerPanelNavigation.current ?: return this
    return focusRequester(navigation.focusRequester(key))
        .onFocusChanged { if (it.hasFocus) navigation.noteFocused(key) }
}

@Composable
fun PlayerPanelAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvActionButton(text = label, icon = icon, onClick = onClick, modifier = modifier.playerPanelFocusKey("action:$label"))
}

/**
 * Leaves the fullscreen player OSD while keeping background playback (minimize).
 * Distinct from remote Back with OSD hidden, which stops video.
 * On handhelds, orientation owns fullscreen, so the chip reads as ordinary Back.
 */
/** Action-bar entry to the sleep timer, left of the settings gear; filled while a timer runs. */
@Composable
fun PlayerSleepTimerButton(onClick: () -> Unit, selected: Boolean) {
    val active = com.ambient.tv.ui.player.BackgroundPlaybackSession.sleepTimer.kind != com.ambient.tv.ui.player.SleepTimerKind.OFF
    PlayerIconButton(
        if (active) Icons.Filled.Bedtime else Icons.Outlined.BedtimeOutlined,
        stringResource(R.string.player_sleep_timer),
        onClick,
        selected = selected,
        osd = com.ambient.tv.ui.player.OsdButton.SLEEP,
    )
}

/** Stats-for-Nerds and FPS overlay toggles; opt-in OSD buttons, only with a playback session. */
@Composable
fun PlayerDiagnosticsButtons() {
    val session = com.ambient.tv.ui.player.activeDiagnosticsSession ?: return
    PlayerIconButton(
        Icons.Filled.Info,
        stringResource(R.string.player_stats_for_nerds),
        { session.performanceHint = !session.performanceHint },
        selected = session.performanceHint,
        osd = com.ambient.tv.ui.player.OsdButton.STATS,
    )
    PlayerIconButton(
        Icons.Filled.Speed,
        stringResource(R.string.player_fps_overlay),
        { session.showFpsOverlay = !session.showFpsOverlay },
        selected = session.showFpsOverlay,
        osd = com.ambient.tv.ui.player.OsdButton.FPS,
    )
}

@Composable
fun PlayerExitButton(onBack: () -> Unit) {
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    PlayerIconButton(
        icon = if (handheld) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.FullscreenExit,
        label = stringResource(if (handheld) R.string.back else R.string.player_exit_fullscreen),
        onClick = onBack,
        osd = com.ambient.tv.ui.player.OsdButton.EXIT,
    )
}

/** Compact, icon-led action used in video overlays and the background mini player. */
@Composable
fun PlayerIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    loading: Boolean = false,
    /** False: stays visible and focusable, but dimmed and without action. */
    enabled: Boolean = true,
    /** Action-bar button the viewer can hide for the current player. */
    osd: com.ambient.tv.ui.player.OsdButton? = null,
    /** Round image (e.g. a channel avatar) shown instead of [icon] once loaded. */
    imageUrl: String? = null,
) {
    if (osd != null) {
        // Registered before the visibility check: a hidden button stays switchable in the settings.
        val presence = com.ambient.tv.ui.player.LocalOsdPresence.current
        if (presence != null) androidx.compose.runtime.DisposableEffect(presence, osd) {
            presence.add(osd)
            onDispose { presence.remove(osd) }
        }
        if (!com.ambient.tv.ui.player.osdButtonVisible(osd)) return
    }
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    var focused by remember { mutableStateOf(false) }
    val playPauseFocus = LocalPlayerPlayPauseFocus.current
    val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
    // Action-bar buttons root the panel click path; closing it focuses the button again.
    val navigation = LocalPlayerPanelNavigation.current
    val originKey = "bar:$label"
    val controlFocus = com.ambient.tv.ui.player.LocalPlayerControlFocus.current
    val controlKey = if (primary) "play-pause" else label
    val controlRequester = remember { FocusRequester() }
    val controlOwner = remember { Any() }
    androidx.compose.runtime.DisposableEffect(controlFocus, controlKey) {
        controlFocus?.register(controlKey, controlRequester, controlOwner)
        onDispose { controlFocus?.unregister(controlKey, controlOwner) }
    }
    val click: () -> Unit = { if (enabled) { navigation?.fromOrigin(originKey, root = true, onClick) ?: onClick() } }
    Row(
        modifier = modifier
            .then(if (controlFocus != null) Modifier.focusRequester(controlRequester).onFocusChanged {
                controlFocus.focused(controlKey, controlOwner, it.isFocused)
            } else Modifier)
            .then(if (primary && playPauseFocus != null) Modifier.focusRequester(playPauseFocus) else Modifier)
            .then(if (navigation != null) Modifier.focusRequester(navigation.focusRequester(originKey)) else Modifier)
            // Keep the compact television chrome unchanged. Touch controls need a full
            // 48 dp target even when the visible glyph remains deliberately small.
            .size(if (primary) 58.dp else 48.dp, if (handheld) 48.dp else 40.dp)
            .onFocusChanged { focused = it.isFocused }
            .menuFocusMotion(focused)
            // Size identifies the main action; only actual focus draws the focus treatment.
            .appControlSurface(focused, selected, shape = shape)
            .activateOnTvKey(onClick = click)
            .clickable(role = Role.Button, onClick = click),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val foreground = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = 0.38f) }
        if (loading) {
            CircularProgressIndicator(
                color = foreground,
                modifier = Modifier.size(if (primary) 23.dp else 20.dp),
            )
        } else if (imageUrl != null) {
            coil3.compose.SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = label,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = if (enabled) 1f else 0.38f,
                modifier = Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape),
                error = { Icon(icon, contentDescription = label, tint = foreground, modifier = Modifier.size(20.dp)) },
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = foreground,
                modifier = Modifier.size(if (primary) 23.dp else 20.dp),
            )
        }
    }
}

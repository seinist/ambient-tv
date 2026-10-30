package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.composed
import androidx.compose.runtime.Stable
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.ambient.tv.ui.player.LocalVisibleBackgroundVideo
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.theme.menuFocusMotion

@Composable
fun PageBackground(modifier: Modifier = Modifier) {
    val visibleBackgroundVideo = LocalVisibleBackgroundVideo.current
    if (!visibleBackgroundVideo && !LocalHomeBackgroundVisible.current) AppBackground(modifier)
}

/** App-wide ambient backdrop. It adds depth to otherwise empty TV-sized surfaces without
 * competing with artwork, cards or text. OLED deliberately remains pure black. */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val raised = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val isOled = background == Color.Black

    Canvas(modifier = modifier) {
        drawRect(background)
        if (!isOled) {
            val longestEdge = maxOf(size.width, size.height)

            // A restrained tonal lift prevents the upper content area from reading as a flat
            // grey slab while preserving the dark-room character of the application.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        raised.copy(alpha = 0.24f),
                        surface.copy(alpha = 0.10f),
                        background.copy(alpha = 0.04f),
                        Color.Black.copy(alpha = 0.28f),
                    ),
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.13f),
                        accent.copy(alpha = 0.045f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.84f, size.height * 0.04f),
                    radius = longestEdge * 0.68f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        raised.copy(alpha = 0.20f),
                        surface.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.12f, size.height * 0.92f),
                    radius = longestEdge * 0.52f,
                ),
            )
            // Gentle edge falloff gives large panels depth and keeps the visual emphasis on
            // the content rather than on the background treatment itself.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.20f)),
                    center = Offset(size.width * 0.52f, size.height * 0.43f),
                    radius = longestEdge * 0.78f,
                ),
            )
        }
    }
}

/** Compact utility rail for nested screens. It makes Back and Settings discoverable on a TV
 * remote without consuming the hero/content area. */
@Composable
fun PageUtilityBar(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onDown: (() -> Unit)? = null,
    requestInitialFocus: Boolean = false,
    leftNavigatesBack: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val backFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    RegisterScrollListActions(backFocus)
    var restoreSettings by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(requestInitialFocus) {
        if (!requestInitialFocus) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (runCatching { backFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (restoreSettings) {
                repeat(3) {
                    withFrameNanos { }
                    if (runCatching { settingsFocus.requestFocus() }.getOrDefault(false)) {
                        restoreSettings = false
                        return@repeatOnLifecycle
                    }
                }
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = tokens.screenPadding, end = tokens.screenPadding, top = pageHeaderTop(tokens.screenPadding)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Same icon chips as every page header (Settings, Files, …), so Back looks alike everywhere.
        TvChip(
            stringResource(R.string.back),
            modifier = Modifier.focusRequester(backFocus).downAction(onDown)
                .then(if (leftNavigatesBack) Modifier.leftKeyAction(onBack) else Modifier),
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            iconOnly = true,
            onClick = onBack,
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
            TvChip(
                stringResource(R.string.nav_settings),
                modifier = Modifier.focusRequester(settingsFocus).downAction(onDown),
                icon = Icons.Filled.Settings,
                iconOnly = true,
                onClick = { restoreSettings = true; onSettings() },
            )
            // Detail pages use this rail instead of PageHeader. They follow the same
            // clock setting, while the buttons keep their full touch/focus targets.
            if (LocalClockSettings.current.inHeader) HeaderClock(Modifier.weight(1f, fill = false))
        }
    }
}

/** D-pad Left on a leftmost Back chip does what clicking it does (go back or open the menu). */
fun Modifier.leftKeyAction(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key != Key.DirectionLeft) false else {
        if (event.type == KeyEventType.KeyDown) action()
        true
    }
}

private fun Modifier.downAction(onDown: (() -> Unit)?): Modifier =
    if (onDown == null) this else onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionDown) false else {
            if (event.type == KeyEventType.KeyDown) onDown()
            true
        }
    }

/**
 * Top line of a page: the [title] block left, its [actions] right-aligned on the same line
 * (wrapping below the title on handheld). Header controls are [TvChip]s with an icon, tabs and
 * actions alike, so every page header shares one size and focus look. On a phone, pass
 * [TvChip]'s iconOnly so those actions stay icon buttons; television keeps the labels.
 */
/** Distance of a TV page header from the screen top, where the clock and Home's floating clock sit too. */
val PageHeaderTopTv = 24.dp

/** Header top for a page: the shared TV value, or the page's own [phone] spacing on handhelds. */
@Composable
fun pageHeaderTop(phone: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = if (LocalHandheldUi.current) phone else PageHeaderTopTv

@Composable
fun PageHeader(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    focusChain: PageFocusChain? = null,
    showClock: Boolean = true,
    title: @Composable ColumnScope.() -> Unit,
) {
    val handheld = LocalHandheldUi.current
    val ownActionFocus = remember { FocusRequester() }
    val actionFocus = focusChain?.actions ?: ownActionFocus
    // A list's own Up exit at its top follows the page chain (filter, chips, then these actions).
    val chainUp = remember(focusChain) { focusChain?.let { chain -> { chain.upTarget() } } }
    RegisterScrollListActions(actionFocus, chainUp)
    val clockVisible = showClock && LocalClockSettings.current.inHeader
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // On a phone the full-width clock row keeps long dates and large type away from
        // page actions. It is real layout space, so it never covers the first content row.
        if (handheld && clockVisible) HeaderClock(Modifier.align(Alignment.End))
        DeviceActionRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // The title yields to the actions, so a long library or folder name ellipsizes instead of pushing them out.
            Column(if (handheld) Modifier else Modifier.weight(1f).padding(end = 18.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = title)
            // On handheld the actions never wrap among themselves: they sit beside the title or move as one block to the next line, right-aligned.
            if (handheld) Row(Modifier.weight(1f).focusRequester(actionFocus).browseFocusTarget("page-actions", content = false).focusRestorer().downTo(focusChain).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
            else DeviceActionRow(modifier = Modifier.focusRequester(actionFocus).browseFocusTarget("page-actions", content = false).focusRestorer().downTo(focusChain).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            // Top-aligned, not centred: with every header starting at [pageHeaderTop] the clock sits at the
            // same spot on every page, also where Home's floating clock is.
            if (!handheld && clockVisible) HeaderClock(Modifier.align(Alignment.Top))
        }
    }
}

/**
 * Fixed D-pad Up chain of a page top: content → chip row (its selected chip) → header actions,
 * and Down back the same way. Geometric search alone sent Up from the right side of a grid
 * straight to the right-aligned header actions, past the left-aligned chips.
 */
/**
 * Page controls are traversed by their actual vertical order, never by the D-pad beam.
 * A missing or detached level is skipped, including a hidden filter bar or page chip row.
 */
internal enum class PageFocusLevel(val order: Int) {
    HEADER(0),
    SEARCH_TOP(10),      // Search page: query lives above source chips
    CHIPS(20),
    CONTEXT(23),         // Optional breadcrumb / selected facet
    SEARCH_LOWER(26),    // Provider search below its category chips
    FILTER(30),
    CONTENT(40),
}

@Stable
class PageFocusChain {
    val actions = FocusRequester()
    val chips = FocusRequester()
    val selectedChip = FocusRequester()
    internal var hasChips = false
    private data class Stage(val level: PageFocusLevel, val focus: FocusRequester)
    private val stages = linkedMapOf<Any, Stage>()

    internal fun register(owner: Any, level: PageFocusLevel, requester: FocusRequester) {
        stages[owner] = Stage(level, requester)
    }
    internal fun unregister(owner: Any) { stages.remove(owner) }

    private fun candidates(from: PageFocusLevel, down: Boolean): List<Stage> =
        stages.values.toList().asReversed().distinctBy { it.level }
            .filter { if (down) it.level.order > from.order else it.level.order < from.order }
            .sortedWith(compareBy(if (down) { it.level.order } else { -it.level.order }))

    internal fun previousRequester(from: PageFocusLevel): FocusRequester =
        candidates(from, false).firstOrNull()?.focus ?: actions

    internal fun requestNext(from: PageFocusLevel): Boolean = candidates(from, true).any {
        runCatching { it.focus.requestFocus() }.getOrDefault(false)
    }

    internal fun requestPrevious(from: PageFocusLevel): Boolean {
        if (candidates(from, false).any {
                runCatching { it.focus.requestFocus() }.getOrDefault(false)
            }) return true
        return runCatching { actions.requestFocus() }.getOrDefault(false)
    }

    /** Kept for callers which explicitly request the chip stage, e.g. Search's query field. */
    val contentUp: FocusRequester get() = if (hasChips) chips else actions
    /** Used by the shared lazy-list edge handler. Resolved at key time, not registration time. */
    internal var upTarget: () -> FocusRequester = { previousRequester(PageFocusLevel.CONTENT) }
}

@Composable
internal fun RegisterPageFocusLevel(chain: PageFocusChain, level: PageFocusLevel, requester: FocusRequester) {
    val owner = remember(chain, level, requester) { Any() }
    DisposableEffect(chain, owner, level, requester) {
        chain.register(owner, level, requester)
        onDispose { chain.unregister(owner) }
    }
}

/** For the optional search field or contextual actions that sit between header and content. */
fun Modifier.pageFocusStage(
    chain: PageFocusChain,
    level: PageFocusLevel,
    requester: FocusRequester,
): Modifier = composed {
    RegisterPageFocusLevel(chain, level, requester)
    this.focusRequester(requester).focusRestorer()
        .onPreviewKeyEvent { event ->
            when (event.key) {
                Key.DirectionUp -> when (event.type) {
                    KeyEventType.KeyDown -> chain.requestPrevious(level)
                    KeyEventType.KeyUp -> true
                    else -> false
                }
                Key.DirectionDown -> when (event.type) {
                    KeyEventType.KeyDown -> chain.requestNext(level)
                    KeyEventType.KeyUp -> true
                    else -> false
                }
                else -> false
            }
        }.focusGroup()
}

@Composable
fun rememberPageFocusChain(): PageFocusChain = remember { PageFocusChain() }

private fun Modifier.downTo(chain: PageFocusChain?): Modifier =
    if (chain == null) this else onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionDown) false else when (event.type) {
            KeyEventType.KeyDown -> chain.requestNext(PageFocusLevel.HEADER)
            KeyEventType.KeyUp -> true
            else -> false
        }
    }

/** Up from the content goes to its nearest visible controls, and Down restores this content. */
fun Modifier.pageContentUp(chain: PageFocusChain, target: () -> FocusRequester = { chain.contentUp }): Modifier = composed {
    val requester = remember { FocusRequester() }
    RegisterPageFocusLevel(chain, PageFocusLevel.CONTENT, requester)
    // The shared lazy-list edge handler reads this only when the first content item was reached.
    chain.upTarget = { chain.previousRequester(PageFocusLevel.CONTENT) }
    this.focusRequester(requester).focusRestorer().focusProperties {
        onExit = {
            if (requestedFocusDirection == FocusDirection.Up) {
                cancelFocusChange()
                if (!chain.requestPrevious(PageFocusLevel.CONTENT))
                    runCatching { target().requestFocus() }
            }
        }
    }
}

/**
 * Tab/filter chips under a [PageHeader]. Entering the row lands on the chip focused last (the
 * selected one at first, marked with [PageFocusChain.selectedChip]); Up leaves for [up].
 */
/**
 * Makes a row of its own (a breadcrumb) the chip stage of [chain]: entered on the chip focused
 * last, else on the one carrying [PageFocusChain.selectedChip]; Up leaves for [up].
 */
@Composable
fun Modifier.pageChips(chain: PageFocusChain, up: FocusRequester = chain.actions): Modifier {
    RegisterPageFocusLevel(chain, PageFocusLevel.CHIPS, chain.chips)
    DisposableEffect(chain) {
        chain.hasChips = true
        onDispose { chain.hasChips = false }
    }
    return focusRequester(chain.chips)
        .browseFocusTarget("page-chips", content = false)
        .focusRestorer(chain.selectedChip)
        .onPreviewKeyEvent { event ->
            when (event.key) {
                Key.DirectionUp -> when (event.type) {
                    KeyEventType.KeyDown ->
                        (if (up !== chain.actions) runCatching { up.requestFocus() }.getOrDefault(false) else false) ||
                            chain.requestPrevious(PageFocusLevel.CHIPS)
                    KeyEventType.KeyUp -> true
                    else -> false
                }
                Key.DirectionDown -> when (event.type) {
                    KeyEventType.KeyDown -> chain.requestNext(PageFocusLevel.CHIPS)
                    KeyEventType.KeyUp -> true
                    else -> false
                }
                else -> false
            }
        }
        .focusGroup()
}

@Composable
fun PageChipRow(
    chain: PageFocusChain,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Up from the chips; a page with a control between header and chips (a search field) passes that. */
    up: FocusRequester = chain.actions,
    /** Optional selected position so phone tab swipes/taps keep the active chip visible. */
    selectedIndex: Int? = null,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    val handheld = LocalHandheldUi.current
    LaunchedEffect(selectedIndex, handheld) {
        val index = selectedIndex ?: return@LaunchedEffect
        if (handheld && index >= 0) state.animateScrollToItem(index)
    }
    LazyRow(
        state = state,
        modifier = modifier.pageChips(chain, up),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    firstInContent: Boolean = false,
) {
    SettingsHeading(title, SettingsHeadingLevel.SECTION, modifier, description, firstInContent)
}

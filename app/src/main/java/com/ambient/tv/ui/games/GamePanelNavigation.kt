package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.PlayerPanelNavigation
import com.ambient.tv.ui.common.RestorePlayerPanelFocus
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.common.playerPanelFocusKey
import com.ambient.tv.ui.common.retryFocusFor
import com.ambient.tv.ui.player.PlayerChromeState

internal enum class GamePanel {
    SETTINGS, OSD_BUTTONS, PICTURE, DISPLAY, CORE, DISC, CONTROLS, INFO, ACHIEVEMENTS, CHEATS, NETPLAY,
}

/** A distinct page identity separates core categories and diagnostics from their parent panel. */
@Immutable
internal data class GamePanelPage(
    val panel: GamePanel,
    val optionCategory: String? = null,
    /** Info → Vulkan/HDR diagnostics, the game's counterpart of the video "Stats for nerds". */
    val diagnostics: Boolean = false,
)

/** Game pages own their content; the common video navigation owns origin/focus restoration. */
@Stable
internal class GamePanelNavigation(
    val navigation: PlayerPanelNavigation,
    internal val chrome: PlayerChromeState? = null,
) {
    val page: GamePanelPage? get() = navigation.externalPage as? GamePanelPage
    val panel: GamePanel? get() = page?.panel
    val optionCategory: String? get() = page?.optionCategory
    val diagnosticsOpen: Boolean get() = page?.diagnostics == true
    val fromSettings: Boolean get() = navigation.hasExternalParent(GamePanelPage(GamePanel.SETTINGS))
    var statesOpen by mutableStateOf(false)
        private set
    private var statesOrigin: String? = null

    fun open(target: GamePanel) {
        closeStates(restore = false)
        navigation.openExternal(GamePanelPage(target))
    }

    /** Hardware shortcuts can open a root directly, without inventing an unrelated parent. */
    fun openRoot(target: GamePanel) {
        navigation.close()
        navigation.cancelFocusReturn()
        open(target)
    }

    fun openCategory(key: String) {
        val current = page?.takeIf { it.panel == GamePanel.CORE } ?: return
        navigation.openExternal(current.copy(optionCategory = key))
    }

    fun openDiagnostics() {
        val current = page?.takeIf { it.panel == GamePanel.INFO } ?: return
        navigation.openExternal(current.copy(diagnostics = true))
    }

    /** A late animation callback from a former page must not pop the page now being used. */
    fun back(expected: GamePanelPage? = page) {
        if (expected != null && page == expected) navigation.back(UnifiedPlayerPanel.GAME)
    }

    fun close() = navigation.close()

    fun toggleStates() {
        if (statesOpen) {
            closeStates()
        } else {
            val origin = navigation.currentOriginKey
            navigation.close()
            navigation.cancelFocusReturn()
            statesOrigin = origin
            statesOpen = true
        }
    }

    fun closeStates(restore: Boolean = true) {
        if (!statesOpen) return
        statesOpen = false
        val origin = statesOrigin
        statesOrigin = null
        if (restore) navigation.restoreExternalOrigin(origin)
    }

    /** Resume/exit cannot schedule a focus hand-back that reveals the OSD again. */
    fun dismissForResume() {
        closeStates(restore = false)
        navigation.close()
        navigation.cancelFocusReturn()
    }
}

@Composable
internal fun rememberGamePanelNavigation(chrome: PlayerChromeState): GamePanelNavigation {
    val panels = remember(chrome) { GamePanelNavigation(chrome.panels, chrome) }
    RestorePlayerPanelFocus(panels.navigation, chrome)
    return panels
}

/** Same panel row and visuals, with its click origin retained and unavailable rows omitted. */
@Composable
internal fun GamePanelChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    focusKey: String = "game-entry:$text",
) {
    if (!enabled) return
    val navigation = LocalPlayerPanelNavigation.current
    RightPanelChoice(
        text = text, selected = selected, icon = icon, subtitle = subtitle,
        trailingIcon = trailingIcon,
        modifier = modifier.playerPanelFocusKey(focusKey),
        onClick = { navigation?.fromOrigin(focusKey, root = false, onClick) ?: onClick() },
    )
}

/** The drawer owns its right anchor; Back and remounts reuse the common keyed-row rescue. */
@Composable
internal fun BoxScope.GameSidePanel(
    panels: GamePanelNavigation,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val page = panels.page ?: return
    val navigation = panels.navigation
    val initialFocus = remember(navigation, page) { navigation.levelFresh && navigation.restoreKey == null }
    RightSidePanel(
        // The shared slide adds one drawer width to this resting position. Without an End
        // anchor it enters near the centre and settles at the player's left edge.
        title = title, subtitle = subtitle, modifier = modifier.align(Alignment.CenterEnd),
        onBack = { panels.back(page) },
        onOutsideTap = panels::close,
        closeInsteadOfBack = navigation.depth <= 1,
        // A child level returns to its parent inside the same drawer (see GamePanelOverlay).
        backWithinPanel = navigation.depth > 1,
        requestHeaderFocus = initialFocus || navigation.restoreFailed,
        focusRescue = navigation.focusRescue(),
        content = content,
    )
}

/** Continue is the initial/fallback target; returning from a child never overwrites its origin. */
@Composable
internal fun RequestGameOsdFocus(
    active: Boolean,
    panels: GamePanelNavigation,
    continueRequester: FocusRequester,
    recoveryRequester: FocusRequester = continueRequester,
) {
    val currentActive by rememberUpdatedState(active)
    val navigation = panels.navigation
    val window = LocalWindowInfo.current
    val controls = panels.chrome?.focus
    // This reads the observable control registrations and last button; disappearance is an
    // event, not a frame poll. Ordinary movement preserves its already focused destination.
    val registeredTarget = controls?.buttonTarget(recoveryRequester)
    val hasControlFocus = controls?.hasFocus == true
    val focusedTarget = if (hasControlFocus) controls.target(recoveryRequester) else null
    var retainedTarget by remember(controls) { mutableStateOf<FocusRequester?>(null) }
    val removedTarget = retainedTarget?.takeUnless { controls?.isRegistered(it) == true }
    SideEffect {
        if (!active || panels.panel != null || panels.statesOpen || navigation.restoreKey != null ||
            navigation.dialogFocusOwner != null) {
            retainedTarget = null
        } else if (removedTarget == null && hasControlFocus) {
            // Capture during composition: node removal can already change live focus before
            // this side effect runs, while the outgoing registration is being disposed.
            retainedTarget = focusedTarget
        }
    }
    fun unobstructed(): Boolean = currentActive && panels.panel == null && !panels.statesOpen &&
        navigation.restoreKey == null && navigation.dialogFocusOwner == null && window.isWindowFocused
    fun recover(): Boolean = runCatching { continueRequester.requestFocus() }.getOrDefault(false) ||
        runCatching { recoveryRequester.requestFocus() }.getOrDefault(false)
    LaunchedEffect(active, panels, continueRequester, recoveryRequester) {
        if (!active) {
            navigation.cancelFocusReturn()
            return@LaunchedEffect
        }
        retryFocusFor {
            if (!unobstructed()) true else recover()
        }
    }
    LaunchedEffect(active, navigation.restoreFailed, navigation.restoreGeneration, navigation.dialogFocusOwner) {
        if (!active || !navigation.restoreFailed || panels.panel != null || panels.statesOpen ||
            navigation.dialogFocusOwner != null) return@LaunchedEffect
        retryFocusFor {
            if (!unobstructed()) true
            else recover().also { placed ->
                if (placed) navigation.cancelFocusReturn()
            }
        }
    }
    LaunchedEffect(active, registeredTarget, hasControlFocus, removedTarget, window.isWindowFocused) {
        // Detaching a focused button can make Compose focus its first surviving sibling.
        // Retain the old registration until our hand-back finishes, so that automatic focus
        // cannot disguise a removal as ordinary navigation (or land on Exit while saving).
        if (controls == null || (removedTarget == null && controls.hasFocus) || !unobstructed()) return@LaunchedEffect
        retryFocusFor {
            if (!unobstructed() || (removedTarget == null && controls.hasFocus)) true
            else recover().also { placed ->
                if (placed) retainedTarget = controls.target(recoveryRequester)
            }
        }
    }
}

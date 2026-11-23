package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.ambient.tv.ui.common.RightPanelOverlay

/**
 * One drawer per game-panel session, like the video player's settings: opening a sub-level
 * (Settings → Picture → shader group, core category → option) swaps the content inside the
 * drawer that is already shown instead of sliding in another panel. Each level still gets its
 * own fresh panel content (scroll position, focus rescue), keyed by [level]. Back from a
 * sub-level returns in place ([GameSidePanel] passes `backWithinPanel`); only closing the
 * whole session animates the drawer out.
 */
@Composable
internal fun GamePanelOverlay(
    vararg level: Any?,
    onOutsideTap: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    RightPanelOverlay(onOutsideTap = onOutsideTap) {
        // One structural key also supports a dynamic number of nested levels. The pinned
        // Compose compiler cannot lower a spread-only vararg key call.
        key(level.toList()) { content() }
    }
}

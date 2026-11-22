package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.ambient.tv.ui.common.RightPanelOverlay

/**
 * Each standalone game-panel level owns its entrance and one-shot dismissal state. A child
 * animates out before Back restores its parent; retaining the dismissed child's overlay would
 * leave that parent offscreen with input disabled. The level includes nested core/shader pages.
 */
@Composable
internal fun GamePanelOverlay(
    vararg level: Any?,
    onOutsideTap: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    // One structural key also supports a dynamic number of nested levels. The pinned
    // Compose compiler cannot lower a spread-only vararg key call.
    key(level.toList()) {
        RightPanelOverlay(onOutsideTap = onOutsideTap, content = content)
    }
}

package com.ambient.tv.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import com.ambient.tv.data.local.UiRenderResolutionPolicy

/** Pixel geometry of the current UI surface, independent of the user's control-size setting. */
internal data class UiRenderEnvironment(
    val modeKey: String = UiRenderResolutionPolicy.SYSTEM,
    val viewport: UiRenderViewport? = null,
) {
    val embedded: Boolean get() = modeKey != UiRenderResolutionPolicy.SYSTEM
    val densityScale: Float get() = viewport?.let {
        if (it.windowWidth > 0) it.renderWidth.toFloat() / it.windowWidth else 1f
    } ?: 1f
}

internal val LocalUiRenderEnvironment = staticCompositionLocalOf { UiRenderEnvironment() }

/** Android's WindowMetrics describe the outer Activity, not its embedded ViewRoot. */
internal class UiRenderWindowInfo(
    windowInfo: WindowInfo,
    viewport: UiRenderViewport,
) : WindowInfo by windowInfo {
    override val containerSize = IntSize(viewport.renderWidth, viewport.renderHeight)
    // Logical dp breakpoints, focus and modifier keys still belong to the original window.
}

/** Render-space gesture pixels must represent the same distances and speeds on the outer window. */
internal class UiRenderViewConfiguration(
    private val windowConfiguration: ViewConfiguration,
    private val renderScale: Float,
) : ViewConfiguration by windowConfiguration {
    override val touchSlop: Float get() = windowConfiguration.touchSlop * renderScale
    override val handwritingSlop: Float get() = windowConfiguration.handwritingSlop * renderScale
    override val handwritingGestureLineMargin: Float
        get() = windowConfiguration.handwritingGestureLineMargin * renderScale
    override val minimumFlingVelocity: Float get() = windowConfiguration.minimumFlingVelocity * renderScale
    override val maximumFlingVelocity: Float
        get() = (windowConfiguration.maximumFlingVelocity * renderScale).coerceAtMost(Float.MAX_VALUE)
    // Timeouts and the dp-valued minimumTouchTargetSize are intentionally delegated unchanged.
}

/**
 * Lower buffer resolution must not enlarge the menus. Preserve dp and font scale in the
 * original window, then let SurfaceFlinger enlarge the smaller UI buffer to that same window.
 * Dialogs pass their parent's density with its render factor removed, retaining uiScale.
 */
@Composable
internal fun ProvideUiRenderEnvironment(
    modeKey: String,
    viewport: UiRenderViewport?,
    windowDensity: Density = LocalDensity.current,
    content: @Composable () -> Unit,
) {
    val environment = remember(modeKey, viewport) { UiRenderEnvironment(modeKey, viewport) }
    val density = remember(windowDensity.density, windowDensity.fontScale, environment.densityScale) {
        Density(windowDensity.density * environment.densityScale, windowDensity.fontScale)
    }
    val windowConfiguration = LocalViewConfiguration.current
    val viewConfiguration = remember(windowConfiguration, environment.densityScale) {
        if (environment.densityScale == 1f) windowConfiguration
        else UiRenderViewConfiguration(windowConfiguration, environment.densityScale)
    }
    val windowInfo = LocalWindowInfo.current
    val renderWindowInfo = remember(windowInfo, viewport) {
        if (viewport == null) windowInfo else UiRenderWindowInfo(windowInfo, viewport)
    }
    CompositionLocalProvider(
        LocalUiRenderEnvironment provides environment,
        LocalDensity provides density,
        LocalViewConfiguration provides viewConfiguration,
        LocalWindowInfo provides renderWindowInfo,
    ) {
        content()
    }
}

package com.ambient.tv.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SportsEsports
import androidx.tv.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle

/** Layout stays stable when the OSD opens, so pausing never resizes/recreates the native surface. */
@Composable
internal fun GamePlayerTouchLayout(
    handheld: Boolean,
    directPointer: Boolean,
    touchEnabled: Boolean,
    touchMode: GameTouchMode,
    playing: Boolean,
    menuVisible: Boolean,
    onMenu: () -> Unit,
    onButton: (RetroPadButton, Boolean) -> Unit,
    onStick: (Int, Float, Float) -> Unit,
    surface: @Composable (Modifier) -> Unit,
    touchStyle: TouchControlStyle = TouchControlStyle(),
    presentation: GameTouchPresentation = GameTouchPresentation.Generic,
    coreActions: Map<RetroPadButton, String> = emptyMap(),
    hiddenForGamepad: Boolean = false,
    onShowTouch: () -> Unit = {},
    contentAspectRatio: Float = 4f / 3f,
    fitSurface: Boolean = true,
    /** Known source size while whole-number scaling owns the fit ([fitSurface] false). */
    integerFrame: GameContentFrame? = null,
    integerScalingAuto: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxSize().then(if (handheld) Modifier.safeDrawingPadding() else Modifier)) {
        val landscape = handheld && maxWidth >= maxHeight
        val docked = handheld && touchEnabled && maxHeight > maxWidth
        val deckHeight = if (docked) gameTouchDeckHeight(touchMode, maxWidth.value, touchStyle, presentation).dp.coerceAtMost(maxHeight) else 0.dp
        val sideLayout = remember(landscape, maxWidth, maxHeight, touchMode, touchEnabled, touchStyle,
            presentation, contentAspectRatio, fitSurface) {
            if (landscape) gamePlayerLandscapeLayout(touchMode, maxWidth.value, maxHeight.value,
                contentAspectRatio, touchEnabled, touchStyle, presentation, fitSurface) else null
        }
        // Portrait keeps its deck and app controls. Landscape utilities use the side gutters,
        // so a 4:3 game can use the full safe height without a top strip shrinking its picture.
        val toolbarHeight = if (handheld && !landscape) 72.dp else 0.dp
        val viewportHeight = (maxHeight - deckHeight - toolbarHeight).coerceAtLeast(1.dp)
        val surfaceBounds = sideLayout?.surface ?: if (handheld && fitSurface)
            gamePictureBounds(maxWidth.value, viewportHeight.value, contentAspectRatio)
                .translate(Offset(0f, toolbarHeight.value))
            else Rect(0f, toolbarHeight.value, maxWidth.value, toolbarHeight.value + viewportHeight.value)
        fun Rect.position() = Modifier.offset(left.dp, top.dp).size(width.dp, height.dp)
        // Whole-number scaling with a known source size: the surface takes the exact picture in
        // pixels (dp rounding could lose a whole factor), so the ambient fill meets its edge.
        val density = LocalDensity.current
        val integerPicture = if (handheld && !fitSurface && integerFrame != null) with(density) {
            val area = IntRect(surfaceBounds.left.dp.roundToPx(), surfaceBounds.top.dp.roundToPx(),
                surfaceBounds.right.dp.roundToPx(), surfaceBounds.bottom.dp.roundToPx())
            gameIntegerPictureBounds(area, integerFrame, integerScalingAuto, contentAspectRatio)
        } else null
        surface(
            (integerPicture?.pixelPosition() ?: surfaceBounds.position())
                .testTag("game-player-surface")
                // With a gamepad shown, only the dedicated menu button opens the OSD.
                // Pointer systems continue to receive untouched native DS/3DS/Wii input.
                .then(if (handheld && !directPointer && !touchEnabled && !menuVisible)
                    Modifier.clickable(onClick = onMenu) else Modifier),
        )
        if (docked) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(deckHeight)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .24f)))),
        )
        if (handheld && playing && touchEnabled && !menuVisible && !hiddenForGamepad) {
            GameTouchControls(
                mode = touchMode,
                onButton = onButton,
                onStick = onStick,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .height(if (docked) deckHeight else maxHeight),
                portrait = docked,
                applySafeInsets = false,
                style = touchStyle,
                presentation = presentation,
                coreActions = coreActions,
                layoutOverride = sideLayout?.controls,
            )
        }
        if (handheld && touchEnabled && hiddenForGamepad && !menuVisible) {
            Box((sideLayout?.showTouch?.position() ?: Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp))
                .testTag("game-touch-show").clip(CircleShape)
                .background(Color.Black.copy(alpha = .58f)).clickable(onClick = onShowTouch),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SportsEsports, stringResource(R.string.game_touch_show),
                    tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        if (handheld && !menuVisible) {
            Box(
                (sideLayout?.menu?.position() ?: Modifier.align(Alignment.TopStart).padding(12.dp).size(48.dp))
                    .testTag("game-player-menu")
                    .clip(CircleShape).background(Color.Black.copy(alpha = .58f))
                    .clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.game_controls_menu),
                    tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Exact pixel placement; the native renderer's whole-number factor depends on every pixel. */
private fun IntRect.pixelPosition(): Modifier = Modifier
    .offset { IntOffset(left, top) }
    .layout { measurable, _ ->
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SportsEsports
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import com.ambient.tv.ui.common.holdAction

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
    rewindSeconds: Float? = null,
    onRewind: (Boolean) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize().then(if (handheld) Modifier.safeDrawingPadding() else Modifier)) {
        val docked = handheld && touchEnabled && maxHeight > maxWidth
        val deckHeight = if (docked) gameTouchDeckHeight(touchMode, maxWidth.value, touchStyle, presentation).dp.coerceAtMost(maxHeight) else 0.dp
        // Pad utilities are above the picture in landscape and below it in portrait.
        // Reserve the same space while paused/hidden so the native surface never jumps.
        val toolbarHeight = when {
            !handheld -> 0.dp
            touchEnabled && !docked -> gameTouchUtilityToolbarHeight(
                touchMode, maxWidth.value, maxHeight.value, touchStyle, presentation,
            ).dp
            else -> GAME_TOUCH_TOOLBAR_MIN_HEIGHT.dp
        }
        val viewportHeight = (maxHeight - deckHeight - toolbarHeight).coerceAtLeast(1.dp)
        surface(
            Modifier.align(Alignment.TopCenter).offset(y = toolbarHeight).fillMaxWidth().height(viewportHeight)
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
            )
        }
        if (handheld && touchEnabled && hiddenForGamepad && !menuVisible) {
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp)
                .testTag("game-touch-show").clip(CircleShape)
                .background(Color.Black.copy(alpha = .58f)).clickable(onClick = onShowTouch),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SportsEsports, stringResource(R.string.game_touch_show),
                    tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        if (handheld && !menuVisible) {
            Box(
                Modifier.align(Alignment.TopStart).padding(12.dp).size(48.dp)
                    .testTag("game-player-menu")
                    .clip(CircleShape).background(Color.Black.copy(alpha = .58f))
                    .clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.game_controls_menu),
                    tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        if (handheld && playing && !menuVisible && rewindSeconds != null) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                .size(width = 56.dp, height = 48.dp).testTag("game-player-rewind").clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = .58f)).holdAction(onHold = onRewind),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FastRewind, stringResource(R.string.game_rewind_hold),
                        tint = Color.White, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.game_rewind_buffer_seconds, rewindSeconds),
                        color = Color.White, style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                        maxLines = 1)
                }
            }
        }
    }
}

package com.ambient.tv.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * Touch layer shared by all playback backends.
 *
 * It deliberately sits between the native video surface and the Compose OSD. That means OSD
 * buttons keep receiving their own taps, while taps on otherwise empty video space still work.
 * A single tap toggles the OSD. Double-tapping the left/right thirds seeks when seeking is
 * available; double-tapping the centre toggles play/pause.
 */
@Composable
fun PlayerTouchLayer(
    onToggleControls: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBack: (() -> Unit)? = null,
    onSeekForward: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(onToggleControls, onTogglePlayback, onSeekBack, onSeekForward) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { position: Offset ->
                        when {
                            position.x < size.width * 0.35f && onSeekBack != null -> onSeekBack()
                            position.x > size.width * 0.65f && onSeekForward != null -> onSeekForward()
                            else -> onTogglePlayback()
                        }
                    },
                )
            },
    )
}

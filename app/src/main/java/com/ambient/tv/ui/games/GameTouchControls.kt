package com.ambient.tv.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Responsive touch-only overlay. Empty areas remain transparent to core pointer input (DS/3DS). */
@Composable
internal fun GameTouchControls(
    mode: GameTouchMode,
    onButton: (RetroPadButton, Boolean) -> Unit,
    onStick: (Int, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.safeDrawingPadding()) {
        val small = maxWidth < 600.dp
        val key = if (small) 40.dp else 49.dp
        val large = if (small) 116.dp else 148.dp
        val margin = if (small) 8.dp else 18.dp
        val bottom = if (small) 12.dp else 24.dp
        val faceSize = if (mode == GameTouchMode.DUAL) key * 0.85f else key

        if (mode == GameTouchMode.DPAD) {
            GameTouchDpad(
                size = large,
                onDirection = { next ->
                    // Exactly the changed bits are emitted. Holding A on another finger remains untouched.
                    RetroPadButton.entries.filter { it in DPAD_BUTTONS }.forEach { button ->
                        onButton(button, button in next)
                    }
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = margin, bottom = bottom),
            )
        } else {
            GameTouchStick(
                index = 0, diameter = large, onStick = onStick,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = margin, bottom = bottom),
            )
        }

        if (mode == GameTouchMode.DUAL) {
            GameTouchStick(
                index = 1, diameter = large * 0.82f, onStick = onStick,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = margin, bottom = bottom),
            )
        }
        GameTouchFaces(
            key = faceSize,
            onButton = onButton,
            modifier = Modifier.align(if (mode == GameTouchMode.DUAL) Alignment.TopEnd else Alignment.BottomEnd)
                .padding(end = margin, top = if (mode == GameTouchMode.DUAL) 68.dp else 0.dp,
                    bottom = if (mode == GameTouchMode.DUAL) 0.dp else bottom),
        )

        // L/R and secondary triggers are reachable while the left thumb is on the D-pad/stick.
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 70.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GameTouchButton(RetroPadButton.L2, key * 0.85f, onButton)
            GameTouchButton(RetroPadButton.L, key * 0.85f, onButton)
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = margin, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GameTouchButton(RetroPadButton.R, key * 0.85f, onButton)
            GameTouchButton(RetroPadButton.R2, key * 0.85f, onButton)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(
                bottom = if (maxWidth < 460.dp) large + 8.dp else 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GameTouchButton(RetroPadButton.SELECT, key * 0.85f, onButton, label = "SEL")
            GameTouchButton(RetroPadButton.START, key * 0.85f, onButton, label = "START")
        }
    }
}

/** Input calculation is independent of Compose so diagonal drags and center release can be tested. */
internal fun touchDpadDirections(position: Offset, size: IntSize): Set<RetroPadButton> {
    if (size.width <= 0 || size.height <= 0) return emptySet()
    val x = (position.x - size.width / 2f) / (size.width / 2f)
    val y = (position.y - size.height / 2f) / (size.height / 2f)
    return buildSet {
        if (x < -0.28f) add(RetroPadButton.LEFT)
        if (x > 0.28f) add(RetroPadButton.RIGHT)
        if (y < -0.28f) add(RetroPadButton.UP)
        if (y > 0.28f) add(RetroPadButton.DOWN)
    }
}

private val DPAD_BUTTONS = setOf(
    RetroPadButton.UP, RetroPadButton.DOWN, RetroPadButton.LEFT, RetroPadButton.RIGHT,
)

@Composable
private fun GameTouchDpad(
    size: Dp,
    onDirection: (Set<RetroPadButton>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(emptySet<RetroPadButton>()) }
    val currentOnDirection by rememberUpdatedState(onDirection)
    DisposableEffect(Unit) {
        onDispose { currentOnDirection(emptySet()) }
    }
    Box(
        modifier = modifier.size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.37f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun update(position: Offset) {
                        val next = touchDpadDirections(position, this@pointerInput.size)
                        if (next != selected) {
                            selected = next
                            currentOnDirection(next)
                        }
                    }
                    try {
                        update(down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            update(change.position)
                            change.consume()
                        }
                    } finally {
                        selected = emptySet()
                        currentOnDirection(emptySet())
                    }
                }
            },
    ) {
        GameDpadArrow("▲", Alignment.TopCenter, RetroPadButton.UP in selected)
        GameDpadArrow("▼", Alignment.BottomCenter, RetroPadButton.DOWN in selected)
        GameDpadArrow("◀", Alignment.CenterStart, RetroPadButton.LEFT in selected)
        GameDpadArrow("▶", Alignment.CenterEnd, RetroPadButton.RIGHT in selected)
    }
}

@Composable
private fun GameDpadArrow(symbol: String, alignment: Alignment, pressed: Boolean) {
    Text(
        text = symbol,
        fontSize = 27.sp,
        color = if (pressed) Color.White else Color.White.copy(alpha = 0.68f),
        modifier = Modifier.padding(14.dp).then(Modifier),
    )
}

/** Clamp a thumb gesture to the stick circle and return libretro axis signs. */
internal fun touchStickAxes(position: Offset, size: IntSize): Offset {
    if (size.width <= 0 || size.height <= 0) return Offset.Zero
    val x = (position.x - size.width / 2f) / (size.width * 0.42f)
    val y = (position.y - size.height / 2f) / (size.height * 0.42f)
    val length = hypot(x.toDouble(), y.toDouble()).toFloat().coerceAtLeast(1f)
    return Offset(x / length, y / length)
}

@Composable
private fun GameTouchStick(
    index: Int,
    diameter: Dp,
    onStick: (Int, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    val currentOnStick by rememberUpdatedState(onStick)
    DisposableEffect(index) {
        onDispose { currentOnStick(index, 0f, 0f) }
    }
    val pixels = with(LocalDensity.current) { diameter.toPx() }
    Box(
        modifier = modifier.size(diameter)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.37f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .pointerInput(index) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun update(position: Offset) {
                        offset = touchStickAxes(position, this@pointerInput.size)
                        currentOnStick(index, offset.x, offset.y)
                    }
                    try {
                        update(down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            update(change.position)
                            change.consume()
                        }
                    } finally {
                        offset = Offset.Zero
                        currentOnStick(index, 0f, 0f)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .offset {
                    val travel = pixels * 0.28f
                    IntOffset((offset.x * travel).roundToInt(), (offset.y * travel).roundToInt())
                }
                .size(diameter * 0.45f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.32f))
                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
        )
    }
}

@Composable
private fun GameTouchFaces(
    key: Dp,
    onButton: (RetroPadButton, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            Spacer(Modifier.size(key))
            GameTouchButton(RetroPadButton.X, key, onButton)
            Spacer(Modifier.size(key))
        }
        Row {
            GameTouchButton(RetroPadButton.Y, key, onButton)
            Spacer(Modifier.size(key))
            GameTouchButton(RetroPadButton.A, key, onButton)
        }
        Row {
            Spacer(Modifier.size(key))
            GameTouchButton(RetroPadButton.B, key, onButton)
            Spacer(Modifier.size(key))
        }
    }
}

@Composable
private fun GameTouchButton(
    button: RetroPadButton,
    diameter: Dp,
    onButton: (RetroPadButton, Boolean) -> Unit,
    label: String = button.key.uppercase(),
) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnButton by rememberUpdatedState(onButton)
    DisposableEffect(button) {
        onDispose { currentOnButton(button, false) }
    }
    Box(
        modifier = Modifier.size(diameter)
            .padding(2.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = if (pressed) 0.78f else 0.43f))
            .border(1.dp, Color.White.copy(alpha = if (pressed) 0.9f else 0.42f), CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    currentOnButton(button, true)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                        currentOnButton(button, false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = if (label.length > 2) 10.sp else 15.sp,
            color = Color.White,
        )
    }
}

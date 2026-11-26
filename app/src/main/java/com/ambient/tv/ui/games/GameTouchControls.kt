package com.ambient.tv.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import kotlin.math.roundToInt

private val DPAD_BUTTONS = setOf(
    RetroPadButton.UP, RetroPadButton.DOWN, RetroPadButton.LEFT, RetroPadButton.RIGHT,
)

/**
 * Shared by local play and the phone's TV controller. Neo-RetroPad's flat outlines and continuous
 * eight-way areas are drawn natively: artwork and hit areas are deliberately separate. Only the
 * control areas receive pointers; the rest of the picture stays available to native touch cores.
 */
@Composable
internal fun GameTouchControls(
    mode: GameTouchMode,
    onButton: (RetroPadButton, Boolean) -> Unit,
    onStick: (Int, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    onDpadDirections: ((Set<RetroPadButton>) -> Unit)? = null,
    portrait: Boolean? = null,
    applySafeInsets: Boolean = true,
    // The remote sends one complete button mask per change, including a direct A-to-B slide.
    onButtonsChanged: ((Set<RetroPadButton>) -> Unit)? = null,
    style: TouchControlStyle = TouchControlStyle(),
    presentation: GameTouchPresentation = GameTouchPresentation.Generic,
    coreActions: Map<RetroPadButton, String> = emptyMap(),
    layoutOverride: GameTouchLayout? = null,
) {
    BoxWithConstraints(modifier.then(if (applySafeInsets) Modifier.safeDrawingPadding() else Modifier)) {
        val tall = portrait ?: (maxWidth < maxHeight)
        val density = LocalDensity.current.density
        val opacity = style.opacityPercent.coerceIn(25, 100) / 100f
        val lifecycleOwner = LocalLifecycleOwner.current
        val pulse by rememberUpdatedState(rememberGameButtonHaptic())
        val buttonHaptics by rememberUpdatedState(style.buttonHaptics)
        val layout = remember(mode, maxWidth, maxHeight, tall, style, presentation, layoutOverride) {
            layoutOverride ?: gameTouchLayout(mode, maxWidth.value, maxHeight.value, tall, style, presentation)
        }
        // A new layout cancels every old contact before its new targets become interactive.
        key(layout, style.stickDeadzonePercent, style.diagonalSensitivity, density, lifecycleOwner) {
            val input = remember { GameTouchInput(layout, style) }
            var state by remember { mutableStateOf(input.state) }
            val currentOnButton by rememberUpdatedState(onButton)
            val currentOnStick by rememberUpdatedState(onStick)
            val currentOnDpad by rememberUpdatedState(onDpadDirections)
            val currentOnButtons by rememberUpdatedState(onButtonsChanged)
            val publish: () -> Unit = {
                val previous = state
                val next = input.state
                state = next
                if (previous.buttons != next.buttons) {
                    val wholePad = currentOnButtons
                    if (wholePad != null) wholePad(next.buttons)
                    else {
                        val dpad = currentOnDpad
                        if (dpad != null && previous.buttons.intersect(DPAD_BUTTONS) != next.buttons.intersect(DPAD_BUTTONS)) {
                            dpad(next.buttons.intersect(DPAD_BUTTONS))
                        }
                        (previous.buttons + next.buttons).forEach { button ->
                            if ((dpad == null || button !in DPAD_BUTTONS) &&
                                (button in previous.buttons) != (button in next.buttons)) {
                                currentOnButton(button, button in next.buttons)
                            }
                        }
                    }
                }
                if (previous.leftStick != next.leftStick) currentOnStick(0, next.leftStick.x, next.leftStick.y)
                if (previous.rightStick != next.rightStick) currentOnStick(1, next.rightStick.x, next.rightStick.y)
                if (gameTouchButtonFeedback(buttonHaptics, previous.buttons, next.buttons)) {
                    pulse()
                }
            }
            val currentPublish by rememberUpdatedState(publish)
            var acceptingInput by remember(lifecycleOwner) {
                mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
            DisposableEffect(input, lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> acceptingInput = true
                        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                            acceptingInput = false
                            input.clear()
                            currentPublish()
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    acceptingInput = false
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    input.clear()
                    currentPublish()
                }
            }
            fun Rect.receiver(): Modifier = position().size(width.dp, height.dp)
                .graphicsLayer { alpha = opacity }
                .gameTouchInput(input, topLeft, density, { acceptingInput }) { currentPublish() }

            layout.dpad?.let { bounds ->
                GameTouchDpad(state.buttons, bounds.receiver().testTag("game-touch-dpad"))
            }
            layout.leftStick?.let { bounds ->
                GameTouchStick(0, bounds.width.dp, state.leftStick, state.leftStickHeld,
                    bounds.receiver().testTag("game-touch-stick-0"))
            }
            layout.rightStick?.let { bounds ->
                GameTouchStick(1, bounds.width.dp, state.rightStick, state.rightStickHeld,
                    bounds.receiver().testTag("game-touch-stick-1"))
            }
            layout.faceButtonsBounds?.let { cluster ->
                // One receiver includes the intermediate A+B / B+Y / Y+X / X+A sectors.
                Box(cluster.receiver().testTag("game-touch-face-area")) {
                    layout.faces.forEach { button ->
                        layout.buttons[button]?.let { bounds ->
                            GameTouchButton(button, button in state.buttons,
                                Modifier.offset((bounds.left - cluster.left).dp, (bounds.top - cluster.top).dp)
                                    .size(bounds.width.dp, bounds.height.dp),
                                label = presentation.labels[button].orEmpty(), action = coreActions[button], face = true)
                        }
                    }
                }
            }
            layout.buttons.filterKeys { it !in layout.faces }.forEach { (button, bounds) ->
                GameTouchButton(button, button in state.buttons, bounds.receiver(),
                    label = presentation.labels[button].orEmpty(), action = coreActions[button], face = false)
            }
        }
    }
}

private fun Rect.position(): Modifier = Modifier.offset(left.dp, top.dp)

/**
 * An original contact keeps its receiver until UP, even when it slides to another button.
 * DOWN/MOVE/UP are all consumed; an ancestor must never interpret the final UP as an OSD tap.
 * Fingers that started on the native picture are never acquired halfway through their gesture.
 */
private fun Modifier.gameTouchInput(
    input: GameTouchInput,
    origin: Offset,
    density: Float,
    acceptingInput: () -> Boolean,
    onStateChanged: () -> Unit,
): Modifier = pointerInput(input, origin, density) {
    val contacts = mutableSetOf<PointerId>()
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                var changed = false
                event.changes.forEach { change ->
                    val position = origin + change.position / density
                    if (!change.previousPressed && change.pressed && !change.isConsumed && acceptingInput() &&
                        input.press(change.id.value, position)) {
                        contacts.add(change.id)
                        changed = true
                    }
                    if (change.id in contacts) {
                        if (change.pressed) input.move(change.id.value, position)
                        else {
                            input.release(change.id.value)
                            contacts.remove(change.id)
                        }
                        change.consume()
                        changed = true
                    }
                }
                if (changed) onStateChanged()
            }
        }
    } finally {
        contacts.forEach { input.release(it.value) }
        if (contacts.isNotEmpty()) onStateChanged()
    }
}

@Composable
private fun GameTouchDpad(selected: Set<RetroPadButton>, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val label = stringResource(GameTouchMode.DPAD.titleRes)
    Canvas(modifier.semantics { contentDescription = label }) {
        val side = size.minDimension
        val segment = Path().apply {
            moveTo(side * .34f, side * .05f)
            lineTo(side * .66f, side * .05f)
            lineTo(side * .66f, side * .30f)
            lineTo(side * .50f, side * .46f)
            lineTo(side * .34f, side * .30f)
            close()
        }
        val arrow = Path().apply {
            moveTo(side * .50f, side * .17f)
            lineTo(side * .45f, side * .23f)
            lineTo(side * .55f, side * .23f)
            close()
        }
        listOf(RetroPadButton.UP, RetroPadButton.RIGHT, RetroPadButton.DOWN, RetroPadButton.LEFT)
            .forEachIndexed { index, button ->
                val held = button in selected
                rotate(index * 90f) {
                    drawPath(segment, if (held) accent.copy(alpha = .36f) else Color.Black.copy(alpha = .30f))
                    drawPath(segment, Color.Black.copy(alpha = .45f), style = Stroke(3.dp.toPx()))
                    drawPath(segment, if (held) accent else Color.White.copy(alpha = .74f), style = Stroke(2.dp.toPx()))
                    drawPath(arrow, Color.White.copy(alpha = if (held) 1f else .70f), style = Stroke(1.5.dp.toPx()))
                }
            }
        drawCircle(Color.White.copy(alpha = .24f), radius = 2.dp.toPx())
    }
}

@Composable
private fun GameTouchStick(
    index: Int,
    diameter: Dp,
    axes: Offset,
    held: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val pixels = with(LocalDensity.current) { diameter.toPx() }
    Box(modifier.padding(3.dp).border(2.dp, Color.White.copy(alpha = .46f), CircleShape),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(diameter * .68f).border(1.dp, Color.White.copy(alpha = .16f), CircleShape))
        Box(
            Modifier.offset {
                val travel = pixels * .25f
                IntOffset((axes.x * travel).roundToInt(), (axes.y * travel).roundToInt())
            }.size(diameter * .44f).clip(CircleShape)
                .background(if (held) accent.copy(alpha = .30f) else Color.Black.copy(alpha = .30f))
                .border(2.dp, if (held) accent else Color.White.copy(alpha = .70f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (index == 0) "L" else "R", color = Color.White.copy(alpha = .84f),
                fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun GameTouchButton(button: RetroPadButton, pressed: Boolean, modifier: Modifier = Modifier,
    label: String, action: String? = null, face: Boolean,
) {
    val utility = button == RetroPadButton.START || button == RetroPadButton.SELECT
    val caption = action?.takeUnless { it.equals(label, ignoreCase = true) }
    if (utility) {
        GameTouchUtilityButton(button, pressed, modifier, label, caption)
        return
    }
    val shape = if (face) CircleShape else RoundedCornerShape(6.dp)
    val accent = MaterialTheme.colorScheme.primary
    val ink = if (pressed) Color.White else Color.White.copy(alpha = .84f)
    val labelStyle = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .75f), blurRadius = 4f))
    Box(
        modifier.testTag("game-touch-${button.key}")
            .semantics { role = Role.Button; contentDescription = if (caption == null) label else "$label: $caption" }
            .padding(3.dp).clip(shape)
            .background(if (pressed) accent.copy(alpha = .36f) else Color.Black.copy(alpha = .30f))
            .border(3.dp, Color.Black.copy(alpha = .45f), shape)
            .border(2.dp, if (pressed) accent else Color.White.copy(alpha = .74f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.padding(horizontal = 3.dp)) {
            Text(label, fontSize = if (face) 23.sp else 16.sp,
                fontWeight = FontWeight.Medium, style = labelStyle, color = ink, maxLines = 1)
            if (caption != null) Text(caption, fontSize = 9.sp, lineHeight = 10.sp, style = labelStyle,
                color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Start/Select as on the real pads: a slim capsule in the same flat outline as every other
 * control, with the printed name and no extra glyphs. The whole (at least 48 dp) slot stays
 * the touch target; only the drawing is slimmer.
 */
@Composable
private fun GameTouchUtilityButton(button: RetroPadButton, pressed: Boolean, modifier: Modifier,
    label: String, caption: String?,
) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = if (pressed) Color.White else Color.White.copy(alpha = .84f)
    val shape = RoundedCornerShape(50)
    val text = label.uppercase(java.util.Locale.ROOT)
    BoxWithConstraints(
        modifier.testTag("game-touch-${button.key}")
            .semantics { role = Role.Button; contentDescription = if (caption == null) label else "$label: $caption" },
        contentAlignment = Alignment.Center,
    ) {
        val pillWidth = maxWidth - 6.dp
        // Long names (Lynx "Option 1", computer "Return") shrink rather than wrap or clip.
        val fontSize = ((pillWidth.value - 12f) / (text.length.coerceAtLeast(1) * .74f)).coerceIn(7f, 11f).sp
        Box(
            Modifier.size(pillWidth, 28.dp).clip(shape)
                .background(if (pressed) accent.copy(alpha = .36f) else Color.Black.copy(alpha = .30f))
                .border(3.dp, Color.Black.copy(alpha = .45f), shape)
                .border(2.dp, if (pressed) accent else Color.White.copy(alpha = .74f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = fontSize, lineHeight = fontSize, letterSpacing = .6.sp,
                fontWeight = FontWeight.Medium,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .75f), blurRadius = 4f)),
                color = ink, maxLines = 1, softWrap = false)
        }
    }
}

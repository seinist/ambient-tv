package com.ambient.tv.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.ui.gamepad.GamepadButton
import com.ambient.tv.ui.gamepad.GamepadLabels
import com.ambient.tv.ui.gamepad.retro.RetroPadButton

/**
 * One vector material family for attached devices and emulated console pads.
 * No screenshots, per-brand color skins, bitmaps or independent focus animations.
 */
internal enum class InputArtworkShape { PAD, OVAL, THREE_GRIP, REMOTE, KEYBOARD, HANDHELD, DUAL_SCREEN }
internal data class InputArtworkKey(
    val id: String, val label: String, val x: Float, val y: Float,
    val target: RetroPadButton? = null, val wide: Boolean = false,
)
internal data class InputArtwork(
    val title: String,
    val shape: InputArtworkShape,
    val keys: List<InputArtworkKey>,
)

private fun key(id: String, label: String, x: Float, y: Float, target: RetroPadButton? = null, wide: Boolean = false) =
    InputArtworkKey(id, label, x, y, target, wide)

private fun direction() = listOf(
    key("up", "▲", .255f, .39f, RetroPadButton.UP),
    key("down", "▼", .255f, .66f, RetroPadButton.DOWN),
    key("left", "◀", .185f, .525f, RetroPadButton.LEFT),
    key("right", "▶", .325f, .525f, RetroPadButton.RIGHT),
)

private fun snesKeys() = direction() + listOf(
    key("y", "Y", .672f, .535f, RetroPadButton.Y),
    key("x", "X", .755f, .395f, RetroPadButton.X),
    key("b", "B", .755f, .67f, RetroPadButton.B),
    key("a", "A", .84f, .535f, RetroPadButton.A),
    key("l", "L", .25f, .21f, RetroPadButton.L),
    key("r", "R", .75f, .21f, RetroPadButton.R),
    key("select", "−", .455f, .63f, RetroPadButton.SELECT),
    key("start", "+", .55f, .63f, RetroPadButton.START),
)

internal fun attachedArtwork(kind: String, labels: GamepadLabels = GamepadLabels.XBOX): InputArtwork = when (kind) {
    "remote" -> InputArtwork("Fernbedienung", InputArtworkShape.REMOTE, listOf(
        key("power", "⏻", .50f, .13f),
        key("back", "↩", .41f, .25f), key("menu", "☰", .59f, .25f),
        key("up", "▲", .50f, .39f), key("left", "◀", .39f, .49f),
        key("ok", "OK", .50f, .49f), key("right", "▶", .61f, .49f),
        key("down", "▼", .50f, .59f),
        key("home", "⌂", .41f, .69f), key("play", "▶Ⅱ", .59f, .69f),
        key("red", "R", .35f, .81f), key("green", "G", .45f, .81f),
        key("yellow", "Y", .55f, .81f), key("blue", "B", .65f, .81f),
    ))
    "keyboard" -> InputArtwork("Tastatur", InputArtworkShape.KEYBOARD, buildList {
        val first = listOf("Esc", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12")
        first.forEachIndexed { i, name -> add(key(name, name, .1f + .8f * i / 12f, .23f)) }
        listOf("Q","W","E","R","T","Z","U","I","O","P").forEachIndexed { i, name ->
            add(key(name, name, .14f + .72f * i / 9f, .43f))
        }
        listOf("A","S","D","F","G","H","J","K","L").forEachIndexed { i, name ->
            add(key(name, name, .16f + .68f * i / 8f, .60f))
        }
        listOf("Y","X","C","V","B","N","M").forEachIndexed { i, name ->
            add(key(name, name, .25f + .5f * i / 6f, .76f))
        }
        add(key("Space", "Leertaste", .49f, .91f, wide = true))
        add(key("Enter", "↵", .9f, .60f))
    })
    else -> InputArtwork("Controller", InputArtworkShape.PAD, listOf(
        key("l2", labels.label(GamepadButton.L2), .20f, .13f),
        key("r2", labels.label(GamepadButton.R2), .80f, .13f),
        key("l1", labels.label(GamepadButton.L1), .27f, .23f),
        key("r1", labels.label(GamepadButton.R1), .73f, .23f),
        key("up", "▲", .28f, .43f),
        key("left", "◀", .20f, .54f),
        key("right", "▶", .36f, .54f),
        key("down", "▼", .28f, .65f),
        key("x", labels.label(GamepadButton.X), .67f, .54f),
        key("y", labels.label(GamepadButton.Y), .75f, .41f),
        key("a", labels.label(GamepadButton.A), .75f, .66f),
        key("b", labels.label(GamepadButton.B), .83f, .54f),
        key("select", labels.label(GamepadButton.SELECT), .435f, .39f),
        key("start", labels.label(GamepadButton.START), .565f, .39f),
        key("l3", labels.label(GamepadButton.THUMB_L), .39f, .77f),
        key("r3", labels.label(GamepadButton.THUMB_R), .61f, .77f),
        key("mode", "●", .5f, .6f),
    ))
}

/** Original console shape and its Libretro RetroPad targets (the core remains the authority). */
internal fun consoleArtwork(systemId: String): InputArtwork {
    val short = when (systemId) {
        "nes" -> "NES"; "snes" -> "Super Nintendo"; "gb" -> "Game Boy"; "gba" -> "Game Boy Advance"
        "n64" -> "Nintendo 64"; "nds" -> "Nintendo DS"; "n3ds" -> "Nintendo 3DS"
        "gamecube" -> "GameCube"; "wii" -> "Wii"; "wiiu" -> "Wii U"
        "psx" -> "PlayStation"; "ps2" -> "PlayStation 2"; "psp" -> "PSP"
        "megadrive" -> "Mega Drive"; "sega32x" -> "Sega 32X"; "saturn" -> "Saturn"
        "dreamcast" -> "Dreamcast"; "c64" -> "Commodore 64"; else -> systemId.uppercase()
    }
    return when (systemId) {
        "snes" -> InputArtwork(short, InputArtworkShape.OVAL, snesKeys())
        "nes", "gb" -> InputArtwork(short, InputArtworkShape.OVAL, direction() + listOf(
            key("b", "B", .73f, .62f, RetroPadButton.B),
            key("a", "A", .84f, .47f, RetroPadButton.A),
            key("select", "−", .46f, .73f, RetroPadButton.SELECT),
            key("start", "+", .56f, .73f, RetroPadButton.START),
        ))
        "megadrive", "sega32x" -> InputArtwork(short, InputArtworkShape.OVAL, direction() + listOf(
            key("y", "A", .62f, .69f, RetroPadButton.Y),
            key("b", "B", .74f, .69f, RetroPadButton.B),
            key("a", "C", .86f, .69f, RetroPadButton.A),
            key("l", "X", .62f, .44f, RetroPadButton.L),
            key("x", "Y", .74f, .44f, RetroPadButton.X),
            key("r", "Z", .86f, .44f, RetroPadButton.R),
            key("start", "Start", .50f, .80f, RetroPadButton.START, wide = true),
        ))
        "psx", "ps2" -> InputArtwork(short, InputArtworkShape.PAD, direction() + listOf(
            key("y", "□", .67f, .53f, RetroPadButton.Y),
            key("x", "△", .75f, .39f, RetroPadButton.X),
            key("b", "✕", .75f, .66f, RetroPadButton.B),
            key("a", "○", .83f, .53f, RetroPadButton.A),
            key("l", "L1", .25f, .21f, RetroPadButton.L),
            key("r", "R1", .75f, .21f, RetroPadButton.R),
            key("l2", "L2", .20f, .12f, RetroPadButton.L2),
            key("r2", "R2", .80f, .12f, RetroPadButton.R2),
            key("select", "Select", .44f, .38f, RetroPadButton.SELECT, wide = true),
            key("start", "Start", .56f, .38f, RetroPadButton.START, wide = true),
        ))
        "n64" -> InputArtwork(short, InputArtworkShape.THREE_GRIP, direction() + listOf(
            key("b", "B", .72f, .61f, RetroPadButton.B),
            key("a", "A", .84f, .65f, RetroPadButton.A),
            key("x", "C▲", .85f, .40f, RetroPadButton.X),
            key("y", "C◀", .75f, .43f, RetroPadButton.Y),
            key("l", "L", .22f, .19f, RetroPadButton.L),
            key("r", "R", .78f, .19f, RetroPadButton.R),
            key("start", "Start", .51f, .37f, RetroPadButton.START, wide = true),
            key("z", "Z", .50f, .77f, RetroPadButton.L2),
        ))
        "nds", "n3ds", "wiiu" -> InputArtwork(short, InputArtworkShape.DUAL_SCREEN, listOf(
            key("up", "▲", .20f, .47f, RetroPadButton.UP),
            key("down", "▼", .20f, .66f, RetroPadButton.DOWN),
            key("left", "◀", .14f, .56f, RetroPadButton.LEFT),
            key("right", "▶", .26f, .56f, RetroPadButton.RIGHT),
            key("y", "Y", .77f, .56f, RetroPadButton.Y),
            key("x", "X", .84f, .45f, RetroPadButton.X),
            key("b", "B", .84f, .67f, RetroPadButton.B),
            key("a", "A", .91f, .56f, RetroPadButton.A),
            key("start", "+", .61f, .83f, RetroPadButton.START),
            key("select", "−", .40f, .83f, RetroPadButton.SELECT),
        ))
        "gba", "psp", "gamecube" -> InputArtwork(short, InputArtworkShape.HANDHELD, snesKeys())
        else -> InputArtwork(short, InputArtworkShape.PAD, snesKeys())
    }
}

private fun DrawScope.drawArtworkBody(shape: InputArtworkShape, base: androidx.compose.ui.graphics.Color,
                                     raised: androidx.compose.ui.graphics.Color, rim: androidx.compose.ui.graphics.Color) {
    val w = size.width
    val h = size.height
    val plate = Brush.verticalGradient(listOf(raised, base))
    when (shape) {
        InputArtworkShape.KEYBOARD -> {
            drawRoundRect(plate, Offset(w*.055f,h*.12f), Size(w*.89f,h*.80f), CornerRadius(h*.06f))
            drawRoundRect(rim, Offset(w*.055f,h*.12f), Size(w*.89f,h*.80f), CornerRadius(h*.06f), style=Stroke(width=1.6.dp.toPx()))
        }
        InputArtworkShape.REMOTE -> {
            drawRoundRect(plate, Offset(w*.33f,h*.045f), Size(w*.34f,h*.91f), CornerRadius(w*.11f))
            drawRoundRect(rim, Offset(w*.33f,h*.045f), Size(w*.34f,h*.91f), CornerRadius(w*.11f), style=Stroke(width=1.6.dp.toPx()))
        }
        InputArtworkShape.OVAL, InputArtworkShape.HANDHELD, InputArtworkShape.DUAL_SCREEN -> {
            val top = if (shape == InputArtworkShape.DUAL_SCREEN) .12f else .20f
            val bottom = if (shape == InputArtworkShape.DUAL_SCREEN) .87f else .83f
            drawRoundRect(plate, Offset(w*.08f,h*top), Size(w*.84f,h*(bottom-top)), CornerRadius(h*.22f))
            drawRoundRect(rim, Offset(w*.08f,h*top), Size(w*.84f,h*(bottom-top)), CornerRadius(h*.22f), style=Stroke(width=1.8.dp.toPx()))
            if (shape == InputArtworkShape.DUAL_SCREEN) {
                drawRoundRect(base, Offset(w*.37f,h*.20f), Size(w*.28f,h*.24f), CornerRadius(8.dp.toPx()), style=Stroke(width=2.dp.toPx()))
                drawRoundRect(base, Offset(w*.37f,h*.51f), Size(w*.28f,h*.23f), CornerRadius(8.dp.toPx()), style=Stroke(width=2.dp.toPx()))
            }
        }
        InputArtworkShape.PAD, InputArtworkShape.THREE_GRIP -> {
            val p = Path().apply {
                moveTo(w*.30f,h*.22f)
                cubicTo(w*.39f,h*.18f,w*.40f,h*.25f,w*.50f,h*.25f)
                cubicTo(w*.60f,h*.25f,w*.62f,h*.18f,w*.71f,h*.22f)
                cubicTo(w*.82f,h*.25f,w*.88f,h*.33f,w*.93f,h*.77f)
                cubicTo(w*.96f,h*.96f,w*.79f,h*.90f,w*.70f,h*.76f)
                cubicTo(w*.63f,h*.67f,w*.37f,h*.67f,w*.30f,h*.76f)
                cubicTo(w*.21f,h*.90f,w*.04f,h*.96f,w*.07f,h*.77f)
                cubicTo(w*.12f,h*.33f,w*.18f,h*.25f,w*.30f,h*.22f)
                close()
            }
            drawPath(p, plate)
            drawPath(p, rim, style=Stroke(width=1.8.dp.toPx()))
            if (shape == InputArtworkShape.THREE_GRIP) {
                drawRoundRect(plate, Offset(w*.43f,h*.57f), Size(w*.14f,h*.36f), CornerRadius(w*.05f))
                drawRoundRect(rim, Offset(w*.43f,h*.57f), Size(w*.14f,h*.36f), CornerRadius(w*.05f), style=Stroke(width=1.dp.toPx()))
            }
        }
    }
}

/**
 * Interactive Compose vector, intentionally using theme surfaces/focus instead of a separate
 * SVG color palette. Every key is a focusable DPAD/touch target and exposes its label.
 */
@Composable
internal fun InputDeviceArtwork(
    artwork: InputArtwork,
    selectedKey: String?,
    onSelectKey: (InputArtworkKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isRemote = artwork.shape == InputArtworkShape.REMOTE
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(if (isRemote) 2.15f else 2.35f)) {
        val maxW = maxWidth
        val maxH = maxHeight
        val tiny = maxW < 400.dp
        Canvas(Modifier.matchParentSize()) {
            drawArtworkBody(artwork.shape, colors.surface, colors.surfaceVariant, colors.onSurfaceVariant.copy(alpha = .26f))
        }
        artwork.keys.forEach { entry ->
            val wide = entry.wide
            val keyWidth: Dp = when {
                wide -> if (tiny) 44.dp else 56.dp
                artwork.shape == InputArtworkShape.KEYBOARD -> if (tiny) 15.dp else 24.dp
                isRemote -> if (tiny) 20.dp else 29.dp
                else -> if (tiny) 23.dp else 34.dp
            }
            val keyHeight: Dp = when {
                artwork.shape == InputArtworkShape.KEYBOARD -> if (tiny) 19.dp else 28.dp
                isRemote -> if (tiny) 20.dp else 29.dp
                else -> if (tiny) 23.dp else 34.dp
            }
            val shape = RoundedCornerShape(if (artwork.shape == InputArtworkShape.KEYBOARD) 5.dp else 10.dp)
            var focused by remember(artwork.title, entry.id) { mutableStateOf(false) }
            val active = entry.id == selectedKey || focused
            Box(
                modifier = Modifier
                    .offset(x = maxW * entry.x - keyWidth / 2, y = maxH * entry.y - keyHeight / 2)
                    .width(keyWidth).height(keyHeight)
                    .clip(shape)
                    .background(if (active) colors.primary.copy(alpha=.23f) else colors.surfaceVariant)
                    .border(if (active) 2.dp else 1.dp,
                        if (active) colors.primary else colors.onSurfaceVariant.copy(alpha=.28f), shape)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onSelectKey(entry) }
                    .focusable()
                    .semantics { contentDescription = "${artwork.title}: ${entry.label}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(entry.label, textAlign=TextAlign.Center,
                    color=if (active) colors.primary else colors.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines=1)
            }
        }
    }
}

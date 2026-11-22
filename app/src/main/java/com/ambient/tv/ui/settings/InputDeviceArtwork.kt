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
import com.ambient.tv.ui.theme.animatedFocusBorder

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
        InputArtworkShape.JOYSTICK -> {
            drawRoundRect(plate, Offset(w*.17f, h*.44f), Size(w*.67f, h*.47f), CornerRadius(h*.12f))
            drawRoundRect(rim, Offset(w*.17f, h*.44f), Size(w*.67f, h*.47f),
                CornerRadius(h*.12f), style = Stroke(width=1.8.dp.toPx()))
            // Classical stick: short shaft and cap, all using the same neutral material.
            drawRoundRect(rim.copy(alpha=.5f), Offset(w*.38f, h*.25f), Size(w*.06f,h*.31f), CornerRadius(w*.025f))
            drawCircle(raised, radius = w*.055f, center=Offset(w*.41f,h*.26f))
            drawCircle(rim, radius = w*.055f, center=Offset(w*.41f,h*.26f), style=Stroke(width=1.dp.toPx()))
        }
        InputArtworkShape.SCREEN_PAD -> {
            drawRoundRect(plate, Offset(w*.06f,h*.15f), Size(w*.88f,h*.70f), CornerRadius(h*.22f))
            drawRoundRect(rim, Offset(w*.06f,h*.15f), Size(w*.88f,h*.70f), CornerRadius(h*.22f),
                style=Stroke(width=1.8.dp.toPx()))
            drawRoundRect(base, Offset(w*.36f,h*.30f), Size(w*.29f,h*.29f), CornerRadius(6.dp.toPx()))
            drawRoundRect(rim, Offset(w*.36f,h*.30f), Size(w*.29f,h*.29f), CornerRadius(6.dp.toPx()),
                style=Stroke(width=1.5.dp.toPx()))
        }
        InputArtworkShape.WII_REMOTE -> {
            drawRoundRect(plate, Offset(w*.425f,h*.045f), Size(w*.15f,h*.92f), CornerRadius(w*.045f))
            drawRoundRect(rim, Offset(w*.425f,h*.045f), Size(w*.15f,h*.92f), CornerRadius(w*.045f),
                style=Stroke(width=1.8.dp.toPx()))
        }
        InputArtworkShape.REMOTE -> {
            drawRoundRect(plate, Offset(w*.39f,h*.045f), Size(w*.22f,h*.91f), CornerRadius(w*.055f))
            drawRoundRect(rim, Offset(w*.39f,h*.045f), Size(w*.22f,h*.91f), CornerRadius(w*.055f), style=Stroke(width=1.6.dp.toPx()))
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
    val isRemote = artwork.shape == InputArtworkShape.REMOTE || artwork.shape == InputArtworkShape.WII_REMOTE
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(if (isRemote) 1.7f else 2.35f)) {
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
            val selected = entry.id == selectedKey
            val active = selected || focused
            Box(
                modifier = Modifier
                    .offset(x = maxW * entry.x - keyWidth / 2, y = maxH * entry.y - keyHeight / 2)
                    .width(keyWidth).height(keyHeight)
                    .clip(shape)
                    .background(if (active) colors.primary.copy(alpha=.23f) else colors.surfaceVariant)
                    .border(1.dp,
                        if (selected) colors.primary.copy(alpha=.65f) else colors.onSurfaceVariant.copy(alpha=.28f), shape)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onSelectKey(entry) }
                    .focusable()
                    .animatedFocusBorder(focused = focused, shape = shape,
                        focusedColor = colors.primary, width = 2.dp)
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

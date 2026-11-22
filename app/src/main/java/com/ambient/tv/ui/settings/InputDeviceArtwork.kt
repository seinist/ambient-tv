package com.ambient.tv.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.animatedFocusBorder
import kotlin.math.min

@Composable
private fun artworkTitle(artwork: InputArtwork): String = when (artwork.title) {
    "Controller" -> stringResource(R.string.input_mapping_controller)
    "Fernbedienung" -> stringResource(R.string.input_mapping_remote)
    "Tastatur" -> stringResource(R.string.input_mapping_keyboard)
    "Handy-Controller" -> stringResource(R.string.retro_kind_companion)
    else -> artwork.title
}

@Composable
private fun artworkKeyLabel(entry: InputArtworkKey): String = when (entry.id) {
    "up" -> stringResource(R.string.input_artwork_up)
    "down" -> stringResource(R.string.input_artwork_down)
    "left" -> stringResource(R.string.input_artwork_left)
    "right" -> stringResource(R.string.input_artwork_right)
    "Space" -> stringResource(R.string.input_artwork_space)
    else -> entry.label
}

/**
 * One fitted viewport owns the image, labels and hit testing. Small screens never distort
 * the silhouette or grow individual marks independently of it. A separate key strip gives
 * dense diagrams accessible, full-size touch targets without overlapping invisible buttons.
 */
@Composable
internal fun InputDeviceArtwork(
    artwork: InputArtwork,
    selectedKey: String?,
    onSelectKey: (InputArtworkKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val handheld = LocalHandheldUi.current
    val useKeyStrip = handheld || density.fontScale > 1.3f
    val onSelect by rememberUpdatedState(onSelectKey)
    val title = artworkTitle(artwork)
    val frontKeys = remember(artwork) { artwork.keys.filterNot { it.auxiliary } }
    val extraKeys = remember(artwork) { artwork.keys.filter { it.auxiliary } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fitted = fitInputArtwork(artwork, (maxWidth.value - 24f).coerceIn(0f, 580f),
            if (handheld) 300f else 340f)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (extraKeys.isNotEmpty()) SettingsActionRow {
                extraKeys.forEach { entry ->
                    val label = artworkKeyLabel(entry)
                    TvActionButton(label, onClick = { onSelect(entry) }, selected = entry.id == selectedKey,
                        modifier = Modifier.testTag("input-artwork-extra-${entry.id}")
                            .semantics {
                                contentDescription = "$title: $label"
                                selected = entry.id == selectedKey
                            })
                }
            }
            if (fitted.width > 0f && fitted.height > 0f) Box(Modifier.padding(12.dp)) {
                val pixelSize = with(density) { Size(fitted.width.dp.toPx(), fitted.height.dp.toPx()) }
                Box(Modifier.size(fitted.width.dp, fitted.height.dp).testTag("input-artwork-image")
                    .then(if (useKeyStrip) Modifier.pointerInput(artwork, pixelSize) {
                        detectTapGestures { point ->
                            inputArtworkKeyAt(artwork, pixelSize, point, 10.dp.toPx())?.let(onSelect)
                        }
                    } else Modifier)) {
                    val vector = artwork.vectorId?.let(ControllerVectorAssets::get)
                    if (vector != null) Image(
                        painter = painterResource(vector.drawableRes), contentDescription = null,
                        modifier = Modifier.matchParentSize(), contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(colors.onSurfaceVariant.copy(alpha = .68f)),
                    ) else Canvas(Modifier.matchParentSize()) {
                        drawOriginalDevice(artwork.shape, colors.surface, colors.surfaceVariant,
                            colors.onSurfaceVariant.copy(alpha = .42f))
                    }
                    frontKeys.forEach { entry ->
                        val bounds = inputArtworkKeyBounds(entry, fitted)
                        ArtworkKeyMark(entry, title, artwork.shape, selectedKey == entry.id,
                            interactive = !useKeyStrip,
                            modifier = Modifier.offset(bounds.left.dp, bounds.top.dp)
                                .size(bounds.width.dp, bounds.height.dp),
                            width = bounds.width, height = bounds.height,
                            onClick = { onSelect(entry) })
                    }
                }
            }
            if (useKeyStrip && frontKeys.isNotEmpty()) {
                val state = rememberLazyListState()
                LaunchedEffect(artwork, selectedKey) {
                    val index = frontKeys.indexOfFirst { it.id == selectedKey }
                    if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                        state.animateScrollToItem(index)
                    }
                }
                LazyRow(state = state, modifier = Modifier.fillMaxWidth().testTag("input-artwork-key-strip"),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(frontKeys, key = { it.id }) { entry ->
                        val label = artworkKeyLabel(entry)
                        TvActionButton(label, onClick = { onSelect(entry) }, selected = entry.id == selectedKey,
                            modifier = Modifier.testTag("input-artwork-select-${entry.id}")
                                .semantics {
                                    contentDescription = "$title: $label"
                                    selected = entry.id == selectedKey
                                })
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtworkKeyMark(
    entry: InputArtworkKey, title: String, artworkShape: InputArtworkShape,
    isSelected: Boolean, interactive: Boolean, modifier: Modifier,
    width: Float, height: Float, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember(title, entry.id) { mutableStateOf(false) }
    val shape = if (artworkShape == InputArtworkShape.KEYBOARD || entry.wide) RoundedCornerShape(18) else CircleShape
    val active = isSelected || focused
    val label = artworkKeyLabel(entry)
    val display = when {
        entry.id == "start" && entry.label.length > 3 -> "≡"
        entry.id == "select" && entry.label.length > 3 -> "▣"
        entry.id == "mode" -> "●"
        entry.id == "Space" -> "␣"
        else -> entry.label
    }
    // The diagram itself scales as a unit. The key strip keeps normal system font scaling.
    val textSize = (min(height * .58f, width / (display.length.coerceAtLeast(1) * .64f))
        .coerceIn(1f, 14f) / LocalDensity.current.fontScale).sp
    Box(modifier.testTag("input-artwork-key-${entry.id}")
        .clip(shape)
        .background(if (active) colors.primary.copy(alpha = .28f) else colors.surface.copy(alpha = .86f))
        .border(1.dp, if (isSelected) colors.primary else colors.onSurfaceVariant.copy(alpha = .4f), shape)
        .then(if (interactive) Modifier.onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick).semantics {
                contentDescription = "$title: $label"
                selected = isSelected
            } else Modifier.clearAndSetSemantics { })
        .animatedFocusBorder(focused = focused, shape = shape, focusedColor = colors.primary, width = 2.dp),
        contentAlignment = Alignment.Center) {
        Text(display, textAlign = TextAlign.Center, color = if (active) colors.primary else colors.onSurface,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = textSize, lineHeight = textSize),
            maxLines = 1, softWrap = false)
    }
}

/** Original matching artwork for devices without an appropriate licensed front view. */
private fun DrawScope.drawOriginalDevice(shape: InputArtworkShape, base: Color, raised: Color, rim: Color) {
    val w = size.width
    val h = size.height
    val plate = Brush.verticalGradient(listOf(raised, base))
    val line = (w * .004f).coerceIn(.75f.dp.toPx(), 1.8f.dp.toPx())
    fun body(x: Float, y: Float, width: Float, height: Float, radius: Float) {
        val offset = Offset(w * x, h * y)
        val bounds = Size(w * width, h * height)
        val corner = CornerRadius(min(w, h) * radius)
        drawRoundRect(plate, offset, bounds, corner)
        drawRoundRect(rim, offset, bounds, corner, style = Stroke(line))
    }
    fun screen(x: Float, y: Float, width: Float, height: Float) {
        val offset = Offset(w * x, h * y)
        val bounds = Size(w * width, h * height)
        val corner = CornerRadius(min(w, h) * .035f)
        drawRoundRect(base, offset, bounds, corner)
        drawRoundRect(rim.copy(alpha = rim.alpha * .65f), offset, bounds, corner, style = Stroke(line))
    }
    when (shape) {
        InputArtworkShape.KEYBOARD -> {
            body(.006f, .045f, .988f, .92f, .07f)
            drawLine(rim.copy(alpha = .18f), Offset(w * .025f, h * .27f), Offset(w * .83f, h * .27f), line)
            drawLine(rim.copy(alpha = .18f), Offset(w * .84f, h * .30f), Offset(w * .84f, h * .68f), line)
        }
        InputArtworkShape.REMOTE, InputArtworkShape.WII_REMOTE -> {
            body(.025f, .012f, .95f, .976f, .20f)
            drawCircle(rim.copy(alpha = .16f), radius = w * .36f, center = Offset(w * .5f, h * .34f),
                style = Stroke(line))
            drawLine(rim.copy(alpha = .25f), Offset(w * .35f, h * .945f), Offset(w * .65f, h * .945f), line)
        }
        InputArtworkShape.SCREEN_PAD -> {
            body(.018f, .10f, .964f, .82f, .16f)
            screen(.31f, .23f, .38f, .34f)
            drawLine(rim, Offset(w * .46f, h * .17f), Offset(w * .54f, h * .17f), line)
        }
        InputArtworkShape.DUAL_SCREEN -> {
            body(.10f, .02f, .80f, .43f, .07f)
            body(.02f, .48f, .96f, .50f, .10f)
            screen(.23f, .09f, .54f, .29f)
            screen(.37f, .53f, .28f, .31f)
            drawLine(rim, Offset(w * .20f, h * .465f), Offset(w * .80f, h * .465f), line)
        }
        InputArtworkShape.HANDHELD -> {
            body(.02f, .09f, .96f, .82f, .14f)
            screen(.36f, .20f, .29f, .39f)
        }
        InputArtworkShape.JOYSTICK -> {
            body(.05f, .41f, .90f, .56f, .12f)
            drawRoundRect(rim.copy(alpha = .5f), Offset(w * .39f, h * .16f), Size(w * .055f, h * .43f),
                CornerRadius(w * .02f))
            drawCircle(raised, w * .10f, Offset(w * .4175f, h * .17f))
            drawCircle(rim, w * .10f, Offset(w * .4175f, h * .17f), style = Stroke(line))
        }
        InputArtworkShape.OVAL -> body(.018f, .11f, .964f, .80f, .38f)
        InputArtworkShape.PAD, InputArtworkShape.THREE_GRIP -> {
            val path = Path().apply {
                moveTo(w * .27f, h * .13f)
                cubicTo(w * .37f, h * .07f, w * .40f, h * .17f, w * .50f, h * .17f)
                cubicTo(w * .60f, h * .17f, w * .63f, h * .07f, w * .73f, h * .13f)
                cubicTo(w * .87f, h * .17f, w * .92f, h * .39f, w * .98f, h * .82f)
                cubicTo(w, h * 1.02f, w * .79f, h * .97f, w * .69f, h * .75f)
                cubicTo(w * .62f, h * .64f, w * .38f, h * .64f, w * .31f, h * .75f)
                cubicTo(w * .21f, h * .97f, 0f, h * 1.02f, w * .02f, h * .82f)
                cubicTo(w * .08f, h * .39f, w * .13f, h * .17f, w * .27f, h * .13f)
                close()
            }
            drawPath(path, plate)
            drawPath(path, rim, style = Stroke(line))
            if (shape == InputArtworkShape.THREE_GRIP) body(.43f, .58f, .14f, .39f, .05f)
        }
    }
}

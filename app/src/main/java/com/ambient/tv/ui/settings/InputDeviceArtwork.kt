package com.ambient.tv.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
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
    "power" -> stringResource(R.string.input_artwork_power)
    "menu" -> stringResource(R.string.input_artwork_menu)
    "home" -> stringResource(R.string.input_artwork_home)
    "back" -> stringResource(R.string.input_artwork_back)
    "play" -> stringResource(R.string.input_artwork_play_pause)
    "red" -> stringResource(R.string.input_artwork_red)
    "green" -> stringResource(R.string.input_artwork_green)
    "yellow" -> stringResource(R.string.input_artwork_yellow)
    "blue" -> stringResource(R.string.input_artwork_blue)
    else -> entry.label
}

/**
 * One fitted viewport owns the image, labels and hit testing. Small screens never distort
 * the silhouette or grow individual marks independently of it. A separate key strip gives
 * dense diagrams accessible, full-size touch targets without overlapping invisible buttons.
 *
 * [assignments] (key id → short label) is listed below the picture, one selectable entry per
 * key, instead of legends crowding the drawing; [changedKeys] marks entries that differ from
 * the defaults. The picture itself only shows the keys, so no mark is ever printed twice.
 */
@Composable
internal fun InputDeviceArtwork(
    artwork: InputArtwork,
    selectedKey: String?,
    onSelectKey: (InputArtworkKey) -> Unit,
    modifier: Modifier = Modifier,
    assignments: Map<String, String> = emptyMap(),
    changedKeys: Set<String> = emptySet(),
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val handheld = LocalHandheldUi.current
    val useKeyStrip = handheld || density.fontScale > 1.3f
    val onSelect by rememberUpdatedState(onSelectKey)
    val title = artworkTitle(artwork)
    val frontKeys = remember(artwork) { artwork.keys.filterNot { it.auxiliary } }
    val extraKeys = remember(artwork) { artwork.keys.filter { it.auxiliary } }
    val showMapping = assignments.isNotEmpty()
    // Console buttons are always listed (unassigned ones too); device keys once they act.
    val mappedKeys = remember(artwork, assignments) {
        if (!showMapping) emptyList() else artwork.keys.filter { it.id in assignments || it.target != null }
    }
    // Keys the mapping list does not already offer keep their own full-size targets:
    // rear/auxiliary keys above the picture, and on touch a strip for the front keys.
    val listed = remember(mappedKeys) { mappedKeys.mapTo(HashSet()) { it.id } }
    val stripKeys = remember(frontKeys, listed) { frontKeys.filterNot { it.id in listed } }
    val separateExtras = remember(extraKeys, listed) { extraKeys.filterNot { it.id in listed } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val inset = 12f
        val fitted = fitInputArtwork(artwork, (maxWidth.value - inset * 2).coerceIn(0f, 580f),
            if (handheld) 300f else 340f)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (separateExtras.isNotEmpty()) SettingsActionRow {
                separateExtras.forEach { entry ->
                    val label = assignedButtonLabel(artworkKeyLabel(entry), assignments[entry.id])
                    TvActionButton(label, onClick = { onSelect(entry) }, selected = entry.id == selectedKey,
                        modifier = Modifier.testTag("input-artwork-extra-${entry.id}")
                            .semantics {
                                contentDescription = "$title: $label"
                                selected = entry.id == selectedKey
                            })
                }
            }
            if (fitted.width > 0f && fitted.height > 0f) Box(Modifier.padding(inset.dp)) {
                val pixelSize = with(density) { Size(fitted.width.dp.toPx(), fitted.height.dp.toPx()) }
                Box(Modifier.size(fitted.width.dp, fitted.height.dp).testTag("input-artwork-image")
                    .then(if (useKeyStrip) Modifier.pointerInput(artwork, pixelSize) {
                        detectTapGestures { point ->
                            inputArtworkKeyAt(artwork, pixelSize, point, 10.dp.toPx())?.let(onSelect)
                        }
                    } else Modifier)) {
                    val vector = artwork.vectorId?.let(ControllerVectorAssets::get)
                    val palette = inputArtworkPalette()
                    // Flat housing in the UI's own surface tones: no gradients, outlines or shadows.
                    if (vector != null) Image(
                        painter = painterResource(vector.drawableRes), contentDescription = null,
                        modifier = Modifier.matchParentSize(), contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(palette.housing),
                    ) else Canvas(Modifier.matchParentSize()) {
                        drawOriginalDevice(artwork.shape, palette.housing, palette.well)
                        artwork.wells.forEach { well ->
                            drawCircle(palette.well, well.radius * size.width, Offset(well.x * size.width, well.y * size.height))
                        }
                        artwork.dpad?.let { pad ->
                            val center = Offset(pad.x * size.width, pad.y * size.height)
                            val reach = pad.radius * size.width
                            val arm = reach * .58f
                            val corner = CornerRadius(arm * .22f)
                            drawRoundRect(palette.key, Offset(center.x - arm / 2f, center.y - reach), Size(arm, reach * 2f), corner)
                            drawRoundRect(palette.key, Offset(center.x - reach, center.y - arm / 2f), Size(reach * 2f, arm), corner)
                        }
                    }
                    frontKeys.forEach { entry ->
                        val bounds = inputArtworkKeyBounds(entry, fitted)
                        ArtworkKeyMark(entry, title, artwork.shape, selectedKey == entry.id,
                            interactive = !useKeyStrip,
                            modifier = Modifier.offset(bounds.left.dp, bounds.top.dp)
                                .size(bounds.width.dp, bounds.height.dp),
                            width = bounds.width, height = bounds.height,
                            assignment = assignments[entry.id],
                            onClick = { onSelect(entry) })
                    }
                }
            }
            if (showMapping) ArtworkMappingList(mappedKeys, title, selectedKey, assignments, changedKeys) {
                onSelect(it)
            }
            if (useKeyStrip && stripKeys.isNotEmpty()) {
                val state = rememberLazyListState()
                LaunchedEffect(artwork, selectedKey) {
                    val index = stripKeys.indexOfFirst { it.id == selectedKey }
                    if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                        state.animateScrollToItem(index)
                    }
                }
                LazyRow(state = state, modifier = Modifier.fillMaxWidth().testTag("input-artwork-key-strip"),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(stripKeys, key = { it.id }) { entry ->
                        val label = assignedButtonLabel(artworkKeyLabel(entry), assignments[entry.id])
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
    width: Float, height: Float, assignment: String? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val palette = inputArtworkPalette()
    var focused by remember(title, entry.id) { mutableStateOf(false) }
    val shape = when {
        entry.color != null -> RoundedCornerShape(50)
        artworkShape == InputArtworkShape.KEYBOARD || entry.wide -> RoundedCornerShape(18)
        else -> CircleShape
    }
    val active = isSelected || focused
    val label = assignedButtonLabel(artworkKeyLabel(entry), assignment)
    val display = when {
        entry.color != null && !active -> ""
        entry.id == "start" && entry.label.length > 3 -> "≡"
        entry.id == "select" && entry.label.length > 3 -> "▣"
        entry.id == "mode" -> "●"
        entry.id == "Space" -> "␣"
        else -> entry.label
    }
    // The diagram itself scales as a unit. The key strip keeps normal system font scaling.
    val textSize = (min(height * .58f, width / (display.length.coerceAtLeast(1) * .64f))
        .coerceIn(1f, 14f) / LocalDensity.current.fontScale).sp
    // Same language as buttons and chips: a solid fill marks focus or selection, never a line.
    val fill = when {
        focused -> colors.onSurface
        isSelected -> colors.primary
        entry.color != null -> Color(entry.color)
        entry.plain -> Color.Transparent
        else -> palette.key
    }
    val foreground = when {
        focused -> colors.surface
        isSelected -> colors.onPrimary
        else -> palette.label
    }
    Box(modifier.testTag("input-artwork-key-${entry.id}")
        .clip(shape)
        .background(fill)
        .then(if (interactive) Modifier.onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick).semantics {
                contentDescription = "$title: $label"
                selected = isSelected
            } else Modifier.clearAndSetSemantics { }),
        contentAlignment = Alignment.Center) {
        if (entry.id == "power") Canvas(Modifier.fillMaxSize()) {
            // U+23FB is missing from some Android fonts. Draw the standard power mark as
            // geometry so the remote never shows a missing-glyph box on those devices.
            val side = size.minDimension
            val stroke = side * .075f
            drawArc(foreground, -45f, 270f, false,
                topLeft = Offset(size.width * .25f, size.height * .30f),
                size = Size(size.width * .50f, size.height * .50f),
                style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(foreground, Offset(size.width * .5f, size.height * .20f),
                Offset(size.width * .5f, size.height * .52f), strokeWidth = stroke, cap = StrokeCap.Round)
        } else Text(display, textAlign = TextAlign.Center, color = foreground,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = textSize, lineHeight = textSize),
                maxLines = 1, softWrap = false)
    }
}

/** "B → A" in buttons and spoken labels; the plain key name when nothing is assigned. */
internal fun assignedButtonLabel(label: String, assignment: String?): String =
    if (assignment.isNullOrBlank()) label else "$label → $assignment"

/** The list entry's name: d-pad directions spelled out, artwork shorthand (+/−) by its role. */
@Composable
private fun artworkMappingName(entry: InputArtworkKey): String {
    val role = entry.target?.takeIf {
        entry.label in setOf("+", "−", "-") && (it == RetroPadButton.START || it == RetroPadButton.SELECT)
    }
    return if (role != null) stringResource(role.titleRes) else artworkKeyLabel(entry)
}

/**
 * The current mapping as a list below the picture: "B → A" per key, in the artwork's order.
 * The same flat buttons as everywhere in Settings; the selected key is filled like a selected
 * chip, a key that differs from the default carries an edit icon (no outlines or lines).
 */
@Composable
private fun ArtworkMappingList(
    keys: List<InputArtworkKey>,
    title: String,
    selectedKey: String?,
    assignments: Map<String, String>,
    changedKeys: Set<String>,
    onSelect: (InputArtworkKey) -> Unit,
) {
    if (keys.isEmpty()) return
    val none = stringResource(R.string.key_binding_none)
    SettingsActionRow(Modifier.testTag("input-artwork-mapping")) {
        keys.forEach { entry ->
            val label = assignedButtonLabel(artworkMappingName(entry), assignments[entry.id] ?: none)
            TvActionButton(label, onClick = { onSelect(entry) }, selected = entry.id == selectedKey,
                icon = if (entry.id in changedKeys) Icons.Filled.Edit else null,
                modifier = Modifier.testTag("input-artwork-assignment-${entry.id}")
                    .semantics {
                        contentDescription = "$title: $label"
                        selected = entry.id == selectedKey
                    })
        }
    }
}

/** Flat tones taken from the UI theme, so the diagrams follow light/dark and accent settings. */
@androidx.compose.runtime.Immutable
internal data class InputArtworkPalette(val housing: Color, val well: Color, val key: Color, val label: Color)

@Composable
internal fun inputArtworkPalette(): InputArtworkPalette {
    val colors = MaterialTheme.colorScheme
    return remember(colors.surface, colors.surfaceVariant, colors.onSurface, colors.onSurfaceVariant) {
        InputArtworkPalette(
            housing = androidx.compose.ui.graphics.lerp(colors.surfaceVariant, colors.onSurface, .05f),
            well = colors.surface,
            key = androidx.compose.ui.graphics.lerp(colors.surfaceVariant, colors.onSurface, .14f),
            label = colors.onSurfaceVariant,
        )
    }
}

/** Original flat housings for devices without a licensed front view: solid fills only. */
private fun DrawScope.drawOriginalDevice(shape: InputArtworkShape, housing: Color, well: Color) {
    val w = size.width
    val h = size.height
    fun body(x: Float, y: Float, width: Float, height: Float, radius: Float) {
        drawRoundRect(housing, Offset(w * x, h * y), Size(w * width, h * height), CornerRadius(min(w, h) * radius))
    }
    fun screen(x: Float, y: Float, width: Float, height: Float) {
        drawRoundRect(well, Offset(w * x, h * y), Size(w * width, h * height), CornerRadius(min(w, h) * .035f))
    }
    when (shape) {
        InputArtworkShape.KEYBOARD -> body(.006f, .045f, .988f, .92f, .07f)
        InputArtworkShape.REMOTE -> drawRoundRect(housing, Offset(w * .04f, h * .005f), Size(w * .92f, h * .99f), CornerRadius(w * .46f))
        InputArtworkShape.WII_REMOTE -> body(.025f, .012f, .95f, .976f, .20f)
        InputArtworkShape.SCREEN_PAD -> {
            body(.018f, .10f, .964f, .82f, .16f)
            screen(.31f, .23f, .38f, .34f)
        }
        InputArtworkShape.DUAL_SCREEN -> {
            body(.10f, .02f, .80f, .43f, .07f)
            body(.02f, .48f, .96f, .50f, .10f)
            screen(.23f, .09f, .54f, .29f)
            screen(.37f, .53f, .28f, .31f)
        }
        InputArtworkShape.HANDHELD -> {
            body(.02f, .09f, .96f, .82f, .14f)
            screen(.36f, .20f, .29f, .39f)
        }
        InputArtworkShape.JOYSTICK -> {
            body(.05f, .41f, .90f, .56f, .12f)
            drawRoundRect(housing, Offset(w * .39f, h * .16f), Size(w * .055f, h * .43f), CornerRadius(w * .02f))
            drawCircle(housing, w * .10f, Offset(w * .4175f, h * .17f))
        }
        InputArtworkShape.OVAL -> body(.018f, .11f, .964f, .80f, .38f)
        InputArtworkShape.PAD, InputArtworkShape.THREE_GRIP -> {
            val path = Path().apply {
                moveTo(w * .27f, h * .13f)
                cubicTo(w * .37f, h * .07f, w * .40f, h * .17f, w * .50f, h * .17f)
                cubicTo(w * .60f, h * .17f, w * .63f, h * .07f, w * .73f, h * .13f)
                cubicTo(w * .87f, h * .17f, w * .92f, h * .39f, w * .98f, h * .82f)
                cubicTo(w, h * 1.02f, w * .79f, h * .99f, w * .70f, h * .82f)
                cubicTo(w * .63f, h * .74f, w * .37f, h * .74f, w * .30f, h * .82f)
                cubicTo(w * .21f, h * .99f, 0f, h * 1.02f, w * .02f, h * .82f)
                cubicTo(w * .08f, h * .39f, w * .13f, h * .17f, w * .27f, h * .13f)
                close()
            }
            drawPath(path, housing)
            if (shape == InputArtworkShape.THREE_GRIP) body(.43f, .58f, .14f, .39f, .05f)
        }
    }
}

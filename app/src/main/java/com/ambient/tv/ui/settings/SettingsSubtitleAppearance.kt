package com.ambient.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.SubtitleAppearance
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.RightPanelValueSlider
import com.ambient.tv.ui.common.TvSettingRow

internal fun LazyListScope.subtitleAppearanceItems(
    appearance: SubtitleAppearance,
    onChange: ((SubtitleAppearance) -> SubtitleAppearance) -> Unit,
) {
    item { SubtitlePreview(appearance) }
    item {
        SettingsGroup(title = stringResource(R.string.subtitle_look_title), subtitle = stringResource(R.string.subtitle_look_hint)) {
            LookChoice(R.string.subtitle_look_size, appearance.size, SubtitleAppearance.sizes, ::sizeLabel) { v -> onChange { it.copy(size = v) } }
            LookChoice(R.string.subtitle_look_color, appearance.color, SubtitleAppearance.colors, ::colorLabel) { v -> onChange { it.copy(color = v) } }
            LookChoice(R.string.subtitle_look_edge, appearance.edge, SubtitleAppearance.edges, ::edgeLabel) { v -> onChange { it.copy(edge = v) } }
            LookChoice(R.string.subtitle_look_background, appearance.background, SubtitleAppearance.backgrounds, ::backgroundLabel) { v -> onChange { it.copy(background = v) } }
            TvSettingRow(
                title = stringResource(R.string.subtitle_look_bold),
                checked = appearance.bold,
                onCheckedChange = { v -> onChange { it.copy(bold = v) } },
            )
            RightPanelValueSlider(
                title = stringResource(R.string.subtitle_look_position),
                value = appearance.positionPercent,
                valueRange = SubtitleAppearance.MIN_POSITION..SubtitleAppearance.MAX_POSITION,
                step = 1,
                valueLabel = "${appearance.positionPercent} %",
                onValueChange = { v -> onChange { it.copy(positionPercent = v) } },
            )
        }
    }
}

/** A 16:9 strip of picture stand-in with one subtitle line, drawn like Media3 will draw it. */
@Composable
private fun SubtitlePreview(appearance: SubtitleAppearance) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(180.dp)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF6F8FB0), Color(0xFFC9B48F), Color(0xFF3B3326))),
                RoundedCornerShape(10.dp),
            ),
    ) {
        val textSize = with(androidx.compose.ui.platform.LocalDensity.current) { (maxHeight * appearance.textFraction * 2.2f).toSp() }
        val color = Color(appearance.textArgb)
        val base = TextStyle(
            color = color,
            fontSize = textSize,
            fontWeight = if (appearance.bold) FontWeight.Bold else FontWeight.Normal,
            shadow = if (appearance.edge == SubtitleAppearance.EDGE_SHADOW && appearance.background == SubtitleAppearance.BACKGROUND_NONE)
                Shadow(Color.Black, Offset(3f, 3f), 4f) else null,
        )
        val line = stringResource(R.string.subtitle_look_sample)
        Box(
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = maxHeight * (appearance.positionPercent / 100f))
                .background(Color(appearance.backgroundArgb), RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            if (appearance.edge == SubtitleAppearance.EDGE_OUTLINE && appearance.background == SubtitleAppearance.BACKGROUND_NONE) {
                Text(line, style = base.copy(color = Color.Black, drawStyle = Stroke(width = 5f)))
            }
            Text(line, style = base)
        }
    }
}

@Composable
private fun LookChoice(labelRes: Int, value: String, keys: List<String>, label: (String) -> Int, onSelected: (String) -> Unit) {
    val labels = keys.map { it to stringResource(label(it)) }
    PlayerDropUpSelector(
        label = stringResource(labelRes),
        value = labels.firstOrNull { it.first == value }?.second.orEmpty(),
        options = labels,
        selectedKey = value,
        onSelected = onSelected,
    )
}

private fun sizeLabel(key: String) = when (key) {
    SubtitleAppearance.SIZE_SMALL -> R.string.subtitle_look_size_small
    SubtitleAppearance.SIZE_LARGE -> R.string.subtitle_look_size_large
    SubtitleAppearance.SIZE_XLARGE -> R.string.subtitle_look_size_xlarge
    else -> R.string.subtitle_look_size_medium
}

private fun colorLabel(key: String) = when (key) {
    SubtitleAppearance.COLOR_YELLOW -> R.string.subtitle_look_color_yellow
    SubtitleAppearance.COLOR_GRAY -> R.string.subtitle_look_color_gray
    SubtitleAppearance.COLOR_CYAN -> R.string.subtitle_look_color_cyan
    SubtitleAppearance.COLOR_GREEN -> R.string.subtitle_look_color_green
    else -> R.string.subtitle_look_color_white
}

private fun edgeLabel(key: String) = when (key) {
    SubtitleAppearance.EDGE_SHADOW -> R.string.subtitle_look_edge_shadow
    SubtitleAppearance.EDGE_NONE -> R.string.subtitle_look_edge_none
    else -> R.string.subtitle_look_edge_outline
}

private fun backgroundLabel(key: String) = when (key) {
    SubtitleAppearance.BACKGROUND_TRANSLUCENT -> R.string.subtitle_look_background_translucent
    SubtitleAppearance.BACKGROUND_OPAQUE -> R.string.subtitle_look_background_opaque
    else -> R.string.subtitle_look_background_none
}

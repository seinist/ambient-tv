package com.ambient.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.artworkAccentSurface
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PlaybackStatusBar
import com.ambient.tv.ui.common.WatchedBadge
import com.ambient.tv.ui.theme.AccentOptions
import com.ambient.tv.ui.theme.accentColorFor
import com.ambient.tv.ui.theme.AccentStyle
import com.ambient.tv.ui.theme.accentBrush
import com.ambient.tv.ui.theme.LocalAccentStyle
import com.ambient.tv.ui.theme.accentMaterialBorder
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.theme.playbackAccentColorFor

internal enum class AccentColorRole { PRIMARY, PLAYBACK }

@Composable
internal fun accentStyleLabel(style: AccentStyle): String = stringResource(when (style) {
    AccentStyle.SATIN -> R.string.settings_accent_style_satin
    AccentStyle.GLASS -> R.string.settings_accent_style_glass
    AccentStyle.GLOW -> R.string.settings_accent_style_glow
    AccentStyle.PEARL -> R.string.settings_accent_style_pearl
    AccentStyle.CERAMIC -> R.string.settings_accent_style_ceramic
    AccentStyle.CHROME -> R.string.settings_accent_style_chrome
    AccentStyle.FACET -> R.string.settings_accent_style_facet
    AccentStyle.LIGHT -> R.string.settings_accent_style_light
})

/** Production artwork surfaces and both accent roles, without adding any focus targets. */
@Composable
internal fun AccentStylePreview(styleKey: String) {
    val style = AccentStyle.fromKey(styleKey)
    CompositionLocalProvider(LocalAccentStyle provides style) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 420.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentStyleCardPreview(Modifier.widthIn(max = 280.dp).fillMaxWidth())
                    AccentStyleControlsPreview(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccentStyleCardPreview(Modifier.width(236.dp))
                    AccentStyleControlsPreview(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AccentStyleCardPreview(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.aspectRatio(16f / 9f)
            .accentMaterialBorder(2.dp, scheme.primary, shape, scheme.primary, active = true)
            .padding(2.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(shape)) {
            Image(
                painter = painterResource(R.drawable.accent_preview_mountains),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            MetaBadge("4K", emphasized = true, modifier = Modifier.align(Alignment.TopStart).padding(7.dp))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .artworkAccentSurface(RectangleShape, scheme.primary, baseColor = Color.Black.copy(alpha = .58f))
                    .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.settings_preview_title),
                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("2026 · HDR", style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary, maxLines = 1)
            }
            PlaybackStatusBar(.55f, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AccentStyleControlsPreview(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.width(72.dp).height(36.dp)
                    .appControlSurface(focused = false, selected = true, shape = RoundedCornerShape(9.dp), animate = false),
                contentAlignment = Alignment.Center,
            ) {
                Text("Aa", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            WatchedBadge()
        }
        PlaybackStatusBar(.55f, Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f))
    }
}

internal fun accentSwatchColors(key: String, primaryHex: String, playbackHex: String, dual: Boolean): List<Color> = when {
    key == "artwork" -> listOfNotNull(AccentOptions["blue"], AccentOptions["pink"], AccentOptions["gold"])
    dual -> listOf(accentColorFor(key, primaryHex), playbackAccentColorFor(key, playbackHex, primaryHex))
    else -> listOf(accentColorFor(key, primaryHex))
}

/** Swap the raw palette colors so contrast/HDR adjustments remain tied to their new roles. */
internal fun SettingsUiState.withSwappedAccentColors(): SettingsUiState {
    if (!dualAccentEnabled) return this
    val primary = accentColorFor(accentColorKey, customAccentHex)
    val playback = playbackAccentColorFor(accentColorKey, customPlaybackAccentHex, customAccentHex)
    fun Color.hex() = "#%06X".format(java.util.Locale.ROOT, toArgb() and 0xFFFFFF)
    return copy(
        accentColorKey = "custom",
        customAccentHex = playback.hex(),
        customPlaybackAccentHex = primary.hex(),
    )
}

/** With the dual accent opted into, both roles stay visible in the selector and its non-focusable preview. */
@Composable
internal fun AccentPairPreview(key: String, primaryHex: String, playbackHex: String, dual: Boolean) {
    val primary = accentColorFor(key, primaryHex)
    // Single mode mirrors the theme: playback reuses the contrast-adjusted main accent.
    val playback = if (dual) playbackAccentColorFor(key, playbackHex, primaryHex) else MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AccentRoleSwatch(stringResource(R.string.settings_primary_accent), primary, Modifier.weight(1f))
            if (dual) AccentRoleSwatch(stringResource(R.string.settings_playback_accent), playback, Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
        }
        if (key == "artwork" && dual) {
            Text(stringResource(R.string.settings_artwork_playback_accent_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlaybackStatusBar(
            progress = .38f,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .15f),
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
    }
}

@Composable
private fun AccentRoleSwatch(label: String, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp).background(accentBrush(color, LocalAccentStyle.current), RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .25f), RoundedCornerShape(6.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CustomAccentSetting(
    role: AccentColorRole,
    value: String,
    onChoose: () -> Unit,
) {
    val playback = role == AccentColorRole.PLAYBACK
    SettingsGroup(
        title = stringResource(if (playback) R.string.settings_playback_accent else R.string.settings_primary_accent),
        subtitle = stringResource(if (playback) R.string.settings_playback_accent_hint else R.string.settings_primary_accent_hint),
    ) {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(12.dp)
        val color = Color(android.graphics.Color.parseColor(canonicalAccentHex(value)))
        Row(
            modifier = Modifier.fillMaxWidth()
                .border(if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .25f), shape)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .onFocusChanged { focused = it.isFocused }
                .activateOnTvKey(onClick = onChoose)
                .clickable(role = Role.Button, onClick = onChoose)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RainbowAccentSwatch(color)
            Text(
                stringResource(if (playback) R.string.settings_choose_playback_accent else R.string.settings_choose_primary_accent),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun RainbowAccentSwatch(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).background(
            Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
            CircleShape,
        ).padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().background(color, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape))
    }
}

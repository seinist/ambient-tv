package com.ambient.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.theme.AccentOptions
import com.ambient.tv.ui.theme.accentColorFor
import com.ambient.tv.ui.theme.playbackAccentColorFor

internal enum class AccentColorRole { PRIMARY, PLAYBACK }

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
        androidx.compose.material3.LinearProgressIndicator(
            progress = { .38f }, color = playback,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .15f),
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
    }
}

@Composable
private fun AccentRoleSwatch(label: String, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp).background(color, RoundedCornerShape(6.dp))
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

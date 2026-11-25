package com.ambient.tv.ui.moonlight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.moonlight.MoonlightStreamProfile
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.settings.SettingsGroup
import com.ambient.tv.ui.settings.SettingsSubheader

/** Shared TV and phone controls; codecs are hardware-validated again at session start. */
@Composable
fun MoonlightStreamSettingsSection(
    profile: MoonlightStreamProfile,
    update: (MoonlightStreamProfile) -> Unit,
) {
    var bitrate by rememberSaveable(profile.bitrateKbps) {
        mutableStateOf(profile.bitrateKbps.toString())
    }
    SettingsSubheader(stringResource(R.string.moonlight_quality_title))
    SettingsGroup(card = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.moonlight_quality_resolution),
                style = MaterialTheme.typography.titleSmall)
            SettingsActionRow {
                listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160).forEach { (w, h) ->
                    TvActionButton("$w×$h", { update(profile.copy(width = w, height = h)) },
                        selected = profile.width == w && profile.height == h)
                }
            }
            Text(stringResource(R.string.moonlight_quality_fps),
                style = MaterialTheme.typography.titleSmall)
            SettingsActionRow {
                listOf(30, 60, 90, 120).forEach { fps ->
                    TvActionButton("$fps FPS", { update(profile.copy(fps = fps)) },
                        selected = profile.fps == fps)
                }
            }
            Text(stringResource(R.string.moonlight_quality_codec),
                style = MaterialTheme.typography.titleSmall)
            SettingsActionRow {
                listOf("auto", "h264", "hevc", "av1").forEach { codec ->
                    val label = if (codec == "auto") stringResource(R.string.moonlight_quality_auto) else codec.uppercase()
                    TvActionButton(label, { update(profile.copy(codec = codec)) },
                        selected = profile.codec == codec)
                }
            }
            Text(stringResource(R.string.moonlight_quality_audio),
                style = MaterialTheme.typography.titleSmall)
            SettingsActionRow {
                listOf(2 to "Stereo", 6 to "5.1", 8 to "7.1").forEach { (channels, label) ->
                    TvActionButton(label, { update(profile.copy(audioChannels = channels)) },
                        selected = profile.audioChannels == channels)
                }
            }
            TvTextField(
                bitrate,
                { bitrate = it.filter(Char::isDigit).take(6) },
                stringResource(R.string.moonlight_quality_bitrate),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            SettingsActionRow {
                TvActionButton(stringResource(R.string.moonlight_quality_save_bitrate), {
                    bitrate.toIntOrNull()?.let { update(profile.copy(bitrateKbps = it)) }
                }, enabled = (bitrate.toIntOrNull() ?: 0) in 1000..150_000)
            }
            Text(stringResource(R.string.moonlight_quality_bitrate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

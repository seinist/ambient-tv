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
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.settings.SettingsGroup
import com.ambient.tv.ui.settings.SettingsSubheader

/** Shared TV and phone controls; codecs are hardware-validated again at session start. */
@Composable
fun MoonlightStreamSettingsSection(
    profile: MoonlightStreamProfile,
    update: (MoonlightStreamProfile) -> Unit,
    includeDisplay: Boolean = true,
) {
    var bitrate by rememberSaveable(profile.bitrateKbps) {
        mutableStateOf(profile.bitrateKbps.toString())
    }
    SettingsSubheader(stringResource(R.string.moonlight_quality_title))
    SettingsGroup(card = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val resolution = "${profile.width}×${profile.height}"
            val resolutions = (listOf("1280×720", "1920×1080", "2560×1440", "3840×2160") + resolution).distinct()
            PlayerDropUpSelector(label = stringResource(R.string.moonlight_quality_resolution),
                value = resolution, options = resolutions.map { it to it }, selectedKey = resolution,
                dropUp = false, selectorKey = "moonlight-resolution", onSelected = { value ->
                    val parts = value.split('×')
                    update(profile.copy(width = parts[0].toInt(), height = parts[1].toInt()))
                })
            val fps = (listOf(30, 60, 90, 120) + profile.fps).distinct().sorted().map { it.toString() to "$it FPS" }
            PlayerDropUpSelector(label = stringResource(R.string.moonlight_quality_fps),
                value = "${profile.fps} FPS", options = fps, selectedKey = profile.fps.toString(),
                dropUp = false, selectorKey = "moonlight-fps", onSelected = { update(profile.copy(fps = it.toInt())) })
            val codecs = listOf("auto", "h264", "hevc", "av1").map {
                it to if (it == "auto") stringResource(R.string.moonlight_quality_auto) else it.uppercase()
            }
            PlayerDropUpSelector(label = stringResource(R.string.moonlight_quality_codec),
                value = codecs.firstOrNull { it.first == profile.codec }?.second ?: profile.codec,
                options = codecs, selectedKey = profile.codec, dropUp = false,
                selectorKey = "moonlight-codec", onSelected = { update(profile.copy(codec = it)) })
            val hdr = listOf("auto", "on", "off").map {
                it to stringResource(when (it) {
                    "on" -> R.string.moonlight_quality_hdr_on
                    "off" -> R.string.moonlight_quality_hdr_off
                    else -> R.string.moonlight_quality_auto
                })
            }
            PlayerDropUpSelector(label = stringResource(R.string.moonlight_quality_hdr),
                value = hdr.firstOrNull { it.first == profile.hdrMode }?.second ?: profile.hdrMode,
                options = hdr, selectedKey = profile.hdrMode, dropUp = false,
                selectorKey = "moonlight-hdr", onSelected = { update(profile.copy(hdrMode = it)) })
            Text(stringResource(R.string.moonlight_quality_hdr_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val audio = listOf("2" to "Stereo", "6" to "5.1", "8" to "7.1")
            val audioKey = profile.audioChannels.toString()
            PlayerDropUpSelector(label = stringResource(R.string.moonlight_quality_audio),
                value = audio.firstOrNull { it.first == audioKey }?.second ?: audioKey,
                options = audio, selectedKey = audioKey, dropUp = false,
                selectorKey = "moonlight-audio", onSelected = { update(profile.copy(audioChannels = it.toInt())) })
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
    if (includeDisplay) MoonlightDisplaySettingsSection(profile, update)
}

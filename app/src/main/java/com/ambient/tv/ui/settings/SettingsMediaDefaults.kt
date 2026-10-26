package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.*
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.player.*
import com.ambient.tv.ui.player.backend.MpvBackendAvailability
import com.ambient.tv.ui.player.backend.VlcNightProcessing

private fun profileRendererKey(playbackBackendKey: String): String =
    playbackBackendKey.takeIf { it in setOf("mpv", "vlc", "media3") } ?: "media3"

/** Renderer output and acceleration only; picture filters are set per playback in the player's settings panel. */
@Composable
internal fun SettingsImageDefaults(state: SettingsUiState, model: SettingsViewModel) {
    var renderer by rememberSaveable(state.playbackBackendKey) {
        mutableStateOf(profileRendererKey(state.playbackBackendKey))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ImageHint(stringResource(R.string.settings_image_filters_in_player))
        ImageHint(stringResource(R.string.settings_defaults_renderer_hint))
        if (state.playbackBackendKey == "auto") {
            ImageHint(stringResource(R.string.settings_defaults_renderer_auto_hint))
        }
        ImageChoice(
            R.string.settings_defaults_renderer, renderer,
            listOf("media3" to "Media3", "vlc" to "VLC", "mpv" to "MPV"),
        ) { renderer = it }
        if (renderer == "mpv" && !MpvBackendAvailability.isAvailable) ImageHint(stringResource(R.string.player_backend_mpv_unavailable))
        TvSettingRow(
            title = stringResource(R.string.settings_svp_available),
            body = stringResource(R.string.settings_svp_available_hint),
            checked = state.svpAvailable,
            onCheckedChange = model::onSvpAvailableChanged,
        )
        SettingsVideoAcceleration(state, model, renderer)
    }
}

@Composable
internal fun SettingsAudioDefaults(state: SettingsUiState, model: SettingsViewModel) {
    var renderer by rememberSaveable {
        mutableStateOf(profileRendererKey(state.playbackBackendKey))
    }
    // Cached per process and queried off the main thread; no audio session during composition.
    val vlcNightAvailable by produceState(initialValue = false) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VlcNightProcessing.deviceSupported }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ImageHint(stringResource(R.string.settings_defaults_hint))
        ImageHint(stringResource(R.string.settings_audio_shared))
        ImageHint(stringResource(R.string.settings_defaults_renderer_hint))
        if (state.playbackBackendKey == "auto") {
            ImageHint(stringResource(R.string.settings_defaults_renderer_auto_hint))
        }
        ImageChoice(
            R.string.settings_defaults_renderer,
            renderer,
            listOf("media3" to "Media3", "vlc" to "VLC", "mpv" to "MPV"),
        ) { renderer = it }
        // Passthrough is chosen per audio profile ("Audio-Ausgabe"); the former global switch
        // only remains as the meaning of untouched "source" profiles.
        ImageHint(stringResource(R.string.settings_audio_source_hint))
        ImageHint(stringResource(R.string.settings_passthrough_visualizer_hint))
        if (renderer == "vlc") ImageHint(stringResource(R.string.settings_audio_vlc_night))
        val route = rememberAudioRoute()
        AudioProfileEditor(
            settings = state.audioSettings,
            backend = renderer,
            // Untouched "source" profiles still follow the stored former global switch.
            sourcePassthrough = state.mediaAudioPassthroughEnabled,
            supportsNight = renderer != "vlc" || vlcNightAvailable,
            selectProfile = { model.onAudioSettingsChanged(state.audioSettings.copy(selectedProfile = it)) },
            change = { model.onAudioSettingsChanged(state.audioSettings.updateForRoute(it, route.profile)) },
            trackPreference = { model.onAudioSettingsChanged(state.audioSettings.copy(tracks = it)) },
            route = route,
        )
    }
}

package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.AudioCatalog
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.AudioSettings
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.data.local.VideoResolutionClass
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.ui.common.AppDialog

/** Saved values only, including audio overrides in resolution profiles. */
internal data class PlaybackOverviewSnapshot(
    val playbackBackend: String,
    val liveBackend: String,
    val displayMode: String,
    val displayAdjustment: String,
    val mpvOutput: String,
    val mpvDecoder: String,
    val vlcOutput: String,
    val mpv: MpvImageOptions,
    val vlc: VlcImageOptions,
    val media3: Media3ImageOptions,
    val audioPassthrough: Boolean,
    val audio: AudioSettings,
    val liveDeinterlaceMode: String,
    val liveVlcDeinterlaceFilter: String,
    val liveMpvDeinterlaceFilter: String,
    val profiles: Map<String, ResolutionVideoProfile>,
)

@Composable
internal fun PlaybackOverviewDialog(snapshot: PlaybackOverviewSnapshot, onDismiss: () -> Unit) {
    val displayModeSwitchingAvailable = com.ambient.tv.ui.player.rememberAvailableDisplayModes().size > 1
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_playback_overview),
        hint = stringResource(R.string.settings_playback_overview_hint),
        fillHeight = true,
        widthFraction = 0.72f,
        maxWidth = 920.dp,
    ) {
        OverviewSection(stringResource(R.string.settings_playback_overview_global)) {
            OverviewLine(stringResource(R.string.settings_playback_overview_video_renderer), rendererName(snapshot.playbackBackend))
            OverviewLine(stringResource(R.string.settings_playback_overview_live_renderer), rendererName(snapshot.liveBackend))
            if (displayModeSwitchingAvailable) {
                OverviewLine(stringResource(R.string.settings_display_mode_title), when (snapshot.displayMode) {
                    AppPreferences.DISPLAY_MODE_AUTO_CONTENT -> stringResource(R.string.settings_display_mode_auto_content)
                    AppPreferences.DISPLAY_MODE_AUTO_DISPLAY -> stringResource(R.string.settings_display_mode_auto_display)
                    "system" -> stringResource(R.string.settings_display_mode_system)
                    else -> snapshot.displayMode
                })
                OverviewLine(stringResource(R.string.settings_display_adjustment_title), when (snapshot.displayAdjustment) {
                    AppPreferences.DISPLAY_REFRESH_ALWAYS -> stringResource(R.string.settings_display_adjustment_always)
                    AppPreferences.DISPLAY_REFRESH_START -> stringResource(R.string.settings_display_adjustment_start)
                    AppPreferences.DISPLAY_REFRESH_START_STOP -> stringResource(R.string.settings_display_adjustment_start_stop)
                    else -> stringResource(R.string.settings_display_adjustment_off)
                })
            }
            OverviewLine("MPV · ${stringResource(R.string.settings_playback_overview_output)}", snapshot.mpvOutput)
            OverviewLine("MPV · ${stringResource(R.string.settings_playback_overview_decoder)}", snapshot.mpvDecoder)
            OverviewLine("VLC · ${stringResource(R.string.settings_playback_overview_output)}", snapshot.vlcOutput)
            OverviewLine(stringResource(R.string.settings_playback_overview_live_deinterlace), snapshot.liveDeinterlaceMode)
            OverviewLine("VLC · ${stringResource(R.string.settings_playback_overview_deinterlace_filter)}", snapshot.liveVlcDeinterlaceFilter)
            OverviewLine("MPV · ${stringResource(R.string.settings_playback_overview_deinterlace_filter)}", snapshot.liveMpvDeinterlaceFilter)
        }
        OverviewSection("MPV · ${stringResource(R.string.settings_playback_overview_filters)}") { MpvOverview(snapshot.mpv) }
        OverviewSection("VLC · ${stringResource(R.string.settings_playback_overview_filters)}") { VlcOverview(snapshot.vlc) }
        OverviewSection("Media3 · ${stringResource(R.string.settings_playback_overview_filters)}") { Media3Overview(snapshot.media3) }
        OverviewSection(stringResource(R.string.settings_playback_overview_audio)) {
            OverviewLine(stringResource(R.string.settings_playback_overview_route),
                if (snapshot.audio.selectedProfile == "auto") stringResource(R.string.audio_profile_auto) else routeName(snapshot.audio.selectedProfile))
            OverviewLine(stringResource(R.string.settings_playback_overview_tracks),
                "${snapshot.audio.tracks.role} · ${snapshot.audio.tracks.channels}")
            AudioCatalog.profiles.forEach { key ->
                OverviewSubheading(routeName(key))
                AudioOverview(snapshot.audio.profiles[key] ?: AudioOptions(), snapshot.audioPassthrough)
            }
        }
    }
}

@Composable
private fun OverviewSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun OverviewSubheading(title: String) {
    Text(title, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun OverviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun yesNo(value: Boolean) = stringResource(if (value) R.string.settings_playback_overview_yes else R.string.settings_playback_overview_no)

@Composable
internal fun rendererName(key: String) = when (key) {
    "mpv" -> "MPV"
    "vlc" -> "VLC"
    "media3" -> "Media3"
    else -> stringResource(R.string.player_backend_auto)
}

@Composable
private fun routeName(key: String) = when (key) {
    "avr" -> stringResource(R.string.settings_playback_overview_hdmi)
    "headphones" -> stringResource(R.string.settings_playback_overview_headphones)
    else -> "TV"
}

@Composable
private fun strength(key: String) = when (key) {
    "off" -> stringResource(R.string.player_off)
    "light" -> stringResource(R.string.image_light)
    "medium" -> stringResource(R.string.image_medium)
    "strong" -> stringResource(R.string.image_strong)
    "auto" -> stringResource(R.string.player_automatic)
    else -> key
}

/** Preset name in the UI language, "Benutzerdefiniert (…)" with analysis overrides. */
@Composable
private fun svpOverview(mode: String, options: com.ambient.tv.data.local.SvpOptions) =
    com.ambient.tv.ui.player.svpModeLabel(mode, options.isCustom)

@Composable
private fun MpvOverview(o: MpvImageOptions) {
    OverviewLine(stringResource(R.string.settings_playback_overview_scaler), VideoImageCatalog.mpvScalers[o.scaler] ?: o.scaler)
    OverviewLine(stringResource(R.string.settings_playback_overview_chroma), VideoImageCatalog.chromaScalers[o.chromaScaler] ?: o.chromaScaler)
    OverviewLine(stringResource(R.string.settings_playback_overview_downscale), VideoImageCatalog.downscalers[o.downscaler] ?: o.downscaler)
    OverviewLine(stringResource(R.string.settings_playback_overview_deband), strength(o.deband))
    OverviewLine(stringResource(R.string.settings_playback_overview_sharpen), strength(o.sharpening))
    OverviewLine(stringResource(R.string.settings_playback_overview_denoise), strength(o.denoise))
    OverviewLine(stringResource(R.string.settings_playback_overview_deblock), strength(o.deblock))
    OverviewLine(stringResource(R.string.image_film_grain), strength(o.filmGrain))
    OverviewLine(stringResource(R.string.settings_playback_overview_super_resolution), yesNo(o.superResolution))
    OverviewLine(stringResource(R.string.settings_playback_overview_antiring), "${o.antiring}%")
    OverviewLine(stringResource(R.string.settings_playback_overview_sigmoid), yesNo(o.sigmoid))
    OverviewLine(stringResource(R.string.settings_playback_overview_picture), "${o.brightness} / ${o.contrast} / ${o.saturation} / ${o.gamma}")
    OverviewLine(stringResource(R.string.settings_playback_overview_tone_mapping), o.toneMapping)
    OverviewLine(stringResource(R.string.settings_playback_overview_dither), o.ditherDepth)
    OverviewLine("SVP", svpOverview(o.svpMode, o.svp))
}

@Composable
private fun VlcOverview(o: VlcImageOptions) {
    OverviewLine(stringResource(R.string.settings_playback_overview_software), yesNo(o.softwareProcessing))
    OverviewLine(stringResource(R.string.settings_playback_overview_scaler), VideoImageCatalog.vlcScalers[o.scaler] ?: o.scaler)
    OverviewLine(stringResource(R.string.settings_playback_overview_deband), strength(o.deband))
    OverviewLine(stringResource(R.string.settings_playback_overview_denoise), strength(o.denoise))
    OverviewLine(stringResource(R.string.settings_playback_overview_picture), "${o.brightness} / ${o.contrast} / ${o.saturation} / ${o.gamma}")
    OverviewLine("SVP", svpOverview(o.svpMode, o.svp))
}

@Composable
private fun Media3Overview(o: Media3ImageOptions) {
    OverviewLine(stringResource(R.string.settings_playback_overview_enabled), yesNo(o.enabled))
    OverviewLine(stringResource(R.string.settings_playback_overview_scaler), VideoImageCatalog.media3Scalers[o.scaler] ?: o.scaler)
    OverviewLine(stringResource(R.string.settings_playback_overview_deband), strength(o.deband))
    OverviewLine(stringResource(R.string.settings_playback_overview_sharpen), if (o.sharpening == "cas") "AMD CAS" else strength(o.sharpening))
    OverviewLine(stringResource(R.string.settings_playback_overview_picture), "${o.brightness} / ${o.contrast} / ${o.saturation} / ${o.gamma}")
}

@Composable
private fun AudioOverview(o: AudioOptions, sourcePassthrough: Boolean) {
    OverviewLine(stringResource(R.string.settings_playback_overview_output),
        when (com.ambient.tv.ui.player.displayedAudioOutput(o, sourcePassthrough)) {
            "force" -> stringResource(R.string.audio_prefer_bitstream)
            "pcm" -> stringResource(R.string.audio_fallback_pcm)
            else -> stringResource(R.string.player_automatic)
        })
    OverviewLine(stringResource(R.string.audio_pcm_channels),
        when (com.ambient.tv.ui.player.displayedPcmChannels(o)) {
            "2" -> "2.0"; "6" -> "5.1"; "8" -> "7.1"
            else -> stringResource(R.string.player_automatic)
        })
    OverviewLine(stringResource(R.string.settings_playback_overview_codecs), o.allowedCodecs.joinToString().ifEmpty { "—" })
    OverviewLine(stringResource(R.string.audio_unsupported), when (com.ambient.tv.ui.player.displayedUnsupportedAudio(o)) {
        "ac3" -> stringResource(R.string.audio_unsupported_ac3)
        "pcm" -> stringResource(R.string.audio_fallback_pcm)
        "server" -> stringResource(R.string.audio_unsupported_server)
        "off" -> stringResource(R.string.player_off)
        else -> stringResource(R.string.player_automatic)
    })
    OverviewLine(stringResource(R.string.settings_playback_overview_delay), "${o.delayMs} ms")
    OverviewLine(stringResource(R.string.settings_playback_overview_channels), strength(o.channels))
    OverviewLine(stringResource(R.string.settings_playback_overview_night), yesNo(o.night))
    OverviewLine(stringResource(R.string.settings_playback_overview_dialogue), o.dialogue.toString())
    OverviewLine(stringResource(R.string.settings_playback_overview_normalize), yesNo(o.normalize))
    OverviewLine("ReplayGain", strength(o.replayGain))
    OverviewLine(stringResource(R.string.settings_playback_overview_eq),
        "${yesNo(o.eqEnabled)} · ${o.preampDb} dB · ${o.eqDb.joinToString()}")
    OverviewLine(stringResource(R.string.settings_playback_overview_upmix), yesNo(o.upmix))
    OverviewLine(stringResource(R.string.settings_playback_overview_downmix_volume), yesNo(o.maintainDownmixVolume))
}

internal fun savedPlaybackOverview(appPreferences: AppPreferences) = PlaybackOverviewSnapshot(
        playbackBackend = appPreferences.playbackBackendKey,
        liveBackend = appPreferences.tvheadendPlaybackBackendKey,
        displayMode = appPreferences.preferredDisplayModeKey,
        displayAdjustment = appPreferences.displayRefreshAdjustmentKey,
        mpvOutput = appPreferences.mpvVideoOutputModeKey,
        mpvDecoder = appPreferences.mpvVideoDecoderKey,
        vlcOutput = appPreferences.vlcVideoOutputModeKey,
        mpv = appPreferences.mpvImageOptions,
        vlc = appPreferences.vlcImageOptions,
        media3 = appPreferences.media3ImageOptions,
        audioPassthrough = appPreferences.mediaAudioPassthroughEnabled,
        audio = appPreferences.audioSettings,
        liveDeinterlaceMode = appPreferences.tvheadendDeinterlaceModeKey,
        liveVlcDeinterlaceFilter = appPreferences.tvheadendDeinterlaceFilterKey,
        liveMpvDeinterlaceFilter = appPreferences.tvheadendMpvDeinterlaceFilterKey,
        profiles = appPreferences.resolutionVideoProfiles,
    )

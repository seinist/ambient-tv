package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.media3.ui.AspectRatioFrameLayout
import com.ambient.tv.R
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.domain.playback.PlaybackMode
import com.ambient.tv.ui.common.formatHdrMetadata

/** Labels and value words for the settings rows, so [nerdSettingRows] stays testable. */
data class NerdSettingLabels(
    val hdr: String = "HDR",
    val streamMode: String = "Stream mode",
    val picture: String = "Picture",
    val speed: String = "Playback speed",
    val profile: String = "Audio profile",
    val delay: String = "Audio delay",
    val sound: String = "Sound processing",
    val off: String = "Off",
    val suspended: String = "suspended (bitstream)",
    val automatic: String = "automatic",
    val fit: String = "Fit",
    val fill: String = "Fill",
    val stretch: String = "Stretch",
    val directPlay: String = "Direct play",
    val directStream: String = "Direct stream",
    val transcode: String = "Transcode",
    val eq: String = "EQ",
    val dialogue: String = "Dialogue",
    val night: String = "Night mode",
    val normalize: String = "Normalization",
    val replayGain: String = "ReplayGain",
    val upmix: String = "Upmix",
    val titleScope: String = "title",
)

/** What the audio session plays with, independent of Compose. */
data class NerdAudioSettings(
    val profileName: String,
    val options: AudioOptions,
    val titleDelay: Boolean,
    val nightAutomatic: Boolean,
    val dspSuspended: Boolean,
    val supportsNight: Boolean,
    val backend: String,
    val downmix: String?,
)

/** The picture and sound settings of this playback, beside what the engine reports. */
internal fun nerdSettingRows(
    labels: NerdSettingLabels,
    hdr: String?,
    mode: PlaybackMode?,
    presentation: VideoPresentation,
    speed: Float?,
    audio: NerdAudioSettings?,
): Pair<List<NerdStatsOverlayRow>, List<NerdStatsOverlayRow>> {
    val video = buildList {
        hdr?.takeIf { it.isNotBlank() }?.let { add(NerdStatsOverlayRow(labels.hdr, it)) }
        mode?.let {
            add(NerdStatsOverlayRow(labels.streamMode, when (it) {
                PlaybackMode.DIRECT_PLAY -> labels.directPlay
                PlaybackMode.DIRECT_STREAM -> labels.directStream
                PlaybackMode.TRANSCODE -> labels.transcode
            }, warn = it == PlaybackMode.TRANSCODE))
        }
        val p = presentation.normalized()
        val fit = when {
            p.aspectRatio != null -> aspectLabel(p.aspectRatio)
            p.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> labels.fill
            p.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL -> labels.stretch
            else -> labels.fit
        }
        val zoom = p.zoom.takeIf { kotlin.math.abs(it - 1f) > 0.001f }?.let { "${(it * 100).toInt()} %" }
        add(NerdStatsOverlayRow(labels.picture, listOfNotNull(fit, zoom).joinToString(" · ")))
        speed?.takeIf { kotlin.math.abs(it - 1f) > 0.001f }?.let {
            add(NerdStatsOverlayRow(labels.speed, "%.2f×".format(java.util.Locale.ROOT, it).replace(".00×", "×")))
        }
    }
    val sound = buildList {
        audio ?: return@buildList
        add(NerdStatsOverlayRow(labels.profile, audio.profileName))
        val delay = audio.options.delayMs
        add(NerdStatsOverlayRow(labels.delay,
            (if (delay > 0) "+" else "") + "$delay ms" + if (audio.titleDelay) " (${labels.titleScope})" else ""))
        val o = audio.options
        val parts = if (audio.dspSuspended) listOf(labels.suspended) else listOfNotNull(
            audio.downmix,
            if (o.eqEnabled) labels.eq + if (o.preampDb != 0) " ${signed(o.preampDb)} dB" else "" else null,
            if (o.dialogue > 0) "${labels.dialogue} +${o.dialogue}" else null,
            if (o.night && audio.supportsNight) labels.night + if (audio.nightAutomatic) " (${labels.automatic})" else "" else null,
            if (o.normalize && audio.backend != "vlc") labels.normalize else null,
            if (o.replayGain != "off" && audio.backend == "mpv") "${labels.replayGain} ${o.replayGain}" else null,
            if (o.upmix) labels.upmix else null,
        )
        add(NerdStatsOverlayRow(labels.sound, parts.joinToString(" · ").ifEmpty { labels.off }))
    }
    return video to sound
}

private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

private fun aspectLabel(ratio: Float): String = when {
    kotlin.math.abs(ratio - 16f / 9f) < 0.01f -> "16:9"
    kotlin.math.abs(ratio - 4f / 3f) < 0.01f -> "4:3"
    kotlin.math.abs(ratio - 21f / 9f) < 0.01f -> "21:9"
    else -> "%.2f:1".format(java.util.Locale.ROOT, ratio)
}

internal fun AudioSession.nerdAudioSettings(profileName: (String) -> String): NerdAudioSettings {
    val plan = outputPlan()
    return NerdAudioSettings(
        profileName = profileName(settings.effectiveProfile(route.profile)),
        options = options,
        titleDelay = titleDelayMs != null,
        nightAutomatic = nightAutomatic,
        dspSuspended = plan.encoded && !plan.applyDsp,
        supportsNight = supportsNight,
        backend = backend,
        downmix = audioDownmixLabel(sourceChannels, plan),
    )
}

/** Rows for the Stats-for-Nerds overlay: (video, audio). */
@Composable
internal fun rememberNerdSettingRows(
    state: PlayerUiState.Ready,
    session: AudioSession?,
    speed: Float?,
): Pair<List<NerdStatsOverlayRow>, List<NerdStatsOverlayRow>> {
    val presentation by BackgroundPlaybackSession.videoPresentation.collectAsState()
    val labels = NerdSettingLabels(
        hdr = stringResource(R.string.player_nerd_hdr),
        streamMode = stringResource(R.string.player_stream_mode),
        picture = stringResource(R.string.player_scaling_mode),
        speed = stringResource(R.string.player_speed),
        profile = stringResource(R.string.audio_profile),
        delay = stringResource(R.string.audio_delay),
        sound = stringResource(R.string.player_nerd_sound_processing),
        off = stringResource(R.string.player_off),
        suspended = stringResource(R.string.player_nerd_dsp_suspended),
        automatic = stringResource(R.string.player_nerd_automatic),
        fit = stringResource(R.string.player_fit_screen),
        fill = stringResource(R.string.player_fill_screen),
        stretch = stringResource(R.string.player_stretch),
        directPlay = stringResource(R.string.player_direct_play),
        directStream = stringResource(R.string.player_direct_stream),
        transcode = stringResource(R.string.player_transcode),
        eq = stringResource(R.string.audio_equalizer),
        dialogue = stringResource(R.string.audio_dialogue),
        night = stringResource(R.string.audio_night),
        normalize = stringResource(R.string.audio_normalize),
        replayGain = stringResource(R.string.audio_replaygain),
        upmix = stringResource(R.string.audio_upmix),
        titleScope = stringResource(R.string.player_nerd_title_scope),
    )
    val hdmi = stringResource(R.string.audio_profile_hdmi)
    val headphones = stringResource(R.string.audio_headphones)
    val hdr = formatHdrMetadata(state.videoDynamicRange, state.videoProfile)
        ?: state.runtimeHdrStandard?.takeIf { state.runtimeHdrKnown }?.label?.uppercase()
    val audio = session?.nerdAudioSettings { key -> when (key) { "avr" -> hdmi; "headphones" -> headphones; else -> "TV" } }
    val hasVideo = !audioOnlyPlaybackHint(state.sourceRatingKey, state.mediaType, state.videoCodec)
    val rows = nerdSettingRows(labels, hdr, state.playbackMode, presentation, speed, audio)
    // Music has no picture to fit; its picture rows would only say "Fit".
    return (if (hasVideo) rows.first else rows.first.filter { it.label != labels.picture }) to rows.second
}

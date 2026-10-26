@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.data.local.VideoResolutionClass
import com.ambient.tv.notifications.*
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

internal fun playbackProfileRenderer(handle: PlaybackHandle?): String? = when (handle) {
    is MpvPlaybackController -> "mpv"
    is VlcPlaybackController -> "vlc"
    is Media3PlaybackHandle -> "media3"
    else -> null
}

internal fun playbackProfileAudio(handle: PlaybackHandle?): AudioSession? = when (handle) {
    is MpvPlaybackController -> handle.audioSession
    is VlcPlaybackController -> handle.audioSession
    is Media3PlaybackHandle -> Media3AudioProcessing.session(handle.player)
    else -> null
}

private fun profileNotice(message: Int, error: Boolean = false, args: List<String> = emptyList()) {
    AppNotifications.post(AppNotice(
        key = "playback-profile:${System.nanoTime()}", message = message, args = args,
        phase = if (error) NoticePhase.ERROR else NoticePhase.SUCCESS,
        kind = NoticeKind.SETTINGS, interruptPlayback = true,
    ))
}

/** Automatic restoration is silent; only explicit load/save actions announce a preset. */
internal fun restoreSavedPlaybackAudio(
    session: AudioSession, title: TitleVideoSettings?, resolution: ResolutionVideoProfile?,
) {
    if (session.restoreAutomaticProfile(title?.audio ?: resolution?.audio)) {
        session.restoreTitleDelay(title?.audioDelayMs)
    }
}

/** Reapply to the running backend; output changes use the existing session restart path. */
private fun applyPlaybackProfile(
    context: Context, metadata: PlaybackMetadata?, handle: PlaybackHandle,
    title: TitleVideoSettings?, resolution: ResolutionVideoProfile?,
) {
    val preferences = videoImagePreferences(context)
    val output = PlayerVideoSettings.from(preferences).withResolution(resolution).withTitle(title)
    playbackProfileAudio(handle)?.let { audio ->
        audio.restoreProfile(title?.audio ?: resolution?.audio)
        audio.restoreTitleDelay(title?.audioDelayMs)
    }
    when (handle) {
        is MpvPlaybackController -> {
            handle.updateImageOptions((title?.mpv ?: resolution?.mpv ?: preferences.mpvImageOptions).withGlobalSvp(preferences))
            val mode = title?.mpvDeinterlaceMode ?: resolution?.mpvDeinterlaceMode
                ?: if (metadata?.isLive == true) com.ambient.tv.ui.player.backend.tvheadendMpvDeinterlaceMode(preferences.tvheadendDeinterlaceModeKey) else "off"
            val filter = title?.mpvDeinterlaceFilter ?: resolution?.mpvDeinterlaceFilter
                ?: if (metadata?.isLive == true) preferences.tvheadendMpvDeinterlaceFilterKey else "auto"
            handle.setDeinterlace(mode != "off", filter, mode == "auto")
        }
        is VlcPlaybackController -> {
            handle.updateImageOptions((title?.vlc ?: resolution?.vlc ?: preferences.vlcImageOptions).withGlobalSvp(preferences), preservePosition = true)
            handle.setDeinterlace(
                title?.vlcDeinterlaceMode ?: resolution?.vlcDeinterlaceMode
                    ?: if (metadata?.isLive == true) preferences.tvheadendDeinterlaceModeKey else "off",
                title?.vlcDeinterlaceFilter ?: resolution?.vlcDeinterlaceFilter
                    ?: if (metadata?.isLive == true) preferences.tvheadendDeinterlaceFilterKey else "auto",
            )
        }
        is Media3PlaybackHandle -> Media3ImageProcessing.get(context, handle.player)
            .updateOptions(title?.media3 ?: resolution?.media3 ?: preferences.media3ImageOptions)
    }
    (handle as? SubtitleDelayTarget)?.setSubtitleDelay(title?.subtitleDelayMs ?: 0)
    metadata?.let { BackgroundPlaybackSession.setVideoSettings(it, output) }
}

/** Central storage panel for the complete running audio/video configuration. */
@Composable
internal fun PlaybackProfileControls(
    onRendererSelected: ((String, Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val metadata = LocalPlaybackMetadata.current
    val handle = BackgroundPlaybackSession.activeHandle()
    var revision by remember { mutableIntStateOf(0) }
    // Store changes made while the panel is open (polling capture, other panels) refresh the layers.
    val storeRevision by TitleVideoSettingsStore.revision.collectAsState()
    ImageHint(stringResource(R.string.playback_profile_includes))
    if (handle == null || playbackProfileRenderer(handle) == null) {
        ImageHint(stringResource(R.string.playback_profile_unavailable))
        return
    }
    val titleKey = TitleVideoSettingsStore.key(metadata)
    val resolutionKey = ResolutionVideoProfileStore.key(metadata, handle)
    val resolution = remember(resolutionKey, revision) {
        resolutionKey?.let { com.ambient.tv.data.local.resolutionProfileFor(
            videoImagePreferences(context).resolutionVideoProfiles, it.removePrefix("live:"), metadata?.isLive == true) }
    }
    fun perform(message: Int? = null, action: () -> Unit) {
        try {
            action()
            revision++
            message?.let { profileNotice(it) }
        } catch (_: Exception) {
            profileNotice(R.string.playback_profile_failed, error = true)
        }
    }
    fun apply(titleProfile: TitleVideoSettings?, resolutionProfile: ResolutionVideoProfile?) {
        val preferences = videoImagePreferences(context)
        // Live profiles: "auto" inherits the Tvheadend default like an unset player (liveRendererOrDefault).
        val renderer = titleProfile?.rendererKey
            ?: resolutionProfile?.rendererKey?.takeUnless { metadata?.isLive == true && it == "auto" }
            ?: if (metadata?.isLive == true) preferences.tvheadendPlaybackBackendKey else preferences.playbackBackendKey
        // A renderer change restarts through the existing backend selection mechanism.
        if (com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(renderer).key != playbackProfileRenderer(handle)) {
            check(onRendererSelected != null)
            applyPlaybackProfile(context, metadata, handle, titleProfile, resolutionProfile)
            TitleVideoSettingsStore.withSessionRendererSelection { onRendererSelected(renderer, false) }
        } else applyPlaybackProfile(context, metadata, handle, titleProfile, resolutionProfile)
    }
    // Automatic memory and "remember changes for" first: they decide what the next episode gets.
    if (titleKey != null) ImageHint(stringResource(if (metadata?.isLive == true) R.string.channel_profile_auto else R.string.playback_profile_auto_title))
    else ImageHint(stringResource(R.string.playback_profile_title_unavailable))
    val scopes = remember(titleKey, revision, storeRevision) { TitleVideoSettingsStore.layerValues(context, metadata) }
    if (titleKey != null && metadata?.isLive != true) {
        val target = remember(titleKey, revision, storeRevision) { TitleVideoSettingsStore.rememberLayer(context, metadata) }
        val episode = metadata?.videoScope?.episode == true
        val movie = metadata?.videoScope != null && !episode
        val labels = scopes.map { (layer, _) -> layer.key to videoScopeLabel(layer, episode, movie) }
        if (scopes.size > 1) {
            val switchedBroad = stringResource(R.string.playback_scope_switched_broad)
            val switchedTitle = stringResource(R.string.playback_scope_switched_title)
            ImageChoice(R.string.playback_scope_remember_for, target?.key ?: titleKey, labels) { key ->
                scopes.firstOrNull { it.first.key == key }?.let {
                    if (it.first.key == target?.key) return@let
                    TitleVideoSettingsStore.setRememberLayer(context, metadata, it.first)
                    revision++
                    val label = labels.firstOrNull { (k, _) -> k == key }?.second.orEmpty()
                    profileNotice(R.string.playback_scope_switched, args = listOf(label,
                        if (it.first.scope == VideoSettingsScope.TITLE) switchedTitle else switchedBroad))
                }
            }
        }
        val origins = videoSettingOrigins(scopes, resolution)
        val names = listOf(origins.renderer, origins.picture, origins.deinterlace, origins.output, origins.audio, origins.delay)
            .map { stringResource(videoOriginLabel(it, episode, movie)) }
        ImageHint(stringResource(R.string.playback_scope_origins, *names.toTypedArray()))
        scopes.filter { (layer, value) -> layer.scope != VideoSettingsScope.TITLE && value != null }.forEach { (layer, _) ->
            TvActionButton(text = stringResource(R.string.playback_scope_reset, videoScopeLabel(layer, episode, movie)), onClick = {
                perform {
                    TitleVideoSettingsStore.forgetScope(context, layer)
                    apply(TitleVideoSettingsStore.load(context, metadata), ResolutionVideoProfileStore.load(context, metadata))
                }
            }, modifier = Modifier.fillMaxWidth(), fillWidth = true)
        }
    }
    // Video edits are remembered automatically; only shared defaults need explicit saving.
    TvActionButton(text = stringResource(R.string.playback_profile_save_all), onClick = {
        perform(R.string.playback_profile_saved_all) {
            check(ResolutionVideoProfileStore.save(context, metadata, handle, global = true) != null)
        }
    }, modifier = Modifier.fillMaxWidth(), fillWidth = true)
    val label = resolutionKey?.let { VideoResolutionClass.label(it.removePrefix("live:")) }
    TvActionButton(
        text = if (metadata?.isLive == true && label != null) stringResource(R.string.live_profiles_save_scope, label)
            else stringResource(R.string.playback_profile_save_resolution_scope),
        enabled = resolutionKey != null,
        onClick = {
            perform(R.string.playback_profile_saved_resolution) {
                check(ResolutionVideoProfileStore.save(context, metadata, handle) != null)
            }
        }, modifier = Modifier.fillMaxWidth(), fillWidth = true,
    )
    if (label != null) ImageHint(stringResource(if (metadata?.isLive == true) R.string.live_profiles_scope_hint
        else R.string.playback_profile_resolution_scope, label))
    if (ResolutionVideoProfileStore.global(context) != null) {
        TvActionButton(text = stringResource(R.string.playback_profile_load_all), onClick = {
            perform(R.string.playback_profile_loaded) {
                TitleVideoSettingsStore.clearProfileOverrides(context, metadata)
                apply(null, ResolutionVideoProfileStore.global(context))
            }
        }, modifier = Modifier.fillMaxWidth(), fillWidth = true)
    }
    if (resolutionKey != null) {
        val label = VideoResolutionClass.label(resolutionKey.removePrefix("live:"))
        if (resolution != null) {
            TvActionButton(text = stringResource(if (metadata?.isLive == true) R.string.live_profiles_load_scope
                else R.string.playback_profile_load_resolution, label), onClick = {
                perform(R.string.playback_profile_loaded) {
                    TitleVideoSettingsStore.clearProfileOverrides(context, metadata)
                    apply(null, resolution)
                }
            }, modifier = Modifier.fillMaxWidth(), fillWidth = true)
        }
    } else ImageHint(stringResource(R.string.playback_profile_resolution_unavailable))
    ImageHint(stringResource(R.string.playback_profile_reset_scope_hint))
    TvActionButton(text = stringResource(R.string.playback_profile_reset_all), onClick = {
        perform {
            resetGlobalPlaybackProfile(videoImagePreferences(context))
            apply(TitleVideoSettingsStore.load(context, metadata), ResolutionVideoProfileStore.load(context, metadata))
        }
    }, modifier = Modifier.fillMaxWidth(), fillWidth = true)
    TvActionButton(text = stringResource(R.string.playback_profile_reset_resolution), enabled = resolutionKey != null, onClick = {
        perform {
            resolutionKey?.let { ResolutionVideoProfileStore.forget(context, it) }
            apply(TitleVideoSettingsStore.load(context, metadata), ResolutionVideoProfileStore.load(context, metadata))
        }
    }, modifier = Modifier.fillMaxWidth(), fillWidth = true)
    if (titleKey != null) TvActionButton(text = stringResource(if (metadata?.isLive == true) R.string.channel_profile_reset else R.string.playback_profile_reset_title), onClick = {
        perform {
            TitleVideoSettingsStore.forget(context, metadata)
            // Season/series/collection profiles still apply: the title must not re-save the width profile over them.
            apply(TitleVideoSettingsStore.load(context, metadata), ResolutionVideoProfileStore.load(context, metadata))
        }
    }, modifier = Modifier.fillMaxWidth(), fillWidth = true)

}

@Composable
private fun videoScopeLabel(layer: VideoScopeLayer, episode: Boolean, movie: Boolean): String = when (layer.scope) {
    VideoSettingsScope.TITLE -> stringResource(when {
        episode -> R.string.playback_scope_episode
        movie -> R.string.playback_scope_movie
        else -> R.string.playback_scope_title
    })
    VideoSettingsScope.SEASON -> stringResource(R.string.playback_scope_season)
    VideoSettingsScope.SERIES -> stringResource(R.string.playback_scope_series)
    VideoSettingsScope.COLLECTION -> stringResource(R.string.playback_scope_collection, layer.label.orEmpty())
}

private fun videoOriginLabel(origin: VideoSettingOrigin, episode: Boolean, movie: Boolean): Int = when (origin) {
    VideoSettingOrigin.TITLE -> when {
        episode -> R.string.playback_origin_episode
        movie -> R.string.playback_origin_movie
        else -> R.string.playback_origin_title
    }
    VideoSettingOrigin.SEASON -> R.string.playback_origin_season
    VideoSettingOrigin.SERIES -> R.string.playback_origin_series
    VideoSettingOrigin.COLLECTION -> R.string.playback_origin_collection
    VideoSettingOrigin.RESOLUTION -> R.string.playback_origin_resolution
    VideoSettingOrigin.DEFAULT -> R.string.playback_origin_default
}

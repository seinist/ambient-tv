@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer

/**
 * Prime saved audio settings before Media3 installs its live reconfiguration callbacks. Otherwise
 * the Compose restore can change passthrough/channels after the first frame and the audio layer
 * deliberately answers with stop()/prepare(), producing a visible startup gap.
 */
internal fun primeMedia3AudioProfile(
    context: android.content.Context,
    session: AudioSession,
    metadata: PlaybackMetadata?,
) {
    val title = TitleVideoSettingsStore.load(context, metadata)
    val resolution = ResolutionVideoProfileStore.load(context, metadata)
    restoreSavedPlaybackAudio(session, title, resolution)
    session.titleScoped = TitleVideoSettingsStore.key(metadata) != null
    if (session.acceptsAutomaticProfile) {
        TitleVideoSettingsStore.attachAudio(session, metadata, session.titleDelayMs ?: title?.audioDelayMs)
    }
}

/**
 * Select the saved image profile before the first prepare whenever the source geometry is already
 * known. Media3 otherwise starts with the global defaults and can show a first frame before the
 * Compose restore enables the GPU sink, forcing a visible stop/prepare cycle.
 */
internal fun primeMedia3PlaybackProfile(
    context: android.content.Context,
    player: ExoPlayer,
    metadata: PlaybackMetadata?,
) {
    val title = TitleVideoSettingsStore.load(context, metadata)
    val resolution = ResolutionVideoProfileStore.load(context, metadata)
    val image = title?.media3 ?: resolution?.media3 ?: videoImagePreferences(context).media3ImageOptions
    Media3ImageProcessing.get(context, player).restoreAutomaticOptions(image)
}

/** Shared by library, YouTube and live TV, including geometry discovered after startup. */
@Composable
internal fun RestoreMedia3PlaybackProfile(player: ExoPlayer, metadata: PlaybackMetadata?) {
    val context = LocalContext.current
    val processing = remember(player) { Media3ImageProcessing.get(context, player) }
    LaunchedEffect(player, metadata?.route, metadata?.sourceWidth, metadata?.sourceHeight) {
        val title = TitleVideoSettingsStore.load(context, metadata)
        val resolution = ResolutionVideoProfileStore.load(context, metadata)
        Media3AudioProcessing.session(player)?.let { audio ->
            restoreSavedPlaybackAudio(audio, title, resolution)
            audio.titleScoped = TitleVideoSettingsStore.key(metadata) != null
            if (audio.acceptsAutomaticProfile) {
                TitleVideoSettingsStore.attachAudio(audio, metadata, audio.titleDelayMs ?: title?.audioDelayMs)
            }
        }
        val image = title?.media3 ?: resolution?.media3 ?: videoImagePreferences(context).media3ImageOptions
        processing.restoreAutomaticOptions(image)
        processing.restoreOverloadContinue(title?.media3OverloadContinueSignature)
    }
}

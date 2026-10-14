@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer

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
    }
}

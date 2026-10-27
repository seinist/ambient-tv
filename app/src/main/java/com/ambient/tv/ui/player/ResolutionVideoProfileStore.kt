@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.AudioSettings
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.VideoResolutionClass
import com.ambient.tv.data.local.liveRendererOrDefault
import com.ambient.tv.data.local.resolutionProfileFor
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

/** Video defaults shared by every title with the same source width. */
internal object ResolutionVideoProfileStore {
    const val GLOBAL_KEY = "all"
    fun key(metadata: PlaybackMetadata?, handle: PlaybackHandle? = null): String? {
        if (metadata == null || metadata.isTrailer || !metadata.hasVideo) return null
        val size = when (handle) {
            is MpvPlaybackController -> handle.sourceDimensions()
            is VlcPlaybackController -> handle.sourceDimensions()
            is Media3PlaybackHandle -> handle.player.videoFormat?.let { it.width to it.height }
            else -> null
        }
        // Match the same provider geometry used before playback to select the renderer.
        val resolution = VideoResolutionClass.key(metadata.sourceWidth)
            ?: VideoResolutionClass.key(size?.first)
        return resolution?.let { if (metadata.isLive) "live:$it" else it }
    }

    fun load(context: Context, metadata: PlaybackMetadata?): ResolutionVideoProfile? {
        if (metadata == null || metadata.isTrailer || !metadata.hasVideo) return null
        val profiles = videoImagePreferences(context).resolutionVideoProfiles
        return resolutionProfileFor(profiles, key(metadata)?.removePrefix("live:"), metadata.isLive) ?: global(context)
    }

    /** Saved width preset that applies to [metadata] (exact width or a similar one), without the global fallback. */
    fun matchedKey(context: Context, metadata: PlaybackMetadata?): String? {
        if (metadata == null || metadata.isTrailer || !metadata.hasVideo) return null
        return com.ambient.tv.data.local.resolutionProfileKeyFor(videoImagePreferences(context).resolutionVideoProfiles,
            key(metadata)?.removePrefix("live:"), metadata.isLive)
    }

    /** Global controls remain editable from Settings after an explicit global save. */
    fun global(context: Context): ResolutionVideoProfile? {
        val prefs = videoImagePreferences(context)
        val saved = prefs.resolutionVideoProfiles[GLOBAL_KEY] ?: return null
        return saved.copy(rendererKey = prefs.playbackBackendKey,
            mpv = prefs.mpvImageOptions, vlc = prefs.vlcImageOptions, media3 = prefs.media3ImageOptions,
            audio = prefs.audioSettings, mpvOutput = prefs.mpvVideoOutputModeKey,
            mpvDecoder = prefs.mpvVideoDecoderKey, vlcOutput = prefs.vlcVideoOutputModeKey)
    }

    fun rendererFor(context: Context, width: Int?, default: String, isLive: Boolean = false): String {
        val profiles = videoImagePreferences(context).resolutionVideoProfiles
        val key = VideoResolutionClass.key(width) ?: return default
        // A live profile's "auto" inherits the Tvheadend default ([liveRendererOrDefault]).
        if (isLive) com.ambient.tv.data.local.matchingResolutionKey(profiles, key, "live:")?.let { return profiles.getValue(it).liveRendererOrDefault(default) }
        return com.ambient.tv.data.local.matchingResolutionKey(profiles, key)?.let { profiles.getValue(it).rendererOrDefault(default) } ?: default
    }

    /** Removes the preset that applies to [key]: the exact width, else the similar width that was used. */
    fun forget(context: Context, key: String) {
        val prefs = videoImagePreferences(context)
        val profiles = prefs.resolutionVideoProfiles
        val live = key.startsWith("live:")
        val actual = key.takeIf { it in profiles }
            ?: com.ambient.tv.data.local.matchingResolutionKey(profiles, key.removePrefix("live:"), if (live) "live:" else "")
            ?: key
        prefs.resolutionVideoProfiles = profiles - actual
    }

    fun save(context: Context, metadata: PlaybackMetadata?, handle: PlaybackHandle?, global: Boolean = false): String? {
        val key = if (global) GLOBAL_KEY else key(metadata, handle) ?: return null
        val prefs = videoImagePreferences(context)
        val title = TitleVideoSettingsStore.load(context, metadata)
        // The player's automatic HDR "direct" is not saved as the profile's output.
        val output = BackgroundPlaybackSession.chosenVideoSettings(metadata,
            PlayerVideoSettings.from(prefs).withResolution(load(context, metadata)).withTitle(title))
        val profile = when (handle) {
            is MpvPlaybackController -> ResolutionVideoProfile(
                rendererKey = "mpv", mpv = handle.imageOptions,
                mpvOutput = output.mpvOutput, mpvDecoder = output.mpvDecoder,
                mpvDeinterlaceMode = handle.deinterlaceModeKey,
                mpvDeinterlaceFilter = handle.deinterlaceFilterKey,
            )
            is VlcPlaybackController -> ResolutionVideoProfile(
                rendererKey = "vlc", vlc = handle.imageOptions, vlcOutput = output.vlcOutput,
                vlcDeinterlaceMode = handle.deinterlaceMode,
                vlcDeinterlaceFilter = handle.deinterlaceFilter,
            )
            is Media3PlaybackHandle -> ResolutionVideoProfile(
                rendererKey = "media3", media3 = Media3ImageProcessing.get(context, handle.player).options,
            )
            else -> return null
        }
        val audio = playbackProfileAudio(handle)?.displaySettings
        val captured = profile.copy(audio = audio)
        val saved = prefs.resolutionVideoProfiles[key]?.withSnapshot(captured) ?: captured
        prefs.resolutionVideoProfiles = prefs.resolutionVideoProfiles + (key to saved)
        if (global) {
            // Tvheadend keeps its own default player (Settings › Tvheadend; live profiles may override it).
            prefs.playbackBackendKey = profile.rendererKey
            profile.mpv?.let { prefs.mpvImageOptions = it }
            profile.vlc?.let { prefs.vlcImageOptions = it }
            profile.media3?.let { prefs.media3ImageOptions = it }
            audio?.let { prefs.audioSettings = it }
            output.save(prefs, profile.rendererKey)
        }
        // The saved audio/video choices now come from the width profile instead of title overrides.
        if (!global) TitleVideoSettingsStore.clearProfileOverrides(context, metadata)
        return key
    }
}

internal fun PlayerVideoSettings.withResolution(profile: ResolutionVideoProfile?): PlayerVideoSettings =
    if (profile == null) this else copy(
        mpvOutput = profile.mpvOutput ?: mpvOutput,
        mpvDecoder = profile.mpvDecoder ?: mpvDecoder,
        vlcOutput = profile.vlcOutput ?: vlcOutput,
    )

/**
 * Global SVP only makes SVP available. A saved preset keeps its own SVP mode and applies it
 * while the switch is on; with the switch off the filter stays out despite the preset.
 */
internal fun MpvImageOptions.withGlobalSvp(preferences: AppPreferences): MpvImageOptions =
    if (preferences.svpAvailable) this else copy(svpMode = "off")

internal fun VlcImageOptions.withGlobalSvp(preferences: AppPreferences): VlcImageOptions =
    if (preferences.svpAvailable) this else copy(svpMode = "off")

/** Reset only the global scope; width, individual-video profiles and the Tvheadend defaults stay intact. */
internal fun resetGlobalPlaybackProfile(preferences: AppPreferences) {
    preferences.resolutionVideoProfiles = preferences.resolutionVideoProfiles - ResolutionVideoProfileStore.GLOBAL_KEY
    preferences.playbackBackendKey = "media3"
    preferences.mpvImageOptions = MpvImageOptions()
    preferences.mpvImageExplicitChoice = false
    preferences.vlcImageOptions = VlcImageOptions()
    preferences.media3ImageOptions = Media3ImageOptions()
    preferences.audioSettings = AudioSettings()
    preferences.mpvVideoOutputModeKey = "direct"
    preferences.mpvVideoDecoderKey = "auto"
    preferences.vlcVideoOutputModeKey = "auto"
    preferences.mpvScalingFilterKey = MpvImageOptions().scaler
    preferences.mpvDebandEnabled = MpvImageOptions().deband != "off"
    preferences.vlcScalingFilterKey = VlcImageOptions().scaler
    // Tvheadend defaults (player and deinterlacing, Settings › Tvheadend) are not part of the global profile:
    // the global save does not write them, so the reset leaves them alone too.
}

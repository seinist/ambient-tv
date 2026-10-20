@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

/**
 * Remembers picture/filter choices per title (source + id) and restores them when the same title
 * plays again. Captured during playback and flushed before replacement/stop, including "reduce"
 * after an overload. Live channels are scoped by server and channel; trailers are not remembered.
 */
internal object TitleVideoSettingsStore {
    private var sessionRendererSelection = false

    /** Loading/resetting a profile must not recreate a removed per-video renderer override. */
    fun withSessionRendererSelection(action: () -> Unit) {
        val previous = sessionRendererSelection
        sessionRendererSelection = true
        try { action() } finally { sessionRendererSelection = previous }
    }

    fun key(metadata: PlaybackMetadata?): String? {
        metadata ?: return null
        if (metadata.isTrailer || metadata.source == "shared" || metadata.mediaId.isBlank()) return null
        if (metadata.isLive) return channelKey(metadata.liveTvSourceId, metadata.mediaId)
        return "${metadata.source}:${metadata.mediaId}"
    }

    /** Length-delimited source identity avoids collisions between server/channel ID pairs. */
    fun channelKey(sourceId: String?, channelId: String): String? = sourceId?.takeIf { it.isNotBlank() }
        ?.takeIf { channelId.isNotBlank() }?.let { "live-channel:${it.length}:$it:$channelId" }

    fun load(context: Context, metadata: PlaybackMetadata?): TitleVideoSettings? =
        key(metadata)?.let { videoImagePreferences(context).titleVideoSettings[it] }

    fun rendererFor(context: Context, source: String, mediaId: String, default: String): String =
        videoImagePreferences(context).titleVideoSettings["$source:$mediaId"]?.rendererOrDefault(default) ?: default

    fun rememberRenderer(context: Context, metadata: PlaybackMetadata?, renderer: String?) {
        if (sessionRendererSelection) return
        val key = key(metadata) ?: return
        val preferences = videoImagePreferences(context)
        val all = preferences.titleVideoSettings
        val current = all[key] ?: TitleVideoSettings()
        val next = current.copy(rendererKey = renderer?.takeIf { it in setOf("auto", "media3", "vlc", "mpv") },
            savedAt = System.currentTimeMillis())
        preferences.titleVideoSettings = TitleVideoSettings.put(all, key, next)
    }

    /** Panel action: drop everything remembered for this title. */
    fun forget(context: Context, metadata: PlaybackMetadata?) {
        val key = key(metadata) ?: return
        val preferences = videoImagePreferences(context)
        val all = preferences.titleVideoSettings
        if (key in all) preferences.titleVideoSettings = all - key
    }

    fun clearProfileOverrides(context: Context, metadata: PlaybackMetadata?) {
        val key = key(metadata) ?: return
        val preferences = videoImagePreferences(context)
        val all = preferences.titleVideoSettings
        val title = all[key] ?: return
        val updated = title.copy(snapshot = false, audio = null, audioDelayMs = null, rendererKey = null, mpv = null, vlc = null, media3 = null,
            mpvOutput = null, mpvDecoder = null, vlcOutput = null,
            mpvDeinterlaceMode = null, mpvDeinterlaceFilter = null,
            vlcDeinterlaceMode = null, vlcDeinterlaceFilter = null,
            mpvOverloadContinueSignature = null, vlcOverloadContinueSignature = null)
        preferences.titleVideoSettings = TitleVideoSettings.put(all, key, updated)
    }

    /**
     * Persists "keep watching anyway" for [metadata] immediately so a later play of the
     * same title skips the overload dialog for that filter signature.
     */
    fun rememberOverloadContinue(context: Context, metadata: PlaybackMetadata?, backend: String, signature: String?) {
        val key = key(metadata) ?: return
        val preferences = videoImagePreferences(context)
        val all = preferences.titleVideoSettings
        val current = all[key] ?: TitleVideoSettings()
        val next = when (backend) {
            "mpv" -> current.copy(mpvOverloadContinueSignature = signature?.takeIf { it.isNotBlank() })
            "vlc" -> current.copy(vlcOverloadContinueSignature = signature?.takeIf { it.isNotBlank() })
            else -> return
        }
        val updated = TitleVideoSettings.put(all, key, next.copy(savedAt = System.currentTimeMillis()))
        if (updated != all) preferences.titleVideoSettings = updated
    }

    /** Marks [session] as belonging to a rememberable title and applies [delayMs] (null = profile delay). */
    fun attachAudio(session: AudioSession?, metadata: PlaybackMetadata?, delayMs: Int?) {
        session ?: return
        session.titleScoped = key(metadata) != null
        if (session.titleScoped && delayMs != null) session.restoreTitleDelay(delayMs)
    }

    fun capture(context: Context, metadata: PlaybackMetadata?, handle: PlaybackHandle?, output: PlayerVideoSettings?) {
        val key = key(metadata) ?: return
        if (handle == null) return
        val preferences = videoImagePreferences(context)
        val all = preferences.titleVideoSettings
        var next = all[key] ?: TitleVideoSettings()
        val profile = ResolutionVideoProfileStore.load(context, metadata)
        val full = next.snapshot // Keep old explicit snapshots compatible; new edits are automatic overrides.
        when (handle) {
            is MpvPlaybackController -> {
                next = next.copy(
                    mpv = handle.imageOptions.takeIf { full || it != (profile?.mpv ?: preferences.mpvImageOptions) },
                    mpvDeinterlaceMode = handle.deinterlaceModeKey.takeIf { full || it != (profile?.mpvDeinterlaceMode ?: if (metadata?.isLive == true) com.ambient.tv.ui.player.backend.tvheadendMpvDeinterlaceMode(preferences.tvheadendDeinterlaceModeKey) else "off") },
                    mpvDeinterlaceFilter = handle.deinterlaceFilterKey.takeIf { full || it != (profile?.mpvDeinterlaceFilter ?: if (metadata?.isLive == true) preferences.tvheadendMpvDeinterlaceFilterKey else "auto") },
                    mpvOverloadContinueSignature = handle.overloadContinueSignature,
                )
            }
            is VlcPlaybackController -> {
                next = next.copy(
                    vlc = handle.imageOptions.takeIf { full || it != (profile?.vlc ?: preferences.vlcImageOptions) },
                    vlcDeinterlaceMode = handle.deinterlaceMode.takeIf { full || it != (profile?.vlcDeinterlaceMode ?: if (metadata?.isLive == true) preferences.tvheadendDeinterlaceModeKey else "off") },
                    vlcDeinterlaceFilter = handle.deinterlaceFilter.takeIf { full || it != (profile?.vlcDeinterlaceFilter ?: if (metadata?.isLive == true) preferences.tvheadendDeinterlaceFilterKey else "auto") },
                    vlcOverloadContinueSignature = handle.overloadContinueSignature,
                )
            }
            is Media3PlaybackHandle -> next = next.copy(
                media3 = Media3ImageProcessing.get(context, handle.player).options.takeIf { full || it != (profile?.media3 ?: preferences.media3ImageOptions) },
            )
            else -> Unit
        }
        val audio = when (handle) {
            is MpvPlaybackController -> handle.audioSession
            is VlcPlaybackController -> handle.audioSession
            is Media3PlaybackHandle -> Media3AudioProcessing.session(handle.player)
            else -> null
        }
        if (full) next = next.copy(audio = audio?.displaySettings)
        else if (audio != null) next = next.copy(audio = audio.settings.takeIf {
            it != (profile?.audio ?: preferences.audioSettings).normalized().copy(performanceHint = false)
        })
        if (audio != null && audio.titleScoped) {
            next = next.copy(audioDelayMs = audio.titleDelayMs?.takeIf { it != audio.settings.currentForRoute(audio.route.profile).delayMs })
        }
        if (handle is SubtitleDelayTarget) next = next.copy(subtitleDelayMs = handle.subtitleDelayMs.takeIf { it != 0 })
        // Output/decoder only when changed in this session; otherwise the stored choice stays.
        // The player's automatic HDR "direct" is no choice: the stored output stays as it was.
        output?.let { chosen ->
            val defaults = PlayerVideoSettings.from(preferences).withResolution(profile)
            next = next.copy(
                mpvOutput = titleMpvOutputToStore(next.mpvOutput, chosen, defaults.mpvOutput, full),
                mpvDecoder = chosen.mpvDecoder.takeIf { full || it != defaults.mpvDecoder },
                vlcOutput = chosen.vlcOutput.takeIf { full || it != defaults.vlcOutput },
            )
        }
        // Polling unchanged settings must not rewrite storage or change title eviction order.
        if (next.copy(savedAt = 0L) == (all[key] ?: TitleVideoSettings()).copy(savedAt = 0L)) return
        val updated = TitleVideoSettings.put(all, key, next.copy(savedAt = System.currentTimeMillis()))
        if (updated != all) preferences.titleVideoSettings = updated
    }
}

/** Output/decoder chosen for this title over the global defaults. */
internal fun PlayerVideoSettings.withTitle(title: TitleVideoSettings?): PlayerVideoSettings = if (title == null) this else copy(
    mpvOutput = title.mpvOutput ?: mpvOutput,
    mpvDecoder = title.mpvDecoder ?: mpvDecoder,
    vlcOutput = title.vlcOutput ?: vlcOutput,
)

/**
 * Output stored for a title after a session: the session's choice when it deviates from
 * [defaultOutput] (or always for an old full [snapshot]). The player's automatic HDR "direct"
 * ([PlayerVideoSettings.automaticHdrDirect]) is no choice: [stored] stays as it was.
 */
internal fun titleMpvOutputToStore(stored: String?, chosen: PlayerVideoSettings, defaultOutput: String, snapshot: Boolean): String? =
    if (chosen.automaticHdrDirect) stored else chosen.mpvOutput.takeIf { snapshot || it != defaultOutput }

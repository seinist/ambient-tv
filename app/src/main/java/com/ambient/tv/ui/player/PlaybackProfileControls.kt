@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.data.local.VideoResolutionClass
import com.ambient.tv.notifications.*
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.SettingsSectionHeader
import com.ambient.tv.ui.common.playerPanelFocusKey
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
    // A renderer switch or restart drops the handle (and the resolution) for a moment: keep the
    // last ones so the buttons stay in place (and keep focus), marked as switching.
    val liveHandle = BackgroundPlaybackSession.activeHandle()?.takeIf { playbackProfileRenderer(it) != null }
    val titleKey = TitleVideoSettingsStore.key(metadata)
    val lastValues = remember(titleKey) { arrayOfNulls<Any>(2) }
    val handleState = stickyDuringRestart(liveHandle, lastValues[0] as? PlaybackHandle)
    if (liveHandle != null) lastValues[0] = liveHandle
    val handle = handleState.value
    val switching = handleState.pending
    var revision by remember { mutableIntStateOf(0) }
    // Store changes made while the panel is open (polling capture, other panels) refresh the layers.
    val storeRevision by TitleVideoSettingsStore.revision.collectAsState()
    ImageHint(stringResource(R.string.playback_profile_includes))
    if (handle == null) {
        ImageHint(stringResource(R.string.playback_profile_unavailable))
        return
    }
    SettingsHint(playbackProfileScopeSummary())
    val liveResolutionKey = if (switching) null else ResolutionVideoProfileStore.key(metadata, handle)
    // The new engine reports the video size shortly after it starts; if it never does, stop waiting.
    var resolutionWaitOver by remember(titleKey, liveHandle) { mutableStateOf(false) }
    if (!switching && liveResolutionKey == null && lastValues[1] != null) {
        LaunchedEffect(titleKey, liveHandle) {
            kotlinx.coroutines.delay(PROFILE_RESOLUTION_WAIT_MS)
            resolutionWaitOver = true
        }
    }
    val resolutionState = stickyDuringRestart(liveResolutionKey, lastValues[1] as? String, expired = resolutionWaitOver)
    resolutionState.value?.let { lastValues[1] = it }
    val resolutionKey = resolutionState.value
    val pending = switching || resolutionState.pending
    val switchingLabel = stringResource(R.string.player_nerd_switching)
    @Composable
    fun ProfileButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, fillWidth: Boolean = false,
        selected: Boolean = false, focusId: String = text) {
        TvActionButton(text = rendererTransitionValue(text, !pending, switchingLabel), onClick = onClick,
            modifier = modifier.playerPanelFocusKey("action:$focusId"), enabled = enabled, selected = selected, fillWidth = fillWidth, loading = pending)
    }
    // The saved preset that applies: this width, a similar one, or for live TV the general width preset.
    val appliedResolutionKey = remember(resolutionKey, revision, storeRevision) {
        resolutionKey?.let { com.ambient.tv.data.local.resolutionProfileKeyFor(
            videoImagePreferences(context).resolutionVideoProfiles, it.removePrefix("live:"), metadata?.isLive == true) }
    }
    val resolution = appliedResolutionKey?.let { videoImagePreferences(context).resolutionVideoProfiles[it] }
    fun perform(message: Int? = null, action: () -> Unit) {
        // The old engine is gone while switching; the buttons ignore clicks until the new one runs.
        if (pending) return
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
    val episode = metadata?.videoScope?.episode == true
    val movie = metadata?.videoScope != null && !episode
    val live = metadata?.isLive == true
    // Destructive actions need a second press on the same button.
    var confirm by remember(titleKey) { mutableStateOf<String?>(null) }
    @Composable
    fun ConfirmButton(id: String, text: String, modifier: Modifier = Modifier, enabled: Boolean = true, action: () -> Unit) {
        val confirming = confirm == id
        ProfileButton(text = if (confirming) stringResource(R.string.saved_profiles_delete_confirm) else text, enabled = enabled,
            onClick = { if (confirming) { confirm = null; action() } else confirm = id }, modifier = modifier, focusId = id)
    }
    fun reapply() = apply(TitleVideoSettingsStore.load(context, metadata), ResolutionVideoProfileStore.load(context, metadata))

    // The cascade, narrow to broad: video, season, series, collection, width, global. Per setting the
    // first layer that holds a value wins; each row names what it currently supplies.
    val origins = videoSettingOrigins(scopes, resolution)
    val originParts = listOf(
        origins.renderer to stringResource(R.string.settings_playback_overview_renderer),
        origins.picture to stringResource(R.string.saved_profiles_part_picture),
        origins.deinterlace to stringResource(R.string.saved_profiles_part_deinterlace),
        origins.output to stringResource(R.string.saved_profiles_part_output),
        origins.audio to stringResource(R.string.saved_profiles_part_audio),
        origins.delay to stringResource(R.string.saved_profiles_part_audio_delay),
    )
    val suppliesFormat = stringResource(R.string.playback_profile_supplies)
    fun supplies(origin: VideoSettingOrigin): String? = originParts.filter { it.first == origin }
        .takeIf { it.isNotEmpty() }?.joinToString(", ") { it.second }?.let { suppliesFormat.format(it) }
    ProfileSection(stringResource(R.string.playback_profile_section_cascade))
    ImageHint(stringResource(R.string.playback_profile_cascade_hint))

    if (titleKey != null) {
        val target = remember(titleKey, revision, storeRevision) { TitleVideoSettingsStore.rememberLayer(context, metadata) }
        val selectable = !live && scopes.size > 1
        val switchedBroad = stringResource(R.string.playback_scope_switched_broad)
        val switchedTitle = stringResource(R.string.playback_scope_switched_title)
        scopes.forEach { (layer, value) ->
            val name = if (live && layer.scope == VideoSettingsScope.TITLE) stringResource(R.string.playback_scope_channel)
                else videoScopeLabel(layer, episode, movie)
            val stored = value?.takeUnless { it.isEmpty }
            val summary = stored?.let { com.ambient.tv.ui.settings.entrySummary(com.ambient.tv.ui.settings.settingsEntry(
                com.ambient.tv.ui.settings.SavedProfileCategory.TITLE, com.ambient.tv.ui.settings.SavedProfileStore.TITLE,
                layer.key, null, name, it)) }?.ifBlank { null } ?: stringResource(R.string.playback_profile_not_saved)
            val active = (target?.key ?: titleKey) == layer.key
            ProfileRow(name, summary, supplies(layer.scope.origin)) {
                if (selectable) ProfileButton(
                    text = stringResource(if (active) R.string.playback_scope_remembering else R.string.playback_scope_remember_here),
                    selected = active, focusId = "remember:${layer.key}",
                    onClick = {
                        if (active) return@ProfileButton
                        TitleVideoSettingsStore.setRememberLayer(context, metadata, layer)
                        revision++
                        profileNotice(R.string.playback_scope_switched, args = listOf(name,
                            if (layer.scope == VideoSettingsScope.TITLE) switchedTitle else switchedBroad))
                    })
                ConfirmButton("scope:${layer.key}", stringResource(R.string.playback_profile_reset),
                    enabled = stored != null) {
                    perform {
                        if (layer.scope == VideoSettingsScope.TITLE) TitleVideoSettingsStore.forget(context, metadata)
                        else TitleVideoSettingsStore.forgetScope(context, layer)
                        // Broader scopes still apply: the title must not re-save the width profile over them.
                        reapply()
                    }
                }
            }
        }
    }

    // Shared width preset: explicit save, load, delete.
    val label = resolutionKey?.let { VideoResolutionClass.label(it.removePrefix("live:")) }
    ProfileRow(
        title = when {
            label == null -> stringResource(R.string.playback_profile_section_width_unknown)
            live -> stringResource(R.string.playback_profile_section_live_width, label)
            else -> stringResource(R.string.playback_profile_section_width, label)
        },
        summary = when {
            label == null -> stringResource(R.string.playback_profile_resolution_unavailable)
            else -> resolution?.let { profileSummary(resolutionKey, it) } ?: stringResource(R.string.playback_profile_not_saved)
        },
        supplies = supplies(VideoSettingOrigin.RESOLUTION),
    ) {
        ProfileButton(text = stringResource(R.string.playback_profile_save), enabled = label != null, onClick = {
            perform(R.string.playback_profile_saved_resolution) {
                check(ResolutionVideoProfileStore.save(context, metadata, handle) != null)
            }
        }, focusId = "width:save")
        ProfileButton(text = stringResource(R.string.playback_profile_load), enabled = resolution != null, onClick = {
            perform(R.string.playback_profile_loaded) {
                TitleVideoSettingsStore.clearProfileOverrides(context, metadata)
                apply(null, resolution)
            }
        }, focusId = "width:load")
        // Live TV only resets its own preset; an inherited general width preset is managed in Settings.
        ConfirmButton("width", stringResource(R.string.playback_profile_reset),
            enabled = appliedResolutionKey != null && (!live || appliedResolutionKey.startsWith("live:"))) {
            perform {
                appliedResolutionKey?.let { ResolutionVideoProfileStore.forget(context, it) }
                reapply()
            }
        }
    }

    // Global fallback (the playback settings); edited in Settings, here only replaced by the running setup.
    ProfileRow(stringResource(R.string.saved_profiles_default_title),
        ResolutionVideoProfileStore.global(context)?.let { profileSummary("all", it) } ?: stringResource(R.string.playback_profile_global_plain),
        supplies(VideoSettingOrigin.DEFAULT)) {
        ProfileButton(text = stringResource(R.string.playback_profile_save_all), onClick = {
            perform(R.string.playback_profile_saved_all) {
                check(ResolutionVideoProfileStore.save(context, metadata, handle, global = true) != null)
            }
        }, focusId = "global:save")
    }
}

private val VideoSettingsScope.origin: VideoSettingOrigin get() = when (this) {
    VideoSettingsScope.TITLE -> VideoSettingOrigin.TITLE
    VideoSettingsScope.SEASON -> VideoSettingOrigin.SEASON
    VideoSettingsScope.SERIES -> VideoSettingOrigin.SERIES
    VideoSettingsScope.COLLECTION -> VideoSettingOrigin.COLLECTION
}

@Composable
private fun ProfileSection(title: String) {
    SettingsSectionHeader(title)
}

@Composable
private fun ProfileRow(title: String, summary: String, supplies: String?, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        ImageHint(summary)
        supplies?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        ProfileActions(actions)
    }
}

@Composable
private fun ProfileActions(content: @Composable RowScope.() -> Unit) {
    SettingsActionRow(content = content)
}

/** The persistence target is independent from the panel currently open and changes live with it. */
@Composable
internal fun playbackProfileScopeSummary(): String {
    val context = LocalContext.current
    val metadata = LocalPlaybackMetadata.current
    val revision by TitleVideoSettingsStore.revision.collectAsState()
    val layer = remember(context, metadata, revision) { TitleVideoSettingsStore.rememberLayer(context, metadata) }
        ?: return stringResource(R.string.settings_playback_scope_current)
    val episode = metadata?.videoScope?.episode == true
    val name = if (metadata?.isLive == true && layer.scope == VideoSettingsScope.TITLE) {
        stringResource(R.string.playback_scope_channel)
    } else {
        videoScopeLabel(layer, episode, metadata?.videoScope != null && !episode)
    }
    return stringResource(R.string.settings_playback_scope_remember, name)
}

@Composable
private fun profileSummary(key: String, profile: ResolutionVideoProfile): String =
    com.ambient.tv.ui.settings.entrySummary(com.ambient.tv.ui.settings.savedProfileEntries(
        if (key == "all") emptyMap() else mapOf(key.removePrefix("live:") to profile), emptyMap(), emptyMap(),
        global = profile.takeIf { key == "all" }).firstOrNull() ?: return "")

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

private const val PROFILE_RESOLUTION_WAIT_MS = 3_000L

/** A value shown while the player restarts: [value] and whether it is the previous engine's ([pending]). */
internal data class StickyValue<T>(val value: T?, val pending: Boolean)

/**
 * The running engine's [current] value, or during a restart (current null) the [last] one,
 * marked pending so the UI keeps its controls but shows "switching". [expired] ends the wait.
 */
internal fun <T> stickyDuringRestart(current: T?, last: T?, expired: Boolean = false): StickyValue<T> = when {
    current != null -> StickyValue(current, pending = false)
    last != null && !expired -> StickyValue(last, pending = true)
    else -> StickyValue(null, pending = false)
}

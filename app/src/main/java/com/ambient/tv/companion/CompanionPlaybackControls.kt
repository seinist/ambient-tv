@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.companion

import androidx.compose.runtime.*
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import com.ambient.tv.ui.player.*
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

/** Main-thread, route-owned callbacks. Disposing an old screen cannot remove a newer lease. */
internal object CompanionPlaybackControls {
    private data class Lease(val owner: Any, val route: String, val controls: SourceControls)
    private val leases = mutableListOf<Lease>()
    data class SourceControls(
        val quality: List<CompanionOption> = emptyList(), val chooseQuality: ((String) -> Unit)? = null,
        val audio: List<CompanionOption>? = null, val chooseAudio: ((String) -> Unit)? = null,
        val next: (() -> Unit)? = null, val previous: (() -> Unit)? = null,
        val seek: ((Long) -> Unit)? = null,
        /** Complete preparation before unpausing when a seek needs a new compatibility stream. */
        val resumeSeek: ((Long, () -> Boolean, (Boolean) -> Unit) -> Unit)? = null,
        /** Only compatibility streams can seek by reopening a server URL without a native index. */
        val resumeSeekCanReload: Boolean = false,
    )
    fun update(owner: Any, route: String, controls: SourceControls) {
        leases.removeAll { it.owner === owner }; leases.add(Lease(owner, route, controls))
    }
    fun remove(owner: Any) { leases.removeAll { it.owner === owner } }
    fun current(): SourceControls = leases.lastOrNull { it.route == BackgroundPlaybackSession.snapshot.value.metadata?.route }?.controls ?: SourceControls()

    fun options(): List<CompanionOptionGroup> {
        val source = current()
        val handle = BackgroundPlaybackSession.activeHandle() ?: return emptyList()
        val audio = source.audio ?: rendererTracks(handle, C.TRACK_TYPE_AUDIO)
        val subtitles = rendererTracks(handle, C.TRACK_TYPE_TEXT) + externalSubtitles(handle)
            .map { (key, track) -> CompanionOption(key, listOfNotNull(track.languageCode ?: track.language, track.title).joinToString(" · ").ifBlank { key.takeLast(6) }.take(120)) }
        val quality = source.quality.ifEmpty { rendererTracks(handle, C.TRACK_TYPE_VIDEO).takeIf { it.size > 1 }.orEmpty() }
        return buildList {
            if (audio.isNotEmpty()) add(CompanionOptionGroup("audio", audio))
            if (subtitles.isNotEmpty()) add(CompanionOptionGroup("subtitle", listOf(CompanionOption("off", "", subtitles.none { it.selected })) + subtitles))
            if (quality.size > 1) add(CompanionOptionGroup("quality", quality))
        }
    }
    fun select(group: String, key: String) {
        val option = options().firstOrNull { it.kind == group }?.options?.firstOrNull { it.key == key }
        requireNotNull(option) // Re-read actual tracks; stale indices never select a replacement track.
        val source = current()
        if (group == "quality" && source.quality.isNotEmpty()) { requireNotNull(source.chooseQuality).invoke(key); return }
        if (group == "audio" && source.audio != null) { requireNotNull(source.chooseAudio).invoke(key); return }
        val handle = requireNotNull(BackgroundPlaybackSession.activeHandle())
        if (group == "subtitle" && key.startsWith("external:")) {
            val track = requireNotNull(externalSubtitles(handle).firstOrNull { it.first == key }?.second)
            BackgroundPlaybackSession.manualSubtitleSelection()
            when (handle) {
                is MpvPlaybackController -> handle.selectExternalSubtitle(track)
                is VlcPlaybackController -> handle.selectExternalSubtitle(track)
                else -> error("No external subtitle adapter")
            }
            return
        }
        val type = when (group) { "audio" -> C.TRACK_TYPE_AUDIO; "subtitle" -> C.TRACK_TYPE_TEXT; "quality" -> C.TRACK_TYPE_VIDEO; else -> error("Unsupported group") }
        if (type == C.TRACK_TYPE_TEXT) BackgroundPlaybackSession.manualSubtitleSelection()
        when (handle) {
            is Media3PlaybackHandle -> {
                val parameters = handle.player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, key == "off")
                if (key !in setOf("off", "auto")) {
                    val parts = key.split(':'); val g = parts[0].toInt(); val t = parts[1].toInt()
                    parameters.setOverrideForType(TrackSelectionOverride(handle.player.currentTracks.groups[g].mediaTrackGroup, t))
                }
                handle.player.trackSelectionParameters = parameters.build()
            }
            is MpvPlaybackController -> when (type) {
                C.TRACK_TYPE_AUDIO -> handle.selectAudio(key.toIntOrNull())
                C.TRACK_TYPE_TEXT -> handle.selectSubtitle(key.toIntOrNull())
                C.TRACK_TYPE_VIDEO -> handle.selectVideo(key.toIntOrNull())
            }
            is VlcPlaybackController -> when (type) {
                C.TRACK_TYPE_AUDIO -> handle.selectAudioTrack(key.toInt())
                C.TRACK_TYPE_TEXT -> handle.selectSubtitleTrack(key.toIntOrNull())
                C.TRACK_TYPE_VIDEO -> handle.selectVideoTrack(key.toInt())
            }
        }
        BackgroundPlaybackSession.registerUserInteraction()
        BackgroundPlaybackSession.tick()
    }
}

@Composable
internal fun CompanionSourceControls(route: String, controls: CompanionPlaybackControls.SourceControls) {
    val owner = remember { Any() }
    SideEffect { CompanionPlaybackControls.update(owner, route, controls) }
    DisposableEffect(owner) { onDispose { CompanionPlaybackControls.remove(owner) } }
}

private fun rendererTracks(handle: PlaybackHandle, type: Int): List<CompanionOption> = when (handle) {
    is Media3PlaybackHandle -> {
        val parameters = handle.player.trackSelectionParameters
        val overridden = parameters.overrides.values.any { it.type == type }
        val tracks = handle.player.currentTracks.groups.flatMapIndexed { gi, group ->
            if (group.type != type) emptyList() else (0 until group.length).mapNotNull { ti ->
                if (!group.isTrackSupported(ti)) null else group.getTrackFormat(ti).let { f ->
                    val signature = fingerprint(listOf(f.id, f.language, f.label, f.codecs, f.width, f.height, f.bitrate).joinToString("|").toByteArray()).take(12)
                    val label = if (type == C.TRACK_TYPE_VIDEO) listOfNotNull(f.height.takeIf { it > 0 }?.let { "${it}p" }, f.codecs, f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kb/s" }).joinToString(" · ")
                        else listOfNotNull(f.language, f.label, f.codecs, f.channelCount.takeIf { it > 0 }?.let { "${it}ch" }).joinToString(" · ")
                    CompanionOption("$gi:$ti:$signature", label.ifBlank { "${ti + 1}" }.take(120), group.isTrackSelected(ti) && type !in parameters.disabledTrackTypes && (type != C.TRACK_TYPE_VIDEO || overridden))
                }
            }
        }.take(24)
        if (type == C.TRACK_TYPE_VIDEO && tracks.size > 1) listOf(CompanionOption("auto", "", !overridden)) + tracks else tracks
    }
    is MpvPlaybackController -> (when (type) { C.TRACK_TYPE_AUDIO -> handle.audioTracks(); C.TRACK_TYPE_TEXT -> handle.subtitleTracks(); else -> handle.videoTracks() })
        .take(24).map { CompanionOption(it.id.toString(), it.label.take(120), it.selected) }
    is VlcPlaybackController -> {
        val selected = when (type) { C.TRACK_TYPE_AUDIO -> handle.currentAudioTrackId(); C.TRACK_TYPE_TEXT -> handle.currentSubtitleTrackId(); else -> handle.currentVideoTrackId() }
        (when (type) { C.TRACK_TYPE_AUDIO -> handle.audioTracks(); C.TRACK_TYPE_TEXT -> handle.subtitleTracks(); else -> handle.videoTracks() })
            .filter { it.first >= 0 }.take(24).map { CompanionOption(it.first.toString(), it.second.take(120), it.first == selected) }
    }
    else -> emptyList()
}

private fun externalSubtitles(handle: PlaybackHandle): List<Pair<String, com.ambient.tv.domain.model.MediaTrack>> {
    if (handle !is MpvPlaybackController && handle !is VlcPlaybackController) return emptyList()
    val metadata = BackgroundPlaybackSession.snapshot.value.metadata ?: return emptyList()
    if (!metadata.externalSubtitlesAllowed) return emptyList()
    return metadata.subtitleTracks.filter { it.externalUrl != null }.take(24).map { track ->
        "external:${fingerprint(requireNotNull(track.externalUrl).toByteArray()).take(16)}" to track
    }
}

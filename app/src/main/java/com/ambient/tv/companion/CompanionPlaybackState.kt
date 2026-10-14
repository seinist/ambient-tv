package com.ambient.tv.companion

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.spotify.SpotifyPlaybackState
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import kotlinx.serialization.encodeToString

internal data class PlaybackBrowseSources(
    val library: Map<String, String> = emptyMap(),
    val files: Map<String, String> = emptyMap(),
    val dlna: Map<String, String> = emptyMap(),
    val live: Map<String, String> = emptyMap(),
)

internal fun samePlaybackMedia(a: CompanionMedia?, b: CompanionMedia?): Boolean =
    a != null && b != null && a.kind == b.kind && a.id == b.id && a.source == b.source && a.trailer == b.trailer

internal val CompanionFrame.playbackActive: Boolean get() = active ?: title.isNotBlank()
internal val CompanionFrame.phase: String get() = playbackState.takeIf {
    it in setOf("playing", "paused", "buffering", "loading", "error", "idle", "ended")
} ?: if (!playbackActive) "idle" else if (playing) "playing" else "paused"
internal val CompanionFrame.playIntent: Boolean get() = playing || playWhenReady == true ||
    playWhenReady == null && phase in setOf("loading", "buffering")
internal val CompanionFrame.seekable: Boolean get() = playbackActive && !isLive && canSeek != false && duration > 0 && phase !in setOf("loading", "error", "ended")

internal fun CompanionState.currentPlayback(): CompanionFrame? = remote?.takeIf {
    online && target != null && (it.receiverId.isEmpty() || it.receiverId == target.fingerprint) && paired.any { peer -> peer.fingerprint == target.fingerprint } && it.playbackActive && it.phase !in setOf("idle", "ended")
}

/** The same permitted primary action is used by the dialog, mini player and notification. */
internal val CompanionFrame.primaryPlaybackAction: String? get() {
    if ("retry" in capabilities) return "retry"
    val preferred = if (playIntent) "pause" else "play"
    if (capabilities.isEmpty() || preferred in capabilities) return preferred
    return (if (preferred == "play") "pause" else "play").takeIf { it in capabilities }
}

internal fun companionCommandIdentity(receiver: String, frame: CompanionFrame?): String =
    "ambienttv://companion-command/$receiver/${frame?.playbackSessionId ?: 0}/${fingerprint(companionJson.encodeToString(frame?.media).toByteArray()).take(16)}"

/** Leave room for nonce, sequence and control/transfer acknowledgements in the 16 KiB frame. */
internal fun boundedPlaybackFrame(frame: CompanionFrame): CompanionFrame {
    fun fits(value: CompanionFrame) = companionJson.encodeToString(value).toByteArray(Charsets.UTF_8).size <= 14_000
    if (fits(frame)) return frame
    val withoutArtwork = frame.copy(artwork = "")
    if (fits(withoutArtwork)) return withoutArtwork
    // Preserve exact identities or omit them: truncation could mark/control another file.
    var bounded = withoutArtwork
    // Keep the first page entries whole and report total separately; never truncate a media ref.
    while (bounded.queue.isNotEmpty() && !fits(bounded)) bounded = bounded.copy(queue = bounded.queue.dropLast(1))
    if (fits(bounded)) return bounded
    bounded = bounded.copy(options = emptyList(), capabilities = bounded.capabilities - "select")
    if (fits(bounded)) return bounded
    return bounded.copy(media = null, parentIds = emptyList())
}

/** Shared absolute content timeline; renderer-relative transcode offsets stay on the TV. */
internal fun localPlaybackFrame(snapshot: BackgroundPlaybackSession.Snapshot): CompanionFrame {
    val metadata = snapshot.metadata
    val active = snapshot.active && !snapshot.status.ended
    val phase = when {
        !active -> "idle"
        snapshot.status.error != null -> "error"
        snapshot.replacingSource || metadata == null -> "loading"
        snapshot.status.playing -> "playing"
        snapshot.wantsToPlay -> "buffering"
        else -> "paused"
    }
    val offset = metadata?.timelineOffsetMs ?: 0L
    val duration = if (metadata?.isLive == true) 0L else metadata?.durationMs?.takeIf { it > 0 }
        ?: (snapshot.status.durationMs + offset).coerceAtLeast(0)
    return CompanionFrame(type = "state", active = active, playbackState = phase, playbackSessionId = snapshot.generation,
        media = metadata?.companionMedia?.takeIf { it.id.length <= 4096 },
        parentIds = metadata?.parentMediaIds.orEmpty().take(8).map { it.take(128) },
        title = metadata?.title.orEmpty().take(200), subtitle = metadata?.subtitle.orEmpty().take(200),
        position = (snapshot.status.positionMs + offset).coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it },
        duration = duration, playing = snapshot.playing, playWhenReady = snapshot.wantsToPlay, isLive = metadata?.isLive == true,
        canSeek = active && metadata != null && !metadata.isLive && duration > 0 && phase != "error")
}

internal fun useSpotifyPlayback(snapshot: BackgroundPlaybackSession.Snapshot, spotify: SpotifyPlaybackState): Boolean =
    spotify.current != null && (spotify.playing || (!snapshot.active || snapshot.status.ended))

internal fun spotifyPlaybackFrame(spotify: SpotifyPlaybackState) = CompanionFrame(type = "state",
    active = spotify.current != null, playbackState = when { spotify.current == null -> "idle"; spotify.error != null -> "error"; spotify.playing -> "playing"; else -> "paused" },
    media = spotify.current?.let { CompanionMedia("spotify:track", it.id) },
    parentIds = (listOfNotNull(spotify.current?.album?.id) + spotify.current?.artists.orEmpty().map { it.id }).take(8),
    title = spotify.current?.name.orEmpty().take(200), subtitle = spotify.current?.artists.orEmpty().joinToString(", ") { it.name }.take(200),
    position = spotify.positionMs.coerceIn(0, spotify.durationMs.coerceAtLeast(0)), duration = spotify.durationMs.coerceAtLeast(0),
    playing = spotify.playing, playWhenReady = spotify.playing, canSeek = spotify.current != null && spotify.durationMs > 0 && spotify.error == null)

/** New controls target the state they displayed; a late seek must not touch the next item. */
internal fun matchesPlaybackCommand(command: CompanionFrame, current: CompanionFrame): Boolean {
    if (command.media != null && !samePlaybackMedia(command.media, current.media)) return false
    if (command.playbackSessionId != 0L && command.playbackSessionId != current.playbackSessionId) return false
    return current.playbackActive
}

internal data class PlaybackIndicator(
    val media: CompanionMedia,
    val parentIds: List<String>,
    val phase: String,
    val device: String,
    val remote: Boolean,
)

internal fun PlaybackIndicator.matches(item: MediaItem, sources: PlaybackBrowseSources): Boolean = item.allRatingKeys.any { key ->
    val prefix = when { key.startsWith("j:") -> "j:"; key.startsWith("e:") -> "e:"; else -> "" }
    when (media.kind) {
        "library" -> media.source.isNotEmpty() && sources.library[prefix] == media.source && !media.trailer &&
            (media.id == key || item.type in setOf("show", "season", "album", "artist") && key in parentIds)
        "device-local" -> !remote && media.id == key
        "file" -> runCatching {
            val (source, path) = com.ambient.tv.data.files.FileKeys.parse(key)
            sources.files[source] == media.source && com.ambient.tv.data.files.FilePaths.relative(path) == media.id
        }.getOrDefault(false)
        "dlna" -> runCatching {
            val (server, id) = com.ambient.tv.data.dlna.DlnaKeys.parse(key)
            sources.dlna[server] == media.source && id == media.id
        }.getOrDefault(false)
        else -> false
    }
}

internal fun validSpotifyMedia(media: CompanionMedia): Boolean =
    media.kind in setOf("spotify:track", "spotify:album", "spotify:playlist", "spotify:artist") &&
        media.id.matches(Regex("[A-Za-z0-9]{22}")) && media.source.isEmpty() &&
        (media.offsetId.isEmpty() || media.offsetId.matches(Regex("[A-Za-z0-9]{22}")))

internal fun decodeCompanionArtwork(value: String): android.graphics.Bitmap? = runCatching {
    if (value.isBlank() || value.length > 4096) return@runCatching null
    val bytes = java.util.Base64.getDecoder().decode(value)
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..128 || bounds.outHeight !in 1..128) return@runCatching null
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

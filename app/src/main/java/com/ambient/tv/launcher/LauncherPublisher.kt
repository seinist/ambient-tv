package com.ambient.tv.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** One title as the launcher shows it. */
data class LauncherTitle(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val artworkUrl: String? = null,
    val artworkHeaders: Map<String, String> = emptyMap(),
    val episode: Boolean = false,
    /** 16:9 still instead of a 2:3 poster. */
    val landscapeArtwork: Boolean = episode,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    /** Set for a live TV channel entry (opens the channel instead of a title). */
    val liveChannel: LiveChannelRef? = null,
    /** When it was last watched (server data); null = now. Orders the Watch Next row. */
    val engagedAtMs: Long? = null,
)

data class LiveChannelRef(val uuid: String, val sourceId: String)

/** A home row's title list as one launcher channel; [name] null uses the channel's own name. */
data class LauncherChannelContent(val name: String?, val titles: List<LauncherTitle>)

/** Uses the row's display setting: poster rows stay 2:3, landscape/episode rows go 16:9. */
internal fun MediaItem.toLauncherTitle(style: com.ambient.tv.ui.common.MediaTileStyle): LauncherTitle {
    val resolved = com.ambient.tv.ui.common.resolveMediaTileStyle(style, type, handheld = false)
    val landscape = resolved != com.ambient.tv.ui.common.MediaTileStyle.POSTER &&
        resolved != com.ambient.tv.ui.common.MediaTileStyle.AUTO
    val artwork = com.ambient.tv.ui.common.mediaTileImageUrl(this, resolved)
    return LauncherTitle(
        key = ratingKey,
        title = if (type == "episode") grandparentTitle?.takeIf { it.isNotBlank() } ?: title else title,
        subtitle = if (type == "episode") title else null,
        description = summary,
        artworkUrl = artwork ?: thumbUrl ?: artUrl,
        episode = type == "episode",
        landscapeArtwork = landscape,
        durationMs = durationMs ?: 0,
        positionMs = viewOffsetMs ?: 0,
        engagedAtMs = lastViewedAt?.takeIf { it > 0 }?.times(1000),
    )
}

/**
 * Publishes to the Android TV home screen like the system launcher expects: the "Continue
 * watching" row (Watch Next) and two preview channels (continue watching, recently added).
 * Television only, Android 8+, and only while the user keeps it enabled. Everything runs off the
 * main thread and failures only log: the launcher is a convenience, never a dependency.
 */
// tvprovider exposes PreviewProgram/WatchNextProgram builders through restricted base classes.
// These calls use only their public inherited column accessors; no internal provider operations.
@SuppressLint("RestrictedApi")
@Singleton
class LauncherPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var channelJob: Job? = null
    /** Content last written per channel key (guarded by [lock]); in memory, so a restart republishes once. */
    private val publishedFingerprints = mutableMapOf<String, Int>()
    /** Watch Next keys changed by local playback, with when (guarded by [lock]). */
    private val localChanges = mutableMapOf<String, Long>()

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private val active: Boolean get() = supported && preferences.launcherIntegrationEnabled
    /** False on phones and whenever the launcher feature is disabled. */
    val channelPublishingEnabled: Boolean get() = active

    /**
     * Playback of a server title stopped (or was marked watched). In progress → Watch Next entry
     * at that position; finished or barely started → removed.
     */
    fun onPlaybackStopped(title: LauncherTitle, finished: Boolean) {
        if (!active) return
        scope.launch {
            lock.withLock {
                localChanges[title.key] = android.os.SystemClock.elapsedRealtime()
                runCatching { syncWatchNext(title, finished) }.onFailure { Timber.w(it, "Watch Next update failed") }
            }
        }
    }

    /**
     * The server's own continue-watching list for [system], fetched successfully: Watch Next
     * mirrors it, so titles finished or started on another device follow. Only entries of that
     * system are touched; a disabled row or an unreachable server never calls this.
     */
    fun syncWatchNextFromServer(system: com.ambient.tv.ui.common.MediaSystem, titles: List<LauncherTitle>) {
        if (!active) return
        scope.launch {
            lock.withLock {
                runCatching {
                    // A title just stopped here wins over a server list fetched before its report landed.
                    val now = android.os.SystemClock.elapsedRealtime()
                    localChanges.values.removeAll { now - it > LOCAL_CHANGE_WINDOW_MS }
                    val wanted = titles.filter { it.durationMs > 0 && it.positionMs.toDouble() / it.durationMs in WATCH_NEXT_RANGE }
                        .distinctBy { it.key }.take(MAX_PROGRAMS).associateBy { it.key }
                    val existing = watchNextPrograms()
                    existing.filter { program ->
                        val key = program.internalProviderId.orEmpty()
                        key !in wanted && key !in localChanges && com.ambient.tv.connectivity.PlaybackServerUnreachable.mediaSystemForRatingKey(key) == system
                    }.forEach { deleteWatchNext(it.id) }
                    wanted.values.forEach { title ->
                        if (title.key in localChanges) return@forEach
                        val current = existing.firstOrNull { it.internalProviderId == title.key }
                        // Unchanged progress keeps its place in the row instead of jumping to the front.
                        if (current != null && current.lastPlaybackPositionMillis.toLong() == title.positionMs.coerceAtMost(Int.MAX_VALUE.toLong())) return@forEach
                        syncWatchNext(title, finished = false)
                    }
                }.onFailure { Timber.w(it, "Watch Next server sync failed") }
            }
        }
    }

    /** Compatibility method for already prepared channel content. */
    fun scheduleChannels(continueWatching: LauncherChannelContent, recentlyAdded: LauncherChannelContent, liveTv: LauncherChannelContent?) =
        scheduleChannelsWhenIdle { Triple(continueWatching, recentlyAdded, liveTv) }

    /** Prepare channel titles off-main only after Home has settled, then publish on IO. */
    fun scheduleChannelsWhenIdle(
        prepare: () -> Triple<LauncherChannelContent, LauncherChannelContent, LauncherChannelContent?>,
    ) {
        if (!active) return
        channelJob?.cancel()
        channelJob = scope.launch {
            delay(CHANNEL_SETTLE_MS)
            val (continueWatching, recentlyAdded, liveTv) = prepare()
            currentCoroutineContext().ensureActive()
            lock.withLock {
                runCatching {
                    val posters = mutableSetOf<Uri>()
                    publishChannel(CHANNEL_CONTINUE, continueWatching.name ?: context.getString(R.string.home_continue_watching),
                        continueWatching.titles, play = true, posters)
                    publishChannel(CHANNEL_RECENT, recentlyAdded.name ?: context.getString(R.string.launcher_channel_recent),
                        recentlyAdded.titles, play = false, posters)
                    // Null: the live preview is still loading or its server is asleep; keep the last row.
                    if (liveTv != null) publishChannel(CHANNEL_LIVE, liveTv.name ?: context.getString(R.string.launcher_channel_live),
                        liveTv.titles, play = true, posters)
                    else keepChannelPosters(CHANNEL_LIVE, posters)
                    posters += watchNextPosters()
                    LauncherArtwork.prune(context, posters)
                }.onFailure { Timber.w(it, "Launcher channel update failed") }
            }
        }
    }

    /** Switched off in Settings: everything this app put on the home screen goes away. */
    fun clearAll() {
        if (!supported) return
        channelJob?.cancel()
        scope.launch {
            lock.withLock {
                runCatching {
                    val helper = PreviewChannelHelper(context)
                    helper.allChannels.forEach { helper.deletePreviewChannel(it.id) }
                    publishedFingerprints.clear()
                    watchNextPrograms().forEach { deleteWatchNext(it.id) }
                    LauncherArtwork.prune(context, emptySet())
                }.onFailure { Timber.w(it, "Launcher cleanup failed") }
            }
        }
    }

    private fun syncWatchNext(title: LauncherTitle, finished: Boolean) {
        val existing = watchNextPrograms().filter { it.internalProviderId == title.key }
        val fraction = if (title.durationMs > 0) title.positionMs.toDouble() / title.durationMs else 0.0
        if (finished || fraction !in WATCH_NEXT_RANGE) {
            existing.forEach { deleteWatchNext(it.id) }
            return
        }
        val poster = LauncherArtwork.fetch(context, title.artworkUrl, title.artworkHeaders)
        val builder = existing.firstOrNull()?.let { WatchNextProgram.Builder(it) } ?: WatchNextProgram.Builder()
        builder
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(title.engagedAtMs ?: System.currentTimeMillis())
            .setType(if (title.episode) TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE else TvContractCompat.PreviewProgramColumns.TYPE_MOVIE)
            .setTitle(title.title)
            .setEpisodeTitle(title.subtitle)
            .setDescription(title.description)
            .setDurationMillis(title.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setLastPlaybackPositionMillis(title.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setInternalProviderId(title.key)
            .setIntentUri(LauncherIntents.openTitleUri(context, title.key, play = true))
            .setPosterArtAspectRatio(aspect(title))
        poster?.let(builder::setPosterArtUri)
        val program = builder.build()
        val helper = PreviewChannelHelper(context)
        val current = existing.firstOrNull()
        if (current != null) {
            helper.updateWatchNextProgram(program, current.id)
            existing.drop(1).forEach { deleteWatchNext(it.id) }
        } else {
            helper.publishWatchNextProgram(program)
        }
    }

    private fun publishChannel(key: String, name: String, titles: List<LauncherTitle>, play: Boolean, posters: MutableSet<Uri>) {
        val helper = PreviewChannelHelper(context)
        val channels = helper.allChannels
        val existing = channels.firstOrNull { it.internalProviderId == key }
        val items = titles.distinctBy { it.key }.take(MAX_PROGRAMS)
        if (items.isEmpty()) {
            // Keep the channel itself: deleting it would drop the user's "customize channels"
            // choice, and a re-created channel starts hidden again.
            existing?.let { channel -> previewPrograms(channel.id).forEach { helper.deletePreviewProgram(it.id) } }
            publishedFingerprints.remove(key)
            return
        }
        // A renamed row renames the channel.
        if (existing != null && existing.displayName?.toString() != name) {
            helper.updatePreviewChannel(existing.id, PreviewChannel.Builder(existing).setDisplayName(name).build())
        }
        val channelId = existing?.id ?: helper.publishChannel(
            PreviewChannel.Builder()
                .setDisplayName(name)
                .setAppLinkIntentUri(LauncherIntents.appUri(context))
                .setInternalProviderId(key)
                .setLogo(logo())
                .build(),
        ).also { id ->
            // Only the app's first channel may appear without asking; the others the user adds
            // through the launcher's "customize channels". Whichever row has content first gets it.
            if (channels.none { it.isBrowsable }) runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
        }
        // Home refreshes often with the same rows; rewriting them would make the launcher redraw.
        val fingerprint = listOf(name, play, items).hashCode()
        if (existing != null && publishedFingerprints[key] == fingerprint) {
            previewPrograms(channelId).mapNotNullTo(posters) { it.posterArtUri }
            return
        }
        // Replace the programs wholesale: simpler than diffing and these rows are short.
        previewPrograms(channelId).forEach { helper.deletePreviewProgram(it.id) }
        var postersComplete = true
        items.forEachIndexed { index, title ->
            val poster = LauncherArtwork.fetch(context, title.artworkUrl, title.artworkHeaders)?.also(posters::add)
            // Retried on the next refresh; a recently failed URL waits for its retry window instead.
            if (poster == null && title.artworkUrl != null && !LauncherArtwork.lastSkipped) postersComplete = false
            val live = title.liveChannel
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setWeight(MAX_PROGRAMS - index)
                .setType(when {
                    live != null -> TvContractCompat.PreviewProgramColumns.TYPE_CHANNEL
                    title.episode -> TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE
                    else -> TvContractCompat.PreviewProgramColumns.TYPE_MOVIE
                })
                .setLive(live != null)
                .setTitle(title.title)
                .setEpisodeTitle(title.subtitle)
                .setDescription(title.description)
                .setDurationMillis(title.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setLastPlaybackPositionMillis(title.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setInternalProviderId(title.key)
                .setIntentUri(live?.let { LauncherIntents.openChannelUri(context, it.uuid, it.sourceId) }
                    ?: LauncherIntents.openTitleUri(context, title.key, play))
                .setPosterArtAspectRatio(aspect(title))
            poster?.let(builder::setPosterArtUri)
            helper.publishPreviewProgram(builder.build())
        }
        // A missing poster (server busy) is retried on the next refresh.
        if (postersComplete) publishedFingerprints[key] = fingerprint else publishedFingerprints.remove(key)
    }

    /** A channel left as it is still needs its posters kept from pruning. */
    private fun keepChannelPosters(key: String, posters: MutableSet<Uri>) {
        val channel = PreviewChannelHelper(context).allChannels.firstOrNull { it.internalProviderId == key } ?: return
        previewPrograms(channel.id).mapNotNullTo(posters) { it.posterArtUri }
    }

    private fun aspect(title: LauncherTitle) =
        if (title.landscapeArtwork) TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9 else TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3

    private fun logo(): Bitmap =
        requireNotNull(ContextCompat.getDrawable(context, R.mipmap.brand_launcher)).toBitmap(LOGO_PX, LOGO_PX)

    /** The provider only returns this app's own rows. */
    @SuppressLint("RestrictedApi")
    private fun watchNextPrograms(): List<WatchNextProgram> =
        context.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) add(WatchNextProgram.fromCursor(cursor)) } }
            .orEmpty()

    @SuppressLint("RestrictedApi")
    private fun previewPrograms(channelId: Long): List<PreviewProgram> =
        context.contentResolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION, null, null, null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) add(PreviewProgram.fromCursor(cursor)) } }
            .orEmpty()

    private fun watchNextPosters(): Set<Uri> = watchNextPrograms().mapNotNull { it.posterArtUri }.toSet()

    private fun deleteWatchNext(id: Long) {
        context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
    }

    private companion object {
        const val CHANNEL_CONTINUE = "continue"
        const val CHANNEL_RECENT = "recent"
        const val CHANNEL_LIVE = "live"
        const val MAX_PROGRAMS = 20
        const val CHANNEL_SETTLE_MS = 5_000L
        const val LOGO_PX = 320
        /** Same window as the app's own "in progress": not just started, not practically done. */
        val WATCH_NEXT_RANGE = 0.01..0.94
        const val LOCAL_CHANGE_WINDOW_MS = 60_000L
    }
}

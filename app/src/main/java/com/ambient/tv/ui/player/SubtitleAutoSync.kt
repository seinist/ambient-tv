package com.ambient.tv.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.R
import com.ambient.tv.data.subtitles.SubtitleSync
import com.ambient.tv.data.subtitles.findSubtitleSync
import com.ambient.tv.data.subtitles.parseCueSpans
import com.ambient.tv.data.subtitles.retimeSubtitle
import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lays a loaded subtitle file (from OpenSubtitles, say) onto a subtitle the server has for the
 * same title: their cue timelines are matched (shift and frame rate), the file is rewritten with
 * the found timing and loaded again. Works the same in every player.
 */
internal object SubtitleAutoSync {
    var running by mutableStateOf(false)
        private set

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    }

    /** The most recently loaded local subtitle file of the playback. */
    fun target(metadata: PlaybackMetadata?): MediaTrack? =
        metadata?.subtitleTracks?.lastOrNull { it.externalUrl?.startsWith("file:") == true && isTextSubtitle(it.codec) }

    /** Text subtitles the server serves for the title, full ones before forced ones. */
    fun references(metadata: PlaybackMetadata?, target: MediaTrack?): List<MediaTrack> =
        metadata?.subtitleTracks.orEmpty()
            .filter { it.externalUrl != target?.externalUrl && it.externalUrl?.startsWith("http") == true && isTextSubtitle(it.codec) }
            .sortedBy { it.forced }

    suspend fun run(): Boolean {
        if (running) return false
        val metadata = BackgroundPlaybackSession.snapshot.value.metadata
        val target = target(metadata) ?: return false
        val references = references(metadata, target).take(MAX_REFERENCES)
        if (metadata == null || references.isEmpty()) return false
        running = true
        try {
            val result = withContext(Dispatchers.IO) { align(target, references) }
            when {
                result == null -> notice(R.string.subtitle_sync_failed, NoticePhase.ERROR)
                result.second.scale == 1.0 && kotlin.math.abs(result.second.offsetMs) < IN_SYNC_MS -> notice(R.string.subtitle_sync_already, NoticePhase.SUCCESS)
                else -> {
                    val (file, sync) = result
                    val track = target.copy(
                        externalUrl = android.net.Uri.fromFile(file).toString(),
                        title = target.title?.substringBefore(SYNCED_SUFFIX).orEmpty() + SYNCED_SUFFIX,
                        selected = true,
                    )
                    loadExternalSubtitle(metadata, track, replacesUrl = target.externalUrl)
                    notice(R.string.subtitle_sync_done, NoticePhase.SUCCESS, listOf(formatShift(sync.offsetMs)))
                }
            }
            return result != null
        } catch (_: Exception) {
            notice(R.string.subtitle_sync_failed, NoticePhase.ERROR)
            return false
        } finally {
            running = false
        }
    }

    private fun align(target: MediaTrack, references: List<MediaTrack>): Pair<File, SubtitleSync>? {
        val source = File(requireNotNull(android.net.Uri.parse(target.externalUrl).path))
        val text = source.readText()
        val spans = parseCueSpans(text)
        val sync = references.mapNotNull { reference ->
            val body = runCatching { download(reference.externalUrl!!) }.getOrNull() ?: return@mapNotNull null
            findSubtitleSync(parseCueSpans(body), spans)
        }.maxByOrNull { it.score } ?: return null
        val out = File(source.parentFile, source.nameWithoutExtension.removeSuffix(".sync") + ".sync." + source.extension.ifBlank { "srt" })
        out.writeText(retimeSubtitle(text, sync))
        return out to sync
    }

    private fun download(url: String): String? =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body.string()
        }

    private fun notice(message: Int, phase: NoticePhase, args: List<String> = emptyList()) = AppNotifications.post(AppNotice(
        "subtitle-sync", message, phase = phase, kind = NoticeKind.PLAYBACK, args = args, interruptPlayback = false,
    ))

    private const val MAX_REFERENCES = 3
    private const val IN_SYNC_MS = 80L
    private const val SYNCED_SUFFIX = " · sync"
}

/** Text formats whose cue times can be read; picture subtitles (PGS, VobSub, DVB) cannot. */
internal fun isTextSubtitle(codec: String?): Boolean {
    val c = codec?.lowercase()?.trim() ?: return true
    return c !in setOf("pgs", "pgssub", "hdmv_pgs_subtitle", "dvd_subtitle", "dvdsub", "vobsub", "dvb_subtitle", "dvbsub", "xsub")
}

/** `+1.25 s` / `−0.40 s`. */
internal fun formatShift(offsetMs: Long): String =
    (if (offsetMs < 0) "−" else "+") + String.format(java.util.Locale.ROOT, "%.2f s", kotlin.math.abs(offsetMs) / 1000.0)

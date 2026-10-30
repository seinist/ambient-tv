package com.ambient.tv.livetv.teletext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Teletext and HbbTV signalling of the live channel that is playing, decoded in the app for every
 * player engine.
 *
 * With local Timeshift the decoder reads along the bytes the player is served (no extra traffic,
 * subtitles follow a paused or rewound picture). Otherwise it opens its own connection to the
 * channel: briefly at the start to read PMT and AIT (what the channel offers), then only while the
 * viewer or teletext subtitles need pages. TVHeadend serves that from the same tuner. A stream
 * without a PMT (not MPEG-TS) is given up after [PROBE_TIMEOUT_MS].
 */
object LiveTeletext {
    data class Status(
        val running: Boolean = false,
        /** PMT read (or given up): [hasTeletext] and [hbbtv] are final for this channel. */
        val probed: Boolean = false,
        /** The PMT names a teletext stream. */
        val hasTeletext: Boolean = false,
        /** Probed: this channel carries no teletext. */
        val noTeletext: Boolean = false,
        /** The HbbTV application the channel announces (autostart first), null without HbbTV. */
        val hbbtv: HbbtvApplication? = null,
        val announced: List<TeletextAnnouncedPage> = emptyList(),
        val subtitlePagesSeen: List<Int> = emptyList(),
        /** Bumped when pages arrive (throttled); the viewer re-reads its page. */
        val revision: Long = 0,
        /** Page the subtitle choice resolves to, null when off or not found yet. */
        val subtitlePage: Int? = null,
    )

    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus.asStateFlow()
    private val mutableSubtitles = MutableStateFlow<List<TeletextSubtitleLine>>(emptyList())
    val subtitles: StateFlow<List<TeletextSubtitleLine>> = mutableSubtitles.asStateFlow()
    /** Pages are needed (viewer, subtitles, page list): keep reading after the probe. */
    private val needed = MutableStateFlow(false)

    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .also { com.ambient.tv.network.CredentialRedirectGuard.install(it) }.build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var decoder: TeletextDecoder? = null
    /** Provider/server metadata used only when the live stream yields no launchable AIT app. */
    @Volatile private var hbbtvFallback: HbbtvApplication? = null
    private var job: Job? = null
    @Volatile private var call: okhttp3.Call? = null
    private var sourceUrl: String? = null

    /** "off", "auto" or a decimal page ("150"). */
    @Volatile private var subtitleChoice = "off"
    @Volatile private var preferredLanguages: Set<String> = emptySet()
    @Volatile private var lastRevisionAt = 0L

    // Timeshift tap: only the newest served connection is the player's.
    private val tapCounter = AtomicLong()
    @Volatile private var tapLatest = 0L
    @Volatile private var tapWanted = false
    private val tapLock = Any()
    private var tapFed = 0L

    /** Called by the timeshift server for each player connection; returns its stream id. */
    fun openTapStream(): Long = tapCounter.incrementAndGet().also { tapLatest = it }

    fun tap(stream: Long, data: ByteArray, length: Int) {
        if (!tapWanted || stream != tapLatest) return
        val target = decoder ?: return
        // Runs on the timeshift server thread: a decoder failure must not end the player's stream.
        runCatching {
            synchronized(tapLock) {
                if (tapFed != stream) { target.discontinuity(); tapFed = stream }
                target.feed(data, 0, length)
            }
        }.onFailure { error ->
            Timber.w(error, "Teletext decoding failed; teletext stops for this channel")
            tapWanted = false
        }
    }

    /**
     * Probes the channel at [url] (the player's URL; a local Timeshift URL is read along) and keeps
     * its pages while [setNeeded]. Same URL: keeps the running session.
     */
    @Synchronized
    fun start(url: String, headers: Map<String, String>) {
        if (url == sourceUrl && decoder != null) return
        stop()
        sourceUrl = url
        val created = TeletextDecoder(onPageCommitted = ::pageCommitted, onServiceChanged = ::serviceChanged)
        decoder = created
        mutableStatus.value = Status(running = true)
        resolveSubtitlePage()
        if (com.ambient.tv.livetv.isLocalTimeshiftUrl(url)) {
            tapWanted = true
            job = scope.launch { probeTimeout(created) }
        } else {
            job = scope.launch { readHttp(created, url, headers) }
        }
    }

    @Synchronized
    fun stop() {
        tapWanted = false
        job?.cancel()
        job = null
        call?.cancel()
        call = null
        decoder = null
        sourceUrl = null
        hbbtvFallback = null
        mutableStatus.value = Status()
        mutableSubtitles.value = emptyList()
    }

    fun setNeeded(value: Boolean) { needed.value = value }

    /**
     * Supplies provider/server-side HbbTV discovery for the current channel. Stream AIT data wins
     * whenever it is available; this only makes HbbTV usable when a profile filtered the AIT.
     */
    fun setHbbtvFallback(value: HbbtvApplication?) {
        hbbtvFallback = value
        val streamApp = decoder?.hbbtvApps?.firstOrNull()
        mutableStatus.update { it.copy(hbbtv = streamApp ?: value) }
    }

    fun setSubtitleChoice(choice: String, languages: Set<String>) {
        subtitleChoice = choice
        preferredLanguages = languages.map { it.lowercase() }.toSet()
        mutableSubtitles.value = emptyList()
        resolveSubtitlePage()
    }

    fun page(number: Int, subcode: Int? = null): TeletextPage? = decoder?.page(number, subcode)
    fun subcodes(number: Int): List<Int> = decoder?.subcodes(number).orEmpty()
    fun pageNumbers(): List<Int> = decoder?.pageNumbers().orEmpty()
    fun lastHeader(): TeletextPage? = decoder?.lastHeader

    private suspend fun readHttp(target: TeletextDecoder, url: String, headers: Map<String, String>) {
        var backoff = 1_000L
        val started = System.currentTimeMillis()
        while (decoder === target) {
            // Probe done and nothing needs pages: no connection until something does.
            if (target.probeComplete() && !needed.value) {
                needed.first { it }
                if (decoder !== target) return
            }
            val failure = runCatching {
                val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                val current = client.newCall(request).also { call = it }
                current.execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    target.discontinuity()
                    val input = response.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (decoder === target) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        target.feed(buffer, 0, n)
                        backoff = 1_000L
                        if (target.probeComplete() && !needed.value) break
                        if (System.currentTimeMillis() - started > PROBE_TIMEOUT_MS) {
                            if (!target.pmtSeen) {
                                giveUp(target)
                                return
                            }
                            if (!target.probeComplete()) target.finishAitProbe()
                        }
                    }
                }
            }.exceptionOrNull()
            if (decoder !== target) return
            if (target.probeComplete() && !needed.value) continue
            if (System.currentTimeMillis() - started > PROBE_TIMEOUT_MS) {
                if (!target.pmtSeen) { giveUp(target); return }
                if (!target.probeComplete()) target.finishAitProbe()
            }
            if (failure != null) Timber.w(failure, "Teletext stream ended; retry in %d ms", backoff)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    private suspend fun probeTimeout(target: TeletextDecoder) {
        delay(PROBE_TIMEOUT_MS)
        if (decoder !== target) return
        if (!target.pmtSeen) giveUp(target)
        else if (!target.probeComplete()) target.finishAitProbe()
    }

    /** No PMT in time: not an MPEG-TS stream (HLS, Matroska profile …), so no teletext and no HbbTV. */
    private fun giveUp(target: TeletextDecoder) {
        if (decoder !== target) return
        Timber.i("Teletext: no MPEG-TS service information on this channel")
        call?.cancel()
        tapWanted = false
        mutableStatus.update { it.copy(probed = true, noTeletext = true, hasTeletext = false, hbbtv = hbbtvFallback) }
    }

    private fun serviceChanged() {
        val target = decoder ?: return
        mutableStatus.update {
            it.copy(
                probed = target.pmtSeen,
                hasTeletext = target.teletextPid >= 0,
                noTeletext = target.pmtSeen && target.teletextPid < 0,
                hbbtv = target.hbbtvApps.firstOrNull() ?: hbbtvFallback,
                announced = target.announcedPages,
                subtitlePagesSeen = target.subtitlePagesSeen(),
            )
        }
        resolveSubtitlePage()
    }

    private fun pageCommitted(page: TeletextPage) {
        val status = mutableStatus.value
        if (page.subtitle && page.number == status.subtitlePage) mutableSubtitles.value = teletextSubtitleLines(page)
        val now = System.currentTimeMillis()
        if (now - lastRevisionAt >= REVISION_INTERVAL_MS) {
            lastRevisionAt = now
            mutableStatus.update { it.copy(revision = it.revision + 1) }
        }
    }

    private fun resolveSubtitlePage() {
        val status = mutableStatus.value
        val page = teletextSubtitlePage(subtitleChoice, status.announced, status.subtitlePagesSeen, preferredLanguages)
        if (page != status.subtitlePage) {
            mutableSubtitles.value = emptyList()
            mutableStatus.update { it.copy(subtitlePage = page) }
        }
    }

    private const val PROBE_TIMEOUT_MS = 12_000L
    private const val REVISION_INTERVAL_MS = 150L
}

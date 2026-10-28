package com.ambient.tv.livetv.teletext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Teletext of the live channel that is playing, decoded in the app for every player engine.
 *
 * With local Timeshift the decoder reads along the bytes the player is served (no extra traffic,
 * subtitles follow a paused or rewound picture). Otherwise it opens its own connection to the
 * channel, only while the viewer or teletext subtitles need it; TVHeadend serves that from the same
 * tuner. A stream without a teletext PID is given up after [NO_TELETEXT_MS].
 */
object LiveTeletext {
    data class Status(
        val running: Boolean = false,
        /** The PMT names a teletext stream. */
        val hasTeletext: Boolean = false,
        /** Searched long enough: this channel carries no teletext. */
        val noTeletext: Boolean = false,
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

    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .also { com.ambient.tv.network.CredentialRedirectGuard.install(it) }.build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var decoder: TeletextDecoder? = null
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
        synchronized(tapLock) {
            if (tapFed != stream) { target.discontinuity(); tapFed = stream }
            target.feed(data, 0, length)
        }
    }

    /**
     * Decodes the channel at [url] (the player's URL; a local Timeshift URL is read along).
     * Same URL: keeps the running session.
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
            job = scope.launch { watchForTeletext(created) }
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
        mutableStatus.value = Status()
        mutableSubtitles.value = emptyList()
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
                        if (target.teletextPid < 0 && System.currentTimeMillis() - started > NO_TELETEXT_MS) {
                            giveUp(target)
                            return
                        }
                        backoff = 1_000L
                    }
                }
            }.exceptionOrNull()
            if (decoder !== target) return
            if (failure != null) Timber.w(failure, "Teletext stream ended; retry in %d ms", backoff)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    private suspend fun watchForTeletext(target: TeletextDecoder) {
        delay(NO_TELETEXT_MS)
        if (decoder === target && target.teletextPid < 0) giveUp(target)
    }

    private fun giveUp(target: TeletextDecoder) {
        if (decoder !== target) return
        Timber.i("Teletext: no teletext stream on this channel")
        call?.cancel()
        tapWanted = false
        mutableStatus.update { it.copy(noTeletext = true) }
    }

    private fun serviceChanged() {
        val target = decoder ?: return
        mutableStatus.update {
            it.copy(hasTeletext = target.teletextPid >= 0, announced = target.announcedPages,
                subtitlePagesSeen = target.subtitlePagesSeen())
        }
        resolveSubtitlePage()
    }

    private fun pageCommitted(page: TeletextPage) {
        val status = mutableStatus.value
        if (page.subtitle && page.number == status.subtitlePage) mutableSubtitles.value = teletextSubtitleLines(page)
        val now = System.currentTimeMillis()
        if (!status.hasTeletext || now - lastRevisionAt >= REVISION_INTERVAL_MS) {
            lastRevisionAt = now
            mutableStatus.update { it.copy(hasTeletext = true, revision = it.revision + 1) }
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

    private const val NO_TELETEXT_MS = 12_000L
    private const val REVISION_INTERVAL_MS = 150L
}

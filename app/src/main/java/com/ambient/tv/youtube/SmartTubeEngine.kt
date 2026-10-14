package com.ambient.tv.youtube

import kotlinx.coroutines.sync.withLock
import android.content.Context
import android.os.Build
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.local.SmartTubeAccountBackup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import com.ambient.tv.util.withoutUrlQueries
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Reflection boundary around SmartTube MediaServiceCore.
 *  Keeping the app UI independent from upstream concrete types makes engine updates much safer.
 */
@Singleton
class SmartTubeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
) {
    @Volatile private var signInDisposable: Any? = null
    @Volatile private var initialized = false
    private val initLock = Any()
    private val playbackMetadata = YouTubePlaybackMetadataCache()
    /** SmartTube's client selector is global: format loads that may switch it run one at a time. */
    private val formatClientMutex = kotlinx.coroutines.sync.Mutex()
    private val metadataInflight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Result<YouTubeMetadata>?>>()

    init {
        // Lets VLC/MPV keep playing after googlevideo expires a link (hours of pause): the relay
        // asks for a fresh URL of the same stream and resumes at the same byte.
        YouTubeStreamRelay.renewer = { videoId, expiredUrl ->
            // Runs on a relay thread: bounded, so a stalled renewal cannot hang that thread forever.
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(RENEW_TIMEOUT_MS) { renewStreamUrl(videoId, expiredUrl) }
            }
        }
    }

    // Probe the packaged class without running static initializers or restoring an account.
    // UI state constructors query this on the main thread; services are initialized by io().
    val available: Boolean by lazy {
        runCatching {
            Class.forName("com.liskovsoft.youtubeapi.service.YouTubeServiceManager", false, javaClass.classLoader)
        }.isSuccess
    }

    /** Keep YouTube/SmartTube metadata language independent from the app UI language. */
    fun applyMetadataLanguage() {
        playbackMetadata.clear()
        val language = LanguagePreferences.baseLanguage(LanguagePreferences.resolve(appPreferences.metadataLanguageKey))
            ?: return
        globalPreferences()?.call("setPreferredLanguage", language)
        runCatching {
            Class.forName("com.liskovsoft.sharedutils.locale.LocaleUpdater")
                .getMethod("clearCache")
                .invoke(null)
        }.onFailure { Timber.d(it, "SmartTube LocaleUpdater reset") }
        runCatching {
            Class.forName("com.liskovsoft.googlecommon.common.locale.LocaleManager")
                .getMethod("unhold")
                .invoke(null)
        }.onFailure { Timber.d(it, "SmartTube LocaleManager reset") }
    }

    /** Hash only the selected local account identity; never persist account data or credentials. */
    suspend fun browseScope(): String = io {
        val account = service("getSignInService").call("getSelectedAccount")
        val identity = if (account == null) "anonymous" else "${account.call("getId")}:${account.callString("getEmail")}"
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest((identity + "|" + appPreferences.metadataLanguageKey).toByteArray())
        "youtube:" + bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun isSignedIn(): Boolean = io { service("getSignInService").callBool("isSigned") ?: false }

    suspend fun accountProfile(): YouTubeAccountProfile? = io {
        val signIn = service("getSignInService")
        if (signIn.callBool("isSigned") != true) return@io null
        val account = signIn.call("getSelectedAccount") ?: return@io null
        YouTubeAccountProfile(
            name = account.callString("getName")?.trim()?.takeIf { it.isNotEmpty() },
            avatarUrl = account.callString("getAvatarImageUrl")?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun signOut() = io {
        playbackMetadata.clear()
        runCatching { signInDisposable?.call("dispose") }
        signInDisposable = null
        val signIn = service("getSignInService")
        val accounts = ((signIn.call("getAccounts") as? Iterable<*>)?.filterNotNull()?.toList()).orEmpty()
            .ifEmpty { listOfNotNull(signIn.call("getSelectedAccount")) }
        accounts.forEach { account ->
            check(signIn.invokeCompatible("removeAccount", account).invoked) {
                "SmartTube account removal is unavailable"
            }
        }
        // The upstream service caches its Authorization header independently from account
        // storage. Reset it as well so the next request really runs signed out.
        signIn.call("invalidateCache")
        signIn.call("checkAuth")
        check(signIn.callBool("isSigned") != true) { "SmartTube account is still signed in" }
    }
    /** Backup boundary for SmartTube's own persisted accounts. The opaque spec carries the
     *  refresh token, so it only leaves the device as an explicitly requested credential. */
    fun exportAccounts(includeCredentials: Boolean): List<SmartTubeAccountBackup>? {
        if (!available) return null
        val raw = globalPreferences()?.callString("getMediaServiceAccountData") ?: return null
        return storedAccounts(raw).map { SmartTubeAccountBackup(it.id, it.name, it.spec.takeIf { includeCredentials }) }
    }

    fun validateAccounts(accounts: List<SmartTubeAccountBackup>) {
        if (!available) return
        accounts.forEach { account -> account.credential?.let { require(parseAccount(it) != null) { "Invalid SmartTube account" } } }
    }

    /** Replaces the stored account list when the backup carries credentials; entries without a credential keep the local sign-in of the same account. */
    fun importAccounts(accounts: List<SmartTubeAccountBackup>) {
        if (!available) return
        // A backup exported without credentials cannot restore any sign-in; replacing the list
        // with it would only sign the receiver out. Keep the local accounts in that case.
        if (accounts.none { it.credential != null }) return
        val prefs = checkNotNull(globalPreferences()) { "SmartTube preferences unavailable" }
        val raw = prefs.callString("getMediaServiceAccountData").orEmpty()
        val current = storedAccounts(raw).associateBy { it.id }
        val specs = accounts.mapNotNull { it.credential ?: current[it.id]?.spec }.distinct()
        val merged = if (specs.isEmpty()) "" else helpers().getMethod("mergeArray", Array<Any>::class.java)
            .invoke(null, specs.toTypedArray<Any>())?.toString().orEmpty()
        if (merged == raw) return
        check(prefs.invokeCompatible("setMediaServiceAccountData", merged).invoked) { "SmartTube account storage unavailable" }
        if (!initialized) return // The first manager() call restores the new list.
        playbackMetadata.clear()
        val signIn = service("getSignInService")
        Class.forName("com.liskovsoft.youtubeapi.service.internal.YouTubeAccountManager")
            .getMethod("instance", signIn.javaClass).invoke(null, signIn)?.call("init")
        signIn.call("invalidateCache")
    }

    private data class StoredAccount(val id: String, val name: String, val spec: String)

    private fun storedAccounts(raw: String): List<StoredAccount> {
        if (raw.isBlank()) return emptyList()
        val specs = helpers().getMethod("splitArray", String::class.java).invoke(null, raw) as? Array<*> ?: return emptyList()
        return specs.filterIsInstance<String>().mapNotNull { spec ->
            val account = parseAccount(spec) ?: return@mapNotNull null
            val email = account.callString("getEmail")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val name = account.callString("getName")?.trim().orEmpty()
            // Hashed so the backup ID never exposes the e-mail address or refresh token.
            val identity = email ?: name.takeIf { it.isNotEmpty() } ?: account.callString("getRefreshToken").orEmpty()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            StoredAccount("yt-" + digest.take(12).joinToString("") { "%02x".format(it) }, name.ifEmpty { email.orEmpty() }, spec)
        }.distinctBy { it.id }
    }

    private fun parseAccount(spec: String): Any? = runCatching {
        Class.forName("com.liskovsoft.googlecommon.service.oauth.YouTubeAccount").getMethod("from", String::class.java).invoke(null, spec)
    }.getOrNull()

    private fun helpers() = Class.forName("com.liskovsoft.sharedutils.helpers.Helpers")

    suspend fun home(onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> {
        val home = contentRows("getHomeObserve", "getHomeRowObserve", "getHome", onRows = onRows)
        if (home.isNotEmpty()) return home

        // A signed-out YouTube account can legitimately have no personalized Home response on
        // some service/backend combinations. SmartTube still supports anonymous browsing, so use
        // its public Trending feed as a resilient non-personalized Home fallback rather than
        // presenting a completely empty YouTube screen.
        return if (!isSignedIn()) {
            contentRows("getTrendingObserve", "getTrending", onRows = onRows).also { fallback ->
                if (fallback.isNotEmpty()) Timber.i("SmartTube anonymous Home fell back to Trending")
            }
        } else {
            emptyList()
        }
    }
    suspend fun subscriptions(onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> = contentRows(
        "getSubscriptionsObserve", "getSubscriptionsRowObserve", "getSubscriptions", onRows = onRows,
    )
    suspend fun history(onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> = contentRows(
        "getHistoryObserve", "getHistoryRowObserve", "getHistory", onRows = onRows,
    )
    suspend fun playlists(onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> = contentRows(
        "getPlaylistsObserve", "getPlaylistsRowObserve", "getPlaylists", onRows = onRows,
    )
    suspend fun search(query: String, onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> = contentRowsWithArgs(
        arrayOf("getSearchObserve", "getSearch"),
        query, onRows = onRows,
    )

    /** SmartTube exposes these as top-level sections. Method names have changed between
     * MediaServiceCore revisions, so probe known public variants across revisions. */
    suspend fun supportedFeedKeys(): Set<String> = io {
        if (!available) return@io emptySet()
        val content = service("getContentService")
        // Subscriptions/History/Playlists are tied to a signed-in Google account and are
        // always empty for a signed-out user - hide them from the UI entirely instead of
        // showing a permanently empty tab. Home is included here too: its personalized feed
        // requires an account, and the anonymous Trending fallback this engine falls back to
        // (see home()) is rejected by YouTube's backend for every client type - upstream marks
        // that endpoint as removed - so Home has no working content path while signed out either.
        val signedIn = service("getSignInService").callBool("isSigned") == true
        buildSet {
            if (signedIn) add("home")
            if (signedIn && content.hasAnyMethod("getSubscriptions", "getSubscriptionsObserve", "getSubscriptionsRowObserve")) add("subscriptions")
            if (signedIn && content.hasAnyMethod("getHistory", "getHistoryObserve", "getHistoryRowObserve")) add("history")
            if (signedIn && content.hasAnyMethod("getPlaylists", "getPlaylistsObserve", "getPlaylistsRowObserve")) add("playlists")
            if (content.hasAnyMethod("getShorts", "getShortsObserve")) add("shorts")
            if (content.hasAnyMethod("getMusic", "getMusicObserve")) add("music")
            if (content.hasAnyMethod("getNews", "getNewsObserve")) add("news")
            if (content.hasAnyMethod("getGaming", "getGamingObserve")) add("gaming")
            if (content.hasAnyMethod("getLive", "getLiveObserve")) add("live")
            if (content.hasAnyMethod("getKids", "getKidsObserve", "getKidsHomeObserve")) add("kids")
        }
    }

    suspend fun feed(key: String, onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> = when (key) {
        "home" -> home(onRows)
        "subscriptions" -> subscriptions(onRows)
        "history" -> history(onRows)
        "playlists" -> playlists(onRows)
        "shorts" -> section(context.getString(com.ambient.tv.R.string.nav_youtube_shorts), "getShortsObserve", "getShorts", onRows = onRows)
        "music" -> section(context.getString(com.ambient.tv.R.string.nav_youtube_music), "getMusicObserve", "getMusic", onRows = onRows)
        "news" -> section(context.getString(com.ambient.tv.R.string.nav_youtube_news), "getNewsObserve", "getNews", onRows = onRows)
        "gaming" -> section(context.getString(com.ambient.tv.R.string.nav_youtube_gaming), "getGamingObserve", "getGaming", onRows = onRows)
        "live" -> section(context.getString(com.ambient.tv.R.string.nav_youtube_live), "getLiveObserve", "getLive", onRows = onRows)
        "kids" -> section(context.getString(com.ambient.tv.R.string.nav_youtube_kids), "getKidsObserve", "getKidsHomeObserve", "getKids", onRows = onRows)
        else -> home(onRows)
    }

    private suspend fun section(label: String, vararg methodNames: String, onRows: suspend (List<YouTubeRow>) -> Unit): List<YouTubeRow> {
        val supported = io { service("getContentService").hasAnyMethod(*methodNames) }
        if (!supported) {
            throw IllegalStateException(context.getString(com.ambient.tv.R.string.youtube_feed_not_supported, label))
        }
        return contentRows(*methodNames, onRows = onRows)
    }

    suspend fun requestSignInCode(): String? {
        playbackMetadata.clear()
        val observable = io { service("getSignInService").call("signInObserve") } ?: return null
        val code = CompletableDeferred<String?>()

        // Keep the Rx subscription alive after the first user-code emission. The same upstream
        // subscription continues polling OAuth and persists the refresh token when the user
        // finishes yt.be/activate. blockingFirst() would dispose it too early.
        // The UI uses yt.be/activate for its link and QR, with the code shown separately.
        runCatching { signInDisposable?.call("dispose") }
        val onNext = rxProxy("io.reactivex.functions.Consumer") { args ->
            val value = args?.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
            if (!code.isCompleted) code.complete(value)
        }
        val onError = rxProxy("io.reactivex.functions.Consumer") { args ->
            val error = args?.firstOrNull() as? Throwable ?: IllegalStateException(context.getString(com.ambient.tv.R.string.youtube_signin_failed))
            if (!code.isCompleted) code.completeExceptionally(error) else Timber.w(error, "SmartTube sign-in")
            signInDisposable = null
        }
        val onComplete = rxProxy("io.reactivex.functions.Action") {
            if (!code.isCompleted) code.complete(null)
            signInDisposable = null
        }
        val subscription = io { observable.call("subscribe", onNext, onError, onComplete) }
            ?: throw IllegalStateException(context.getString(com.ambient.tv.R.string.youtube_signin_start_failed))
        signInDisposable = subscription
        suspend fun disposePending() = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { subscription.call("dispose") }
            if (signInDisposable === subscription) signInDisposable = null
        }
        return try {
            // Only bound the first-code request. The subscription stays alive while the user
            // enters that code on another device, preserving upstream OAuth polling.
            withTimeoutOrNull(30_000) { code.await() }.also { if (it == null) disposePending() }
        } catch (cancelled: CancellationException) {
            disposePending()
            throw cancelled
        }
    }

    suspend fun metadata(videoId: String): YouTubeMetadata {
        playbackMetadata.get(videoId)?.let { return it }
        val gate = CompletableDeferred<Result<YouTubeMetadata>?>()
        val existing = metadataInflight.putIfAbsent(videoId, gate)
        // A null result means the owning caller was cancelled: that is not this waiter's
        // failure, so start (or join) a fresh request instead of inheriting the cancellation.
        if (existing != null) return existing.await()?.getOrThrow() ?: metadata(videoId)
        return try {
            val value = io {
                playbackMetadata.get(videoId)?.let { return@io it }
                val media = service("getMediaItemService")
                val generation = playbackMetadata.generation
                val started = System.nanoTime()
                val meta = media.call("getMetadata", videoId)
                    ?: throw IllegalStateException(context.getString(com.ambient.tv.R.string.youtube_metadata_failed))
                mapMetadata(meta, videoId).also {
                    playbackMetadata.put(it, generation)
                    com.ambient.tv.ui.player.logPlaybackStartup("youtube", "metadata_ready", started)
                }
            }
            gate.complete(Result.success(value))
            value
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            metadataInflight.remove(videoId, gate)
            gate.complete(null)
            throw cancelled
        } catch (error: Throwable) {
            gate.complete(Result.failure(error))
            throw error
        } finally {
            metadataInflight.remove(videoId, gate)
        }
    }

    suspend fun playback(videoId: String): YouTubePlayback {
        val started = System.nanoTime()
        val cached = playbackMetadata.get(videoId)
        return prepareYouTubePlayback(cached,
            loadMetadata = { com.ambient.tv.util.suspendResult { metadata(videoId) }.getOrNull() },
            loadStream = { resolvePlaybackStream(videoId) },
        ).also {
            com.ambient.tv.ui.player.logPlaybackStartup("youtube", "prepared", started, "cached_metadata=${cached != null}")
        }
    }

    suspend fun refreshLivePlayback(videoId: String): YouTubePlayback {
        io { service("getMediaItemService").call("invalidateCache") }
        return playback(videoId)
    }

    private suspend fun resolvePlaybackStream(videoId: String): YouTubePlayback = io {
        val started = System.nanoTime()
        val media = service("getMediaItemService")
        com.ambient.tv.ui.player.logPlaybackStartup("youtube", "service_ready", started)
        val info = loadPlayableFormatInfo(media, videoId)
        if (info.callBool("isLiveOffline") == true) {
            throw YouTubeLiveOfflineException(YouTubeLiveEvent(
                info.callString("getTitle").orEmpty(),
                youtubeScheduledStartMs(info.callString("getScheduledStartTime"), info.callString("getStartTimestamp")),
            ))
        }
        val reason = info.callString("getPlayabilityReason")
        val unplayable = info.callBool("isUnplayable") == true
        if (unplayable) throw IllegalStateException(reason ?: context.getString(com.ambient.tv.R.string.youtube_video_unplayable))

        val isLive = info.callBool("isLive") == true
        val hls = info.callString("getHlsManifestUrl")?.takeIf(String::isNotBlank)
        val formats = if (isLive) emptyList() else adaptiveFormats(info)
        val generatedDash = lazy {
            // Do not prefer an audio-only/OTF VOD manifest over a playable muxed fallback.
            if (isLive || youtubeHasPlayableDashTracks(formats)) generatedDashManifest(info, videoId) else null
        }
        val direct = muxedProgressiveUrl(info)
        val vodPolicy = youtubeVodStreamPolicy(isEmulator = isProbablyEmulator())
        val uri = selectYouTubePlaybackUri(
            isLive = isLive,
            vodPolicy = vodPolicy,
            hls = hls,
            generatedDash = { generatedDash.value },
            direct = { direct },
        )
        val mimeType = when {
            uri == null -> null
            uri.contains(".m3u8", true) || hls == uri -> "application/x-mpegURL"
            uri.contains(".mpd", true) || (generatedDash.isInitialized() && generatedDash.value == uri) -> "application/dash+xml"
            else -> null
        }
        val headers = playbackHeaders(info, videoId)
        // VLC/MPV (relayed) play the separate video and audio streams on every device.
        // "Automatic" follows the display mode; only streams the hardware decoders handle.
        val adaptivePair = if (isLive) null else youtubeAdaptivePair(
            formats,
            YouTubeDeviceVideo.maxHeight(context, appPreferences.youtubeMaxVideoHeight),
            // SDR baseline; the player upgrades to HDR where its output path shows it.
            YouTubeDeviceVideo.decodeCheck(),
        )
        dumpYouTubeStreamDebug(videoId, info, vodPolicy, uri, mimeType, direct, headers, adaptivePair)
        if (uri == null) {
            throw IllegalStateException(context.getString(com.ambient.tv.R.string.youtube_no_compatible_format))
        }
        YouTubePlayback(
            videoId = videoId,
            title = info.callString("getTitle") ?: "SmartTube",
            author = info.callString("getAuthor"),
            description = info.callString("getDescription"),
            uri = uri,
            mimeType = mimeType,
            progressiveUri = direct?.takeIf { it != uri },
            adaptiveVideoUri = adaptivePair?.first,
            adaptiveAudioUri = adaptivePair?.second,
            adaptiveFormats = formats,
            isLive = isLive,
            headers = headers,
            timeline = com.ambient.tv.domain.model.PlaybackTimeline(preview = if (isLive) null else storyboardPreview(info)),
        ).also {
            com.ambient.tv.ui.player.logPlaybackStartup("youtube", "stream_ready", started,
                "live=$isLive kind=${mimeType ?: "direct"} adaptive_pair=${adaptivePair != null} progressive_fallback=${direct != null}")
        }
    }

    /** A fresh URL for the stream [expiredUrl] belonged to (same format and audio track). */
    suspend fun renewStreamUrl(videoId: String, expiredUrl: String): String? = io {
        val media = service("getMediaItemService")
        media.call("invalidateCache")
        val info = com.ambient.tv.util.suspendResult { loadPlayableFormatInfo(media, videoId) }.getOrNull() ?: return@io null
        val candidates = adaptiveFormats(info).mapNotNull { it.url } +
            (info.call("getUrlFormats") as? Iterable<*>)?.mapNotNull { it?.callString("getUrl") }.orEmpty()
        youtubeRenewedUrl(expiredUrl, candidates).also { renewed ->
            com.ambient.tv.diagnostics.DiagnosticLog.log(
                com.ambient.tv.diagnostics.DiagnosticLevel.INFO, "youtube-relay",
                if (renewed != null) "Expired stream link renewed" else "Expired stream link could not be renewed",
            )
        }
    }

    private suspend fun loadPlayableFormatInfo(media: Any, videoId: String): Any = formatClientMutex.withLock {
        val caller = kotlin.coroutines.coroutineContext
        resolveYouTubeFormatInfo(
            load = { allowInnertube -> loadFormatInfo(media, videoId, allowInnertube) },
            switchClient = {
                val switched = manager().invokeCompatible("switchNextClientNow")
                val available = if (switched.invoked) true else {
                    val videoInfo = runCatching {
                        Class.forName("com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService")
                            .getMethod("instance").invoke(null)
                    }.getOrNull()
                    videoInfo?.invokeCompatible("switchNextFormat", true)?.invoked == true
                }
                if (available) media.call("invalidateCache")
                available
            },
            score = ::formatInfoScore,
            isTerminal = { it.callBool("isLiveOffline") == true },
            checkActive = { caller.ensureActive() },
            onAttempt = { attempt, score, started ->
                com.ambient.tv.ui.player.logPlaybackStartup("youtube", "format_response", started,
                    "attempt=$attempt score=$score")
            },
        )
    }

    private fun formatInfoScore(info: Any): Int = youtubePlayableFormatScore(
        unplayable = info.callBool("isUnplayable") == true,
        live = info.callBool("isLive") == true,
        hasHls = !info.callString("getHlsManifestUrl").isNullOrBlank(),
        formats = if (info.callBool("containsDashFormats") == true) adaptiveFormats(info) else emptyList(),
        preferLiveHls = appPreferences.playbackBackendKey != "vlc",
        hasProgressive = { muxedProgressiveUrl(info) != null },
    )

    private fun loadFormatInfo(media: Any, videoId: String, allowInnertube: Boolean = true): Any {
        val primary = media.invokeDetailed("getFormatInfo", videoId)
        if (primary.error != null) {
            android.util.Log.w("AmbientYouTube", "getFormatInfo failed for $videoId: ${primary.error.message}", primary.error)
            Timber.w(primary.error, "SmartTube getFormatInfo failed for %s", videoId)
        }
        var info = primary.value
        if (allowInnertube && info?.callBool("isLiveOffline") != true && (info == null || info.callBool("isUnplayable") == true)) {
            innertubeFormatInfo(videoId)?.let { fallback ->
                if (fallback.callBool("isUnplayable") != true) info = fallback
                else if (info == null) info = fallback
            }
        }
        return info ?: throw IllegalStateException(
            primary.error?.message?.takeIf { it.isNotBlank() }
                ?: context.getString(com.ambient.tv.R.string.youtube_stream_failed),
        )
    }

    private fun muxedProgressiveUrl(info: Any): String? {
        val urlFormats = (info.call("getUrlFormats") as? Iterable<*>)?.map { format ->
            format?.callString("getMimeType") to format?.callString("getUrl")
        }.orEmpty()
        youtubeMuxedProgressiveUrl(urlFormats, emptyList())?.let { return it }
        val extra = (info.call("createUrlList") as? Iterable<*>)
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            .orEmpty()
        return youtubeMuxedProgressiveUrl(urlFormats, extra)
    }

    private fun adaptiveFormats(info: Any): List<YouTubeAdaptiveFormat> =
        (info.call("getAdaptiveFormats") as? Iterable<*>)?.mapNotNull { format ->
            format ?: return@mapNotNull null
            YouTubeAdaptiveFormat(
                mimeType = format.callString("getMimeType"),
                url = format.callString("getUrl"),
                height = format.callInt("getHeight") ?: 0,
                width = format.callInt("getWidth") ?: 0,
                fps = format.callString("getFps")?.toDoubleOrNull()?.toInt() ?: 0,
                audioTrackId = format.callString("getAudioTrackId"),
                language = format.callString("getLanguage"),
                bitrate = format.callString("getBitrate")?.toLongOrNull() ?: 0L,
                isOtf = format.callBool("isOtf") == true,
                isDrc = format.callBool("isDrc") == true,
                xtags = format.callString("getXtags"),
            )
        }.orEmpty()

    private fun innertubeFormatInfo(videoId: String): Any? {
        val clazz = runCatching {
            Class.forName("com.liskovsoft.youtubeapi.innertube.InnertubeService")
        }.getOrNull() ?: return null
        val method = clazz.declaredMethods.firstOrNull {
            it.name.startsWith("createFormatInfo") && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        } ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(null, videoId) }
            .onFailure {
                android.util.Log.w("AmbientYouTube", "Innertube createFormatInfo failed for $videoId: ${it.message}", it)
                Timber.w(it, "Innertube createFormatInfo failed for %s", videoId)
            }
            .getOrNull()
    }

    private fun storyboardPreview(info: Any): com.ambient.tv.domain.model.TimelinePreview? = runCatching {
        val storyboard = info.call("createStoryboard") ?: return@runCatching null
        val size = storyboard.call("getGroupSize") ?: return@runCatching null
        val width = size.callInt("getWidth") ?: return@runCatching null
        val height = size.callInt("getHeight") ?: return@runCatching null
        val columns = size.callInt("getColCount") ?: return@runCatching null
        val rows = size.callInt("getRowCount") ?: return@runCatching null
        val interval = size.callInt("getDurationEachMS")?.toLong() ?: return@runCatching null
        if (interval <= 0 || columns <= 0 || rows <= 0) return@runCatching null
        // URLs are generated lazily by the upstream object; only immutable templates leave it.
        val first = storyboard.call("getGroupUrl", 0)?.toString() ?: return@runCatching null
        val second = storyboard.call("getGroupUrl", 1)?.toString() ?: return@runCatching null
        val marker = Regex("M(\\d+)\\.jpg")
        val template = if (first != second && marker.containsMatchIn(first)) first.replace(marker, "M__INDEX__.jpg") else null
        if (template == null) return@runCatching null
        com.ambient.tv.domain.model.TimelinePreview.Sprite(width, height, columns, rows, interval, Long.MAX_VALUE, urlTemplate = template)
    }.getOrNull()

    suspend fun updateHistory(videoId: String, positionMs: Long) = io {
        playbackMetadata.updatePosition(videoId, positionMs)
        val media = service("getMediaItemService")
        // Method names changed across MediaServiceCore revisions; try the public variants.
        val seconds = (positionMs / 1000L).toFloat()
        val first = media.invokeCompatible("updateHistoryPosition", videoId, seconds)
        if (!first.invoked) {
            media.invokeCompatible("updateHistoryPosition", videoId, positionMs)
        }
    }

    /** Optional enrichment runs after the player is published, and only when enabled. */
    suspend fun playbackSponsorSegments(videoId: String): List<LongRange> {
        if (!appPreferences.youtubeSponsorBlockEnabled) return emptyList()
        return io {
            if (!appPreferences.youtubeSponsorBlockEnabled) emptyList()
            else sponsorSegments(service("getMediaItemService"), videoId)
        }
    }

    private fun mapMetadata(meta: Any, fallbackId: String): YouTubeMetadata {
        val videoId = meta.callString("getVideoId")?.takeIf(String::isNotBlank) ?: fallbackId
        val imageUrl = meta.callString("getCardImageUrl")
            ?.takeIf(String::isNotBlank)
            ?: meta.callString("getBackgroundImageUrl")?.takeIf(String::isNotBlank)
            ?: youtubeThumbnailUrl(videoId)
        return YouTubeMetadata(
            videoId = videoId,
            title = meta.callString("getTitle") ?: "SmartTube",
            author = meta.callString("getAuthor"),
            description = meta.callString("getDescription"),
            imageUrl = imageUrl,
            backgroundUrl = meta.callString("getBackgroundImageUrl")?.takeIf(String::isNotBlank) ?: imageUrl,
            durationMs = meta.callLong("getDurationMs") ?: 0,
            percentWatched = meta.callInt("getPercentWatched") ?: 0,
            isLive = meta.callBool("isLive") ?: false,
            suggestions = mapGroups(meta.call("getSuggestions")),
            chapters = (meta.call("getChapters") as? Iterable<*>)?.mapNotNull { value ->
                val chapter = value ?: return@mapNotNull null
                val start = chapter.callLong("getStartTimeMs") ?: return@mapNotNull null
                com.ambient.tv.domain.model.VideoChapter(start, chapter.callString("getTitle"), chapter.callString("getCardImageUrl"))
            }.orEmpty(),
        )
    }

    private fun sponsorSegments(media: Any, videoId: String): List<LongRange> = runCatching {
        val raw = media.call("getSponsorSegments", videoId) as? Iterable<*> ?: return@runCatching emptyList()
        raw.mapNotNull { segment ->
            segment ?: return@mapNotNull null
            val start = segment.callLong("getStartMs") ?: ((segment.callDouble("getStart") ?: return@mapNotNull null) * 1000).toLong()
            val end = segment.callLong("getEndMs") ?: ((segment.callDouble("getEnd") ?: return@mapNotNull null) * 1000).toLong()
            if (end > start) start..end else null
        }
    }.getOrDefault(emptyList())

    /** Resolve a ContentService feed across MediaServiceCore revisions.
     *
     * A non-null synchronous result is not necessarily useful: getHome() has returned an empty
     * collection in cases where getHomeObserve() subsequently emits valid rows. Therefore every
     * known variant is tried until one produces mappable content. Reactive feeds publish each
     * emission immediately while retaining all later rows.
     */
    private suspend fun contentRows(vararg methodNames: String, onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> =
        contentRowsWithArgs(methodNames, onRows = onRows)

    private suspend fun contentRowsWithArgs(
        methodNames: Array<out String>,
        vararg args: Any?,
        onRows: suspend (List<YouTubeRow>) -> Unit = {},
    ): List<YouTubeRow> = withContext(Dispatchers.IO) {
        val content = service("getContentService")
        var attempted = false
        var lastFailure: Throwable? = null
        var completedEmpty = false
        for (method in methodNames) {
            val invocation = content.invokeDetailed(method, *args)
            if (!invocation.found) continue
            attempted = true
            if (invocation.error != null) {
                lastFailure = invocation.error
                Timber.w(invocation.error, "SmartTube content method %s failed", method)
                continue
            }
            val accumulated = linkedMapOf<String, YouTubeRow>()
            suspend fun publish(raw: Any?) {
                val incoming = mapGroups(raw)
                if (incoming.isEmpty()) return
                incoming.forEach { row ->
                    val previous = accumulated[row.title]
                    accumulated[row.title] = row.copy(items = (previous?.items.orEmpty() + row.items).distinctBy { it.videoId })
                }
                onRows(accumulated.values.toList())
            }
            try {
                val stream = invocation.value?.let(::reactiveContent)
                if (stream != null) {
                    var emissions = 0
                    withTimeout(30_000) { stream.take(16).collect { emissions++; publish(it) } }
                    if (emissions >= 16) throw IncompleteYouTubeFeedException()

                } else {
                    publish(unwrapReactive(invocation.value))
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                // A never-ending upstream feed must not hold the loading job open forever.
                // Preserve partial content and still honour cancellation of the caller.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                throw IncompleteYouTubeFeedException(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (accumulated.isNotEmpty()) throw IncompleteYouTubeFeedException(error)
                lastFailure = error
                continue
            }
            completedEmpty = true
            if (accumulated.isNotEmpty()) return@withContext accumulated.values.toList()
            Timber.d("SmartTube content method %s returned no mappable rows", method)
        }
        if (!attempted) {
            Timber.w("SmartTube has none of the requested content methods: %s", methodNames.joinToString())
        }
        if (!completedEmpty) throw IncompleteYouTubeFeedException(lastFailure)
        emptyList()
    }

    private suspend fun groups(block: () -> Any?): List<YouTubeRow> = io {
        val raw = unwrapReactive(block())
        val rows = mapGroups(raw)
        if (rows.isEmpty()) {
            Timber.w("SmartTube returned no mappable rows. rawType=%s", raw?.javaClass?.name ?: "null")
        }
        rows
    }

    private fun mapGroups(raw: Any?): List<YouTubeRow> {
        val value = unwrapReactive(raw)
        if (value == null) return emptyList()

        // Some MediaServiceCore revisions wrap rows in a holder object instead of returning
        // List<MediaGroup> directly. Probe the common accessors before treating it as one row.
        val nested = listOf("getMediaGroups", "getGroups", "getRows", "getContent", "getItems")
            .firstNotNullOfOrNull { name -> value.call(name)?.takeIf { it !== value } }
        if (nested != null && nested !is CharSequence) {
            val nestedRows = mapGroups(nested)
            if (nestedRows.isNotEmpty()) return nestedRows
        }

        val groups: List<Any?> = when (value) {
            is Iterable<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> listOf(value)
        }

        val rows = mutableListOf<YouTubeRow>()
        for (group in groups) {
            group ?: continue
            val unwrappedGroup = unwrapReactive(group) ?: continue
            val itemContainer = unwrappedGroup.callFirst("getMediaItems", "getItems", "getVideos", "getContent")
            val rawItems = when (val resolved = unwrapReactive(itemContainer)) {
                is Iterable<*> -> resolved.toList()
                is Array<*> -> resolved.toList()
                null -> emptyList()
                else -> listOf(resolved)
            }
            val items = rawItems.mapNotNull(::mapItem)
            if (items.isNotEmpty()) {
                rows += YouTubeRow(unwrappedGroup.callString("getTitle")?.takeIf(String::isNotBlank) ?: "SmartTube", items)
                continue
            }

            // A few feeds expose a flat collection of MediaItem objects with no MediaGroup wrapper.
            mapItem(unwrappedGroup)?.let { item ->
                rows += YouTubeRow("SmartTube", listOf(item))
            }
        }
        return rows
    }

    private fun generatedDashManifest(info: Any, videoId: String): String? = runCatching {
        if (info.callBool("containsDashFormats") != true) return@runCatching null
        val stream = info.call("createMpdStream") as? InputStream ?: return@runCatching null
        val xml = stream.bufferedReader().use { it.readText() }
        if (!youtubeGeneratedDashIsPlayable(xml)) {
            android.util.Log.w("AmbientYouTube", "generated MPD has no HTTP AdaptationSets for $videoId")
            return@runCatching null
        }
        val dir = context.cacheDir.resolve("youtube_mpd").apply { mkdirs() }
        val safeId = videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = dir.resolve("$safeId.mpd")
        file.writeText(xml)
        android.net.Uri.fromFile(file).toString()
    }.onFailure { Timber.w(it, "SmartTube generated DASH manifest failed for %s", videoId) }.getOrNull()


    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        val product = Build.PRODUCT.orEmpty().lowercase()
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }

    private fun playbackHeaders(info: Any, videoId: String): Map<String, String> = buildMap {
        val clientInfo = info.call("getClientInfo")
        val userAgent = clientInfo?.callString("getUserAgent")?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        put("User-Agent", userAgent)
        val referer = clientInfo?.call("getRefererUrl", videoId)?.toString()?.takeIf { it.isNotBlank() }
            ?: clientInfo?.callString("getReferer")?.takeIf { it.isNotBlank() }
            ?: "https://www.youtube.com/"
        put("Referer", referer)
        put("Origin", Regex("https?://[^/]+").find(referer)?.value ?: "https://www.youtube.com")
        info.callString("getVisitorCookie")?.takeIf { it.isNotBlank() }?.let { cookie ->
            put("Cookie", if ('=' in cookie) cookie else "VISITOR_INFO1_LIVE=$cookie")
        }
    }

    private fun hlsOf(info: Any): String? = info.callString("getHlsManifestUrl")?.takeIf(String::isNotBlank)

    private fun dumpYouTubeStreamDebug(
        videoId: String,
        info: Any,
        vodPolicy: YouTubeVodStreamPolicy,
        uri: String?,
        mimeType: String?,
        direct: String?,
        headers: Map<String, String>,
        adaptivePair: Pair<String, String>?,
    ) {
        // Diagnostics only: stream URLs carry the viewer's IP, expiry and signatures.
        if (!com.ambient.tv.BuildConfig.DEBUG && !com.ambient.tv.diagnostics.DiagnosticLog.enabled) return
        val client = info.call("getClientInfo")
        val urlFormats = (info.call("getUrlFormats") as? Iterable<*>)?.mapNotNull { format ->
            format?.callString("getUrl")?.take(80)
        }.orEmpty()
        val summary = buildString {
            appendLine("videoId=$videoId")
            appendLine("client=${client?.callString("getClientName")} ua=${client?.callString("getUserAgent")?.take(80)}")
            appendLine("policy=$vodPolicy mime=$mimeType")
            appendLine("dashFormats=${info.callBool("containsDashFormats")} sabr=${info.callBool("containsSabrFormats")}")
            // HLS manifest URLs carry expiry and signature in the path, not the query.
            appendLine("hls=${!info.callString("getHlsManifestUrl").isNullOrBlank()} muxed=${direct != null}")
            appendLine("uri=${if (uri == hlsOf(info)) "hls" else uri}")
            appendLine("headers=${headers.keys.joinToString()}")
            appendLine("adaptivePair=${adaptivePair != null} video=${adaptivePair?.first?.substringBefore('?')?.take(80)}")
            appendLine("urlFormats=${urlFormats.size} ${urlFormats.firstOrNull()}")
            val formats = info.call("getAdaptiveFormats") as? Iterable<*>
            appendLine("adaptive=${formats?.count()} firstUrl=${formats?.firstOrNull()?.callString("getUrl")?.take(120)}")
            if (uri?.startsWith("file:") == true) {
                runCatching {
                    val path = android.net.Uri.parse(uri).path
                    val xml = path?.let { java.io.File(it).takeIf { file -> file.isFile }?.readText() }
                    appendLine("mpdChars=${xml?.length} googlevideo=${xml?.contains("googlevideo.com")}")
                }
            }
        }
        val safeSummary = summary.withoutUrlQueries().replace('\n', ' ').take(900)
        android.util.Log.e("AmbientYouTube", safeSummary)
        Timber.e("AmbientYouTube %s", safeSummary)
        runCatching {
            val file = context.getExternalFilesDir(null)?.resolve("youtube_last_stream.txt") ?: return
            file.writeText(summary.withoutUrlQueries())
        }
    }

    private fun mapItem(raw: Any?): YouTubeItem? {
        raw ?: return null
        val videoId = raw.callString("getVideoId")?.takeIf(String::isNotBlank) ?: return null
        return YouTubeItem(
            videoId = videoId,
            title = raw.callString("getTitle") ?: "SmartTube",
            subtitle = raw.call("getSecondTitle")?.toString(),
            author = raw.callString("getAuthor"),
            imageUrl = raw.callString("getCardImageUrl")?.takeIf(String::isNotBlank) ?: youtubeThumbnailUrl(videoId),
            backgroundUrl = raw.callString("getBackgroundImageUrl")?.takeIf(String::isNotBlank),
            durationMs = raw.callLong("getDurationMs") ?: 0,
            percentWatched = raw.callInt("getPercentWatched") ?: 0,
            isLive = raw.callBool("isLive") ?: false,
            isUpcoming = raw.callBool("isUpcoming") ?: false,
            scheduledStartMs = youtubeScheduledStartMs(raw.callString("getUpcomingEventStartTime"), null),
            isShorts = raw.callBool("isShorts") ?: false,
            channelId = raw.callString("getChannelId"),
        )
    }

    // --- "Watch on TV" (YouTube Lounge) ----------------------------------------------------

    /** Code for the phone's YouTube app › "Link with TV code" (XXX-XXX-XXX-XXX); null when unavailable. */
    suspend fun loungePairingCode(): String? = io {
        if (!available) return@io null
        service("getRemoteControlService").callString("getPairingCode")?.takeIf { it.isNotBlank() }
    }

    /**
     * Listens for commands from linked phones. Blocks the calling thread (reconnecting on its own)
     * until that thread is interrupted; run it on a dedicated thread.
     */
    fun listenLounge(onCommand: (YouTubeLinkCommand) -> Unit) {
        manager()
        val lounge = loungeService() ?: throw IllegalStateException("SmartTube lounge service unavailable")
        YouTubeLoungeBridge.listen(lounge, onCommand)
    }

    /** Tells linked phones what plays (blocking network call). */
    fun postLoungeNowPlaying(videoId: String, positionMs: Long, durationMs: Long, state: Int) {
        loungeService()?.let { YouTubeLoungeBridge.postNowPlaying(it, videoId, positionMs, durationMs, state) }
    }

    fun postLoungeState(positionMs: Long, durationMs: Long, state: Int) {
        loungeService()?.let { YouTubeLoungeBridge.postState(it, positionMs, durationMs, state) }
    }

    fun postLoungeVolume(volume: Int) {
        loungeService()?.let { YouTubeLoungeBridge.postVolume(it, volume) }
    }

    /** Forgets this screen's lounge identity: linked phones must link again with a new code. */
    suspend fun resetLounge() = io { loungeService()?.let(YouTubeLoungeBridge::reset) }

    private fun loungeService(): Any? {
        if (!available) return null
        globalPreferences()
        return YouTubeLoungeBridge.service()
    }

    private fun manager(): Any {
        if (initialized) return rawManager()
        synchronized(initLock) {
            if (initialized) return rawManager()

            applyMetadataLanguage()
            val manager = rawManager()
            // Construct the services while GlobalPreferences already exists. YouTubeSignInService
            // restores persisted accounts from a GlobalPreferences on-init callback on a worker
            // thread, so a cold-start isSigned()/feed call can otherwise race that restoration.
            val signIn = manager.call("getSignInService")
                ?: throw IllegalStateException("SmartTube sign-in service unavailable")
            manager.call("getContentService")
                ?: throw IllegalStateException("SmartTube content service unavailable")
            awaitStoredAccountRestore(signIn)
            signIn.call("checkAuth")
            initialized = true
            return manager
        }
    }

    private fun rawManager(): Any {
        // MediaServiceCore deliberately doesn't own an Android Application. Its account/session
        // storage is backed by SharedModules GlobalPreferences and must be initialized by the host.
        globalPreferences()
        val clazz = Class.forName("com.liskovsoft.youtubeapi.service.YouTubeServiceManager")
        return clazz.getMethod("instance").invoke(null)
            ?: throw IllegalStateException("SmartTube YouTubeServiceManager unavailable")
    }

    private fun globalPreferences(): Any? = runCatching {
        val prefs = Class.forName("com.liskovsoft.sharedutils.prefs.GlobalPreferences")
            .getMethod("instance", Context::class.java)
            .invoke(null, context.applicationContext)
        val language = LanguagePreferences.baseLanguage(LanguagePreferences.resolve(appPreferences.metadataLanguageKey))
        if (!language.isNullOrBlank()) prefs?.call("setPreferredLanguage", language)
        prefs
    }.onFailure { Timber.d(it, "SmartTube GlobalPreferences init") }.getOrNull()

    /** MediaServiceCore restores account data asynchronously when the SignInService is first
     * created. If persisted account data exists, wait for that bounded restore before exposing
     * auth state or starting content calls. Signed-out users do not pay this wait. */
    private fun awaitStoredAccountRestore(signIn: Any) {
        globalPreferences()?.callString("getMediaServiceAccountData")
            ?.takeIf { it.isNotBlank() } ?: return
        repeat(20) {
            val accounts = (signIn.call("getAccounts") as? Iterable<*>)?.any() == true
            if (accounts || signIn.callBool("isSigned") == true) return
            Thread.sleep(50)
        }
        Timber.w("SmartTube persisted account state was not restored within 1 second")
    }

    private fun service(method: String): Any = manager().call(method)
        ?: throw IllegalStateException("SmartTube engine service $method unavailable")

    private fun rxProxy(interfaceName: String, callback: (Array<out Any?>?) -> Unit): Any {
        val iface = Class.forName(interfaceName)
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "accept", "run" -> { callback(args); null }
                "toString" -> "AmbientTvSmartTubeRxProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    }

    private fun unwrapReactive(value: Any?): Any? {
        value ?: return null
        var current: Any? = value
        repeat(4) {
            val obj = current ?: return null
            val className = obj.javaClass.name
            val looksReactive = className.startsWith("io.reactivex.") ||
                className.startsWith("io.reactivex.rxjava3.") ||
                className.contains("Single") || className.contains("Observable") ||
                className.contains("Flowable") || className.contains("Maybe")
            if (!looksReactive) return obj

            val multiEmission = className.contains("Observable") || className.contains("Flowable")
            val next = if (multiEmission) {
                collectReactiveEmissions(obj)
                    ?: obj.call("blockingLast")
                    ?: obj.call("blockingFirst")
            } else {
                obj.call("blockingGet")
                    ?: obj.call("blockingSingle")
                    ?: obj.call("blockingLast")
                    ?: obj.call("blockingFirst")
            }
            if (next == null || next === obj) return obj
            current = next
        }
        return current
    }

    /** Current MediaServiceCore Home observables can emit several partial List<MediaGroup>
     * snapshots. blockingFirst() drops every later row. Collect finite feed observables to a list
     * and flatten one emission layer so all returned MediaGroups reach the mapper. */
    private fun collectReactiveEmissions(observable: Any): Any? {
        // Feed observables are finite in current MediaServiceCore, but cap collection defensively
        // so a future long-lived/repeating source cannot block the host indefinitely.
        val bounded = observable.call("take", 16L) ?: observable
        val collected = bounded.call("toList")?.call("blockingGet") ?: return null
        val emissions = when (collected) {
            is Iterable<*> -> collected.toList()
            is Array<*> -> collected.toList()
            else -> return collected
        }
        if (emissions.isEmpty()) return emptyList<Any?>()
        if (emissions.size == 1) return emissions.first()

        return buildList {
            emissions.forEach { emission ->
                when (emission) {
                    is Iterable<*> -> addAll(emission)
                    is Array<*> -> addAll(emission)
                    null -> Unit
                    else -> add(emission)
                }
            }
        }
    }

    private fun blockingObservable(value: Any?): Any? = unwrapReactive(value)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}

private fun Any.call(name: String, vararg args: Any?): Any? {
    val candidates = javaClass.methods.filter { it.name == name && it.parameterTypes.size == args.size }
    val method = candidates.firstOrNull { m -> m.parameterTypes.indices.all { i -> args[i] == null || wrap(m.parameterTypes[i]).isAssignableFrom(args[i]!!.javaClass) } }
        ?: candidates.firstOrNull() ?: return null
    return runCatching { method.invoke(this, *args) }.onFailure { Timber.d(it, "SmartTube reflection: %s", name) }.getOrNull()
}
private fun Any.callFirst(vararg names: String): Any? {
    for (name in names) {
        val result = call(name)
        if (result != null) return result
    }
    return null
}
private fun Any.callString(name: String) = call(name)?.toString()
private fun Any.callLong(name: String) = (call(name) as? Number)?.toLong()
private fun Any.callInt(name: String) = (call(name) as? Number)?.toInt()
private fun Any.callDouble(name: String) = (call(name) as? Number)?.toDouble()
private fun Any.callBool(name: String) = call(name) as? Boolean
private fun wrap(c: Class<*>) = when (c) {
    Int::class.javaPrimitiveType -> Int::class.javaObjectType
    Long::class.javaPrimitiveType -> Long::class.javaObjectType
    Float::class.javaPrimitiveType -> Float::class.javaObjectType
    Double::class.javaPrimitiveType -> Double::class.javaObjectType
    Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
    else -> c
}

private data class DetailedReflectionInvocation(val found: Boolean, val value: Any?, val error: Throwable?)
private fun Any.invokeDetailed(name: String, vararg args: Any?): DetailedReflectionInvocation {
    val candidates = javaClass.methods.filter { it.name == name && it.parameterTypes.size == args.size }
    val method = candidates.firstOrNull { m ->
        m.parameterTypes.indices.all { i -> args[i] == null || wrap(m.parameterTypes[i]).isAssignableFrom(args[i]!!.javaClass) }
    } ?: candidates.firstOrNull() ?: return DetailedReflectionInvocation(false, null, null)
    return try {
        DetailedReflectionInvocation(true, method.invoke(this, *args), null)
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t.cause ?: t
        DetailedReflectionInvocation(true, null, cause)
    }
}

private data class ReflectionInvocation(val invoked: Boolean, val value: Any?)
private fun Any.invokeCompatible(name: String, vararg args: Any?): ReflectionInvocation {
    val candidates = javaClass.methods.filter { it.name == name && it.parameterTypes.size == args.size }
    val method = candidates.firstOrNull { m -> m.parameterTypes.indices.all { i -> args[i] == null || wrap(m.parameterTypes[i]).isAssignableFrom(args[i]!!.javaClass) } }
        ?: return ReflectionInvocation(false, null)
    return try { ReflectionInvocation(true, method.invoke(this, *args)) }
    catch (t: Throwable) { Timber.d(t, "SmartTube reflection: %s", name); ReflectionInvocation(false, null) }
}
private fun Any.hasMethod(name: String, arity: Int): Boolean = javaClass.methods.any { it.name == name && it.parameterTypes.size == arity }
private fun Any.hasAnyMethod(vararg names: String): Boolean = names.any { hasMethod(it, 0) }

internal class IncompleteYouTubeFeedException(cause: Throwable? = null) : java.io.IOException("YouTube feed refresh incomplete", cause)

private const val RENEW_TIMEOUT_MS = 20_000L

package com.ambient.tv.youtube

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import android.content.Context
import android.os.Build
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.local.SmartTubeAccountBackup
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.notifications.notifyLogin
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import com.ambient.tv.util.withoutUrlQueries
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
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
    private val localPlaybackDatabase: com.ambient.tv.data.local.LocalPlaybackDatabase,
    private val onlineServices: com.ambient.tv.data.browse.OnlineServiceAvailability,
) {
    constructor(context: Context, appPreferences: AppPreferences) :
        this(context, appPreferences, com.ambient.tv.data.local.LocalPlaybackDatabase.memory(),
            com.ambient.tv.data.browse.OnlineServiceAvailability(context))
    @Volatile private var signInDisposable: Any? = null
    /** One diagnostic notice per incident, shared by Home and SmartTube views. */
    private val accountAttentionReported = java.util.concurrent.atomic.AtomicBoolean(false)

    fun reportAccountAttention() {
        if (accountAttentionReported.compareAndSet(false, true)) {
            notifyLogin("youtube", NoticePhase.ERROR,
                message = com.ambient.tv.R.string.youtube_auth_attention_notice)
        }
    }

    fun clearAccountAttention() {
        accountAttentionReported.set(false)
    }

    /** An intentionally signed-out guest has no stale account to recover. */
    fun hasSavedAccounts(): Boolean =
        !globalPreferences()?.callString("getMediaServiceAccountData").isNullOrBlank()
    @Volatile private var initialized = false
    private val initLock = Any()
    private val playbackMetadata = YouTubePlaybackMetadataCache()
    /**
     * Format infos of recently resolved streams, for history reports. MediaServiceCore caches
     * only the last loaded video; a report for any other video (the final position of the
     * previous video arrives after Next Up loaded the next one) would otherwise run a complete
     * player request and evict the current video from that cache, every 10 s in turn.
     */
    private val playbackFormatInfos = object : java.util.LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 8
    }
    /** Click-tracking parameters of browsed cards; SmartTube passes them with the player request. */
    private val clickTrackingParams = object : java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
    }
    /** scope (feed/search key) -> row title -> latest SmartTube MediaGroup of that row. */
    private val rowContinuations = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Any>>()
    private val rowContinuationLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** Freeze a row's upstream page handle for one player session, even if Home refreshes. */
    fun forkRowContinuation(sourceScope: String, playerScope: String, rowId: String): Boolean {
        val group = rowContinuations[sourceScope]?.get(rowId) ?: return false
        // Flat grids can combine several source rows. Keep all forked handles in the
        // same player scope instead of replacing the first one with each new fork.
        rowContinuations.computeIfAbsent(playerScope) {
            java.util.concurrent.ConcurrentHashMap<String, Any>()
        }[rowId] = group
        return true
    }

    fun discardRowContinuation(scope: String) {
        rowContinuations.remove(scope)
        rowContinuationLocks.keys.removeAll { it.startsWith("$scope|") }
    }
    /** SmartTube's client selector is global: format loads that may switch it run one at a time. */
    private val formatClientMutex = kotlinx.coroutines.sync.Mutex()
    private val playbackWarmupRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val localPlaybackWarmupRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val metadataInflight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Result<YouTubeMetadata>?>>()
    /** Subscription changes of this session; cached metadata still carries the state it was loaded with. */
    private val subscriptionOverrides = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** Channel id -> subscribed, for every change made in this session; screens below the one that changed it follow. */
    val subscriptionChanges: kotlinx.coroutines.flow.StateFlow<Map<String, Boolean>> = subscriptionOverrides

    init {
        YouTubeLocalProgress.init(context, localPlaybackDatabase)
        // Lets VLC/MPV keep playing after googlevideo expires a link (hours of pause): the relay
        // asks for a fresh URL of the same stream and resumes at the same byte.
        YouTubeStreamRelay.renewer = { videoId, expiredUrl ->
            // Runs on a relay thread: bounded, so a stalled renewal cannot hang that thread forever.
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(RENEW_TIMEOUT_MS) { renewStreamUrl(videoId, expiredUrl) }
            }
        }
    }

    /**
     * Called only by a requested SmartTube surface (including cached Home cards).
     * No speculative video request, decoder instance or signed URL is created.
     */
    internal fun requestPlaybackWarmup() {
        if (!available) return
        if (localPlaybackWarmupRequested.compareAndSet(false, true)) {
            backgroundScope.launch {
                runCatching {
                    val started = System.nanoTime()
                    val native = kotlinx.coroutines.coroutineScope {
                        val codecs = async { YouTubeDeviceVideo.warmup() }
                        val mpv = com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable
                        val useRelay = appPreferences.playbackBackendKey == "vlc" || mpv
                        if (useRelay) YouTubeStreamRelay.warmup()
                        codecs.await()
                        useRelay
                    }
                    com.ambient.tv.ui.player.logPlaybackStartup("youtube", "local_warmup", started, "native=$native")
                }.onFailure {
                    localPlaybackWarmupRequested.set(false)
                    Timber.d(it, "SmartTube local playback warmup failed")
                }
            }
        }
        if (onlineServices.state.value.internetUnavailable ||
            !playbackWarmupRequested.compareAndSet(false, true)) return
        backgroundScope.launch {
            runCatching {
                val started = System.nanoTime()
                // Account restoration must never hold the shared format-client slot.
                service("getMediaItemService")
                // Actual playback wins: it restores the client itself if already in the slot.
                if (formatClientMutex.tryLock()) {
                    try { restoreFormatClientOnce() } finally { formatClientMutex.unlock() }
                }
                com.ambient.tv.ui.player.logPlaybackStartup("youtube", "format_warmup", started)
            }.onFailure {
                playbackWarmupRequested.set(false)
                Timber.d(it, "SmartTube playback warmup failed")
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
        rowContinuations.clear()
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
        clearAccountAttention()
        playbackMetadata.clear()
        rowContinuations.clear()
        subscriptionOverrides.value = emptyMap()
        likeOverrides.clear()
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
        rowContinuations.clear()
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
        // Current MediaServiceCore resolves every playlist (including Watch Later) to a video row
        // through getPlaylistRowsObserve(). Keep the older plural spelling as a compatibility
        // fallback, then fall back to the raw playlist-card feed on revisions without row support.
        "getPlaylistRowsObserve", "getPlaylistsRowObserve", "getPlaylistsObserve", "getPlaylists", onRows = onRows,
    )
    suspend fun search(query: String, onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> = contentRowsWithArgs(
        arrayOf("getSearchObserve", "getSearch"),
        query, onRows = onRows,
    )
    /** Subscribed channels as channel tiles, those with new uploads first (SmartTube's default order). */
    suspend fun subscribedChannels(onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> = contentRows(
        "getSubscribedChannelsByNewContentObserve", "getSubscribedChannelsObserve", "getSubscribedChannels", onRows = onRows,
    )

    /** Channel page rows (Uploads, Live, Shorts, Playlists, ...); [scope] keys their continuations. */
    suspend fun channel(channelId: String, scope: String, onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> =
        withContext(RowContinuationScope(scope)) {
            filtered(scope, contentRowsWithArgs(arrayOf("getChannelObserve"), channelId, onRows = { onRows(filtered(scope, it)) }))
        }

    /** Shorts / live filters from SmartTube settings, applied to every load and continuation. */
    private fun filtered(scope: String, rows: List<YouTubeRow>): List<YouTubeRow> =
        youtubeFilterRows(rows, scope, appPreferences.youtubeHideShorts, appPreferences.youtubeHideLiveInSubscriptions)

    suspend fun channelSearch(channelId: String, query: String, scope: String, onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> =
        withContext(RowContinuationScope(scope)) {
            contentRowsWithArgs(arrayOf("getChannelSearchObserve", "getChannelSearch"), channelId, query, onRows = onRows)
        }

    /** Rating changes of this session; cached metadata still carries the rating it was loaded with. */
    private val likeOverrides = java.util.concurrent.ConcurrentHashMap<String, YouTubeLikeStatus>()

    fun likeOverride(videoId: String): YouTubeLikeStatus? = likeOverrides[videoId]

    /** Moves the rating of [videoId] from [from] to [to], as SmartTube's like/dislike buttons do. */
    suspend fun setLikeStatus(videoId: String, from: YouTubeLikeStatus, to: YouTubeLikeStatus) = io {
        if (from == to) return@io
        val media = service("getMediaItemService")
        // The rating calls take a MediaItem but only read its video id.
        val item = videoIdMediaItem(videoId)
        fun run(method: String) {
            val invocation = media.invokeDetailed(method, item)
            check(invocation.found) { "SmartTube has no $method" }
            invocation.error?.let { throw it }
        }
        when (from) {
            YouTubeLikeStatus.LIKE -> run("removeLike")
            YouTubeLikeStatus.DISLIKE -> run("removeDislike")
            YouTubeLikeStatus.NONE -> Unit
        }
        when (to) {
            YouTubeLikeStatus.LIKE -> run("setLike")
            YouTubeLikeStatus.DISLIKE -> run("setDislike")
            YouTubeLikeStatus.NONE -> Unit
        }
        likeOverrides[videoId] = to
    }

    private fun videoIdMediaItem(videoId: String): Any {
        val iface = Class.forName("com.liskovsoft.mediaserviceinterfaces.data.MediaItem")
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "getVideoId" -> videoId
                "toString" -> "AmbientTvMediaItem($videoId)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> when (method.returnType) {
                    Int::class.javaPrimitiveType -> -1
                    Long::class.javaPrimitiveType -> 0L
                    Double::class.javaPrimitiveType -> 0.0
                    Float::class.javaPrimitiveType -> 0f
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
        }
    }

    /** Session state of a channel subscription changed here; null when it was not changed. */
    fun subscriptionOverride(channelId: String): Boolean? = subscriptionOverrides.value[channelId]

    suspend fun setSubscribed(channelId: String, subscribed: Boolean) = io {
        val invocation = service("getMediaItemService").invokeDetailed(if (subscribed) "subscribe" else "unsubscribe", channelId)
        check(invocation.found) { "SmartTube has no subscription call" }
        invocation.error?.let { throw it }
        subscriptionOverrides.update { it + (channelId to subscribed) }
    }

    /** SmartTube exposes these as top-level sections. Method names have changed between
     * MediaServiceCore revisions, so probe known public variants across revisions. */
    suspend fun supportedFeedKeys(): Set<String> = io {
        if (!available) return@io emptySet()
        requestPlaybackWarmup()
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
            if (signedIn && content.hasAnyMethod("getSubscribedChannelsByNewContentObserve", "getSubscribedChannelsObserve", "getSubscribedChannels")) add("channels")
            if (signedIn && content.hasAnyMethod("getHistory", "getHistoryObserve", "getHistoryRowObserve")) add("history")
            if (signedIn && content.hasAnyMethod("getPlaylistRowsObserve", "getPlaylistsRowObserve", "getPlaylistsObserve", "getPlaylists")) add("playlists")
            if (content.hasAnyMethod("getShorts", "getShortsObserve")) add("shorts")
            if (content.hasAnyMethod("getMusic", "getMusicObserve")) add("music")
            if (content.hasAnyMethod("getNews", "getNewsObserve")) add("news")
            if (content.hasAnyMethod("getGaming", "getGamingObserve")) add("gaming")
            if (content.hasAnyMethod("getLive", "getLiveObserve")) add("live")
            if (content.hasAnyMethod("getKids", "getKidsObserve", "getKidsHomeObserve")) add("kids")
        }
    }

    suspend fun feed(key: String, onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> =
        withContext(RowContinuationScope("feed:$key")) {
            filtered("feed:$key", feedRows(key) { onRows(filtered("feed:$key", it)) })
        }

    /** Search rows; [scope] keys their continuations like the caller's cache key. */
    suspend fun search(query: String, scope: String, onRows: suspend (List<YouTubeRow>) -> Unit = {}): List<YouTubeRow> =
        withContext(RowContinuationScope(scope)) { filtered(scope, search(query) { onRows(filtered(scope, it)) }) }

    /**
     * Next page of one row of the last load of [scope] (SmartTube ContentService.continueGroup,
     * the call behind SmartTube's own row scrolling). Null: no continuable group is known, so the
     * row ends. Upstream returns null at the end and skips pages that filtering emptied.
     */
    suspend fun continueRow(scope: String, rowId: String): YouTubeRowPage? =
        rowContinuationLocks.computeIfAbsent("$scope|$rowId") { Mutex() }.withLock { io {
        if (!available) return@io null
        val groups = rowContinuations[scope] ?: return@io null
        val group = groups[rowId] ?: return@io null
        if (group.callString("getNextPageKey").isNullOrBlank()) {
            groups.remove(rowId, group)
            return@io null
        }
        val invocation = service("getContentService").invokeDetailed("continueGroup", group)
        if (!invocation.found) {
            Timber.w("SmartTube has no continueGroup; row %s cannot continue", rowId)
            return@io null
        }
        invocation.error?.let { throw it }
        val next = unwrapReactive(invocation.value)
        if (next == null) {
            groups.remove(rowId, group)
            return@io YouTubeRowPage(emptyList(), hasMore = false)
        }
        val items = mapGroupItems(next, rowId)
        val hasMore = !next.callString("getNextPageKey").isNullOrBlank()
        // Only advance the handle of the load that is still current for this scope.
        if (rowContinuations[scope] === groups) {
            if (hasMore) groups.replace(rowId, group, next) else groups.remove(rowId, group)
        }
        YouTubeRowPage(youtubeFilterItems(items, scope, appPreferences.youtubeHideShorts,
            appPreferences.youtubeHideLiveInSubscriptions), hasMore)
    } }

    private suspend fun feedRows(key: String, onRows: suspend (List<YouTubeRow>) -> Unit): List<YouTubeRow> = when (key) {
        "home" -> home(onRows)
        "subscriptions" -> subscriptions(onRows)
        "channels" -> subscribedChannels(onRows)
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
        rowContinuations.clear()
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

    suspend fun metadata(videoId: String, forceRefresh: Boolean = false): YouTubeMetadata {
        YouTubeLocalProgress.awaitReady()
        if (forceRefresh) {
            io { service("getMediaItemService").call("invalidateCache") }
            playbackMetadata.invalidate(videoId)
        }
        val generation = playbackMetadata.generation
        playbackMetadata.get(videoId)?.let { return it }
        // A post-reconnect request must not join work started against the old connection/account.
        val requestKey = "$generation:$videoId"
        val gate = CompletableDeferred<Result<YouTubeMetadata>?>()
        val existing = metadataInflight.putIfAbsent(requestKey, gate)
        // A null result means the owning caller was cancelled: that is not this waiter's
        // failure, so start (or join) a fresh request instead of inheriting the cancellation.
        if (existing != null) return existing.await()?.getOrThrow() ?: metadata(videoId)
        return try {
            val value = io {
                playbackMetadata.get(videoId)?.let { return@io it }
                val media = service("getMediaItemService")
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
            metadataInflight.remove(requestKey, gate)
            gate.complete(null)
            throw cancelled
        } catch (error: Throwable) {
            gate.complete(Result.failure(error))
            throw error
        } finally {
            metadataInflight.remove(requestKey, gate)
        }
    }

    suspend fun playback(videoId: String, isTrailer: Boolean = false, started: Long = System.nanoTime()): YouTubePlayback {
        val cached = playbackMetadata.get(videoId)
        return prepareYouTubePlayback(cached,
            loadMetadata = { com.ambient.tv.util.suspendResult { metadata(videoId) }.getOrNull() },
            loadStream = {
                // Network resolution overlaps local SQL priming; resume is still canonical.
                val stream = resolvePlaybackStream(videoId, isTrailer, started)
                val progressStarted = System.nanoTime()
                YouTubeLocalProgress.awaitReady()
                com.ambient.tv.ui.player.logPlaybackStartup("youtube", "progress_ready", started,
                    "stage_ms=${(System.nanoTime() - progressStarted) / 1_000_000}")
                stream
            },
        ).also {
            com.ambient.tv.ui.player.logPlaybackStartup("youtube", "prepared", started, "cached_metadata=${cached != null}")
        }
    }

    suspend fun refreshLivePlayback(videoId: String, isTrailer: Boolean = false, started: Long = System.nanoTime()): YouTubePlayback {
        io { service("getMediaItemService").call("invalidateCache") }
        return playback(videoId, isTrailer, started)
    }

    private suspend fun resolvePlaybackStream(videoId: String, isTrailer: Boolean, started: Long): YouTubePlayback = io {
        val serviceStarted = System.nanoTime()
        val media = service("getMediaItemService")
        com.ambient.tv.ui.player.logPlaybackStartup("youtube", "service_ready", started,
            "stage_ms=${(System.nanoTime() - serviceStarted) / 1_000_000}")
        val candidate = loadPlayableFormatInfo(media, videoId, isTrailer, started)
        val info = candidate.info
        synchronized(playbackFormatInfos) { playbackFormatInfos[videoId] = info }
        if (info.callBool("isLiveOffline") == true) {
            throw YouTubeLiveOfflineException(YouTubeLiveEvent(
                info.callString("getTitle").orEmpty(),
                youtubeScheduledStartMs(info.callString("getScheduledStartTime"), info.callString("getStartTimestamp")),
            ))
        }
        val reason = info.callString("getPlayabilityReason")
        val unplayable = info.callBool("isUnplayable") == true
        if (unplayable) throw IllegalStateException(reason ?: context.getString(com.ambient.tv.R.string.youtube_video_unplayable))

        // The winning candidate already contains its selected pair and renderer; do not
        // re-run reflection, hardware checks or profile lookups after the format lock.
        val hls = candidate.hls
        val lengthSeconds = candidate.lengthSeconds
        val isLive = candidate.live
        // Playback clocks of active broadcasts are sliding-window positions, never saved VOD
        // resume points. Discard values written by earlier versions when live is identified.
        if (isLive) YouTubeLocalProgress.discardLive(videoId)
        val formats = candidate.formats
        val direct = candidate.direct
        val vodPolicy = youtubeVodStreamPolicy(isEmulator = isProbablyEmulator())
        val adaptivePair = candidate.pair
        val renderer = candidate.renderer
        // An OTF-only response may still be opened by the Media3 fallback.
        val canUseDash = isLive || youtubeHasPlayableDashTracks(formats,
            allowOtf = renderer == com.ambient.tv.ui.player.backend.PlaybackBackend.MEDIA3 ||
                (adaptivePair == null && hls == null && direct == null))
        val generatedDash = lazy { if (canUseDash) generatedDashManifest(info, videoId, started) else null }
        val deferMpd = adaptivePair != null && (vodPolicy == YouTubeVodStreamPolicy.DASH_FIRST || direct == null) &&
            youtubeHasPlayableDashTracks(formats) &&
            renderer != com.ambient.tv.ui.player.backend.PlaybackBackend.MEDIA3
        // A dormant deferred must not be a child of the process scope (it would retain
        // every unplayed manifest). Await starts it once, even across UI recompositions.
        val deferredDash = if (deferMpd) youtubeDeferredDash { generatedDashManifest(info, videoId, started) } else null
        val uri = if (deferMpd) hls ?: direct ?: requireNotNull(adaptivePair).first else selectYouTubePlaybackUri(
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
        dumpYouTubeStreamDebug(videoId, info, vodPolicy, uri, mimeType, direct, headers, adaptivePair)
        if (uri == null) {
            throw IllegalStateException(context.getString(com.ambient.tv.R.string.youtube_no_compatible_format))
        }
        YouTubePlayback(
            videoId = videoId,
            title = info.callString("getTitle") ?: "SmartTube",
            author = info.callString("getAuthor"),
            description = info.callString("getDescription"),
            // The initial player response may already carry a relative upload date. The
            // watch-next metadata task can enrich it later without delaying playback.
            publishedDate = youtubePublicationText(info.callString("getProductionDate")
                ?: info.callString("getPublishedDate")),
            publishedAtMs = youtubePublicationEpochMs(info.callLong("getPublishedDate")
                ?: info.callString("getPublishedDate")?.toLongOrNull()),
            uri = uri,
            mimeType = mimeType,
            progressiveUri = direct?.takeIf { it != uri },
            adaptiveVideoUri = adaptivePair?.first,
            adaptiveAudioUri = adaptivePair?.second,
            adaptiveFormats = formats,
            deferredDash = deferredDash,
            // Automatic (ASR) captions only when allowed in SmartTube settings.
            captions = if (isLive) emptyList() else captions(info).filter { appPreferences.youtubeAutoCaptions || !it.autoGenerated },
            isLive = isLive,
            // Needed for the resume point when watch-next reports no duration.
            durationMs = if (isLive) 0L else (lengthSeconds?.times(1000)?.toLong() ?: 0L),
            headers = headers,
            timeline = com.ambient.tv.domain.model.PlaybackTimeline(preview = if (isLive) null else storyboardPreview(info)),
        ).also {
            com.ambient.tv.ui.player.logPlaybackStartup("youtube", "stream_ready", started,
                "live=$isLive renderer=${renderer.key} kind=${mimeType ?: "direct"} mpd_deferred=$deferMpd adaptive_pair=${adaptivePair != null} progressive_fallback=${direct != null}")
        }
    }

    /** A fresh URL for the stream [expiredUrl] belonged to (same format and audio track). */
    suspend fun renewStreamUrl(videoId: String, expiredUrl: String): String? = io {
        val media = service("getMediaItemService")
        val candidate = com.ambient.tv.util.suspendResult {
            loadPlayableFormatInfo(media, videoId, expiredUrl = expiredUrl)
        }.getOrNull() ?: return@io null
        youtubeRenewedUrl(expiredUrl, candidate.renewalUrls).also { renewed ->
            com.ambient.tv.diagnostics.DiagnosticLog.log(
                com.ambient.tv.diagnostics.DiagnosticLevel.INFO, "youtube-relay",
                if (renewed != null) "Expired stream link renewed" else "Expired stream link could not be renewed",
            )
        }
    }

    @Volatile private var formatClientRestored = false

    /**
     * MediaServiceCore persists the client that last delivered playable formats but starts every
     * process at the TV client again, which often answers with SABR only: one wasted request
     * (1–5 s) before the client switch on the first video after each app start.
     */
    private fun restoreFormatClientOnce() {
        if (formatClientRestored) return
        runCatching {
            val data = Class.forName("com.liskovsoft.youtubeapi.service.internal.MediaServiceData").getMethod("instance").invoke(null)
            val ordinal = data?.callInt("getVideoInfoType")?.takeIf { it >= 0 } ?: return@runCatching
            val service = Class.forName("com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService").getMethod("instance").invoke(null)
            val client = Class.forName("com.liskovsoft.youtubeapi.common.helpers.AppClient").enumConstants?.getOrNull(ordinal) ?: return@runCatching
            val known = service.javaClass.getDeclaredField("VIDEO_INFO_TYPE_LIST").apply { isAccessible = true }.get(null) as? Array<*>
            if (known?.contains(client) != true) return@runCatching
            val fields = listOf("mNextInfoType", "mActualInfoType").map {
                service.javaClass.getDeclaredField(it).apply { isAccessible = true }
            }
            fields.forEach { it.set(service, client) }
            Timber.i("SmartTube format client restored: %s", client)
        }.onSuccess { formatClientRestored = true }
            .onFailure { Timber.d(it, "SmartTube format client restore") }
    }

    private suspend fun loadPlayableFormatInfo(
        media: Any, videoId: String, isTrailer: Boolean = false, launchStarted: Long = 0L,
        expiredUrl: String? = null,
    ): PlaybackFormatCandidate {
        val queuedAt = System.nanoTime()
        return formatClientMutex.withLock {
        val trace = launchStarted.takeIf { it != 0L } ?: queuedAt
        com.ambient.tv.ui.player.logPlaybackStartup("youtube", "format_slot", trace,
            "stage_ms=${(System.nanoTime() - queuedAt) / 1_000_000}")
        val caller = kotlin.coroutines.coroutineContext
        restoreFormatClientOnce()
        // Do not overwrite a client selected by an actual format request with an old saved one.
        formatClientRestored = true
        if (expiredUrl != null) media.call("invalidateCache")
        resolveYouTubeFormatInfo(
            load = { allowInnertube ->
                PlaybackFormatCandidate(loadFormatInfo(media, videoId, allowInnertube), videoId, isTrailer)
            },
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
            // Renewal serves an already selected representation, possibly after a renderer
            // switch. Match its itag/audio track rather than reselecting startup quality.
            score = { candidate ->
                if (expiredUrl == null) formatInfoScore(candidate)
                else if (candidate.info.callBool("isUnplayable") == true) 0
                else {
                    val renewed = youtubeRenewedUrl(expiredUrl, candidate.renewalUrls)
                    when {
                        renewed == null -> 0
                        renewed == expiredUrl -> 2
                        else -> 4
                    }
                }
            },
            isTerminal = { it.info.callBool("isLiveOffline") == true },
            checkActive = { caller.ensureActive() },
            onAttempt = { attempt, score, started ->
                com.ambient.tv.ui.player.logPlaybackStartup("youtube", "format_response", trace,
                    "attempt=$attempt score=$score stage_ms=${(System.nanoTime() - started) / 1_000_000}")
            },
        )
        }
    }

    private fun playbackAdaptivePair(formats: List<YouTubeAdaptiveFormat>): Pair<String, String>? =
        if (formats.isEmpty()) null else youtubeAdaptivePair(
            formats,
            YouTubeDeviceVideo.videoTarget(context, appPreferences.youtubeVideoPreset, appPreferences.youtubeMaxVideoHeight),
            YouTubeDeviceVideo.decodeCheck(),
            youtubePreferredAudioLanguage(appPreferences.playbackAudioLanguage),
        )

    private fun playbackRenderer(
        videoId: String, info: Any, live: Boolean, formats: List<YouTubeAdaptiveFormat>,
        pair: Pair<String, String>?, isTrailer: Boolean,
    ): com.ambient.tv.ui.player.backend.PlaybackBackend {
        val format = pair?.first?.let { url -> formats.firstOrNull { it.url == url } }
        val route = if (isTrailer) com.ambient.tv.ui.navigation.Destinations.trailerPlayer(videoId)
            else com.ambient.tv.ui.navigation.Destinations.youtubePlayer(videoId)
        val metadata = com.ambient.tv.ui.player.PlaybackMetadata(route, info.callString("getTitle").orEmpty(),
            source = "youtube", mediaId = videoId, sourceWidth = format?.width,
            sourceHeight = format?.height, isLive = live, isTrailer = isTrailer)
        val key = com.ambient.tv.ui.player.preferredYouTubePlaybackRenderer(context, appPreferences.playbackBackendKey, metadata)
        return com.ambient.tv.ui.player.backend.PlaybackBackendResolver.resolve(key)
    }

    /** One immutable choice per upstream response, evaluated only if the response needs it. */
    private inner class PlaybackFormatCandidate(val info: Any, videoId: String, isTrailer: Boolean) {
        val hls by lazy { info.callString("getHlsManifestUrl")?.takeIf(String::isNotBlank) }
        val lengthSeconds by lazy { info.callString("getLengthSeconds")?.toDoubleOrNull() }
        val live by lazy { youtubeCurrentlyLive(info.callBool("isLive") == true, hls, lengthSeconds,
            liveContent = info.callBool("isLiveContent") != false) }
        private val allFormats by lazy { adaptiveFormats(info) }
        val formats by lazy { if (live) emptyList() else allFormats }
        val pair by lazy { playbackAdaptivePair(formats) }
        val renderer by lazy { playbackRenderer(videoId, info, live, formats, pair, isTrailer) }
        val direct by lazy { muxedProgressiveUrl(info) }
        val renewalUrls by lazy { allFormats.mapNotNull { it.url } +
            (info.call("getUrlFormats") as? Iterable<*>)?.mapNotNull { it?.callString("getUrl") }.orEmpty() }
    }

    private fun formatInfoScore(candidate: PlaybackFormatCandidate): Int {
        if (candidate.info.callBool("isUnplayable") == true) return 0
        val renderer = candidate.renderer
        val media3 = renderer == com.ambient.tv.ui.player.backend.PlaybackBackend.MEDIA3
        // Track presence alone is insufficient on the native renderers: the selected pair
        // must have decodable video and audio. Keep MPD as a fallback for Media3.
        val dashFormats = candidate.formats.takeIf {
            candidate.info.callBool("containsDashFormats") == true
        }.orEmpty()
        val playableFormats = if (media3 || candidate.pair != null) dashFormats else emptyList()
        val selectedHeight = candidate.pair?.first?.let { url ->
            candidate.formats.firstOrNull { it.url == url }?.qualityHeight()
        }
        val cap = appPreferences.youtubeMaxVideoHeight
        val score = youtubePlayableFormatScore(
            unplayable = false,
            live = candidate.live,
            hasHls = candidate.hls != null,
            formats = playableFormats,
            preferLiveHls = renderer != com.ambient.tv.ui.player.backend.PlaybackBackend.VLC,
            allowOtf = media3,
            requestedMaxHeight = cap.takeIf { selectedHeight == it } ?: 0,
            hasProgressive = { candidate.direct != null },
        )
        // Try for native-compatible formats, but do not lose the only playable Media3 response.
        return if (score == 0 && !candidate.live && youtubeHasPlayableDashTracks(dashFormats, allowOtf = true)) 1 else score
    }

    private fun loadFormatInfo(media: Any, videoId: String, allowInnertube: Boolean = true): Any {
        // Like SmartTube: the card's click-tracking parameters go with the player request, so
        // YouTube attributes the view to the recommendation it came from.
        val tracking = synchronized(clickTrackingParams) { clickTrackingParams[videoId] }
        val primary = if (tracking != null) media.invokeDetailed("getFormatInfo", videoId, tracking)
            else media.invokeDetailed("getFormatInfo", videoId)
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

    suspend fun updateHistory(videoId: String, positionMs: Long, durationMs: Long = 0L, completed: Boolean = false, flush: Boolean = false) = io {
        val duration = durationMs.takeIf { it > 0L } ?: playbackMetadata.durationMs(videoId)
            ?: YouTubeLocalProgress.durationMs(videoId) ?: 0L
        val position = if (completed && duration > 0L) duration else positionMs.coerceAtLeast(0L)
        // Cards and playback share this exact position. The watch-next metadata cache must
        // not become a second, rounded resume source after a local progress report.
        YouTubeLocalProgress.record(videoId, position, duration, completed)
        if ((flush || completed) && !YouTubeLocalProgress.flush()) throw java.io.IOException("Local YouTube progress could not be committed")
        // History paused: the position stays local, YouTube's history is not touched.
        if (appPreferences.youtubeHistoryPaused) return@io
        val media = service("getMediaItemService")
        // Native EOF can clear both clocks. Recover a length for the completed watch record;
        // sending a made-up zero here would undo the seen state on YouTube.
        val remotePosition = if (completed && duration <= 0L) {
            media.call("getFormatInfo", videoId)?.callString("getLengthSeconds")
                ?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                ?.times(1_000.0)?.toLong() ?: return@io
        } else position
        val seconds = remotePosition / 1000f
        val info = synchronized(playbackFormatInfos) { playbackFormatInfos[videoId] }
        val reported = if (info != null) reportWatchTime(videoId, info, seconds) else false
        if (reported) return@io
        // No format info of our own (resolved before this process started): MediaServiceCore's
        // own path, which reloads the video when it is not the last one it cached.
        val invocation = media.invokeDetailed("updateHistoryPosition", videoId, seconds)
        if (!invocation.found) Timber.w("SmartTube has no updateHistoryPosition(String, float)")
        invocation.error?.let { error ->
            Timber.w(error, "SmartTube history update failed for %s", videoId)
            com.ambient.tv.diagnostics.DiagnosticLog.warn("youtube", "history update failed: ${error.message}", error)
        }
    }

    /**
     * What MediaServiceCore's updateHistoryPosition does, with the format info this engine
     * resolved instead of its single-entry cache: the signed-in check, the one-time sync with
     * the account's player response (its tracking params), then the watch-time call.
     * False when the service lacks that path; an error is logged and counts as reported.
     */
    private fun reportWatchTime(videoId: String, info: Any, positionSec: Float): Boolean {
        val signIn = service("getSignInService")
        if (signIn.callBool("isSigned") != true) return true
        return try {
            val formatInfoClass = Class.forName("com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo")
            Class.forName("com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper")
                .getMethod("syncWithAuthInfoIfNeeded", formatInfoClass).invoke(null, info)
            val tracking = Class.forName("com.liskovsoft.youtubeapi.track.TrackingService")
                .getMethod("instance").invoke(null) ?: return false
            val invocation = tracking.invokeDetailed("updateWatchTime",
                info.callString("getVideoId") ?: videoId, positionSec,
                info.callString("getLengthSeconds")?.toFloatOrNull() ?: 0f,
                info.callString("getEventId"), info.callString("getVisitorMonitoringData"), info.callString("getOfParam"))
            if (!invocation.found) return false
            invocation.error?.let { throw it }
            true
        } catch (error: java.lang.reflect.InvocationTargetException) {
            // Thrown inside the service (e.g. "the format should be synced first"): the
            // service's own path would fail the same way, so report it instead of retrying.
            val cause = error.targetException ?: error
            if (cause is InterruptedException) throw cause
            Timber.w(cause, "SmartTube history update failed for %s", videoId)
            com.ambient.tv.diagnostics.DiagnosticLog.warn("youtube", "history update failed: ${cause.message}", cause)
            true
        } catch (error: ReflectiveOperationException) {
            Timber.d(error, "SmartTube tracking path unavailable")
            false
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "SmartTube history update failed for %s", videoId)
            com.ambient.tv.diagnostics.DiagnosticLog.warn("youtube", "history update failed: ${error.message}", error)
            true
        }
    }

    /** Optional enrichment runs after the player is published, and only when enabled. */
    suspend fun playbackSponsorSegments(videoId: String): List<YouTubeSponsorSegment> {
        if (!appPreferences.youtubeSponsorBlockEnabled) return emptyList()
        val categories = SponsorBlockCategories.requested(appPreferences.youtubeSponsorBlockModes)
        if (categories.isEmpty()) return emptyList()
        return io {
            if (!appPreferences.youtubeSponsorBlockEnabled) emptyList()
            else sponsorSegments(service("getMediaItemService"), videoId, categories)
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
            // Watch-next mostly carries no position (0 or -1); the card's value is kept separately.
            percentWatched = (meta.callInt("getPercentWatched") ?: 0).coerceAtLeast(0),
            isLive = meta.callBool("isLive") ?: false,
            channelId = meta.callString("getChannelId")?.takeIf(String::isNotBlank),
            authorImageUrl = meta.callString("getAuthorImageUrl")?.takeIf(String::isNotBlank),
            subscribed = meta.callBool("isSubscribed") ?: false,
            viewCount = meta.callString("getViewCount")?.takeIf(String::isNotBlank),
            publishedDate = youtubePublicationText(meta.callString("getProductionDate")
                ?: meta.callString("getPublishedDate")),
            publishedAtMs = youtubePublicationEpochMs(meta.callLong("getPublishedDate")
                ?: meta.callString("getPublishedDate")?.toLongOrNull()),
            likeCount = meta.callString("getLikeCount")?.takeIf(String::isNotBlank),
            likeStatus = likeOverrides[videoId] ?: when (meta.callInt("getLikeStatus")) {
                1 -> YouTubeLikeStatus.LIKE
                2 -> YouTubeLikeStatus.DISLIKE
                else -> YouTubeLikeStatus.NONE
            },
            suggestions = mapGroups(meta.call("getSuggestions")),
            chapters = (meta.call("getChapters") as? Iterable<*>)?.mapNotNull { value ->
                val chapter = value ?: return@mapNotNull null
                val start = chapter.callLong("getStartTimeMs") ?: return@mapNotNull null
                com.ambient.tv.domain.model.VideoChapter(start, chapter.callString("getTitle"), chapter.callString("getCardImageUrl"))
            }.orEmpty(),
        )
    }

    private fun sponsorSegments(media: Any, videoId: String, categories: Set<String>): List<YouTubeSponsorSegment> = runCatching {
        val raw = (media.call("getSponsorSegments", videoId, java.util.LinkedHashSet(categories))
            ?: media.call("getSponsorSegments", videoId)) as? Iterable<*> ?: return@runCatching emptyList()
        raw.mapNotNull { segment ->
            segment ?: return@mapNotNull null
            val start = segment.callLong("getStartMs") ?: ((segment.callDouble("getStart") ?: return@mapNotNull null) * 1000).toLong()
            val end = segment.callLong("getEndMs") ?: ((segment.callDouble("getEnd") ?: return@mapNotNull null) * 1000).toLong()
            val category = segment.callString("getCategory")?.takeIf { it in SponsorBlockCategories.ALL } ?: "sponsor"
            if (end > start && category in categories) YouTubeSponsorSegment(start, end, category) else null
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
        requestPlaybackWarmup()
        val rowScope = coroutineContext[RowContinuationScope]?.scope
        val content = service("getContentService")
        onlineServices.request("youtube", isFailure = ::smartTubeServiceRequestFailed) { request ->
            var attempted = false
            var lastFailure: Throwable? = null
            var authFailure: Throwable? = null
            var completedEmpty = false
            for (method in methodNames) {
                val invocation = content.invokeDetailed(method, *args)
                if (!invocation.found) continue
                attempted = true
                if (invocation.error != null) {
                    lastFailure = invocation.error
                    if (smartTubeAuthRejected(invocation.error)) authFailure = invocation.error
                    Timber.w(invocation.error, "SmartTube content method %s failed", method)
                    continue
                }
                val accumulated = linkedMapOf<String, YouTubeRow>()
                // Continuation handles of this load replace those of the previous load of the scope.
                val continuations = rowScope?.let { scope ->
                    java.util.concurrent.ConcurrentHashMap<String, Any>().also { rowContinuations[scope] = it }
                }
                suspend fun publish(raw: Any?) {
                    // A provider emission proves reachability even when filtering leaves no tiles.
                    // Record it before the UI callback and before a later collection limit/error.
                    if (raw != null) onlineServices.succeeded(request)
                    val incoming = mapGroupsWithSource(raw)
                    if (incoming.isEmpty()) return
                    incoming.forEach { (row, group) ->
                        val previous = accumulated[row.title]
                        accumulated[row.title] = row.copy(items = (previous?.items.orEmpty() + row.items).distinctBy { it.videoId })
                        if (continuations != null && group != null) continuations[row.title] = group
                    }
                    onRows(accumulated.values.toList())
                }
                try {
                    val stream = invocation.value?.let(::reactiveContent)
                    if (stream != null) {
                        val progressiveRowsFeed = method.contains("Subscriptions") ||
                            method.contains("SubscribedChannels") ||
                            method.contains("PlaylistRows") ||
                            method.contains("PlaylistsRow")
                        val emissionLimit = if (progressiveRowsFeed) 128 else 16
                        var emissions = 0
                        val finished = stream.collectBoundedSnapshot(
                            timeoutMs = 30_000L,
                            maxEmissions = emissionLimit,
                        ) { emissions++; publish(it) }
                        if (progressiveRowsFeed) {
                            // Subscription and playlist-row observables can emit one row at a time
                            // and may stay open after useful content. Keep a populated snapshot if
                            // the observation window ends instead of discarding already loaded rows.
                            if (!finished && accumulated.isEmpty()) throw IncompleteYouTubeFeedException()
                        } else if (!finished || emissions >= emissionLimit) {
                            throw IncompleteYouTubeFeedException()
                        }
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
                    if (smartTubeAuthRejected(error)) authFailure = error
                    continue
                }
                completedEmpty = true
                if (accumulated.isNotEmpty()) return@request accumulated.values.toList()
                Timber.d("SmartTube content method %s returned no mappable rows", method)
            }
            if (!attempted) {
                Timber.w("SmartTube has none of the requested content methods: %s", methodNames.joinToString())
            }
            // One older upstream variant may finish empty while the next one rejects an expired
            // credential. Never transform that explicit authentication rejection into "no videos".
            authFailure?.let { throw it }
            if (!completedEmpty) throw IncompleteYouTubeFeedException(lastFailure)
            emptyList()
        }
    }

    private suspend fun groups(block: () -> Any?): List<YouTubeRow> = io {
        val raw = unwrapReactive(block())
        val rows = mapGroups(raw)
        if (rows.isEmpty()) {
            Timber.w("SmartTube returned no mappable rows. rawType=%s", raw?.javaClass?.name ?: "null")
        }
        rows
    }

    private fun mapGroups(raw: Any?): List<YouTubeRow> = mapGroupsWithSource(raw).map { it.first }

    /** Rows with the SmartTube MediaGroup they came from (null for flat items), for continuation. */
    private fun mapGroupsWithSource(raw: Any?): List<Pair<YouTubeRow, Any?>> {
        val value = unwrapReactive(raw)
        if (value == null) return emptyList()

        // Some MediaServiceCore revisions wrap rows in a holder object instead of returning
        // List<MediaGroup> directly. Probe the common accessors before treating it as one row.
        val nested = listOf("getMediaGroups", "getGroups", "getRows", "getContent", "getItems")
            .firstNotNullOfOrNull { name -> value.call(name)?.takeIf { it !== value } }
        if (nested != null && nested !is CharSequence) {
            val nestedRows = mapGroupsWithSource(nested)
            if (nestedRows.isNotEmpty()) return nestedRows
        }

        val groups: List<Any?> = when (value) {
            is Iterable<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> listOf(value)
        }

        val rows = mutableListOf<Pair<YouTubeRow, Any?>>()
        for (group in groups) {
            group ?: continue
            val unwrappedGroup = unwrapReactive(group) ?: continue
            val title = unwrappedGroup.callString("getTitle")?.takeIf(String::isNotBlank) ?: "SmartTube"
            val items = mapGroupItems(unwrappedGroup, title)
            if (items.isNotEmpty()) {
                rows += YouTubeRow(title, items) to unwrappedGroup
                continue
            }

            // A few feeds expose a flat collection of MediaItem objects with no MediaGroup wrapper.
            mapItem(unwrappedGroup)?.let { item ->
                rows += YouTubeRow("SmartTube", listOf(item)) to null
            }
        }
        return rows
    }

    /** Playable items and channel tiles of one MediaGroup; other entries without a videoId (playlists, mixes) are counted and logged. */
    private fun mapGroupItems(group: Any, rowTitle: String): List<YouTubeItem> {
        val itemContainer = group.callFirst("getMediaItems", "getItems", "getVideos", "getContent")
        val rawItems = when (val resolved = unwrapReactive(itemContainer)) {
            is Iterable<*> -> resolved.toList()
            is Array<*> -> resolved.toList()
            null -> emptyList()
            else -> listOf(resolved)
        }
        val items = rawItems.mapNotNull(::mapItem)
        val dropped = rawItems.count { it != null } - items.size
        if (dropped > 0 && items.isNotEmpty()) {
            Timber.tag("youtube-rows").i("Row %s: %d of %d items without videoId skipped", rowTitle, dropped, rawItems.size)
        }
        return items
    }

    /** Caption tracks (WebVTT) from the player response; Media3 reads the same ones from the MPD. */
    private fun captions(info: Any): List<YouTubeCaption> = runCatching {
        (info.call("getSubtitles") as? List<*>).orEmpty().mapNotNull { sub ->
            sub ?: return@mapNotNull null
            val url = sub.callString("getBaseUrl")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            YouTubeCaption(url, sub.callString("getLanguageCode"), sub.callString("getName"), sub.callString("getType") == "asr")
        }
    }.onFailure { Timber.w(it, "SmartTube caption list failed") }.getOrDefault(emptyList())

    private val manifestCachePruned = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun generatedDashManifest(info: Any, videoId: String, launchStarted: Long): String? {
        val started = System.nanoTime()
        return runCatching {
            if (info.callBool("containsDashFormats") != true) return@runCatching null
            val stream = info.call("createMpdStream") as? InputStream ?: return@runCatching null
            val xml = stream.bufferedReader().use { it.readText() }.let { text ->
                if (appPreferences.youtubeAutoCaptions) text
                else youtubeMpdWithoutCaptions(text, captions(info).filter { it.autoGenerated }.map { it.url })
            }
            if (!youtubeGeneratedDashIsPlayable(xml)) {
                android.util.Log.w("AmbientYouTube", "generated MPD has no HTTP AdaptationSets for $videoId")
                return@runCatching null
            }
            val dir = context.cacheDir.resolve("youtube_mpd").apply { mkdirs() }
            val safeId = videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            // A late, lazy manifest must not replace a newer resolution of the same video.
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(xml.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
            val file = dir.resolve("$safeId-$hash.mpd")
            if (!file.isFile) {
                val temporary = java.io.File.createTempFile("mpd-", ".tmp", dir)
                try {
                    temporary.writeText(xml)
                    check(temporary.renameTo(file) || file.isFile) { "Could not publish YouTube manifest" }
                } finally {
                    temporary.delete()
                }
            }
            file.setLastModified(System.currentTimeMillis())
            // Signed URLs expire within hours. Prune old cache files away from playback startup.
            if (manifestCachePruned.compareAndSet(false, true)) backgroundScope.launch {
                // Keep recently touched manifests, including any delayed switch from an older
                // session; this is only stale-cache cleanup, never a startup dependency.
                val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff &&
                    (it.extension == "mpd" || it.extension == "tmp") }?.forEach { it.delete() }
            }
            android.net.Uri.fromFile(file).toString()
        }.onFailure { Timber.w(it, "SmartTube generated DASH manifest failed for %s", videoId) }.getOrNull().also {
            com.ambient.tv.ui.player.logPlaybackStartup("youtube", "dash_manifest", launchStarted,
                "success=${it != null} stage_ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

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
        // Subscribed-channel tabs (TV client) carry the uploads group type instead of the channel item type.
        val type = raw.callInt("getType")
        if (type == MEDIA_ITEM_TYPE_CHANNEL || type == MEDIA_GROUP_TYPE_CHANNEL_UPLOADS) return mapChannelItem(raw)
        val videoId = raw.callString("getVideoId")?.takeIf(String::isNotBlank) ?: return null
        val isLive = raw.callBool("isLive") ?: false
        val isUpcoming = raw.callBool("isUpcoming") ?: false
        // A missing/-1 position is not an explicit zero from YouTube. Preserve that
        // distinction in cached cards too, so they cannot clear a known resume point.
        val percentWatched = raw.callInt("getPercentWatched")?.takeIf { it >= 0 }?.coerceAtMost(100) ?: -1
        if (!isLive && !isUpcoming && percentWatched >= 0) {
            YouTubeLocalProgress.observeRemote(videoId, percentWatched)
        }
        raw.callString("getClickTrackingParams")?.takeIf(String::isNotBlank)?.let { params ->
            synchronized(clickTrackingParams) { clickTrackingParams[videoId] = params }
        }
        return YouTubeItem(
            videoId = videoId,
            title = raw.callString("getTitle") ?: "SmartTube",
            subtitle = raw.call("getSecondTitle")?.toString(),
            author = raw.callString("getAuthor"),
            imageUrl = raw.callString("getCardImageUrl")?.takeIf(String::isNotBlank) ?: youtubeThumbnailUrl(videoId),
            backgroundUrl = raw.callString("getBackgroundImageUrl")?.takeIf(String::isNotBlank),
            durationMs = raw.callLong("getDurationMs") ?: 0,
            percentWatched = percentWatched,
            isLive = isLive,
            isUpcoming = isUpcoming,
            scheduledStartMs = youtubeScheduledStartMs(raw.callString("getUpcomingEventStartTime"), null),
            isShorts = raw.callBool("isShorts") ?: false,
            channelId = raw.callString("getChannelId"),
            publishedText = raw.callString("getProductionDate")?.trim()?.takeIf(String::isNotBlank),
            publishedAtMs = raw.callLong("getPublishedDate")?.takeIf { it > 0 }
                ?: youtubePublishedAtMs(raw.callString("getProductionDate")),
        )
    }

    private fun mapChannelItem(raw: Any): YouTubeItem? {
        val channelId = (raw.callString("getChannelId") ?: tabChannelId(raw))?.takeIf(String::isNotBlank) ?: return null
        return YouTubeItem(
            videoId = youtubeChannelKey(channelId),
            title = raw.callString("getTitle") ?: "SmartTube",
            subtitle = raw.call("getSecondTitle")?.toString()?.takeIf(String::isNotBlank),
            imageUrl = raw.callString("getCardImageUrl")?.takeIf(String::isNotBlank),
            backgroundUrl = raw.callString("getBackgroundImageUrl")?.takeIf(String::isNotBlank),
            channelId = channelId,
            hasNewContent = raw.callBool("hasNewContent") ?: false,
        )
    }

    /**
     * SmartTube's TabMediaItem (subscribed channels, TV client) leaves getChannelId unset; its
     * endpoint browses FEsubscriptions with base64 protobuf params that embed the channel id.
     */
    private fun tabChannelId(raw: Any): String? = runCatching {
        val field = raw.javaClass.declaredFields.firstOrNull { it.name == "tabItem" } ?: return null
        field.isAccessible = true
        val browse = field.get(raw)?.call("getEndpoint")?.call("getBrowseEndpoint") ?: return null
        browse.callString("getBrowseId")?.takeIf { it.startsWith("UC") }
            ?: browse.callString("getParams")?.let(::youtubeChannelIdInBrowseParams)
    }.getOrNull()

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
            val started = System.nanoTime()

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
            com.ambient.tv.ui.player.logPlaybackStartup("youtube", "engine_init", started)
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

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        YouTubeLocalProgress.awaitReady()
        block()
    }
}

private data class ReflectionNameKey(val name: String, val arity: Int)
private data class ReflectionMethodKey(
    val name: String,
    val firstType: Class<*>?,
    val secondType: Class<*>?,
    val arity: Int,
    val remainingTypes: List<Class<*>?>,
    val allowArityFallback: Boolean,
)
private data class CachedReflectionMethod(val method: Method?)

/** FIFO on misses only; cache hits do not acquire a monitor or update recency. */
internal class BoundedReflectionCache<K : Any, V : Any>(private val capacity: Int) {
    init { require(capacity > 0) }
    private val values = ConcurrentHashMap<K, V>()
    private val insertionOrder = java.util.ArrayDeque<K>()

    val size: Int get() = values.size

    fun getOrPut(key: K, create: () -> V): V =
        values[key] ?: synchronized(insertionOrder) {
            values[key] ?: create().also { value ->
                if (values.size >= capacity) {
                    values.remove(insertionOrder.removeFirst())
                }
                values[key] = value
                insertionOrder.addLast(key)
            }
        }
}

private const val MAX_REFLECTION_CLASSES = 256
private const val MAX_REFLECTION_SIGNATURES = 256
private class ReflectionClassIndex(type: Class<*>) {
    val methods = type.methods.groupBy { ReflectionNameKey(it.name, it.parameterCount) }
    val zeroArgumentMethods = methods.filterKeys { it.arity == 0 }
        .mapKeys { it.key.name }.mapValues { it.value.first() }
    val resolved = BoundedReflectionCache<ReflectionMethodKey, CachedReflectionMethod>(MAX_REFLECTION_SIGNATURES)
}
private val reflectionIndexes = BoundedReflectionCache<Class<*>, ReflectionClassIndex>(MAX_REFLECTION_CLASSES)

internal fun reflectionClassCacheSize(): Int = reflectionIndexes.size

private fun reflectionIndex(type: Class<*>): ReflectionClassIndex =
    reflectionIndexes.getOrPut(type) { ReflectionClassIndex(type) }

private fun wrap(c: Class<*>) = when (c) {
    Int::class.javaPrimitiveType -> Int::class.javaObjectType
    Long::class.javaPrimitiveType -> Long::class.javaObjectType
    Float::class.javaPrimitiveType -> Float::class.javaObjectType
    Double::class.javaPrimitiveType -> Double::class.javaObjectType
    Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
    else -> c
}

internal fun Any.cachedMethod(name: String, args: Array<out Any?>, allowArityFallback: Boolean): Method? {
    val index = reflectionIndex(javaClass)
    if (args.isEmpty()) return index.zeroArgumentMethods[name]
    val key = ReflectionMethodKey(
        name, args[0]?.javaClass, args.getOrNull(1)?.javaClass, args.size,
        if (args.size <= 2) emptyList() else (2 until args.size).map { args[it]?.javaClass },
        allowArityFallback,
    )
    fun resolve(): CachedReflectionMethod {
        val candidates = index.methods[ReflectionNameKey(name, args.size)].orEmpty()
        val compatible = candidates.firstOrNull { method ->
            val types = method.parameterTypes
            types.indices.all { i -> args[i] == null || wrap(types[i]).isAssignableFrom(args[i]!!.javaClass) }
        }
        return CachedReflectionMethod(compatible ?: if (allowArityFallback) candidates.firstOrNull() else null)
    }
    return index.resolved.getOrPut(key) { resolve() }.method
}

internal fun Any.call(name: String, vararg args: Any?): Any? {
    val method = cachedMethod(name, args, allowArityFallback = true) ?: return null
    return runCatching { method.invoke(this, *args) }
        .onFailure { Timber.d(it, "SmartTube reflection: %s", name) }.getOrNull()
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

internal data class DetailedReflectionInvocation(val found: Boolean, val value: Any?, val error: Throwable?)
internal fun Any.invokeDetailed(name: String, vararg args: Any?): DetailedReflectionInvocation {
    val method = cachedMethod(name, args, allowArityFallback = true)
        ?: return DetailedReflectionInvocation(false, null, null)
    return try {
        DetailedReflectionInvocation(true, method.invoke(this, *args), null)
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t.cause ?: t
        DetailedReflectionInvocation(true, null, cause)
    }
}

internal data class ReflectionInvocation(val invoked: Boolean, val value: Any?)
internal fun Any.invokeCompatible(name: String, vararg args: Any?): ReflectionInvocation {
    val method = cachedMethod(name, args, allowArityFallback = false)
        ?: return ReflectionInvocation(false, null)
    return try { ReflectionInvocation(true, method.invoke(this, *args)) }
    catch (t: Throwable) { Timber.d(t, "SmartTube reflection: %s", name); ReflectionInvocation(false, null) }
}
internal fun Any.hasMethod(name: String, arity: Int): Boolean =
    if (arity == 0) reflectionIndex(javaClass).zeroArgumentMethods.containsKey(name)
    else reflectionIndex(javaClass).methods.containsKey(ReflectionNameKey(name, arity))
private fun Any.hasAnyMethod(vararg names: String): Boolean = names.any { hasMethod(it, 0) }

/** Feed/search key whose loads record row continuation handles in [SmartTubeEngine]. */
private class RowContinuationScope(val scope: String) : kotlin.coroutines.AbstractCoroutineContextElement(RowContinuationScope) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RowContinuationScope>
}

private const val RENEW_TIMEOUT_MS = 20_000L
/** MediaItem.TYPE_CHANNEL */
private const val MEDIA_ITEM_TYPE_CHANNEL = 2
/** MediaGroup.TYPE_CHANNEL_UPLOADS, set as item type on subscribed-channel tabs */
private const val MEDIA_GROUP_TYPE_CHANNEL_UPLOADS = 12

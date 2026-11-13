package com.ambient.tv

import kotlinx.coroutines.launch

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import okio.Path.Companion.toOkioPath
import coil3.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.di.SharedOkHttp
import com.ambient.tv.network.MediaImageAuthInterceptor
import com.ambient.tv.network.MediaArtworkDiskCacheInterceptor
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.ui.UiLocaleContext
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.ui.performance.PerformanceProfileResolver
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/** Loader for full-screen backdrops and hero art (own small memory cache, shared disk cache). */
fun backdropImageLoader(context: Context): coil3.ImageLoader =
    (context.applicationContext as? AmbientTvApplication)?.backdropImageLoader
        ?: coil3.SingletonImageLoader.get(context)

@HiltAndroidApp
class AmbientTvApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(UiLocaleContext.wrap(base))
    }

    @Inject lateinit var hostConnectivity: com.ambient.tv.connectivity.HostConnectivity
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var spotifyRepository: Provider<SpotifyRepository>
    @Inject lateinit var filesPhotoSource: Provider<com.ambient.tv.data.photos.FilesPhotoSource>
    @Inject lateinit var telegramRepository: Provider<com.ambient.tv.data.telegram.TelegramRepository>
    @Inject lateinit var resumeConnectivityMonitor: ResumeConnectivityMonitor
    @Inject lateinit var dlnaRenderer: com.ambient.tv.renderer.DlnaRenderer
    @Inject lateinit var youTubeTvLink: com.ambient.tv.youtube.YouTubeTvLink
    @Inject lateinit var mediaImageAuthInterceptor: MediaImageAuthInterceptor
    @Inject lateinit var mediaArtworkDiskCacheInterceptor: MediaArtworkDiskCacheInterceptor
    @Inject lateinit var performanceProfileResolver: PerformanceProfileResolver
    @Inject @SharedOkHttp lateinit var sharedConnectionPool: ConnectionPool
    @Inject @SharedOkHttp lateinit var sharedDispatcher: Dispatcher

    /**
     * Artwork decoding is CPU work, but it must yield to Compose/RenderThread while the user is
     * moving through a browse view. Keep Coil's two decoder slots on Android background-priority
     * threads instead of the normal-priority Dispatchers.IO pool.
     */
    private val artworkDecoderDispatcher by lazy {
        Executors.newFixedThreadPool(2) { work ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                work.run()
            }, "artwork-decode")
        }.asCoroutineDispatcher()
    }

    /** One OkHttp client for both image loaders; shared with the API pool and dispatcher. */
    private val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .addInterceptor(mediaImageAuthInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Artwork tokens stay on their server even when it redirects elsewhere.
            .let(com.ambient.tv.network.CredentialRedirectGuard::install)
            .build()
    }

    /** One disk cache for both loaders: a backdrop fetched once is a card thumbnail's neighbour on disk. */
    private val imageDiskCache: DiskCache by lazy {
        DiskCache.Builder()
            .directory(cacheDir.resolve("image_cache").toOkioPath())
            .maxSizePercent(if (systemLowRamDevice()) 0.02 else 0.04)
            .build()
    }

    /**
     * Default Coil loader plus Files images (local/SMB/NFS) for the photo viewer.
     * Shares the API [ConnectionPool]/[Dispatcher] so artwork cannot unbounded-parallel
     * with Retrofit. Keeps [MediaImageAuthInterceptor] for JF/Emby/Plex header auth.
     *
     * Its memory cache holds browse cards only. Full-screen backdrops load through
     * [backdropImageLoader]: a few focus moves on Home used to push every visible card out of
     * the shared cache, and the cards then reloaded visibly on the way back.
     */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        buildImageLoader(com.ambient.tv.ui.performance.imageMemoryCacheBudgetBytes(systemLowRamDevice(), totalMemoryBytes(), preferences.imageCacheMb))

    val backdropImageLoader: coil3.ImageLoader by lazy {
        buildImageLoader(com.ambient.tv.ui.performance.backdropMemoryCacheBudgetBytes(systemLowRamDevice(), totalMemoryBytes(), preferences.imageCacheMb))
    }

    private fun buildImageLoader(memoryCacheBytes: Long): coil3.ImageLoader {
        val lowRam = isLowRamDevice()
        return coil3.ImageLoader.Builder(this)
            .apply {
                if (com.ambient.tv.ui.mobile.isTelevision(this@AmbientTvApplication)) {
                    // Visible cards and the ahead window share this budget. A burst of entering
                    // artwork must leave CPU cores available for Compose and RenderThread.
                    decoderCoroutineContext(artworkDecoderDispatcher)
                }
            }
            .memoryCache {
                com.ambient.tv.ui.performance.ForegroundTrimMemoryCache(
                    MemoryCache.Builder()
                        .maxSizeBytes(memoryCacheBytes)
                        .build(),
                ) { lastTrimLevel }
            }
            .diskCache { imageDiskCache }
            .allowRgb565(lowRam || performanceProfileResolver.current().constrainImageDecodeSize)
            .components {
                // Coil 3 has no built-in network loading; the shared client keeps auth headers.
                add(mediaArtworkDiskCacheInterceptor)
                add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
                add(com.ambient.tv.data.photos.FileImageKeyer())
                add(com.ambient.tv.data.photos.FileImageFetcher.Factory { filesPhotoSource.get() })
                // Monogram cards: the post's real thumbnail, loaded only for visible cards.
                add(com.ambient.tv.data.telegram.MonogramThumbFetcher.Factory { telegramRepository.get() })
            }
            .build()
    }

    /** Read by [com.ambient.tv.ui.performance.ForegroundTrimMemoryCache] during Coil's trim callback. */
    @Volatile private var lastTrimLevel: Int = 0

    override fun onTrimMemory(level: Int) {
        // Set before super: Application.onTrimMemory dispatches to Coil's registered callback.
        lastTrimLevel = level
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Timber.i("Memory warning in foreground: level=%d, artwork cache %d/%d KiB", level,
                (coil3.SingletonImageLoader.get(this).memoryCache?.size ?: 0L) / 1024L,
                (coil3.SingletonImageLoader.get(this).memoryCache?.maxSize ?: 0L) / 1024L)
        }
        super.onTrimMemory(level)
    }

    /** Applies the card memory chosen in Settings (0 = automatic) to both running loaders. */
    fun applyImageCacheMb(chosenMb: Int) {
        val lowRam = systemLowRamDevice()
        val total = totalMemoryBytes()
        (coil3.SingletonImageLoader.get(this).memoryCache as? com.ambient.tv.ui.performance.ForegroundTrimMemoryCache)
            ?.resize(com.ambient.tv.ui.performance.imageMemoryCacheBudgetBytes(lowRam, total, chosenMb))
        (backdropImageLoader.memoryCache as? com.ambient.tv.ui.performance.ForegroundTrimMemoryCache)
            ?.resize(com.ambient.tv.ui.performance.backdropMemoryCacheBudgetBytes(lowRam, total, chosenMb))
    }

    /** Automatic card budget of this device, shown next to the choices in Settings. */
    fun automaticImageCacheMb(): Int =
        (com.ambient.tv.ui.performance.imageMemoryCacheBudgetBytes(systemLowRamDevice(), totalMemoryBytes()) / (1024L * 1024L)).toInt()

    private fun systemLowRamDevice(): Boolean =
        (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    private fun totalMemoryBytes(): Long {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        return runCatching { ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem }.getOrDefault(0L)
    }

    /** Decode format and effects only; the cache budgets above do not follow this classification. */
    private fun isLowRamDevice(): Boolean = systemLowRamDevice() || performanceProfileResolver.isLowEndDevice()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Optional file log for release builds; plants its own Timber tree (Settings → Diagnostics).
        com.ambient.tv.diagnostics.DiagnosticLog.init(this, preferences.diagnosticLogEnabled)
        // Written into a crash report; engine and source only, never URLs or titles.
        com.ambient.tv.diagnostics.CrashReports.playbackDescription = {
            val snapshot = com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.value
            val handle = com.ambient.tv.ui.player.BackgroundPlaybackSession.activeHandle()
            "active=${snapshot.active} backend=${snapshot.backend.ifBlank { "none" }} handle=${handle?.javaClass?.simpleName} " +
                "source=${snapshot.metadata?.source} live=${snapshot.metadata?.isLive} video=${snapshot.metadata?.hasVideo} " +
                "playing=${snapshot.playing} positionMs=${snapshot.status.positionMs} replacing=${snapshot.replacingSource} " +
                "error=${snapshot.status.error != null}"
        }
        com.ambient.tv.companion.CompanionShareTargets.syncShareEntry(this)
        hostConnectivity.start()
        resumeConnectivityMonitor.start()
        // Video pauses and then stops, live TV stops when the app leaves the screen.
        com.ambient.tv.ui.player.BackgroundPlaybackGuard.install(this)
        val processStartWallMs = System.currentTimeMillis()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.ambient.tv.livetv.LiveTimeshift.deleteLeftovers(this@AmbientTvApplication, processStartWallMs)
        }
        // Off unless enabled in Settings; follows the app's foreground state.
        dlnaRenderer.attach()
        youTubeTvLink.attach()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.ambient.tv.ui.player.backend.VideoAccelerationRuntime.refresh(this@AmbientTvApplication)
        }
        // Disabled providers must not load native libraries or restore accounts on cold start.
        // YouTube initializes itself on IO on its first actual request.
        if (preferences.spotifyEnabled &&
            com.ambient.tv.ui.mobile.isTelevision(this) &&
            com.ambient.tv.util.LocalNetworkAccess.isGranted(this)) {
            Thread({
                runCatching { spotifyRepository.get().start() }
                    .onFailure { Timber.w(it, "Spotify startup failed") }
            }, "SpotifyInit").start()
        }
    }
}

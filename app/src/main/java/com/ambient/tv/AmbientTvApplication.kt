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
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.ui.UiLocaleContext
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.ui.performance.PerformanceProfileResolver
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class AmbientTvApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(UiLocaleContext.wrap(base))
    }

    @Inject lateinit var hostConnectivity: com.ambient.tv.connectivity.HostConnectivity
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var spotifyRepository: Provider<SpotifyRepository>
    @Inject lateinit var filesPhotoSource: Provider<com.ambient.tv.data.photos.FilesPhotoSource>
    @Inject lateinit var resumeConnectivityMonitor: ResumeConnectivityMonitor
    @Inject lateinit var dlnaRenderer: com.ambient.tv.renderer.DlnaRenderer
    @Inject lateinit var youTubeTvLink: com.ambient.tv.youtube.YouTubeTvLink
    @Inject lateinit var mediaImageAuthInterceptor: MediaImageAuthInterceptor
    @Inject lateinit var performanceProfileResolver: PerformanceProfileResolver
    @Inject @SharedOkHttp lateinit var sharedConnectionPool: ConnectionPool
    @Inject @SharedOkHttp lateinit var sharedDispatcher: Dispatcher

    /**
     * Default Coil loader plus Files images (local/SMB/NFS) for the photo viewer.
     * Shares the API [ConnectionPool]/[Dispatcher] so artwork cannot unbounded-parallel
     * with Retrofit. TV/low-RAM memory + disk caps. Keeps
     * [MediaImageAuthInterceptor] for JF/Emby/Plex header auth.
     */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader {
        val lowRam = isLowRamDevice()
        val imageClient = OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .addInterceptor(mediaImageAuthInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Artwork tokens stay on their server even when it redirects elsewhere.
            .let(com.ambient.tv.network.CredentialRedirectGuard::install)
            .build()
        return coil3.ImageLoader.Builder(this)
            .apply {
                if (com.ambient.tv.ui.mobile.isTelevision(this@AmbientTvApplication)) {
                    // Visible cards and the ahead window share this budget. A burst of entering
                    // artwork must leave CPU cores available for Compose and RenderThread.
                    decoderCoroutineContext(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2))
                }
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this, if (lowRam) 0.12 else 0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizePercent(if (lowRam) 0.02 else 0.04)
                    .build()
            }
            .allowRgb565(lowRam || performanceProfileResolver.current().constrainImageDecodeSize)
            .components {
                // Coil 3 has no built-in network loading; the shared client keeps auth headers.
                add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
                add(com.ambient.tv.data.photos.FileImageKeyer())
                add(com.ambient.tv.data.photos.FileImageFetcher.Factory { filesPhotoSource.get() })
            }
            .build()
    }

    private fun isLowRamDevice(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isLowRamDevice || performanceProfileResolver.isLowEndDevice()
    }

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

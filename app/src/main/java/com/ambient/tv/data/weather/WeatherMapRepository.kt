package com.ambient.tv.data.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.di.SharedOkHttp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

enum class WeatherMapError { NETWORK_DISABLED, OFFLINE, UNAVAILABLE, RATE_LIMITED, ACCESS_DENIED, NO_COVERAGE }

data class WeatherMapImage(
    val bitmap: Bitmap,
    val frame: WeatherMapFrame,
    val fromCache: Boolean,
    val baseMissing: Boolean,
    val coverageMissing: Boolean,
)

data class WeatherMapResult(
    val series: WeatherMapSeries? = null,
    val image: WeatherMapImage? = null,
    val error: WeatherMapError? = null,
)

private class WeatherMapRequestException(val reason: WeatherMapError) : IOException()

/**
 * Small on-demand image client. No SDK, web content, automatic provider changes or tile prefetch.
 * Source metadata and complete rendered frames are keyed by provider and exact map extent.
 */
@Singleton
class WeatherMapRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: AppPreferences,
    @SharedOkHttp pool: ConnectionPool,
) {
    private val folder by lazy { File(context.cacheDir, "weather-maps-v1") }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private var client = OkHttpClient.Builder().connectionPool(pool)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val seriesLock = Mutex()
    private val rateLock = Mutex()
    private var lastRequestElapsed: Long? = null
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val frameLocks = WeatherMapImageLocks()
    private val rawLocks = WeatherMapImageLocks()
    private val rendered = object : LruCache<String, RenderedImage>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: RenderedImage) = value.image.bitmap.byteCount
    }
    private val raw = object : LruCache<String, RawImage>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: RawImage) = value.bitmap.byteCount
    }
    private val optionalRetryAfter = LruCache<String, Long>(128)
    private val rawWrites = AtomicInteger(3) // First successful download also checks an existing disk cache.
    private var optionalTimeoutMillis = 2_500L

    /** Controlled HTTP responses in regression tests; production keeps its standalone client. */
    internal constructor(context: Context, preferences: AppPreferences, client: OkHttpClient,
        optionalTimeoutMillis: Long = 2_500L) : this(context, preferences, client.connectionPool) {
        this.client = client
        this.optionalTimeoutMillis = optionalTimeoutMillis
    }

    suspend fun cached(provider: WeatherMapProvider, viewport: WeatherMapViewport): WeatherMapResult =
        withContext(Dispatchers.IO) { cachedResult(provider, viewport) }

    suspend fun load(provider: WeatherMapProvider, viewport: WeatherMapViewport, networkAllowed: Boolean, force: Boolean = false): WeatherMapResult =
        withContext(Dispatchers.IO) {
            val cached = cachedResult(provider, viewport)
            if (!allowed(networkAllowed)) return@withContext cached.copy(error = WeatherMapError.NETWORK_DISABLED)
            try {
                val series = seriesLock.withLock { loadSeries(provider, networkAllowed, force) }
                if (!viewport.intersects(series.bounds)) return@withContext WeatherMapResult(series, error = WeatherMapError.NO_COVERAGE)
                val frame = series.frames.last()
                val image = loadImage(provider, viewport, frame, networkAllowed, retryOptional = force)
                storeSafely { persistLast(provider, viewport, series, image) }
                WeatherMapResult(series, image)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cached.copy(error = errorFor(e, networkAllowed))
            }
        }

    suspend fun frame(provider: WeatherMapProvider, viewport: WeatherMapViewport, series: WeatherMapSeries, index: Int, networkAllowed: Boolean): WeatherMapResult =
        withContext(Dispatchers.IO) {
            require(provider.id == series.providerId)
            if (!viewport.intersects(series.bounds)) return@withContext WeatherMapResult(series, error = WeatherMapError.NO_COVERAGE)
            val frame = series.frames.getOrNull(index) ?: return@withContext WeatherMapResult(series, error = WeatherMapError.UNAVAILABLE)
            try {
                val image = loadImage(provider, viewport, frame, networkAllowed)
                WeatherMapResult(series, image, if (!allowed(networkAllowed)) WeatherMapError.NETWORK_DISABLED else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeatherMapResult(series, error = errorFor(e, networkAllowed))
            }
        }

    // The server LAN restriction does not apply to this explicitly enabled public weather service.
    private fun allowed(networkAllowed: Boolean) = networkAllowed && preferences.weatherSettings.enabled

    private fun errorFor(error: Exception, networkAllowed: Boolean): WeatherMapError {
        if (!allowed(networkAllowed)) return WeatherMapError.NETWORK_DISABLED
        (error as? WeatherMapRequestException)?.let { return it.reason }
        // Only diagnose the device's internet connection after a request has failed;
        // some working VPNs never acquire Android's validated-internet capability.
        val online = runCatching {
            val network = connectivity?.activeNetwork ?: return@runCatching false
            connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(true)
        return if (!online && error is IOException) WeatherMapError.OFFLINE else WeatherMapError.UNAVAILABLE
    }

    private suspend fun loadSeries(provider: WeatherMapProvider, networkAllowed: Boolean, force: Boolean): WeatherMapSeries {
        val file = File(folder, "series-${provider.id}.json")
        val cached = readJson<WeatherMapSeries>(file)
        val ttl = if (provider.daily) 60 * 60_000L else 5 * 60_000L
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAtMillis in 0 until ttl) return cached
        val now = Instant.now()
        val bytes = get(weatherMapCapabilitiesUrl(provider, now), networkAllowed, 512_000)
        val series = parseWeatherMapSeries(provider, bytes.toString(Charsets.UTF_8), now)
        storeSafely { writeJson(file, series) }
        return series
    }

    private suspend fun loadImage(provider: WeatherMapProvider, viewport: WeatherMapViewport, frame: WeatherMapFrame,
        networkAllowed: Boolean, retryOptional: Boolean = false): WeatherMapImage {
        val composite = compositeFile(provider, viewport, frame)
        return frameLocks.withLock(composite.name) {
            val cached = cachedImage(composite, frame)
            if (cached != null && (!allowed(networkAllowed) ||
                    (!cached.image.baseMissing && !cached.image.coverageMissing) ||
                    (!retryOptional && System.currentTimeMillis() < cached.optionalRetryAtMillis))) {
                return@withLock cached.image.copy(fromCache = true)
            }
            val weatherParts = weatherMapImageParts(provider, viewport, frame)
            val baseParts = weatherBaseMapParts(viewport)
            val coverageUrl = if (provider == WeatherMapProvider.RAINVIEWER)
                weatherMapCoverageUrl(viewport, frame.tileHost) else null
            if (retryOptional) {
                baseParts.forEach { optionalRetryAfter.remove(it.url) }
                coverageUrl?.let(optionalRetryAfter::remove)
            }
            // Independent layers load together. A slow background or coverage mask has a short
            // deadline and cannot hold a usable weather frame behind two full HTTP timeouts.
            val layers = coroutineScope {
                // Normally one required weather image; only a WMS view crossing the dateline
                // needs two bounded strips. The base map uses at most four reusable OSM tiles.
                val weather = weatherParts.map { part -> async { image(part.url, networkAllowed, Long.MAX_VALUE) } }
                val base = baseParts.map { part -> async { optionalImage(part.url, networkAllowed, 7 * 24 * 60 * 60_000L) } }
                val coverage = coverageUrl?.let { url -> async { optionalImage(url, networkAllowed, 24 * 60 * 60_000L) } }
                val weatherBitmaps = weatherParts.zip(weather).map { (part, pending) -> LoadedPart(part, pending.await()) }
                // Give already-running layers one shared grace period after the required image
                // arrives. Slow but healthy layers can use all the time the weather image needed.
                withTimeoutOrNull(optionalTimeoutMillis) { base.forEach { it.await() }; coverage?.await() }
                val baseBitmaps = baseParts.zip(base).map { (part, pending) ->
                    val bitmap = if (pending.isCompleted) pending.await() else {
                        optionalRetryAfter.put(part.url, System.currentTimeMillis() + OPTIONAL_RETRY_MILLIS)
                        pending.cancel()
                        cachedRawImage(part.url)?.bitmap
                    }
                    LoadedPart(part, bitmap)
                }
                val coverageBitmap = if (coverage?.isCompleted == true) coverage.await() else {
                    if (coverage != null) {
                        optionalRetryAfter.put(checkNotNull(coverageUrl), System.currentTimeMillis() + OPTIONAL_RETRY_MILLIS)
                        coverage.cancel()
                    }
                    coverageUrl?.let { cachedRawImage(it)?.bitmap }
                }
                MapLayers(weatherBitmaps, baseBitmaps, coverageBitmap)
            }
            currentCoroutineContext().ensureActive()
            val combined = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combined)
            canvas.drawColor(Color.rgb(36, 43, 51))
            val rect = RectF(0f, 0f, 512f, 512f)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            fun draw(part: LoadedPart) {
                val area = part.part
                part.bitmap?.let { canvas.drawBitmap(it, null,
                    RectF(area.left * 512, area.top * 512, area.right * 512, area.bottom * 512), paint) }
            }
            layers.base.forEach(::draw)
            layers.weather.forEach(::draw)
            // Missing coverage must remain explicit; a transparent radar image alone is not
            // evidence of dry weather outside the service's measured area.
            layers.coverage?.let { canvas.drawBitmap(it, null, rect, Paint().apply { alpha = 160 }) }
            val metadata = CachedImage(layers.base.any { it.bitmap == null }, coverageUrl != null && layers.coverage == null,
                System.currentTimeMillis() + OPTIONAL_RETRY_MILLIS)
            val result = WeatherMapImage(combined, frame, false, metadata.baseMissing, metadata.coverageMissing)
            currentCoroutineContext().ensureActive()
            rendered.put(composite.name, RenderedImage(result, metadata.optionalRetryAtMillis))
            storeSafely {
                atomicWrite(composite) { output -> combined.compress(Bitmap.CompressFormat.PNG, 100, output) }
                writeJson(File(composite.path + ".json"), metadata)
                prune()
            }
            result
        }
    }

    private suspend fun optionalImage(url: String, networkAllowed: Boolean, ttl: Long): Bitmap? =
        rawImage(url, networkAllowed, ttl, optional = true)

    private suspend fun image(url: String, networkAllowed: Boolean, ttl: Long): Bitmap =
        rawImage(url, networkAllowed, ttl, optional = false)
            ?: throw WeatherMapRequestException(WeatherMapError.UNAVAILABLE)

    private suspend fun rawImage(url: String, networkAllowed: Boolean, ttl: Long, optional: Boolean): Bitmap? = rawLocks.withLock(url) {
        val file = File(folder, "raw-${digest(url)}.img")
        val cached = cachedRawImage(url)
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.storedAtMillis in 0 until ttl || !allowed(networkAllowed))) {
            return@withLock cached.bitmap
        }
        if (optional && (!allowed(networkAllowed) || now < (optionalRetryAfter.get(url) ?: 0L))) return@withLock cached?.bitmap
        suspend fun fetch(): Bitmap {
            val bytes = get(url, networkAllowed, 3 * 1024 * 1024)
            val bitmap = decode(bytes) ?: throw WeatherMapRequestException(WeatherMapError.UNAVAILABLE)
            raw.put(url, RawImage(bitmap, System.currentTimeMillis()))
            storeSafely {
                atomicWrite(file) { it.write(bytes) }
                // Even when the required layer repeatedly fails, successful background downloads
                // cannot grow the disk cache indefinitely. Batch scans to avoid one per tile.
                if (rawWrites.incrementAndGet() % 4 == 0) prune()
            }
            return bitmap
        }
        if (!optional) return@withLock fetch()
        val downloaded = try {
            fetch()
        } catch (e: CancellationException) {
            // Hiding the map cancels all its children and never marks a source as unavailable.
            throw e
        } catch (_: Exception) { null }
        if (downloaded != null) {
            optionalRetryAfter.remove(url)
            downloaded
        } else {
            optionalRetryAfter.put(url, System.currentTimeMillis() + OPTIONAL_RETRY_MILLIS)
            cached?.bitmap
        }
    }

    private fun cachedRawImage(url: String): RawImage? {
        raw.get(url)?.let { return it }
        val file = File(folder, "raw-${digest(url)}.img")
        val bitmap = readImage(file) ?: return null
        return RawImage(bitmap, file.lastModified()).also { raw.put(url, it) }
    }

    private fun cachedImage(file: File, frame: WeatherMapFrame): RenderedImage? {
        rendered.get(file.name)?.let { return it }
        val metadata = readJson<CachedImage>(File(file.path + ".json")) ?: return null
        val bitmap = readImage(file) ?: return null
        return RenderedImage(WeatherMapImage(bitmap, frame, true, metadata.baseMissing, metadata.coverageMissing),
            metadata.optionalRetryAtMillis).also { rendered.put(file.name, it) }
    }

    private fun readImage(file: File): Bitmap? = runCatching {
        if (file.length() !in 1..3 * 1024 * 1024L) null else decode(file.readBytes())
    }.getOrNull()

    /** A full or unwritable cache never converts a successfully fetched map into an outage. */
    private inline fun storeSafely(write: () -> Unit) {
        try { write() } catch (_: IOException) { } catch (_: SecurityException) { }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private suspend fun get(url: String, networkAllowed: Boolean, maxBytes: Int): ByteArray {
        if (!allowed(networkAllowed)) throw WeatherMapRequestException(WeatherMapError.NETWORK_DISABLED)
        val request = Request.Builder().url(url)
            .header("User-Agent", "AmbientTV/1.0 (+https://github.com/seinist/ambient-tv)")
            .header("Accept", "image/png,image/jpeg,application/json,text/xml,*/*")
            .build()
        val host = request.url.host
        val rainviewer = host == "rainviewer.com" || host.endsWith(".rainviewer.com")
        val service = if (rainviewer) "rainviewer.com" else host
        if ((retryAfter[service] ?: 0L) > System.currentTimeMillis()) throw WeatherMapRequestException(WeatherMapError.RATE_LIMITED)
        // RainViewer counts API and tile calls together. Other services and the base map do
        // not spend this budget or inherit its delay. Cached animation never enters the gate.
        if (rainviewer) rateLock.withLock {
            lastRequestElapsed?.let { previous ->
                delay((750L - (android.os.SystemClock.elapsedRealtime() - previous)).coerceAtLeast(0))
            }
            lastRequestElapsed = android.os.SystemClock.elapsedRealtime()
        }
        if ((retryAfter[service] ?: 0L) > System.currentTimeMillis()) throw WeatherMapRequestException(WeatherMapError.RATE_LIMITED)
        if (!allowed(networkAllowed)) throw WeatherMapRequestException(WeatherMapError.NETWORK_DISABLED)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!allowed(networkAllowed)) throw WeatherMapRequestException(WeatherMapError.NETWORK_DISABLED)
                            if (it.code == 429) {
                                retryAfter[service] = weatherRetryAfterMillis(it.header("Retry-After"), System.currentTimeMillis())
                                throw WeatherMapRequestException(WeatherMapError.RATE_LIMITED)
                            }
                            if (it.code == 401 || it.code == 403) throw WeatherMapRequestException(WeatherMapError.ACCESS_DENIED)
                            if (!it.isSuccessful) throw WeatherMapRequestException(WeatherMapError.UNAVAILABLE)
                            val body = it.body
                            if (body.contentLength() > maxBytes) throw IOException("Oversize map response")
                            val source = body.source()
                            val buffer = okio.Buffer()
                            while (true) {
                                if (!continuation.isActive || !allowed(networkAllowed)) { call.cancel(); throw IOException("Map request cancelled") }
                                val count = source.read(buffer, 16 * 1024L)
                                if (count < 0) break
                                if (buffer.size > maxBytes) throw IOException("Oversize map response")
                            }
                            buffer.readByteArray()
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun cachedResult(provider: WeatherMapProvider, viewport: WeatherMapViewport): WeatherMapResult {
        val last = readJson<CachedLast>(lastFile(provider, viewport)) ?: return WeatherMapResult()
        if (last.series.providerId != provider.id || last.viewport != viewport) return WeatherMapResult()
        // Cache contents are expendable. Invalid persisted source paths/hosts must never stop
        // a fresh manifest request or make a cache-only read throw into the view model.
        val file = runCatching { compositeFile(provider, viewport, last.frame) }.getOrNull() ?: return WeatherMapResult()
        // Older versions used the timestamp alone. Keep that image available offline, while
        // every new fetch/cache lookup includes the published path and host as well.
        val legacy = File(folder, "map-${provider.id}-${viewport.key}-${last.frame.epochMillis}.png")
        val image = cachedImage(file, last.frame) ?: cachedImage(legacy, last.frame)
        return WeatherMapResult(last.series, image?.image?.copy(fromCache = true))
    }

    private fun persistLast(provider: WeatherMapProvider, viewport: WeatherMapViewport, series: WeatherMapSeries, image: WeatherMapImage) {
        writeJson(lastFile(provider, viewport), CachedLast(viewport, series, image.frame))
    }

    private fun lastFile(provider: WeatherMapProvider, viewport: WeatherMapViewport) = File(folder, "last-${provider.id}-${viewport.key}.json")
    private fun compositeFile(provider: WeatherMapProvider, viewport: WeatherMapViewport, frame: WeatherMapFrame) =
        File(folder, "map-${provider.id}-${viewport.key}-${frame.epochMillis}-${digest(weatherMapImageParts(provider, viewport, frame).joinToString("|") { it.url }).take(16)}.png")

    private inline fun <reified T> readJson(file: File): T? = runCatching {
        if (file.length() > 512_000) return null
        json.decodeFromString<T>(file.readText())
    }.getOrNull()
    private inline fun <reified T> writeJson(file: File, value: T) = atomicWrite(file) { it.write(json.encodeToString(value).toByteArray()) }
    private fun atomicWrite(file: File, block: (java.io.OutputStream) -> Unit) {
        folder.mkdirs()
        val temporary = File.createTempFile("write-", ".tmp", folder)
        try {
            temporary.outputStream().use(block)
            if (!temporary.renameTo(file)) throw IOException("Could not store map cache")
        } finally { temporary.delete() }
    }
    private fun prune() {
        val files = folder.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }.sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= 64L * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class RawImage(val bitmap: Bitmap, val storedAtMillis: Long)
    private data class LoadedPart(val part: WeatherMapImagePart, val bitmap: Bitmap?)
    private data class MapLayers(val weather: List<LoadedPart>, val base: List<LoadedPart>, val coverage: Bitmap?)
    private data class RenderedImage(val image: WeatherMapImage, val optionalRetryAtMillis: Long)
    @Serializable private data class CachedImage(val baseMissing: Boolean, val coverageMissing: Boolean, val optionalRetryAtMillis: Long = 0)
    @Serializable private data class CachedLast(val viewport: WeatherMapViewport, val series: WeatherMapSeries, val frame: WeatherMapFrame)

    private companion object { const val OPTIONAL_RETRY_MILLIS = 60_000L }
}

/** Deduplicate current callers only; retired keys retain neither a lock nor a loading job. */
private class WeatherMapImageLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}

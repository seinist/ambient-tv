package com.ambient.tv.data.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.di.SharedOkHttp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val folder = File(context.cacheDir, "weather-maps-v1")
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().connectionPool(pool)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val seriesLock = Mutex()
    private val rateLock = Mutex()
    private var lastRequestElapsed = 0L
    private val retryAfter = ConcurrentHashMap<String, Long>()

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
                val image = loadImage(provider, viewport, frame, networkAllowed)
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

    private suspend fun loadImage(provider: WeatherMapProvider, viewport: WeatherMapViewport, frame: WeatherMapFrame, networkAllowed: Boolean): WeatherMapImage {
        val composite = compositeFile(provider, viewport, frame)
        val metadata = readJson<CachedImage>(File(composite.path + ".json"))
        if (metadata != null && composite.isFile &&
            (!allowed(networkAllowed) || (!metadata.baseMissing && !metadata.coverageMissing))) readImage(composite)?.let {
            return WeatherMapImage(it, frame, true, metadata.baseMissing, metadata.coverageMissing)
        }
        val weather = image(weatherMapImageUrl(provider, viewport, frame), networkAllowed, Long.MAX_VALUE)
        val base = optionalImage(weatherBaseMapUrl(viewport), networkAllowed, 7 * 24 * 60 * 60_000L)
        val coverage = if (provider == WeatherMapProvider.RAINVIEWER)
            optionalImage(weatherMapCoverageUrl(viewport), networkAllowed, 24 * 60 * 60_000L) else null
        val combined = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.drawColor(Color.rgb(36, 43, 51))
        val rect = Rect(0, 0, 512, 512)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        base?.let { canvas.drawBitmap(it, null, rect, paint) }
        canvas.drawBitmap(weather, null, rect, paint)
        // RainViewer's published mask makes missing radar coverage visibly different from dry weather.
        coverage?.let { canvas.drawBitmap(it, null, rect, Paint().apply { alpha = 160 }) }
        val baseMissing = base == null
        val coverageMissing = provider == WeatherMapProvider.RAINVIEWER && coverage == null
        storeSafely {
            atomicWrite(composite) { output -> combined.compress(Bitmap.CompressFormat.PNG, 100, output) }
            writeJson(File(composite.path + ".json"), CachedImage(baseMissing, coverageMissing))
            prune()
        }
        return WeatherMapImage(combined, frame, false, baseMissing, coverageMissing)
    }

    private suspend fun optionalImage(url: String, networkAllowed: Boolean, ttl: Long): Bitmap? = try {
        image(url, networkAllowed, ttl)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private suspend fun image(url: String, networkAllowed: Boolean, ttl: Long): Bitmap {
        val file = File(folder, "raw-${digest(url)}.img")
        if (file.isFile && (System.currentTimeMillis() - file.lastModified() in 0 until ttl || !allowed(networkAllowed))) {
            readImage(file)?.let { return it }
        }
        val bytes = get(url, networkAllowed, 3 * 1024 * 1024)
        val bitmap = decode(bytes) ?: throw WeatherMapRequestException(WeatherMapError.UNAVAILABLE)
        storeSafely { atomicWrite(file) { it.write(bytes) } }
        return bitmap
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
        if ((retryAfter[host] ?: 0L) > System.currentTimeMillis()) throw WeatherMapRequestException(WeatherMapError.RATE_LIMITED)
        // All sources share this budget. Below RainViewer's 100 requests/IP/minute; cancelled
        // scrubbing also consumes a slot. Cached playback never reaches this gate.
        rateLock.withLock {
            val elapsed = android.os.SystemClock.elapsedRealtime()
            delay((750L - (elapsed - lastRequestElapsed)).coerceAtLeast(0))
            lastRequestElapsed = android.os.SystemClock.elapsedRealtime()
        }
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
                                retryAfter[host] = weatherRetryAfterMillis(it.header("Retry-After"), System.currentTimeMillis())
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
        val file = compositeFile(provider, viewport, last.frame)
        val flags = readJson<CachedImage>(File(file.path + ".json")) ?: return WeatherMapResult(last.series)
        val bitmap = readImage(file) ?: return WeatherMapResult(last.series)
        return WeatherMapResult(last.series, WeatherMapImage(bitmap, last.frame, true, flags.baseMissing, flags.coverageMissing))
    }

    private fun persistLast(provider: WeatherMapProvider, viewport: WeatherMapViewport, series: WeatherMapSeries, image: WeatherMapImage) {
        writeJson(lastFile(provider, viewport), CachedLast(viewport, series, image.frame))
    }

    private fun lastFile(provider: WeatherMapProvider, viewport: WeatherMapViewport) = File(folder, "last-${provider.id}-${viewport.key}.json")
    private fun compositeFile(provider: WeatherMapProvider, viewport: WeatherMapViewport, frame: WeatherMapFrame) =
        File(folder, "map-${provider.id}-${viewport.key}-${frame.epochMillis}.png")

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

    @Serializable private data class CachedImage(val baseMissing: Boolean, val coverageMissing: Boolean)
    @Serializable private data class CachedLast(val viewport: WeatherMapViewport, val series: WeatherMapSeries, val frame: WeatherMapFrame)
}

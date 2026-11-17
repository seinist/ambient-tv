package com.ambient.tv.data.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.RegularPrefs
import com.ambient.tv.di.SharedOkHttp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * One forecast/cache for the page, header and screensaver. Construction and settings changes
 * never read the disk cache or start HTTP; visible consumers refresh explicitly. The provider
 * IDs only configure map consumers.
 */
@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: AppPreferences,
    @RegularPrefs private val regularPreferences: SharedPreferences,
    @SharedOkHttp connectionPool: ConnectionPool,
    @SharedOkHttp dispatcher: Dispatcher,
    private val onlineServices: com.ambient.tv.data.browse.OnlineServiceAvailability? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = Any()
    private val refreshMutex = Mutex()
    private data class ActiveRequest(val generation: Long, val forecast: Boolean)
    private val activeCalls = ConcurrentHashMap<Call, ActiveRequest>()
    private val rateLimitedUntil = AtomicLong(0)
    private val cacheFile by lazy { AtomicFile(File(context.cacheDir, "weather/forecasts-v1.json")) }
    private var cacheLoaded = false // Accessed only under refreshMutex.
    private var cache: Map<String, WeatherSnapshot> = emptyMap() // Accessed under gate.
    private var generation = 0L
    private var lastAttemptKey: String? = null
    private var lastAttemptMillis = 0L
    private val _settings = MutableStateFlow(preferences.weatherSettings)
    val settings: StateFlow<WeatherSettings> = _settings.asStateFlow()
    private val _state = MutableStateFlow(WeatherState(error = accessError(_settings.value)))
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    // A standalone client must never inherit a Plex token, server URL rewrite, cookies or an
    // authentication interceptor. Only the connection pool/dispatcher are shared. The media-server
    // LAN restriction does not apply to this explicitly enabled public online service.
    private var client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            accessError(preferences.weatherSettings)?.let { throw WeatherRequestException(it) }
            chain.proceed(chain.request())
        }.build()

    /** Controlled HTTP responses in repository regression tests; production uses the client above. */
    internal constructor(
        context: Context,
        preferences: AppPreferences,
        regularPreferences: SharedPreferences,
        client: OkHttpClient,
    ) : this(context, preferences, regularPreferences, client.connectionPool, client.dispatcher) {
        this.client = client
    }

    // Keep a strong reference for Android's weak listener registry. This repository is a
    // process singleton; no timer or background weather job is attached to this listener.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "weather_settings") synchronizeSettings()
    }

    init {
        regularPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        synchronizeSettings()
    }

    fun updateSettings(value: WeatherSettings) {
        preferences.weatherSettings = value.normalized()
        synchronizeSettings()
    }

    private fun synchronizeSettings() {
        val (changedGeneration, accessChanged) = synchronized(gate) {
            // Read and apply under one lock: a delayed IO consumer must not restore a preference
            // snapshot from before a completed main-thread update.
            val next = preferences.weatherSettings
            val old = _settings.value
            val oldKey = old.selectedLocation?.let { weatherCacheKey(it, old) }
            val nextKey = next.selectedLocation?.let { weatherCacheKey(it, next) }
            val accessChanged = old.enabled != next.enabled
            val changed = oldKey != nextKey || accessChanged
            _settings.value = next
            if (changed) {
                generation++
                _state.value = WeatherState(
                    data = if (next.enabled) cache[nextKey] ?: _state.value.data?.takeIf { it.matches(next) } else null,
                    error = accessError(next),
                )
            }
            generation.takeIf { changed } to accessChanged
        }
        // A new consumer may already have started the newly selected place by this point.
        // Cancel only requests from an older selection/policy generation.
        if (changedGeneration != null) activeCalls.forEach { (call, request) ->
            // Geocoding depends on its query, not on the forecast's selected place or units.
            if (request.generation < changedGeneration && (accessChanged || request.forecast)) call.cancel()
        }
    }

    suspend fun searchLocations(query: String, language: String): List<WeatherLocation> = withContext(Dispatchers.IO) {
        synchronizeSettings()
        accessError(preferences.weatherSettings)?.let { throw WeatherRequestException(it) }
        val term = query.trim().take(120)
        if (term.length < 2) return@withContext emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", term)
            .addQueryParameter("count", "10")
            .addQueryParameter("language", language.substringBefore('-').substringBefore('_').takeIf { it in setOf("de", "en") } ?: "en")
            .addQueryParameter("format", "json").build()
        try {
            parseWeatherLocations(get(url))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw requestError(error)
        }
    }

    /** The outcome belongs to this call. Superseded and throttled calls return no result. */
    suspend fun refresh(force: Boolean = false): WeatherState? = withContext(Dispatchers.IO) {
        synchronizeSettings()
        refreshMutex.withLock {
            val (selected, token) = synchronized(gate) { _settings.value to generation }
            val place = selected.selectedLocation ?: return@withLock null
            if (!selected.enabled) return@withLock publish(token, WeatherState(error = WeatherError.DISABLED))
            loadCache()
            val key = weatherCacheKey(place, selected)
            val saved = synchronized(gate) { cache[key] ?: _state.value.data?.takeIf { it.matches(selected) } }
            accessError(preferences.weatherSettings)?.let { error ->
                return@withLock publish(token, WeatherState(data = saved.takeIf { selected.enabled }, error = error))
            }
            val now = System.currentTimeMillis()
            if (!force && saved?.isStale(now) == false) {
                return@withLock publish(token, WeatherState(data = saved))
            }
            val retryAt = rateLimitedUntil.get()
            if (now < retryAt) {
                return@withLock publish(token, WeatherState(saved, error = WeatherError.RATE_LIMITED, retryAfterMillis = retryAt))
            }
            // Coalesce simultaneous consumers and repeated Retry clicks, including forced ones.
            if (lastAttemptKey == key && now - lastAttemptMillis in 0 until MIN_REQUEST_INTERVAL_MILLIS) return@withLock null
            lastAttemptKey = key
            lastAttemptMillis = now
            publish(token, WeatherState(saved, loading = true))
            try {
                val snapshot = parseWeatherForecast(get(forecastUrl(place, selected), token), place, selected, System.currentTimeMillis())
                synchronized(gate) {
                    cache = (cache + (key to snapshot)).entries.sortedByDescending { it.value.fetchedAtMillis }
                        .take(MAX_CACHE_ENTRIES).associate { it.key to it.value }
                }
                saveCache()
                onlineServices?.succeeded("weather")
                publish(token, WeatherState(snapshot))
            } catch (cancelled: CancellationException) {
                // Returning to a visible consumer must retry immediately after its predecessor
                // was cancelled, otherwise an empty state can remain until the 30-minute poll.
                lastAttemptKey = null
                lastAttemptMillis = 0L
                publish(token, WeatherState(saved))
                throw cancelled
            } catch (error: Exception) {
                // Preference changes cancel the OkHttp call with IOException, not coroutine
                // cancellation. Such an obsolete attempt must not throttle the new consumer.
                if (synchronized(gate) { generation != token }) {
                    lastAttemptKey = null
                    lastAttemptMillis = 0L
                }
                val failure = requestError(error)
                if (synchronized(gate) { generation == token } &&
                    failure.reason in setOf(WeatherError.OFFLINE, WeatherError.UNAVAILABLE)) {
                    onlineServices?.unavailable("weather")
                }
                publish(token, WeatherState(saved, error = failure.reason, retryAfterMillis = failure.retryAfterMillis))
            }
        }
    }

    private fun publish(token: Long, value: WeatherState): WeatherState? = synchronized(gate) {
        if (generation == token) value.also { _state.value = it } else null
    }

    private suspend fun get(url: HttpUrl, expectedGeneration: Long? = null): String {
        accessError(preferences.weatherSettings)?.let { throw WeatherRequestException(it) }
        val retryAt = rateLimitedUntil.get()
        if (System.currentTimeMillis() < retryAt) throw WeatherRequestException(WeatherError.RATE_LIMITED, retryAt)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", "AmbientTV/1.0 (weather)").build())
            synchronized(gate) {
                if (expectedGeneration != null && expectedGeneration != generation) {
                    continuation.resumeWithException(WeatherRequestException(WeatherError.UNAVAILABLE))
                    return@suspendCancellableCoroutine
                }
                activeCalls[call] = ActiveRequest(generation, forecast = expectedGeneration != null)
            }
            continuation.invokeOnCancellation { call.cancel(); activeCalls.remove(call) }
            call.enqueue(object : Callback {
                private fun fail(call: Call, error: Exception) {
                    if (!continuation.isActive) return
                    // A local policy change can cancel a search even if the settings flow later
                    // conflates disable/re-enable. It is not a provider outage or failed Retry.
                    if (expectedGeneration == null && call.isCanceled()) continuation.cancel(
                        CancellationException("Weather search cancelled by settings change", error),
                    ) else continuation.resumeWithException(error)
                }

                override fun onFailure(call: Call, e: IOException) {
                    activeCalls.remove(call)
                    fail(call, e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (it.code == 429) {
                                val until = weatherRetryAfterMillis(it.header("Retry-After"), System.currentTimeMillis())
                                rateLimitedUntil.updateAndGet { previous -> maxOf(previous, until) }
                                throw WeatherRequestException(WeatherError.RATE_LIMITED, until)
                            }
                            if (!it.isSuccessful) throw WeatherRequestException(WeatherError.UNAVAILABLE)
                            val source = it.body.source()
                            source.request(MAX_RESPONSE_BYTES + 1)
                            if (source.buffer.size > MAX_RESPONSE_BYTES) throw WeatherRequestException(WeatherError.INVALID_DATA)
                            source.readUtf8()
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        fail(call, error)
                    } finally {
                        activeCalls.remove(call)
                    }
                }
            })
        }
    }

    private fun loadCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        val restored = runCatching {
            if (cacheFile.baseFile.length() > MAX_CACHE_BYTES) return@runCatching emptyMap()
            val stored = json.decodeFromString<WeatherDiskCache>(cacheFile.openRead().bufferedReader().use { it.readText() })
            if (stored.version != 1) return@runCatching emptyMap()
            stored.snapshots.filter { snapshot ->
                snapshot.location.normalizedOrNull() != null && snapshot.temperatureUnit in WeatherSettings.TEMPERATURE_UNITS &&
                    snapshot.windUnit in WeatherSettings.WIND_UNITS && snapshot.hourly.size <= 192 && snapshot.daily.size <= 7 &&
                    snapshot.fetchedAtMillis > 0 && runCatching { java.time.ZoneId.of(snapshot.timezone) }.isSuccess
            }.sortedByDescending { it.fetchedAtMillis }.take(MAX_CACHE_ENTRIES).associate { snapshot ->
                weatherCacheKey(snapshot.location, WeatherSettings(temperatureUnit = snapshot.temperatureUnit, windUnit = snapshot.windUnit)) to snapshot
            }
        }.getOrDefault(emptyMap())
        synchronized(gate) { cache = restored + cache }
    }

    private fun saveCache() {
        val text = json.encodeToString(WeatherDiskCache(snapshots = synchronized(gate) { cache.values.toList() }))
        if (text.length > MAX_CACHE_BYTES) return
        var output: java.io.FileOutputStream? = null
        try {
            cacheFile.baseFile.parentFile?.mkdirs()
            output = cacheFile.startWrite()
            output.write(text.toByteArray(Charsets.UTF_8))
            cacheFile.finishWrite(output)
        } catch (_: IOException) {
            output?.let(cacheFile::failWrite)
        }
    }

    private fun requestError(error: Exception): WeatherRequestException {
        accessError(preferences.weatherSettings)?.let { return WeatherRequestException(it) }
        return when (error) {
            is WeatherRequestException -> error
            is UnknownHostException, is ConnectException, is SocketTimeoutException -> WeatherRequestException(WeatherError.OFFLINE)
            is IOException -> WeatherRequestException(WeatherError.UNAVAILABLE)
            else -> WeatherRequestException(WeatherError.INVALID_DATA)
        }
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 10
        private const val MAX_CACHE_BYTES = 2_000_000L
        private const val MAX_RESPONSE_BYTES = 1_000_000L
        private const val MIN_REQUEST_INTERVAL_MILLIS = 15_000L

        private fun accessError(settings: WeatherSettings): WeatherError? =
            WeatherError.DISABLED.takeUnless { settings.enabled }

        private fun forecastUrl(location: WeatherLocation, settings: WeatherSettings): HttpUrl =
            "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", location.latitude.toString())
                .addQueryParameter("longitude", location.longitude.toString())
                .addQueryParameter("timezone", location.timezone)
                .addQueryParameter("timeformat", "unixtime")
                .addQueryParameter("forecast_days", "7")
                .addQueryParameter("temperature_unit", settings.temperatureUnit)
                .addQueryParameter("wind_speed_unit", settings.windUnit)
                .addQueryParameter("precipitation_unit", "mm")
                .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m,wind_direction_10m")
                .addQueryParameter("hourly", "temperature_2m,weather_code,is_day,precipitation_probability,precipitation")
                .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,uv_index_max,sunrise,sunset")
                .build()
    }
}

@Serializable
private data class WeatherDiskCache(val version: Int = 1, val snapshots: List<WeatherSnapshot> = emptyList())

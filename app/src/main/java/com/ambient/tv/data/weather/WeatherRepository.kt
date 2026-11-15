package com.ambient.tv.data.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.RegularPrefs
import com.ambient.tv.di.SharedOkHttp
import com.ambient.tv.network.LanOnlyDns
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * One forecast/cache for the page, header and screensaver. Construction never starts a network
 * request; visible consumers refresh explicitly. The provider IDs only configure map consumers.
 */
@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: AppPreferences,
    @RegularPrefs private val regularPreferences: SharedPreferences,
    @SharedOkHttp connectionPool: ConnectionPool,
    @SharedOkHttp dispatcher: Dispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    private val refreshMutex = Mutex()
    private val activeCalls = ConcurrentHashMap<Call, Long>()
    private val rateLimitedUntil = AtomicLong(0)
    private val cacheFile = AtomicFile(File(context.cacheDir, "weather/forecasts-v1.json"))
    private var cacheLoaded = false // Accessed only under refreshMutex.
    private var cache: Map<String, WeatherSnapshot> = emptyMap() // Accessed under gate.
    private var generation = 0L
    private var localOnly = preferences.localNetworkOnly
    private var lastAttemptKey: String? = null
    private var lastAttemptMillis = 0L
    private val _settings = MutableStateFlow(preferences.weatherSettings)
    val settings: StateFlow<WeatherSettings> = _settings.asStateFlow()
    private val _state = MutableStateFlow(WeatherState(error = accessError(_settings.value, localOnly)))
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    // A standalone client must never inherit a Plex token, server URL rewrite, cookies or an
    // authentication interceptor. Only the connection pool/dispatcher are shared.
    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .dns(LanOnlyDns({ preferences.localNetworkOnly || !preferences.weatherSettings.enabled }))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            accessError(preferences.weatherSettings, preferences.localNetworkOnly)?.let { throw WeatherRequestException(it) }
            chain.proceed(chain.request())
        }.build()

    // Keep a strong reference for Android's weak listener registry. This repository is a
    // process singleton; no timer or background weather job is attached to this listener.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "weather_settings" || key == "local_network_only") synchronizeSettings()
    }

    init {
        regularPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        synchronizeSettings(restoreCache = true)
    }

    fun updateSettings(value: WeatherSettings) {
        preferences.weatherSettings = value.normalized()
        synchronizeSettings()
    }

    private fun synchronizeSettings(restoreCache: Boolean = false) {
        val next = preferences.weatherSettings
        val nextLocalOnly = preferences.localNetworkOnly
        val changedGeneration = synchronized(gate) {
            val old = _settings.value
            val oldKey = old.selectedLocation?.let { weatherCacheKey(it, old) }
            val nextKey = next.selectedLocation?.let { weatherCacheKey(it, next) }
            val changed = oldKey != nextKey || old.enabled != next.enabled || localOnly != nextLocalOnly
            _settings.value = next
            localOnly = nextLocalOnly
            if (changed) {
                generation++
                _state.value = WeatherState(
                    data = if (next.enabled) cache[nextKey] ?: _state.value.data?.takeIf { it.matches(next) } else null,
                    error = accessError(next, nextLocalOnly),
                )
            }
            generation.takeIf { changed }
        }
        // A new consumer may already have started the newly selected place by this point.
        // Cancel only requests from an older selection/policy generation.
        if (changedGeneration != null) activeCalls.forEach { (call, callGeneration) ->
            if (callGeneration < changedGeneration) call.cancel()
        }
        if ((changedGeneration != null || restoreCache) && next.enabled) scope.launch {
            refreshMutex.withLock {
                loadCache()
                synchronized(gate) {
                    val selected = _settings.value
                    val key = selected.selectedLocation?.let { weatherCacheKey(it, selected) }
                    if (selected.enabled && _state.value.data == null && key != null) {
                        _state.value = _state.value.copy(data = cache[key])
                    }
                }
            }
        }
    }

    suspend fun searchLocations(query: String, language: String): List<WeatherLocation> = withContext(Dispatchers.IO) {
        synchronizeSettings()
        accessError(preferences.weatherSettings, preferences.localNetworkOnly)?.let { throw WeatherRequestException(it) }
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

    suspend fun refresh(force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        synchronizeSettings()
        refreshMutex.withLock {
            loadCache()
            val (selected, token) = synchronized(gate) { _settings.value to generation }
            val place = selected.selectedLocation ?: return@withLock
            val key = weatherCacheKey(place, selected)
            val saved = synchronized(gate) { cache[key] ?: _state.value.data?.takeIf { it.matches(selected) } }
            accessError(preferences.weatherSettings, preferences.localNetworkOnly)?.let { error ->
                publish(token, WeatherState(data = saved.takeIf { selected.enabled }, error = error))
                return@withLock
            }
            val now = System.currentTimeMillis()
            if (!force && saved?.isStale(now) == false) {
                publish(token, WeatherState(data = saved))
                return@withLock
            }
            val retryAt = rateLimitedUntil.get()
            if (now < retryAt) {
                publish(token, WeatherState(saved, error = WeatherError.RATE_LIMITED, retryAfterMillis = retryAt))
                return@withLock
            }
            // Coalesce simultaneous consumers and repeated Retry clicks, including forced ones.
            if (lastAttemptKey == key && now - lastAttemptMillis in 0 until MIN_REQUEST_INTERVAL_MILLIS) return@withLock
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
                publish(token, WeatherState(snapshot))
            } catch (cancelled: CancellationException) {
                publish(token, WeatherState(saved))
                throw cancelled
            } catch (error: Exception) {
                val failure = requestError(error)
                publish(token, WeatherState(saved, error = failure.reason, retryAfterMillis = failure.retryAfterMillis))
            }
        }
    }

    private fun publish(token: Long, value: WeatherState) = synchronized(gate) {
        if (generation == token) _state.value = value
    }

    private suspend fun get(url: HttpUrl, expectedGeneration: Long? = null): String {
        accessError(preferences.weatherSettings, preferences.localNetworkOnly)?.let { throw WeatherRequestException(it) }
        val retryAt = rateLimitedUntil.get()
        if (System.currentTimeMillis() < retryAt) throw WeatherRequestException(WeatherError.RATE_LIMITED, retryAt)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", "AmbientTV/1.0 (weather)").build())
            synchronized(gate) {
                if (expectedGeneration != null && expectedGeneration != generation) {
                    continuation.resumeWithException(WeatherRequestException(WeatherError.UNAVAILABLE))
                    return@suspendCancellableCoroutine
                }
                activeCalls[call] = generation
            }
            continuation.invokeOnCancellation { call.cancel(); activeCalls.remove(call) }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCalls.remove(call)
                    if (continuation.isActive) continuation.resumeWithException(e)
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
                        if (continuation.isActive) continuation.resumeWithException(error)
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
        accessError(preferences.weatherSettings, preferences.localNetworkOnly)?.let { return WeatherRequestException(it) }
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

        private fun accessError(settings: WeatherSettings, localOnly: Boolean): WeatherError? = when {
            !settings.enabled -> WeatherError.DISABLED
            localOnly -> WeatherError.LOCAL_ONLY
            else -> null
        }

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

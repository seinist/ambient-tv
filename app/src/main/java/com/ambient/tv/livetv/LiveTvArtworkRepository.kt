package com.ambient.tv.livetv

import android.content.Context
import com.ambient.tv.trailers.TmdbTrailerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Shared, demand-driven landscape art for all Live TV sources and the player.
 *
 * Only composed guide/recording rows or the currently playing programme request lookups. Results
 * (including genuine misses) survive navigation and cold starts. TMDB requests are bounded,
 * cancelled with their requesting screen and never use TV-server authentication headers.
 */
@Singleton
class LiveTvArtworkRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val tmdb: TmdbTrailerRepository,
) {
    private val prefs = context.getSharedPreferences("livetv_tmdb_landscape_v1", Context.MODE_PRIVATE)
    private val lock = Any()
    private val pending = mutableSetOf<String>()
    private val missingUntil = mutableMapOf<String, Long>()
    private val slots = Semaphore(2)
    private var activeLanguage = artworkLanguage()
    private val _titleArt = MutableStateFlow<Map<String, String>>(emptyMap())
    val titleArt: StateFlow<Map<String, String>> = _titleArt.asStateFlow()

    fun requestTitleArt(titles: Collection<String>, scope: CoroutineScope) {
        if (!tmdb.configured) return
        val language = artworkLanguage()
        synchronized(lock) {
            if (activeLanguage != language) {
                activeLanguage = language
                _titleArt.value = emptyMap()
            }
        }
        titles.asSequence().map(String::trim).filter { it.length >= 2 }
            .distinctBy(::liveTvArtKey).take(16).forEach { title ->
                val titleKey = liveTvArtKey(title)
                val key = cacheKey(language, titleKey)
                val scheduled = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    val stillMissing = (missingUntil[key] ?: 0L) > now
                    if (!stillMissing) missingUntil.remove(key)
                    if (stillMissing || titleKey in _titleArt.value) false else pending.add(key)
                }
                if (!scheduled) return@forEach
                scope.launch(Dispatchers.IO) {
                    try {
                        val cached = decodeEntry(prefs.getString(key, null), System.currentTimeMillis())
                        if (cached != null) {
                            if (cached.url == null) synchronized(lock) { missingUntil[key] = cached.expiresAtMillis }
                            else publish(language, titleKey, cached.url)
                            return@launch
                        }
                        slots.withPermit {
                            val result = tmdb.landscapeForProgramme(title, language)
                            // Network/auth/rate-limit failures must remain retryable, never negative-cache them.
                            if (result.isSuccess) {
                                val url = result.getOrNull()
                                val storedAt = System.currentTimeMillis()
                                prefs.edit().putString(key, encodeEntry(storedAt, url)).apply()
                                if (url == null) synchronized(lock) { missingUntil[key] = storedAt + NEGATIVE_TTL_MS }
                                else publish(language, titleKey, url)
                                pruneDisk()
                            }
                        }
                    } finally {
                        synchronized(lock) { pending.remove(key) }
                    }
                }
            }
    }

    private fun publish(language: String, titleKey: String, url: String) = synchronized(lock) {
        // An outstanding request for a previous UI locale must not repaint the new locale's art.
        if (activeLanguage == language) _titleArt.update { it + (titleKey to url) }
    }

    private fun pruneDisk() {
        val entries = prefs.all.filterKeys { it.startsWith("landscape:") }
        if (entries.size <= 400) return
        val editor = prefs.edit()
        entries.entries.sortedBy { (_, value) -> (value as? String)?.substringBefore('|')?.toLongOrNull() ?: 0L }
            .take(entries.size - 300).forEach { (key, _) -> editor.remove(key) }
        editor.apply()
    }

    private fun cacheKey(language: String, titleKey: String) = "landscape:$language:$titleKey"

    companion object {
        internal val POSITIVE_TTL_MS = TimeUnit.DAYS.toMillis(14)
        internal val NEGATIVE_TTL_MS = TimeUnit.HOURS.toMillis(6)

        private fun artworkLanguage(): String = Locale.getDefault().let {
            if (it.language == "de") "de-DE" else it.toLanguageTag().takeIf(String::isNotBlank) ?: "en-US"
        }

        internal data class Cached(val url: String?, val expiresAtMillis: Long)

        internal fun encodeEntry(timestampMillis: Long, url: String?): String = "$timestampMillis|${url.orEmpty()}"

        internal fun decodeEntry(raw: String?, nowMillis: Long): Cached? {
            val value = raw ?: return null
            val split = value.indexOf('|').takeIf { it > 0 } ?: return null
            val timestamp = value.substring(0, split).toLongOrNull() ?: return null
            val url = value.substring(split + 1).ifBlank { null }
            val ttl = if (url == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
            if (timestamp > nowMillis || nowMillis - timestamp > ttl) return null
            return Cached(url, timestamp + ttl)
        }
    }
}

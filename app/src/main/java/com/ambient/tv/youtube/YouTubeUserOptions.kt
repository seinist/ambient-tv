package com.ambient.tv.youtube

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Feed filters chosen in SmartTube settings. [scope] is the row scope of the load
 * ("feed:subscriptions", "feed:shorts", "channel:…", search scopes).
 */
fun youtubeFilterRows(rows: List<YouTubeRow>, scope: String, hideShorts: Boolean, hideLiveInSubscriptions: Boolean): List<YouTubeRow> {
    if (!hideShorts && !hideLiveInSubscriptions) return rows
    return rows.mapNotNull { row ->
        val items = youtubeFilterItems(row.items, scope, hideShorts, hideLiveInSubscriptions)
        if (items.isEmpty() && row.items.isNotEmpty()) null else row.copy(items = items)
    }
}

fun youtubeFilterItems(items: List<YouTubeItem>, scope: String, hideShorts: Boolean, hideLiveInSubscriptions: Boolean): List<YouTubeItem> =
    items.filterNot { item ->
        (hideShorts && item.isShorts && scope != "feed:shorts") ||
            (hideLiveInSubscriptions && scope == "feed:subscriptions" && (item.isLive || item.isUpcoming))
    }

/** DeArrow's community title and thumbnail for one video. */
data class DeArrowBranding(val title: String?, val thumbnailTimeSeconds: Double?)

/**
 * DeArrow (sponsor.ajay.app): crowd-sourced titles and thumbnails instead of clickbait. Only a
 * 4-character hash prefix of the video id is sent (the service's privacy API); results stay in
 * memory for the session.
 */
object DeArrow {
    private val cache = ConcurrentHashMap<String, DeArrowBranding>()
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy {
        okhttp3.OkHttpClient.Builder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    fun cached(videoId: String): DeArrowBranding? = cache[videoId]

    fun thumbnailUrl(videoId: String, timeSeconds: Double): String =
        "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$videoId&time=$timeSeconds"

    suspend fun branding(videoId: String): DeArrowBranding? {
        cache[videoId]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val prefix = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
                    .joinToString("") { "%02x".format(it) }.take(4)
                val request = okhttp3.Request.Builder().url("https://sponsor.ajay.app/api/branding/$prefix").build()
                val body = client.newCall(request).execute().use { if (it.isSuccessful) it.body.string() else null }
                    ?: return@runCatching null
                val all = json.parseToJsonElement(body).jsonObject
                all.forEach { (id, value) -> cache[id] = parse(value.jsonObject) }
                cache.getOrPut(videoId) { DeArrowBranding(null, null) }
            }.getOrNull()
        }
    }

    private fun parse(entry: kotlinx.serialization.json.JsonObject): DeArrowBranding {
        // Trusted entries only: locked or with votes, never the "original" placeholder.
        fun trusted(o: kotlinx.serialization.json.JsonObject) =
            o["locked"]?.jsonPrimitive?.booleanOrNull == true || (o["votes"]?.jsonPrimitive?.intOrNull ?: 0) >= 0
        val title = entry["titles"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["original"]?.jsonPrimitive?.booleanOrNull != true && trusted(it) }
            ?.get("title")?.jsonPrimitive?.contentOrNull
            // DeArrow marks words to keep their case with ">"; drop the marker.
            ?.replace(">", "")?.trim()?.takeIf(String::isNotBlank)
        val thumb = entry["thumbnails"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["original"]?.jsonPrimitive?.booleanOrNull != true && trusted(it) }
            ?.get("timestamp")?.jsonPrimitive?.doubleOrNull
        return DeArrowBranding(title, thumb)
    }
}

/** Removes the subtitle AdaptationSets whose BaseURL is one of [urls] (automatic captions). */
fun youtubeMpdWithoutCaptions(mpd: String, urls: List<String>): String {
    if (urls.isEmpty()) return mpd
    val escaped = urls.flatMap { listOf(it, it.replace("&", "&amp;")) }.toSet()
    return Regex("<AdaptationSet\\b[^>]*>.*?</AdaptationSet>", RegexOption.DOT_MATCHES_ALL).replace(mpd) { set ->
        val block = set.value
        if (block.contains("\"subtitle\"") && escaped.any { block.contains(it) }) "" else block
    }
}

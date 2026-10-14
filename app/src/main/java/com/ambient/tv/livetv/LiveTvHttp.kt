package com.ambient.tv.livetv

import com.ambient.tv.network.LocalNetworkPolicy
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal class LiveTvHttpException(val status: Int) : IOException("Live TV: HTTP $status")

/** Immutable endpoint and credentials: selecting another library server cannot redirect TV calls. */
internal class LiveTvHttp(
    val base: HttpUrl,
    val headers: Map<String, String>,
    private val client: OkHttpClient,
    private val localOnly: () -> Boolean,
) {
    fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val normalized = if (base.encodedPath != "/" && path.startsWith(base.encodedPath)) path.removePrefix(base.encodedPath) else path.trimStart('/')
        val result = requireNotNull(base.resolve(if (normalized.startsWith("http://") || normalized.startsWith("https://") || normalized.startsWith("//")) normalized else "./$normalized"))
        require(sameOrigin(result)) { "Unexpected TV server URL." }
        return result.newBuilder().apply { query.forEach { (k, v) -> if (v != null) setQueryParameter(k, v) } }.build()
    }
    fun sameOrigin(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port
    suspend fun request(path: String, query: Map<String, String?> = emptyMap(), method: String = "GET", body: JsonObject? = null): JsonObject =
        withContext(Dispatchers.IO) {
            check(!localOnly() || LocalNetworkPolicy.isLocalHost(base.host)) { "TV server is blocked by LAN-only mode." }
            val request = Request.Builder().url(url(path, query)).header("Accept", "application/json")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .method(method, if (method in setOf("POST", "PUT")) (body?.toString() ?: "").toRequestBody("application/json".toMediaType()) else null)
                .build()
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resumeWith(runCatching {
                            response.use {
                                if (!it.isSuccessful) throw LiveTvHttpException(it.code)
                                val text = it.body.string().orEmpty()
                                if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
                            }
                        })
                    }
                })
            }
        }
}

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } }
internal fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
internal fun JsonObject.epoch(key: String): Long? = text(key)?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
internal fun segment(value: String): String = HttpUrl.Builder().scheme("http").host("localhost").addPathSegment(value).build().encodedPath.removePrefix("/")

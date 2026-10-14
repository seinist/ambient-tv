package com.ambient.tv.data.browse

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** Small provider-specific display snapshots; callers supply a source/account identity, never a stream. */
@Singleton
class DisplaySnapshots @Inject constructor(@PublishedApi internal val disk: BrowseDiskStore) {
    @PublishedApi internal val json = Json { ignoreUnknownKeys = true }
    private val owners = mutableMapOf<String, String>()

    @Synchronized fun namespace(owner: String, identity: String): String {
        val key = owner + ":" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val old = owners.put(owner, key)
        if (old != null && old != key) disk.remove(old)
        return key
    }
    @Synchronized fun current(owner: String, namespace: String) = owners[owner] == namespace
    @Synchronized fun clear(owner: String) { owners.remove(owner)?.let(disk::remove) }

    suspend inline fun <reified T> read(namespace: String, key: String): T? = withContext(Dispatchers.IO) {
        disk.read(namespace, key)[key]?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
    }

    inline fun <reified T> write(owner: String, namespace: String, key: String, value: T) {
        disk.put(namespace, key, { current(owner, namespace) }) { encodeBrowseJson(json, sanitizeDisplayJson(json.parseToJsonElement(encodeBrowseJson(json, value)))) }
    }
}

@PublishedApi
internal fun sanitizeDisplayJson(value: JsonElement): JsonElement {
    checkBrowseWriteBudget()
    return when (value) {
    is JsonObject -> JsonObject(value.mapValues { (key, field) ->
        if ((key.endsWith("Url") || key == "url") && field is JsonPrimitive && field.isString &&
            (field.content.startsWith("https://") || field.content.startsWith("http://"))) {
            displayArtworkUrl(field.content)?.let(::JsonPrimitive) ?: JsonNull
        } else sanitizeDisplayJson(field)
    })
    is JsonArray -> JsonArray(value.map(::sanitizeDisplayJson))
    else -> value
}
}

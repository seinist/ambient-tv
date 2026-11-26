package com.ambient.tv.tvheadend.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal suspend fun loadTvhEpgPages(
    startEpochSeconds: Long,
    stopEpochSeconds: Long,
    channelId: String?,
    request: suspend (Map<String, String>) -> JsonElement,
): List<JsonObject> {
    val pageSize = 3000
    val filters = buildJsonArray {
        add(buildJsonObject { put("field", "stop"); put("type", "numeric"); put("comparison", "gt"); put("value", startEpochSeconds - 1) })
        add(buildJsonObject { put("field", "start"); put("type", "numeric"); put("comparison", "lt"); put("value", stopEpochSeconds + 1) })
    }
    val rows = linkedMapOf<String, JsonObject>()
    var offset = 0
    var expectedTotal: Int? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        val response = request(buildMap {
            put("start", offset.toString()); put("limit", pageSize.toString())
            put("sort", "start"); put("dir", "ASC"); put("filter", filters.toString())
            channelId?.let { put("channel", it) }
        }).jsonObject
        val page = (response["entries"] as? JsonArray ?: error("TVHeadend EPG response has no entries"))
            .map { it as? JsonObject ?: error("Invalid TVHeadend EPG entry") }
        val before = rows.size
        page.forEach { rows[it["eventId"]?.jsonPrimitive?.content ?: it.toString()] = it }
        check(rows.size - before == page.size) { "TVHeadend EPG pagination duplicated entries." }
        val total = response["totalCount"]?.jsonPrimitive?.intOrNull
        check(total == null || total in 0..150_000) { "Invalid or oversized TVHeadend EPG response." }
        if (offset == 0) expectedTotal = total
        else check(total == expectedTotal) { "TVHeadend EPG changed during pagination; retry." }
        offset += page.size
        check(offset <= 150_000 && (total == null || offset <= total)) { "Invalid TVHeadend EPG page count." }
        check(page.isNotEmpty() || total == null || offset == total) { "TVHeadend EPG pagination ended early." }
        if (total != null && offset == total || total == null && page.size < pageSize) break
    }
    return rows.values.toList()
}

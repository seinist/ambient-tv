package com.ambient.tv.data.browse

import java.io.ByteArrayOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

/** Abort a speculative write at bounded checkpoints; its last complete disk entry stays usable. */
internal class BrowseWritePaused : RuntimeException(null, null, false, false)

@PublishedApi
internal fun checkBrowseWriteBudget() {
    if (BrowseWorkBudget.foreground && !BrowseWorkBudget.idle()) throw BrowseWritePaused()
}

@PublishedApi
internal class BrowseJsonOutput : ByteArrayOutputStream() {
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkBrowseWriteBudget()
        check(count.toLong() + length <= 16L * 1024 * 1024) { "Browse snapshot exceeds disk entry limit" }
        super.write(bytes, offset, length)
    }
    override fun write(value: Int) {
        checkBrowseWriteBudget()
        check(count < 16 * 1024 * 1024) { "Browse snapshot exceeds disk entry limit" }
        super.write(value)
    }
}

/** Stream buffering supplies checkpoints during serialization instead of one monolithic encode. */
inline fun <reified T> encodeBrowseJson(json: Json, value: T): String = encodeBrowseJson(json, serializer<T>(), value)

@OptIn(ExperimentalSerializationApi::class)
fun <T> encodeBrowseJson(json: Json, serializer: SerializationStrategy<T>, value: T): String {
    checkBrowseWriteBudget()
    val output = BrowseJsonOutput()
    json.encodeToStream(serializer, value, output)
    checkBrowseWriteBudget()
    return output.toString(Charsets.UTF_8.name())
}

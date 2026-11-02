package com.ambient.tv.companion

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class CompanionQueueCheckpoint(
    val version: Int = 1,
    val revision: Long = 0,
    val entries: List<CompanionQueueItem> = emptyList(),
    val history: List<CompanionMedia> = emptyList(),
)

/** Only provider references survive a restart; shared URLs can contain expiring credentials. */
internal fun persistableQueueMedia(media: CompanionMedia): Boolean {
    if (media.id.length !in 1..4096 || media.source.length > 64 || media.offsetId.isNotEmpty()) return false
    if (media.mediaIndex != null && media.mediaIndex !in 0..10_000) return false
    if (media.collectionId.isNotEmpty() && !media.collectionId.matches(Regex("[A-Za-z0-9_:-]{1,128}"))) return false
    if (media.collectionSort.length > 128 || media.collectionSort.any { it.isISOControl() }) return false
    return when (media.kind) {
        "youtube" -> media.source.isEmpty() && media.id.matches(Regex("[A-Za-z0-9_-]{11}"))
        "telegram" -> media.source.isEmpty() && parseTelegramCompanionId(media.id) != null
        "library" -> media.source.matches(Regex("[a-f0-9]{64}")) && media.id.matches(Regex("(?:[je]:)?[A-Za-z0-9_-]{1,128}"))
        "file", "dlna" -> media.source.matches(Regex("[a-f0-9]{64}"))
        "live:recording" -> media.source.matches(Regex("[a-f0-9]{64}")) && media.id.length <= 256 && media.id.none { it.isISOControl() }
        else -> false
    }
}

internal fun validateQueueCheckpoint(value: CompanionQueueCheckpoint) {
    require(value.version == 1 && value.revision in 0..Long.MAX_VALUE - 1024)
    require(value.entries.size <= 64 && value.history.size <= 24)
    require(value.entries.map { it.token }.distinct().size == value.entries.size)
    value.entries.forEach {
        require(it.token.matches(Regex("[A-Za-z0-9_-]{1,64}")) && it.title.length <= 160)
        require(it.kind.isEmpty() && it.media?.let(::persistableQueueMedia) == true)
    }
    require(value.history.all(::persistableQueueMedia))
}

internal fun encodeQueueCheckpoint(value: CompanionQueueCheckpoint): ByteArray {
    validateQueueCheckpoint(value)
    return companionJson.encodeToString(value).toByteArray(Charsets.UTF_8).also { require(it.size <= QUEUE_CHECKPOINT_MAX_BYTES) }
}

internal fun decodeQueueCheckpoint(bytes: ByteArray): CompanionQueueCheckpoint {
    require(bytes.size <= QUEUE_CHECKPOINT_MAX_BYTES)
    return companionJson.decodeFromString<CompanionQueueCheckpoint>(bytes.toString(Charsets.UTF_8)).also(::validateQueueCheckpoint)
}

private const val QUEUE_CHECKPOINT_MAX_BYTES = 2 * 1024 * 1024

/** Call on IO. AtomicFile retains the last complete checkpoint if a write is interrupted. */
internal class CompanionQueueStore(file: File) {
    private val atomic = AtomicFile(file)
    private var savedRevision = -1L

    private fun readBytes(): ByteArray = atomic.openRead().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= QUEUE_CHECKPOINT_MAX_BYTES)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    @Synchronized fun load(): CompanionQueueCheckpoint? {
        val bytes = try { readBytes() } catch (failure: java.io.FileNotFoundException) {
            if (!atomic.baseFile.exists()) return null
            throw failure
        }
        return decodeQueueCheckpoint(bytes).also { savedRevision = it.revision }
    }

    @Synchronized fun save(value: CompanionQueueCheckpoint) {
        // An older suspended writer must never resurrect entries removed by a newer command.
        if (value.revision <= savedRevision) return
        val bytes = encodeQueueCheckpoint(value)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
            // Some framework versions only log a failed sync/rename: confirm the durable result.
            check(readBytes().contentEquals(bytes)) { "Queue checkpoint write was not committed" }
            savedRevision = value.revision
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }
}

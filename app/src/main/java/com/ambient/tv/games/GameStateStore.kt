package com.ambient.tv.games

import android.graphics.Bitmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** How a save state came about. Stored by name. */
enum class GameStateKind {
    /** Written when leaving the game; the next start continues from it. */
    AUTO,
    /** The moment before a state was loaded, so loading can be undone. */
    UNDO,
    /** Saved by the user. */
    MANUAL,
}

/**
 * One save state: the core's serialized state, a screenshot and when/with which core it was made.
 * [coreStamp] identifies the core build; another build may not read the state.
 */
@Serializable
data class GameStateEntry(
    val id: String,
    val kind: GameStateKind,
    val createdAt: Long,
    val coreId: String,
    val coreStamp: String,
    val number: Int = 0,
)

/**
 * Save states on this device, per game: `<id>.state`, `<id>.jpg` (screenshot) and `<id>.json`.
 * The layout keeps one directory per game so a server (RomM) can later mirror it.
 */
@Singleton
class GameStateStore @Inject constructor(private val storage: GameStorage) {
    private val json = Json { ignoreUnknownKeys = true }

    fun list(game: PreparedGame): List<GameStateEntry> {
        val directory = storage.statesDirectory(game)
        return directory.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
            .mapNotNull { meta ->
                runCatching { json.decodeFromString<GameStateEntry>(meta.readText()) }.getOrNull()
                    ?.takeIf { it.id.matches(Regex("(auto|undo|m[0-9]+)")) && meta.name == "${it.id}.json" }
                    ?.takeIf { File(directory, "${it.id}.state").isFile }
            }
            .sortedWith(compareBy<GameStateEntry> { it.kind.ordinal }.thenByDescending { it.createdAt })
    }

    fun latestManual(game: PreparedGame): GameStateEntry? =
        list(game).filter { it.kind == GameStateKind.MANUAL }.maxByOrNull { it.createdAt }

    fun entry(game: PreparedGame, kind: GameStateKind): GameStateEntry? = list(game).firstOrNull { it.kind == kind }

    fun read(game: PreparedGame, entry: GameStateEntry): ByteArray? {
        val state = File(storage.statesDirectory(game), "${entry.id}.state")
        // Old states are untrusted local files. Never allocate an arbitrarily large ByteArray.
        if (!state.isFile || state.length() !in 1..MAX_STATE_BYTES) return null
        return storage.read(state)
    }

    fun screenshot(game: PreparedGame, entry: GameStateEntry): File? =
        File(storage.statesDirectory(game), "${entry.id}.jpg").takeIf { it.isFile }

    /** Saves [bytes]; AUTO and UNDO replace their previous state, MANUAL adds a numbered one. */
    fun save(
        game: PreparedGame,
        kind: GameStateKind,
        bytes: ByteArray,
        screenshot: Bitmap?,
        coreStamp: String,
        now: Long = System.currentTimeMillis(),
        coreId: String = game.system.core.id,
    ): GameStateEntry {
        require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_STATE_BYTES) {
            "Save state exceeds the memory safety limit"
        }
        val directory = storage.statesDirectory(game)
        val existing = list(game)
        val entry = when (kind) {
            GameStateKind.MANUAL -> GameStateEntry(
                id = "m$now", kind = kind, createdAt = now, coreId = coreId, coreStamp = coreStamp,
                number = (existing.filter { it.kind == GameStateKind.MANUAL }.maxOfOrNull { it.number } ?: 0) + 1,
            )
            else -> GameStateEntry(kind.name.lowercase(), kind, now, coreId, coreStamp)
        }
        storage.write(File(directory, "${entry.id}.state"), bytes)
        val image = File(directory, "${entry.id}.jpg")
        if (screenshot != null) runCatching {
            image.outputStream().use { screenshot.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } else image.delete()
        storage.write(File(directory, "${entry.id}.json"), json.encodeToString(GameStateEntry.serializer(), entry).toByteArray())
        // Oldest manual states go once there are too many.
        existing.filter { it.kind == GameStateKind.MANUAL }.sortedByDescending { it.createdAt }
            .drop(MAX_MANUAL - 1).takeIf { kind == GameStateKind.MANUAL }?.forEach { delete(game, it) }
        return entry
    }

    fun delete(game: PreparedGame, entry: GameStateEntry) {
        val directory = storage.statesDirectory(game)
        listOf("state", "jpg", "json").forEach { File(directory, "${entry.id}.$it").delete() }
    }

    companion object {
        const val MAX_MANUAL = 50
        /** Includes native serialization plus the JVM buffer; keep memory use safe on Android TV. */
        const val MAX_STATE_BYTES = 64L * 1024 * 1024

        /** Identifies a core build by size and date of its file. */
        fun legacyCoreStamp(core: File): String = "${core.length()}:${core.lastModified()}"

        /** Stable across devices and installations of the same binary, unlike file mtime. */
        fun coreStamp(core: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            core.inputStream().buffered(256 * 1024).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Old timestamp-based saves migrate only when they match the installed core. */
        fun compatible(entry: GameStateEntry, coreId: String, stamp: String, legacyStamp: String): Boolean =
            entry.coreId == coreId && (entry.coreStamp == stamp || entry.coreStamp == legacyStamp)
    }
}

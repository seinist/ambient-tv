package com.ambient.tv.games

import android.graphics.Bitmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
                    ?.takeIf { File(directory, "${it.id}.state").isFile }
            }
            .sortedWith(compareBy<GameStateEntry> { it.kind.ordinal }.thenByDescending { it.createdAt })
    }

    fun latestManual(game: PreparedGame): GameStateEntry? =
        list(game).filter { it.kind == GameStateKind.MANUAL }.maxByOrNull { it.createdAt }

    fun entry(game: PreparedGame, kind: GameStateKind): GameStateEntry? = list(game).firstOrNull { it.kind == kind }

    fun read(game: PreparedGame, entry: GameStateEntry): ByteArray? =
        storage.read(File(storage.statesDirectory(game), "${entry.id}.state"))

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
        File(directory, "${entry.id}.json").writeText(json.encodeToString(GameStateEntry.serializer(), entry))
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

        /** Identifies a core build by size and date of its file. */
        fun coreStamp(core: File): String = "${core.length()}:${core.lastModified()}"
    }
}

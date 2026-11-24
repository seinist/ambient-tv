package com.ambient.tv.romm

import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * File names of Ambient TV's save states on a RomM server. RomM keeps a state's bytes, screenshot,
 * emulator and dates; the rest of a state's identity (kind, number, label, the core build it needs)
 * travels in its file name, so every RomM version from 3.x on keeps it without a schema of its own:
 * `ambient-<id>.<base64url metadata>.state`. Other clients' states do not match and are left alone.
 */
internal object RommStateNames {
    private const val PREFIX = "ambient-"
    private const val SUFFIX = ".state"
    /** RomM (and most file systems) allow 255 bytes per file name. */
    private const val MAX_NAME_BYTES = 255
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val pattern = Regex("""^ambient-(auto|undo|m[0-9]{1,19})\.([A-Za-z0-9_-]{8,240})\.state$""")

    @Serializable
    private data class Meta(
        /** Kind name (AUTO, UNDO, MANUAL). */
        val k: String,
        /** Created at, epoch milliseconds. */
        val t: Long,
        /** Core id. */
        val c: String,
        /** First 32 hex digits of the core build's SHA-256 (or the legacy stamp). */
        val s: String,
        val n: Int = 0,
        val l: String? = null,
    )

    fun fileName(entry: GameStateEntry): String {
        var label = entry.label
        while (true) {
            val meta = Meta(entry.kind.name, entry.createdAt, entry.coreId, compactStamp(entry.coreStamp), entry.number, label)
            val encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(Meta.serializer(), meta).toByteArray(Charsets.UTF_8))
            val name = "$PREFIX${entry.id}.$encoded$SUFFIX"
            if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES || label.isNullOrEmpty()) return name
            // A long label is shortened on the server; the state itself is never dropped.
            label = label.dropLast(maxOf(1, label.length / 8)).ifEmpty { null }
        }
    }

    /**
     * The state a server file name describes, or null for any other file. [currentStamp] is the
     * installed core's stamp: a state made by the same build gets it back, so it stays loadable.
     */
    fun parse(fileName: String, currentStamp: String): GameStateEntry? {
        val match = pattern.matchEntire(fileName) ?: return null
        val id = match.groupValues[1]
        val meta = runCatching {
            json.decodeFromString(Meta.serializer(), String(Base64.getUrlDecoder().decode(match.groupValues[2]), Charsets.UTF_8))
        }.getOrNull() ?: return null
        val kind = runCatching { GameStateKind.valueOf(meta.k) }.getOrNull() ?: return null
        val idKind = when {
            id == "auto" -> GameStateKind.AUTO
            id == "undo" -> GameStateKind.UNDO
            else -> GameStateKind.MANUAL
        }
        if (kind != idKind || meta.c.isBlank() || meta.s.isBlank()) return null
        return GameStateEntry(
            id = id,
            kind = kind,
            createdAt = meta.t,
            coreId = meta.c,
            coreStamp = if (compactStamp(currentStamp) == meta.s) currentStamp else "romm:${meta.s}",
            number = meta.n.coerceAtLeast(0),
            label = meta.l?.take(com.ambient.tv.games.GameStateStore.MAX_LABEL)?.takeIf(String::isNotBlank),
        )
    }

    internal fun compactStamp(stamp: String): String =
        if (stamp.startsWith("sha256:")) stamp.removePrefix("sha256:").take(32)
        else stamp.filter { it.isLetterOrDigit() }.take(32).ifEmpty { "unknown" }
}

package com.ambient.tv.games.library

import java.util.Locale

/** A local catalog request. A null limit deliberately keeps every matching library item. */
data class GameLibraryQuery(
    val section: String = "all",
    val search: String = "",
    val sort: String = "name",
    val descending: Boolean = false,
    val limit: Int? = null,
    val fileId: String? = null,
    /** Folder membership includes overlapping assignments, independently of display deduplication. */
    val folderId: String? = null,
    val afterFileId: String? = null,
)

/** Only catalog-affecting values; unrelated playback/settings preference writes compare equal. */
internal data class GameLibraryRevision(
    val catalog: Long,
    val folders: String,
    val overrides: String,
    val sources: String,
    val sourceScopes: String = "",
)

internal data class GameLibrarySql(val sql: String, val args: Array<String>)

/**
 * Android SQLite's NOCASE only folds ASCII. Persist Java's single-code-point case fold instead,
 * including final sigma, dotless i and supplementary letters, for literal substring searches.
 * This is not full Unicode case folding (which would turn ß into ss and change contains()).
 */
internal fun gameSearchKey(value: String): String = buildString(value.length) {
    var at = 0
    while (at < value.length) {
        val point = value.codePointAt(at)
        appendCodePoint(Character.toLowerCase(Character.toUpperCase(point)))
        at += Character.charCount(point)
    }
}

/** The same bucket drives title ordering, the visible index and all alphabet jumps. */
internal fun gameIndexLetter(title: String): Char {
    val initial = title.trimStart().firstOrNull()
    return initial?.takeIf { it.isLetter() }?.uppercaseChar() ?: '#'
}

/** Group digits/symbols before letters, preserving ROOT lowercase UTF-16 order within each group. */
internal fun gameSortKey(value: String): ByteArray {
    val lower = value.lowercase(Locale.ROOT)
    return ByteArray(1 + lower.length * 2).also { bytes ->
        bytes[0] = if (gameIndexLetter(value) == '#') 0 else 1
        lower.forEachIndexed { index, character ->
            bytes[1 + index * 2] = (character.code ushr 8).toByte()
            bytes[2 + index * 2] = character.code.toByte()
        }
    }
}

internal fun gameDisplayFileName(filename: String): String =
    filename.substringBeforeLast('.', filename)
        .replace('_', ' ').replace(Regex("\\s+"), " ").trim()

internal fun gameQueryDigest(values: Iterable<String>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
    val hex = "0123456789abcdef"
    return buildString(64) {
        digest.digest().forEach { byte ->
            val unsigned = byte.toInt() and 255
            append(hex[unsigned ushr 4]); append(hex[unsigned and 15])
        }
    }
}

internal fun gameLibrarySql(query: GameLibraryQuery, titleStyle: String): GameLibrarySql {
    val style = when (titleStyle) { "internal", "filename" -> titleStyle; else -> "auto" }
    val predicates = mutableListOf<String>()
    val args = mutableListOf<String>()
    when (query.section) {
        "all" -> Unit
        "favorites" -> predicates += "q.favorite=1"
        "recent" -> predicates += "q.last_played!=0"
        else -> { predicates += "q.system_id=?"; args += query.section }
    }
    query.fileId?.let { predicates += "q.file_id=?"; args += it }
    query.folderId?.let {
        predicates += "EXISTS(SELECT 1 FROM game_entries member WHERE member.folder_id=? AND member.file_id=q.file_id)"
        args += it
    }
    query.afterFileId?.let { predicates += "q.file_id>?"; args += it }
    if (query.search.isNotBlank()) {
        predicates += "(instr(q.search_$style,?)>0 OR instr(q.search_filename_raw,?)>0)"
        val needle = gameSearchKey(query.search)
        args += needle
        args += needle
    }
    val reversed = query.descending && query.section != "recent"
    val direction = if (reversed) "DESC" else "ASC"
    val recentDirection = if (reversed) "ASC" else "DESC"
    val order = when {
        query.folderId != null -> "q.file_id ASC"
        query.section == "recent" -> "q.last_played DESC,q.folder_depth DESC,q.file_id ASC"
        query.sort == "recent" -> "q.last_played $recentDirection,q.sort_$style $direction,q.folder_depth $recentDirection,q.file_id $direction"
        query.sort == "system" -> "q.sort_system $direction,q.sort_$style $direction,q.file_id $direction"
        else -> "q.sort_$style $direction,q.file_id $direction"
    }
    val where = if (predicates.isEmpty()) "" else "WHERE " + predicates.joinToString(" AND ")
    val limit = query.limit?.let { " LIMIT ${it.coerceIn(0, 200_000)}" }.orEmpty()
    // The index has exactly one eligible/deepest folder per file. Only matching display rows
    // reach the cursor. DAT revision invalidates metadata misses; CRC/parser fields stay scan-only.
    return GameLibrarySql("""SELECT g.folder_id,g.file_id,g.source_id,g.path,g.filename,g.system_id,
        q.title_$style,g.internal_title,g.file_size,g.modified,q.favorite,q.last_played,
        q.custom_title,g.verified_title,q.metadata_title,q.metadata_year,q.cover_path,q.backdrop_path,
        q.cover_width,q.cover_height,q.metadata_region,q.metadata_platform,g.dat_version
        FROM game_query q JOIN game_entries g ON g.folder_id=q.folder_id AND g.file_id=q.file_id
        $where ORDER BY $order$limit""".trimIndent(), args.toTypedArray())
}

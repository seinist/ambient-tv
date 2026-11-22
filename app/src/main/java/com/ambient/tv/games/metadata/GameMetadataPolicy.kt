package com.ambient.tv.games.metadata

import com.ambient.tv.games.library.LibraryGame
import java.security.MessageDigest

/** A user rename is presentation, never a request to identify a different ROM. */
internal fun gameMetadataQuery(
    game: LibraryGame,
    existing: GameMetadata?,
    language: String,
    region: String,
    crc32: String?,
    searchText: String? = null,
): GameMetadataQuery {
    val explicit = searchText?.trim()?.takeIf(String::isNotEmpty)
    val canonical = existing?.canonicalTitle?.takeIf(String::isNotBlank)
        ?: game.verifiedTitle?.takeIf(String::isNotBlank)
        ?: game.metadataTitle?.takeIf(String::isNotBlank)
    val filename = game.filename.substringBeforeLast('.').replace('_', ' ').trim()
    return GameMetadataQuery(
        systemId = game.systemId,
        title = explicit ?: canonical ?: filename,
        filename = game.filename,
        size = game.size,
        crc32 = if (explicit == null) crc32 else null,
        language = language,
        region = region,
        canonicalTitle = if (explicit == null) canonical else null,
        providerGameId = if (explicit == null) existing?.providerGameId?.takeIf(String::isNotBlank) else null,
    )
}

/** Includes file version and locale so a previous miss cannot suppress a new ROM/region. */
internal fun gameMetadataFingerprint(game: LibraryGame, query: GameMetadataQuery): String =
    gameMetadataDigest(listOf(game.fileId, game.modified.toString(), query.systemId,
        query.size.toString(), query.title, query.filename, query.crc32.orEmpty(), query.serial.orEmpty(),
        query.language, query.region, query.canonicalTitle.orEmpty(), query.providerGameId.orEmpty()).joinToString("\u0000"))

internal fun gameMetadataDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** The same field locks apply to a manual rematch and to background updates. */
internal fun preserveGameMetadataLocks(existing: GameMetadata?, incoming: GameMetadata): GameMetadata {
    if (existing == null) return incoming.bounded()
    val locks = existing.lockedFields
    fun keep(field: String) = field in locks
    val artProviders = incoming.artworkProviders.toMutableMap()
    val artSources = incoming.artworkSources.toMutableMap()
    for (kind in GameArtworkKind.entries) {
        if (!keep(gameArtworkLock(kind))) continue
        existing.artworkProviders[kind.key]?.let { artProviders[kind.key] = it }
            ?: artProviders.remove(kind.key)
        existing.artworkSources[kind.key]?.let { artSources[kind.key] = it }
            ?: artSources.remove(kind.key)
    }
    return incoming.copy(
        title = if (keep("title")) existing.title else incoming.title,
        summary = if (keep("summary")) existing.summary else incoming.summary,
        year = if (keep("year")) existing.year else incoming.year,
        genres = if (keep("genres")) existing.genres else incoming.genres,
        developer = if (keep("developer")) existing.developer else incoming.developer,
        publisher = if (keep("publisher")) existing.publisher else incoming.publisher,
        players = if (keep("players")) existing.players else incoming.players,
        region = if (keep("region")) existing.region else incoming.region,
        coverUrl = if (keep("cover")) existing.coverUrl else incoming.coverUrl,
        backdropUrl = if (keep("backdrop")) existing.backdropUrl else incoming.backdropUrl,
        titleScreenUrl = if (keep("title_screen")) existing.titleScreenUrl else incoming.titleScreenUrl,
        artworkProviders = artProviders, artworkSources = artSources,
        lockedFields = locks,
    ).bounded()
}

internal fun gameArtworkLock(kind: GameArtworkKind): String = when (kind) {
    GameArtworkKind.COVER -> "cover"
    GameArtworkKind.BACKDROP -> "backdrop"
    GameArtworkKind.TITLE -> "title_screen"
}

internal fun GameMetadata.withArtwork(
    kind: GameArtworkKind, reference: String?, providerId: String?, source: String?,
): GameMetadata {
    val changed = when (kind) {
        GameArtworkKind.COVER -> copy(coverUrl = reference)
        GameArtworkKind.BACKDROP -> copy(backdropUrl = reference)
        GameArtworkKind.TITLE -> copy(titleScreenUrl = reference)
    }
    return changed.copy(
        artworkProviders = if (providerId == null) artworkProviders - kind.key
            else artworkProviders + (kind.key to providerId),
        artworkSources = if (source == null) artworkSources - kind.key else artworkSources + (kind.key to source),
    )
}

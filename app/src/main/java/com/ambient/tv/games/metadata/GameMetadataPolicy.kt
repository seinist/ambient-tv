package com.ambient.tv.games.metadata

import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.library.gameDatSearchKey
import com.ambient.tv.games.library.gameDisplayTitle
import com.ambient.tv.games.library.gameTitleRegionLabel
import com.ambient.tv.games.library.gameTitleLanguageLabel
import java.security.MessageDigest

/** A user rename is presentation, never a request to identify a different ROM. */
internal fun gameMetadataQuery(
    game: LibraryGame,
    existing: GameMetadata?,
    language: String,
    region: String,
    crc32: String?,
    searchText: String? = null,
    provider: String? = null,
): GameMetadataQuery {
    val explicit = searchText?.trim()?.takeIf(String::isNotEmpty)
    val matched = existing?.takeIf { provider == null || it.provider == provider }
    // Newly loaded DAT names are the identity used by Libretro thumbnails. An earlier
    // guessed title or another provider's display name must not hide that correction.
    val verified = game.verifiedTitle?.takeIf(String::isNotBlank)
    val canonical = verified?.takeIf { provider == GameMetadataProviderIds.LIBRETRO && matched?.matchLocked != true }
        ?: matched?.canonicalTitle?.takeIf(String::isNotBlank)
        ?: verified
        ?: game.metadataTitle?.takeIf(String::isNotBlank)
    val filename = gameCatalogFilename(game.systemId, game.filename)
    return GameMetadataQuery(
        systemId = game.systemId,
        title = explicit ?: canonical ?: filename,
        filename = game.filename,
        size = game.size,
        crc32 = if (explicit == null) crc32 else null,
        language = language,
        region = region,
        canonicalTitle = if (explicit == null) canonical else null,
        providerGameId = if (explicit == null &&
            !(provider == GameMetadataProviderIds.LIBRETRO && matched?.matchLocked != true && verified != null))
            matched?.providerGameId?.takeIf(String::isNotBlank) else null,
    )
}

/** Remove known DS release numbering, not genuine numeric names on other platforms. */
internal fun gameCatalogFilename(systemId: String, filename: String): String {
    val stem = filename.substringAfterLast('/').substringBeforeLast('.')
    return (if (systemId == "nds") stem.replace(Regex("""^\d{4,5}\s*[-_.]\s*"""), "") else stem)
        .replace('_', ' ').trim()
}

/** Filename/catalog agreement is not a checksum match. Restrict fallback to exact normalized
 * names, preserving explicit region/language tags and never selecting a partial title. */
internal fun matchingGameCatalogTitles(hint: String, candidates: List<String>): List<String> {
    val exact = gameDatSearchKey(hint)
    if (exact.isEmpty()) return emptyList()
    val full = candidates.filter { gameDatSearchKey(it) == exact }.distinct()
    if (full.isNotEmpty()) return full
    if (gameTitleRegionLabel(hint) != null || gameTitleLanguageLabel(hint) != null) return emptyList()
    return candidates.filter { gameDatSearchKey(gameDisplayTitle(it)) == exact }.distinct()
}

/** Includes file version and locale so a previous miss cannot suppress a new ROM/region. */
internal fun gameMetadataFingerprint(game: LibraryGame, query: GameMetadataQuery): String =
    gameMetadataDigest(listOf(game.fileId, game.modified.toString(), query.systemId,
        game.datVersion.toString(), game.verifiedTitle.orEmpty(), game.internalTitle.orEmpty(),
        query.size.toString(), query.title, query.filename, query.crc32.orEmpty(), query.serial.orEmpty(),
        query.language, query.region, query.canonicalTitle.orEmpty(), query.providerGameId.orEmpty()).joinToString("\u0000"))

internal fun gameMetadataDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Old primary-only Libretro misses must not hide images now available from its mirror. */
internal fun gameMetadataLookupFingerprint(provider: String, fingerprint: String): String =
    if (provider == GameMetadataProviderIds.LIBRETRO) gameMetadataDigest("libretro-thumbnails:v3:$fingerprint")
    else fingerprint

/** A cached success for a query is usable only while its saved identity still answers it. */
internal fun gameMetadataMatchesQueryIdentity(metadata: GameMetadata, query: GameMetadataQuery): Boolean =
    query.canonicalTitle.isNullOrBlank() || metadata.canonicalTitle == query.canonicalTitle

/** An image for a guessed filename must never overrule the ROM's checksum-verified name. */
internal fun automaticLibretroCandidates(
    candidates: List<GameMetadataCandidate>, verifiedTitle: String?, matchLocked: Boolean,
): List<GameMetadataCandidate> =
    if (!matchLocked && !verifiedTitle.isNullOrBlank()) candidates.filter { it.catalogMatch }
    else candidates

/** A catalog-only Libretro choice can acquire a proven image namespace on a later refresh. */
internal fun sameGameMetadataMatch(previous: GameMetadata, incoming: GameMetadata): Boolean =
    previous.provider == incoming.provider &&
        if (previous.provider == GameMetadataProviderIds.LIBRETRO && previous.providerGameId.isBlank())
            previous.canonicalTitle.isNotBlank() && previous.canonicalTitle == incoming.canonicalTitle
        else previous.providerGameId == incoming.providerGameId

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

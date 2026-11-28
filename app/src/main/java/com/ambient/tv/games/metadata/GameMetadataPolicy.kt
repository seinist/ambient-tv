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

/** Search-only fallback: strip edge release annotations without altering the ROM, DAT name
 * or pinned provider identity. Preserve parentheses inside the actual title. */
internal fun gameMetadataSearchName(value: String): String {
    var name = value.trim()
    val prefix = Regex("""^(?:\([^()\[\]]*\)|\[[^()\[\]]*\])\s*[-_.]?\s*""")
    val suffix = Regex("""\s*(?:\([^()\[\]]*\)|\[[^()\[\]]*\])$""")
    while (true) {
        val next = name.replace(prefix, "").replace(suffix, "").trim()
        if (next == name) break
        name = next
    }
    return name.ifBlank { value.trim() }
}

/** Strip recognized technical annotations while retaining genuine parenthetical subtitles. */
internal fun gameMetadataCatalogBase(value: String): String {
    val technical = Regex("""(?i)^(?:rev(?:ision)?[ ._-]*[\w.-]+|v(?:er(?:sion)?)?[ ._-]*\d[\w.-]*|(?:disc|disk|side|tape|part)[ ._-]*[0-9]+(?:\s+of\s+\d+)?|beta(?:\s+\d+)?|proto(?:type)?(?:\s+\d+)?|demo|sample|unl|pirate|hack|[!]|[abhot]\d+)$""")
    var name = gameDisplayTitle(value).trim()
    val suffix = Regex("""\s*(?:\(([^()\[\]]*)\)|\[([^()\[\]]*)\])$""")
    while (true) {
        val match = suffix.find(name) ?: break
        val tag = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
        if (!technical.matches(tag)) break
        val next = gameDisplayTitle(name.substring(0, match.range.first).trim())
        if (next.isBlank()) break
        name = next
    }
    return name.ifBlank { value.trim() }
}

/** Retain locale information even for tags before the name. */
private fun gameSearchLocaleTags(value: String): String {
    val tags = Regex("""\([^()\[\]]*\)|\[[^()\[\]]*\]""").findAll(value)
        .joinToString(" ") { it.value }
    return "ROM $tags"
}

/** Filename/catalog agreement is not a checksum match. Restrict fallback to exact normalized
 * names, preserving explicit region/language tags and never selecting a partial title. */
internal fun matchingGameCatalogTitles(hint: String, candidates: List<String>): List<String> {
    val exact = gameDatSearchKey(hint)
    if (exact.isEmpty()) return emptyList()
    val full = candidates.filter { gameDatSearchKey(it) == exact }.distinct()
    if (full.isNotEmpty()) return full
    val base = gameDatSearchKey(gameDisplayTitle(hint))
    val region = gameTitleRegionLabel(gameSearchLocaleTags(hint))?.let(::gameDatSearchKey)
    val language = gameTitleLanguageLabel(gameSearchLocaleTags(hint))?.let(::gameDatSearchKey)
    val compatible = candidates.filter {
        (region == null || gameTitleRegionLabel(gameSearchLocaleTags(it))?.let(::gameDatSearchKey) == region) &&
            (language == null || gameTitleLanguageLabel(gameSearchLocaleTags(it))?.let(::gameDatSearchKey) == language)
    }.distinct()
    val normal = compatible.filter { gameDatSearchKey(gameDisplayTitle(it)) == base }
    if (normal.isNotEmpty()) return normal
    val technicalBase = gameDatSearchKey(gameMetadataCatalogBase(hint))
    val technicalMatches = compatible.filter { gameDatSearchKey(gameMetadataCatalogBase(it)) == technicalBase }
    if (technicalMatches.isNotEmpty()) return technicalMatches
    val clean = gameDatSearchKey(gameMetadataSearchName(hint))
    if (clean.isBlank()) return emptyList()
    // Prefer a real catalog title over another annotated guess. Do not equate different
    // meaningful parenthetical subtitles merely because their stripped stems coincide.
    return compatible.filter { gameDatSearchKey(gameMetadataCatalogBase(it)) == clean }
}

/** Includes file version and locale so a previous miss cannot suppress a new ROM/region. */
internal fun gameMetadataFingerprint(game: LibraryGame, query: GameMetadataQuery): String =
    gameMetadataDigest(listOf(game.fileId, game.modified.toString(), query.systemId,
        game.datVersion.toString(), game.verifiedTitle.orEmpty(), game.internalTitle.orEmpty(),
        query.size.toString(), query.title, query.filename, query.crc32.orEmpty(), query.serial.orEmpty(),
        query.language, query.region, query.canonicalTitle.orEmpty(), query.providerGameId.orEmpty()).joinToString("\u0000"))

internal fun gameMetadataDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Invalidate previous lookup misses after changing search normalization for all providers. */
internal fun gameMetadataLookupFingerprint(provider: String, fingerprint: String): String =
    gameMetadataDigest("rom-search:v4:$provider:$fingerprint")

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

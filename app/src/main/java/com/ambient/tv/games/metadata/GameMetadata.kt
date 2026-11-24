package com.ambient.tv.games.metadata

import kotlinx.serialization.Serializable

/** Provider identity is independent of the user's editable display title. */
object GameMetadataProviderIds {
    const val LIBRETRO = "libretro"
    const val SCREENSCRAPER = "screenscraper"
    const val THEGAMESDB = "thegamesdb"
    /** Not a metadata source: the name databases RetroArch uses (names and checksums only). */
    const val LIBRETRO_DATABASE = "libretro-database"
}

@Serializable
enum class GameArtworkKind(val key: String) {
    COVER("cover"), BACKDROP("backdrop"), TITLE("title");

    companion object {
        fun fromKey(key: String): GameArtworkKind? = entries.firstOrNull { it.key == key }
    }
}

/** Full details are read for one game; browse queries expose only title/year/local artwork. */
@Serializable
data class GameMetadata(
    val title: String = "",
    val summary: String = "",
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val developer: String = "",
    val publisher: String = "",
    val players: String = "",
    val region: String = "",
    val language: String = "",
    val provider: String = "",
    val providerGameId: String = "",
    val canonicalTitle: String = "",
    val matchLocked: Boolean = false,
    /** Public, credential-free identity URLs; credentials are attached only by the provider. */
    val coverUrl: String? = null,
    val backdropUrl: String? = null,
    val titleScreenUrl: String? = null,
    val sourceUrl: String? = null,
    /** An image selected independently can come from a different provider than the text. */
    val artworkProviders: Map<String, String> = emptyMap(),
    val artworkSources: Map<String, String> = emptyMap(),
    val lockedFields: Set<String> = emptySet(),
    val updatedAt: Long = 0L,
) {
    fun artwork(kind: GameArtworkKind): String? = when (kind) {
        GameArtworkKind.COVER -> coverUrl
        GameArtworkKind.BACKDROP -> backdropUrl
        GameArtworkKind.TITLE -> titleScreenUrl
    }

    fun bounded(): GameMetadata = copy(
        title = title.trim().take(256), summary = summary.trim().take(16_000),
        year = year?.takeIf { it in 1800..2200 },
        genres = genres.map { it.trim().take(80) }.filter(String::isNotBlank).distinct().take(20),
        developer = developer.trim().take(256), publisher = publisher.trim().take(256),
        players = players.trim().take(80), region = region.trim().take(80), language = language.take(32),
        provider = provider.take(32), providerGameId = providerGameId.take(512),
        canonicalTitle = canonicalTitle.trim().take(512),
        coverUrl = sanitizedGameMetadataUrl(coverUrl), backdropUrl = sanitizedGameMetadataUrl(backdropUrl),
        titleScreenUrl = sanitizedGameMetadataUrl(titleScreenUrl), sourceUrl = sanitizedGameMetadataUrl(sourceUrl),
        artworkProviders = artworkProviders.filterKeys { GameArtworkKind.fromKey(it) != null }
            .mapValues { it.value.take(32) },
        artworkSources = artworkSources.filterKeys { GameArtworkKind.fromKey(it) != null }
            .mapNotNull { (kind, url) -> sanitizedGameMetadataUrl(url)?.let { kind to it } }.toMap(),
        lockedFields = lockedFields.intersect(EDITABLE_FIELDS),
    )

    companion object {
        val EDITABLE_FIELDS = setOf("title", "summary", "year", "genres", "developer", "publisher",
            "players", "region", "cover", "backdrop", "title_screen")
    }
}

data class GameMetadataQuery(
    val systemId: String,
    val title: String,
    val filename: String,
    val size: Long = 0L,
    val crc32: String? = null,
    val serial: String? = null,
    val language: String = "en",
    val region: String = "eu",
    val canonicalTitle: String? = null,
    val providerGameId: String? = null,
)

data class GameMetadataCandidate(
    val metadata: GameMetadata,
    /** Automatic selection requires an unambiguous provider match; manual search can show all. */
    val exactMatch: Boolean = false,
    /** The name exists in the local DAT catalog, independently of optional online artwork. */
    val catalogMatch: Boolean = false,
)

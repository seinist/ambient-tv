package com.ambient.tv.domain.model

/** Artwork slot of an item that an administrator can replace on the server. */
enum class ArtworkKind { POSTER, BACKDROP }

/** Text field of an item that an administrator can change on the server. */
enum class MetadataField { TITLE, SORT_TITLE, ORIGINAL_TITLE, YEAR, SUMMARY }

/**
 * Current text fields of an item. [locked] fields survive the server's next metadata refresh;
 * [lockable] says which fields the server can lock at all.
 */
data class MetadataFields(
    val values: Map<MetadataField, String>,
    val locked: Set<MetadataField> = emptySet(),
    val lockable: Set<MetadataField> = emptySet(),
) {
    operator fun get(field: MetadataField): String = values[field].orEmpty()
}

/**
 * What the signed-in user may change on the item's server. [NONE] unless the server confirmed
 * administrator rights, so editing options never appear for a user who could not use them.
 */
data class MetadataEditSupport(
    val match: Boolean = false,
    val artwork: Set<ArtworkKind> = emptySet(),
    val fields: Boolean = false,
) {
    val any: Boolean get() = match || artwork.isNotEmpty() || fields

    companion object {
        val NONE = MetadataEditSupport()
        private val MATCH_TYPES = setOf("movie", "show", "artist", "album")
        private val POSTER_TYPES = setOf("movie", "show", "season", "collection", "artist", "album")
        private val BACKDROP_TYPES = setOf("movie", "show", "collection", "artist")
        private val FIELD_TYPES = setOf("movie", "show", "season", "episode", "collection", "artist", "album")

        /** Edit options of an item [type] for an administrator; [matchCollections] where the server can re-identify box sets. */
        fun forType(type: String, matchCollections: Boolean = false) = MetadataEditSupport(
            match = type in MATCH_TYPES || (matchCollections && type == "collection"),
            artwork = buildSet {
                if (type in POSTER_TYPES) add(ArtworkKind.POSTER)
                if (type in BACKDROP_TYPES) add(ArtworkKind.BACKDROP)
            },
            fields = type in FIELD_TYPES,
        )
    }
}

/** One candidate of the server's "Fix match" / "Identify" search. [handle] is opaque to the UI. */
data class MetadataMatch(
    val title: String,
    val year: Int?,
    val thumbUrl: String?,
    val source: String?,
    /** Match confidence in percent where the server rates candidates (Plex). */
    val score: Int?,
    val overview: String?,
    val handle: String,
)

/** One poster or background the server offers for an item. [handle] is opaque to the UI. */
data class ArtworkChoice(
    val previewUrl: String,
    val handle: String,
    val selected: Boolean = false,
    val provider: String? = null,
    val language: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rating: Double? = null,
)

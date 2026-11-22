package com.ambient.tv.games.metadata

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** ScreenScraper returns regional text records and media URLs; never retain its credential query. */
internal fun parseScreenScraper(
    payload: JsonObject,
    query: GameMetadataQuery,
    platforms: Set<Int>,
    exact: Boolean,
    artworkKind: GameArtworkKind? = null,
): List<GameMetadataCandidate> {
    val response = payload.obj("response").takeIf { it.isNotEmpty() } ?: payload
    val records = (response.objects("jeux") + response.objects("jeu")).distinctBy { it.text("id") }
    if (records.isEmpty() && response["jeux"] !is JsonArray && response["jeu"] !is JsonObject) {
        throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
    }
    return records.take(GameMetadataProviders.MAX_MATCHES).flatMap gameLoop@{ game ->
        val id = game.text("id").takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return@gameLoop emptyList()
        val platform = game.obj("systeme").text("id").toIntOrNull()
        if (platform == null || platform !in platforms) return@gameLoop emptyList()
        if (game.text("notgame") in setOf("true", "1")) return@gameLoop emptyList()
        val title = regionalText(game["noms"], query.region, listOf("wor", "us", "ss"), "region")
            .ifBlank { game.text("nom") }.takeIf(String::isNotBlank) ?: return@gameLoop emptyList()
        val summary = regionalText(game["synopsis"], query.language, listOf("en"), "langue")
        val release = regionalText(game["dates"], query.region, listOf("wor", "us", "jp"), "region")
        val genres = when (val raw = game["genres"]) {
            is JsonArray -> raw.take(100).mapNotNull { genre ->
                val data = genre as? JsonObject ?: return@mapNotNull null
                regionalText(data["noms"], query.language, listOf("en"), "langue")
                    .ifBlank { data.text("text") }.takeIf(String::isNotBlank)
            }
            is JsonObject -> (raw["genres_" + query.language] ?: raw["genres_en"]).let { entries ->
                (entries as? JsonArray).orEmpty().mapNotNull { it.scalarText().takeIf(String::isNotBlank) }
            }
            else -> emptyList()
        }
        val media = game.objects("medias").take(512)
        fun imageChoices(vararg kinds: String): List<String> = buildList {
            for (kind in kinds) {
                val candidates = media.filter { it.text("type") == kind }
                val ordered = listOf(query.region, "wor", "us", "jp", "")
                    .flatMap { region -> candidates.filter { it.text("region") == region } } + candidates
                for (entry in ordered.distinct()) {
                    if (size >= GameMetadataProviders.MAX_ARTWORK_CHOICES) return@buildList
                    sanitizedGameArtworkUrl(GameMetadataProviderIds.SCREENSCRAPER, entry.text("url"))?.let { if (it !in this) add(it) }
                }
            }
        }.distinct().take(GameMetadataProviders.MAX_ARTWORK_CHOICES)
        val coverChoices = imageChoices("box-2D", "box-3D")
        val backdropChoices = imageChoices("fanart", "ss")
        val titleChoices = imageChoices("sstitle")
        val metadata = GameMetadata(
            title = title, summary = summary, year = release.take(4).toIntOrNull(), genres = genres,
            developer = game.text("developpeur"), publisher = game.text("editeur"), players = game.text("joueurs"),
            region = query.region, language = query.language, provider = GameMetadataProviderIds.SCREENSCRAPER,
            providerGameId = id, canonicalTitle = title,
            coverUrl = coverChoices.firstOrNull(), backdropUrl = backdropChoices.firstOrNull(), titleScreenUrl = titleChoices.firstOrNull(),
            sourceUrl = "https://www.screenscraper.fr/gameinfos.php?gameid=" + id,
        ).bounded()
        if (artworkKind == null) listOf(GameMetadataCandidate(metadata, exactMatch = exact))
        else artworkCandidates(metadata, artworkKind, when (artworkKind) {
            GameArtworkKind.COVER -> coverChoices
            GameArtworkKind.BACKDROP -> backdropChoices
            GameArtworkKind.TITLE -> titleChoices
        }, exact)
    }.take(if (artworkKind == null) GameMetadataProviders.MAX_MATCHES else GameMetadataProviders.MAX_ARTWORK_CHOICES)
}

private fun regionalText(raw: JsonElement?, preferred: String, fallback: List<String>, key: String): String {
    val order = (listOf(preferred) + fallback).distinct()
    if (raw is JsonArray) {
        val records = raw.mapNotNull { it as? JsonObject }
        for (region in order) records.firstOrNull { it.text(key) == region }?.scalarText()?.takeIf(String::isNotBlank)?.let { return it }
        return records.firstNotNullOfOrNull { it.scalarText().takeIf(String::isNotBlank) }.orEmpty()
    }
    if (raw is JsonObject) {
        for (region in order) raw.entries.firstOrNull { it.key == region || it.key.endsWith("_" + region) }
            ?.value?.scalarText()?.takeIf(String::isNotBlank)?.let { return it }
        return raw.values.firstNotNullOfOrNull { it.scalarText().takeIf(String::isNotBlank) }.orEmpty()
    }
    return raw.scalarText()
}

/** Resolves provider IDs and image paths; pagination links (which contain API keys) are ignored. */
internal fun parseTheGamesDb(
    payload: JsonObject,
    images: JsonObject?,
    query: GameMetadataQuery,
    platforms: Set<String>,
    genres: Map<String, String>,
    developers: Map<String, String>,
    publishers: Map<String, String>,
    exact: Boolean,
    artworkKind: GameArtworkKind? = null,
): List<GameMetadataCandidate> {
    val data = payload.obj("data")
    if (data["games"] !is JsonArray && data["games"] !is JsonObject) throw GameMetadataProviderException(GameMetadataFailure.INVALID_RESPONSE)
    val included = payload.obj("include").obj("boxart")
    val imageData = images?.obj("data")
    fun imageBase(container: JsonObject): String? = container.obj("base_url").let { base ->
        listOf("large", "medium", "original").firstNotNullOfOrNull { base.text(it).takeIf(String::isNotBlank) }
    }
    return data.objects("games").take(GameMetadataProviders.MAX_MATCHES).flatMap gameLoop@{ game ->
        val id = game.text("id").takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return@gameLoop emptyList()
        if (game.text("platform") !in platforms) return@gameLoop emptyList()
        val title = game.text("game_title").takeIf(String::isNotBlank) ?: return@gameLoop emptyList()
        fun names(key: String, lookup: Map<String, String>): List<String> =
            (game[key] as? JsonArray).orEmpty().mapNotNull { value ->
                val text = value.scalarText()
                lookup[text] ?: text.takeIf { it.isNotBlank() && it.toLongOrNull() == null }
            }.distinct()
        val allImages = imageData?.obj("images")?.objects(id).orEmpty().take(512)
        val includedImages = included.obj("data").objects(id).take(512)
        fun imageChoices(kinds: List<String>, front: Boolean = false): List<String> = buildList {
            for (kind in kinds) {
                for ((entries, base) in listOf(allImages to imageData?.let(::imageBase), includedImages to imageBase(included))) {
                    val choices = entries.filter { it.text("type") == kind && (!front || it.text("side").ifBlank { "front" } == "front") }
                    for (entry in choices) {
                        if (size >= GameMetadataProviders.MAX_ARTWORK_CHOICES) return@buildList
                        val file = entry.text("filename")
                        if (file.isBlank() || file.startsWith('/') || file.split('/').any { it == ".." }) continue
                        val url = base?.toHttpUrlOrNull()?.resolve(file)?.toString()
                        sanitizedGameArtworkUrl(GameMetadataProviderIds.THEGAMESDB, url)?.let { if (it !in this) add(it) }
                    }
                }
            }
        }.distinct().take(GameMetadataProviders.MAX_ARTWORK_CHOICES)
        val coverChoices = imageChoices(listOf("boxart"), front = true)
        val backdropChoices = imageChoices(listOf("fanart", "screenshot"))
        val titleChoices = imageChoices(listOf("titlescreen"))
        val metadata = GameMetadata(
            title = title, canonicalTitle = title, summary = game.text("overview"),
            year = game.text("release_date").take(4).toIntOrNull(), genres = names("genres", genres),
            developer = names("developers", developers).joinToString(", "),
            publisher = names("publishers", publishers).joinToString(", "), players = game.text("players"),
            region = query.region, language = "en", provider = GameMetadataProviderIds.THEGAMESDB,
            providerGameId = id, coverUrl = coverChoices.firstOrNull(),
            backdropUrl = backdropChoices.firstOrNull(), titleScreenUrl = titleChoices.firstOrNull(),
            sourceUrl = "https://thegamesdb.net/game.php?id=" + id,
        ).bounded()
        if (artworkKind == null) listOf(GameMetadataCandidate(metadata, exactMatch = exact))
        else artworkCandidates(metadata, artworkKind, when (artworkKind) {
            GameArtworkKind.COVER -> coverChoices
            GameArtworkKind.BACKDROP -> backdropChoices
            GameArtworkKind.TITLE -> titleChoices
        }, exact)
    }.take(if (artworkKind == null) GameMetadataProviders.MAX_MATCHES else GameMetadataProviders.MAX_ARTWORK_CHOICES)
}

private fun artworkCandidates(metadata: GameMetadata, kind: GameArtworkKind, references: List<String>, exact: Boolean) =
    references.map { reference -> GameMetadataCandidate(when (kind) {
        GameArtworkKind.COVER -> metadata.copy(coverUrl = reference)
        GameArtworkKind.BACKDROP -> metadata.copy(backdropUrl = reference)
        GameArtworkKind.TITLE -> metadata.copy(titleScreenUrl = reference)
    }, exactMatch = exact) }

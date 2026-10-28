package com.ambient.tv.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.ambient.tv.connectivity.UnreachableServerGate
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.launcher.LauncherArtwork
import com.ambient.tv.launcher.LauncherIntents
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers the Android TV system search (and the search dialog's suggestions) with titles from
 * Plex, Jellyfin and Emby. A hit opens the title's detail page through [LauncherIntents], which
 * connects (and wakes) the server first. Servers already known to be unreachable are skipped,
 * so typing in the system search never starts a wake.
 */
class SearchSuggestionProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun plex(): MediaRepository
        fun jellyfin(): JellyfinRepository
        fun emby(): EmbyRepository
        fun preferences(): AppPreferences
        fun unreachable(): UnreachableServerGate
    }

    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val query = normalizeExternalQuery(selectionArgs?.firstOrNull() ?: uri.lastPathSegment?.takeIf { it != SearchManager.SUGGEST_URI_PATH_QUERY })
            ?: return cursor
        val limit = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)?.toIntOrNull()?.coerceIn(1, MAX_RESULTS) ?: MAX_RESULTS
        val context = context ?: return cursor
        val deps = runCatching { EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java) }.getOrNull() ?: return cursor
        val items = mergeSuggestions(runBlocking { withTimeoutOrNull(TIMEOUT_MS) { search(deps, query) } }.orEmpty(), limit)
        // Server artwork URLs carry credentials (X-Plex-Token, ApiKey): the system search gets
        // the app's own content URI of a fetched copy, like the launcher rows, never the URL.
        // Downloads block on the network, so they run outside the timeout: what is not done in time
        // shows without a picture now and is cached for the next query.
        val fetches = items.map { item ->
            artworkScope.async { runCatching { LauncherArtwork.fetch(context, item.thumbUrl) }.getOrNull() }
        }
        val artwork = runBlocking {
            withTimeoutOrNull(ARTWORK_TIMEOUT_MS) { fetches.awaitAll() }
                ?: fetches.map { if (it.isCompleted) it.await() else null }
        }
        items.forEachIndexed { index, item ->
            cursor.addRow(arrayOf<Any?>(
                index,
                item.title,
                listOfNotNull(item.year?.toString(), item.type.replaceFirstChar { it.uppercase() }).joinToString(" · "),
                artwork.getOrNull(index)?.toString(),
                item.year,
                LauncherIntents.searchTitleUri(item.ratingKey).toString(),
                android.content.Intent.ACTION_VIEW,
            ))
        }
        return cursor
    }

    private suspend fun search(deps: Dependencies, query: String): List<MediaItem> = coroutineScope {
        val preferences = deps.preferences()
        val unreachable = deps.unreachable().current().unreachable
        listOfNotNull(
            if (preferences.isConfigured && "plex" !in unreachable) async {
                runCatching {
                    val keys = deps.plex().getLibrarySections().getOrThrow().filterNot { it.key in preferences.plexHiddenSectionKeys }.map { it.key }
                    deps.plex().search(keys, query).getOrThrow()
                }.getOrDefault(emptyList())
            } else null,
            if (preferences.isJellyfinConfigured && "jellyfin" !in unreachable) async {
                runCatching {
                    val keys = deps.jellyfin().getLibrarySections().getOrThrow().filterNot { it.key in preferences.jellyfinHiddenSectionKeys }.map { it.key }
                    deps.jellyfin().search(keys, query).getOrThrow()
                }.getOrDefault(emptyList())
            } else null,
            if (preferences.isEmbyConfigured && "emby" !in unreachable) async {
                runCatching {
                    val keys = deps.emby().getLibrarySections().getOrThrow().filterNot { it.key in preferences.embyHiddenSectionKeys }.map { it.key }
                    deps.emby().search(keys, query).getOrThrow()
                }.getOrDefault(emptyList())
            } else null,
        ).awaitAll().flatten()
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
        )
        const val TIMEOUT_MS = 4_000L
        const val ARTWORK_TIMEOUT_MS = 2_500L
        const val MAX_RESULTS = 20
    }
}

/** Movies and shows first, one entry per title across servers, at most [limit]. */
internal fun mergeSuggestions(items: List<MediaItem>, limit: Int): List<MediaItem> =
    items.distinctBy { it.canonicalContentKey }
        .sortedBy { if (it.type == "movie" || it.type == "show") 0 else 1 }
        .take(limit)

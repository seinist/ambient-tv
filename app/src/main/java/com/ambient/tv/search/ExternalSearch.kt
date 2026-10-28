package com.ambient.tv.search

import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Searches started outside the app: the Android TV system search, the Assistant ("search for …
 * in Ambient TV", "play … on Ambient TV") and the voice search key. The query waits here until
 * the navigation host opened the search screen, which runs it.
 */
object ExternalSearch {
    /** Google Assistant / Google TV's search action (no compile-time dependency on Play Services). */
    const val ACTION_GMS_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"

    private val pending = MutableStateFlow<String?>(null)
    val requests = pending.asStateFlow()

    /** The query of a search intent, or null for any other intent. */
    fun queryOf(intent: Intent?): String? {
        val action = intent?.action ?: return null
        if (action != Intent.ACTION_SEARCH && action != ACTION_GMS_SEARCH && action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return null
        // Reopened from Recents: the old search must not run again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return normalizeExternalQuery(intent.getStringExtra(SearchManager.QUERY))
    }

    /** True when [intent] was a search intent; its query (if usable) is queued. */
    fun offer(intent: Intent?): Boolean {
        val action = intent?.action
        val isSearch = action == Intent.ACTION_SEARCH || action == ACTION_GMS_SEARCH || action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
        queryOf(intent)?.let { pending.value = it }
        return isSearch
    }

    fun consumed(query: String) {
        pending.compareAndSet(query, null)
    }
}

/** Trimmed, single-line and bounded; blank or one-character queries are dropped. */
internal fun normalizeExternalQuery(raw: String?): String? =
    raw?.replace(Regex("\\s+"), " ")?.trim()?.take(200)?.takeIf { it.length >= 2 }

package com.ambient.tv.ui.browse

import com.ambient.tv.data.browse.BrowseScopes
import com.ambient.tv.data.browse.DisplaySnapshots
import com.ambient.tv.data.browse.displayOnly
import com.ambient.tv.domain.model.MediaItem

internal interface FilteredBrowseCache {
    val scopeKey: String get() = ""
    fun refreshScope(): Boolean = false
    fun isCurrent(scope: String): Boolean = scope == scopeKey
    suspend fun read(): List<MediaItem>?
    fun write(items: List<MediaItem>)
}

/** Metadata only, bounded by the shared disk store and isolated by endpoint, login and language. */
internal class ScopedFilteredBrowseCache(
    private val snapshots: DisplaySnapshots,
    private val scopes: BrowseScopes,
    private val provider: String,
    private val key: String,
) : FilteredBrowseCache {
    private val owner = "filtered:$provider"
    override var scopeKey = scopes.forProvider(provider)
        private set
    private var namespace = snapshots.namespace(owner, scopeKey)
    override fun isCurrent(scope: String) = scope == scopeKey && scopes.forProvider(provider) == scope
    override fun refreshScope(): Boolean {
        val next = scopes.forProvider(provider)
        if (next == scopeKey) return false
        scopeKey = next
        namespace = snapshots.namespace(owner, scopeKey)
        return true
    }
    override suspend fun read(): List<MediaItem>? =
        scopeKey.let { scope -> if (isCurrent(scope)) snapshots.read<List<MediaItem>>(namespace, key)?.takeIf { isCurrent(scope) } else null }
    override fun write(items: List<MediaItem>) {
        if (isCurrent(scopeKey)) snapshots.writeDeferred(owner, namespace, key) { items.map { it.displayOnly() } }
    }
}

package com.ambient.tv.ui.home

import com.ambient.tv.data.local.HomeRowDefinition

/**
 * The Home widget ("system.contentKey") a content row was built from. Widgets that fan out into
 * several rows name them with a prefix; every other row is named after its widget.
 */
internal fun homeRowSource(rowId: String): String {
    val system = rowId.substringBefore('.')
    val row = rowId.substringAfter('.', "")
    val contentKey = when {
        row.startsWith("recent") -> "recent"
        row.startsWith("collection.") -> "collections"
        row.startsWith("library.") -> "libraries"
        row.startsWith("hub.") -> HomeRowDefinition.CONTENT_HUBS
        else -> row
    }
    return "$system.$contentKey"
}

internal fun orderedHomeRowKeys(
    definitions: List<HomeRowDefinition>, contentIds: List<String>, shortcuts: List<HomeRowDefinition>,
    hasLive: Boolean, hasRecent: Boolean, tvRowSources: Map<String, String> = emptyMap(),
): List<String> {
    val order = definitions.filter { it.enabled }.distinctBy { it.system to it.contentKey }.withIndex().associate { "${it.value.system}.${it.value.contentKey}" to it.index }
    val groups = buildMap<String, String> {
        if (hasLive) put("live-tv", "tvheadend.live")
        if (hasRecent) put("recent-tv", "tvheadend.recent_channels")
        putAll(tvRowSources)
        shortcuts.forEach { put("shortcut:${it.id}", "${it.system}.${it.contentKey}") }
        contentIds.forEach { id -> put(id, homeRowSource(id)) }
    }
    return groups.keys.sortedBy { order[groups[it]] ?: Int.MAX_VALUE }
}

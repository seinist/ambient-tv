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

/** Configured-source fallback when there is no browse snapshot; cached shelves need no extra rows. */
internal fun homeOfflineReconnectSystems(
    definitions: List<HomeRowDefinition>,
    offline: Set<String>,
    hasSavedContent: Boolean = false,
): List<String> = if (hasSavedContent) emptyList() else definitions.asSequence()
    .filter { it.enabled && it.system in offline }
    .map { it.system }
    .distinct()
    .toList()

internal fun orderedHomeRowKeys(
    definitions: List<HomeRowDefinition>, contentIds: List<String>, shortcuts: List<HomeRowDefinition>,
    hasLive: Boolean, hasRecent: Boolean, tvRowSources: Map<String, String> = emptyMap(),
    offlineSystems: List<String> = emptyList(),
): List<String> {
    val order = definitions.filter { it.enabled }.distinctBy { it.system to it.contentKey }.withIndex().associate { "${it.value.system}.${it.value.contentKey}" to it.index }
    val groups = homeRowKeySources(contentIds, shortcuts, hasLive, hasRecent, tvRowSources, offlineSystems, definitions)
    return groups.keys.sortedBy { order[groups[it]] ?: Int.MAX_VALUE }
}

/**
 * Cold start: rows appear top-down in the editor's order. A row stays back while an enabled row
 * above it belongs to a source in [pendingSystems] that has not produced that row yet, so fast
 * sources (Live TV, radio, shortcuts) cannot show first and then jump down when library rows
 * arrive. Rows without an editor position are never held back.
 */
internal fun revealHomeRowsInOrder(
    definitions: List<HomeRowDefinition>,
    orderedKeys: List<String>,
    keySources: Map<String, String>,
    pendingSystems: Set<String>,
): List<String> {
    if (pendingSystems.isEmpty()) return orderedKeys
    val enabled = definitions.filter { it.enabled }.distinctBy { it.system to it.contentKey }
    val presentSources = orderedKeys.mapNotNullTo(HashSet()) { keySources[it] }
    val firstGap = enabled.indexOfFirst {
        it.system in pendingSystems && "${it.system}.${it.contentKey}" !in presentSources
    }
    if (firstGap < 0) return orderedKeys
    val order = enabled.withIndex().associate { "${it.value.system}.${it.value.contentKey}" to it.index }
    return orderedKeys.filter { key -> order[keySources[key]]?.let { it < firstGap } ?: true }
}

/** The widget source of every key [orderedHomeRowKeys] can produce. */
internal fun homeRowKeySources(
    contentIds: List<String>, shortcuts: List<HomeRowDefinition>,
    hasLive: Boolean, hasRecent: Boolean, tvRowSources: Map<String, String> = emptyMap(),
    offlineSystems: List<String> = emptyList(), definitions: List<HomeRowDefinition> = emptyList(),
): Map<String, String> = buildMap {
    if (hasLive) put("live-tv", "tvheadend.live")
    if (hasRecent) put("recent-tv", "tvheadend.recent_channels")
    putAll(tvRowSources)
    shortcuts.forEach { put("shortcut:${it.id}", "${it.system}.${it.contentKey}") }
    contentIds.forEach { id -> put(id, homeRowSource(id)) }
    // Reconnect rows participate in editor order instead of being appended after Live TV.
    offlineSystems.forEach { system ->
        definitions.firstOrNull { it.enabled && it.system == system }?.let { first ->
            put(HOME_OFFLINE_ROW_PREFIX + system, "$system.${first.contentKey}")
        }
    }
}

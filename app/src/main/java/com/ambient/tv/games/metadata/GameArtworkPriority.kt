package com.ambient.tv.games.metadata

/**
 * A small, process-local description of the game cards the user can currently see. Updating it
 * never starts work: an already-running folder enrichment reads it before choosing its next ROM.
 * Owners are composition identity tokens, never views, activities, or full catalog entries.
 */
internal class GameArtworkPriority(private val maxOwners: Int = 128) {
    private data class Demand(val fileId: String, val focused: Boolean)
    private val owners = linkedMapOf<Any, Demand>()

    init { require(maxOwners > 0) }

    @Synchronized
    fun update(owner: Any, fileId: String, visible: Boolean, focused: Boolean) {
        if (!visible || fileId.isBlank()) {
            owners.remove(owner)
            return
        }
        val next = Demand(fileId, focused)
        if (owners[owner] == next) return
        // The latest focus gain wins even during a hand-off between two composed cards.
        owners.remove(owner)
        owners[owner] = next
        while (owners.size > maxOwners) {
            val oldest = owners.entries.firstOrNull { !it.value.focused }?.key ?: owners.keys.first()
            owners.remove(oldest)
        }
    }

    @Synchronized
    fun remove(owner: Any) { owners.remove(owner) }

    @Synchronized
    fun prioritizedFileIds(): List<String> {
        val demands = owners.values.toList()
        return (demands.asReversed().filter { it.focused } + demands.filterNot { it.focused })
            .map { it.fileId }.distinct()
    }
}

internal val SharedGameArtworkPriority = GameArtworkPriority()

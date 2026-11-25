package com.ambient.tv.games.metadata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An identified visible request, including its producer-observed composition lifetime. */
internal data class GameArtworkDemand(val fileId: String, val generation: Long)

/**
 * A small, process-local description of the game cards the user can currently see. An active
 * folder enrichment uses it to order matches; cover recovery only reloads already known images.
 * Owners are composition identity tokens, never views, activities, or full catalog entries.
 */
internal class GameArtworkPriority(internal val maxOwners: Int = 128) {
    private data class Demand(val fileId: String, val focused: Boolean)
    private val owners = linkedMapOf<Any, Demand>()
    private val generations = mutableMapOf<String, Long>()
    private var nextGeneration = 0L
    private val mutable = MutableStateFlow<List<GameArtworkDemand>>(emptyList())
    val demands = mutable.asStateFlow()

    init { require(maxOwners > 0) }

    @Synchronized
    fun update(owner: Any, fileId: String, visible: Boolean, focused: Boolean) {
        if (!visible || fileId.isBlank()) {
            if (owners.remove(owner) != null) publish()
            return
        }
        val next = Demand(fileId, focused)
        val previous = owners[owner]
        if (previous == next) return
        // The latest focus gain wins even during a hand-off between two composed cards.
        owners.remove(owner)
        owners[owner] = next
        while (owners.size > maxOwners) {
            val oldest = owners.entries.firstOrNull { !it.value.focused }?.key ?: owners.keys.first()
            owners.remove(oldest)
        }
        // Assign at the producer, before StateFlow can conflate a quick remove/add. A new
        // visible composition can retry missing artwork even while another owner shows it;
        // focus changes and repeated composition reports preserve the existing generation.
        if (owners[owner] == next && previous?.fileId != fileId) {
            generations[fileId] = ++nextGeneration
        }
        publish()
    }

    @Synchronized
    fun remove(owner: Any) { if (owners.remove(owner) != null) publish() }

    fun prioritizedFileIds(): List<String> = demands.value.map { it.fileId }

    private fun publish() {
        val demands = owners.values.toList()
        val ids = (demands.asReversed().filter { it.focused } + demands.filterNot { it.focused })
            .map { it.fileId }.distinct()
        generations.keys.retainAll(ids.toSet())
        mutable.value = ids.map { GameArtworkDemand(it, generations.getValue(it)) }
    }
}

internal val SharedGameArtworkPriority = GameArtworkPriority()

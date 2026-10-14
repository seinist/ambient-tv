package com.ambient.tv.data.browse

/** Reuse unchanged objects; the successful complete response owns membership and order. */
fun <T, K> reconcileComplete(previous: List<T>, incoming: List<T>, key: (T) -> K): List<T> {
    val old = previous.associateBy(key)
    val result = incoming.distinctBy(key).map { fresh -> old[key(fresh)]?.takeIf { it == fresh } ?: fresh }
    return if (result == previous) previous else result
}

/** Prefer the old next surviving neighbour, then previous; insertion/reordering cannot steal focus. */
fun survivingFocus(previous: List<String>, incoming: List<String>, focused: String?): String? {
    if (focused == null || focused in incoming) return focused
    val available = incoming.toHashSet()
    val index = previous.indexOf(focused)
    if (index >= 0) {
        previous.drop(index + 1).firstOrNull { it in available }?.let { return it }
        previous.take(index).lastOrNull { it in available }?.let { return it }
    }
    return incoming.getOrNull(index.coerceIn(0, incoming.lastIndex.coerceAtLeast(0)))
}

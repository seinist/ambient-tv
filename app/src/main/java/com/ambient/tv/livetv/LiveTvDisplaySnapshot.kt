package com.ambient.tv.livetv

import com.ambient.tv.data.browse.DisplaySnapshots

/** Home and the source browser share metadata snapshots scoped to the exact configured login. */
internal fun liveTvDisplayNamespace(
    snapshots: DisplaySnapshots,
    repository: LiveTvRepository,
    registry: LiveTvRegistry,
): String? = registry.displayCacheIdentity(repository)?.let { identity ->
    snapshots.namespace("livetv:${repository.source.id}", identity)
}

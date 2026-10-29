package com.ambient.tv.ui.common

internal enum class FilterFocusSurface { BAR, PANEL }

internal data class FilterFocusTarget(val key: String, val surface: FilterFocusSurface)

/** Filter edits own focus until the user deliberately returns to the results. */
internal data class FilterFocusPolicy(
    val allowContentAutoFocus: Boolean = true,
    val lastControl: String? = null,
    val lastFilter: String? = null,
    val pending: FilterFocusTarget? = null,
) {
    fun edited(key: String) = copy(
        allowContentAutoFocus = false,
        lastControl = key,
        lastFilter = key.takeIf { it.startsWith("filter:") } ?: lastFilter,
        pending = null,
    )

    fun keepControls() = copy(allowContentAutoFocus = false)
    fun enterContent() = copy(allowContentAutoFocus = true, pending = null)
    fun restoreFilter(surface: FilterFocusSurface) = copy(
        allowContentAutoFocus = false,
        pending = FilterFocusTarget(lastFilter ?: lastControl ?: "filter:", surface),
    )
}

/** A vanished facet falls back to another filter on the currently visible surface. */
internal fun filterFocusReturnTarget(
    requested: FilterFocusTarget,
    available: Collection<FilterFocusTarget>,
): FilterFocusTarget? {
    val candidates = available.filter { it.surface == requested.surface }
    return candidates.firstOrNull { it.key == requested.key }
        ?: candidates.firstOrNull { it.key.startsWith("filter:") }
        ?: candidates.firstOrNull()
}

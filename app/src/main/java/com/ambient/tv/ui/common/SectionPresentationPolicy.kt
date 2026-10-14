package com.ambient.tv.ui.common

/**
 * A view mode may change item geometry, but it must not erase meaningful source sections.
 */
fun hasMultipleNamedContentSections(sections: List<Pair<String, Int>>): Boolean =
    sections.asSequence()
        .filter { (_, itemCount) -> itemCount > 0 }
        .map { (title, _) -> title.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(2)
        .count() > 1

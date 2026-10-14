package com.ambient.tv.ui.navigation


/** Pure selection rules shared by menu state and regression tests. */
internal fun chooseSidebarChild(available: List<String>, remembered: String?, active: String?): String? =
    remembered?.takeIf { it in available } ?: active?.takeIf { it in available } ?: available.firstOrNull()

internal fun reorderedSidebarSystems(order: List<String>, id: String, delta: Int, visible: List<String>): List<String> {
    val shown = order.filter { it in visible }.toMutableList()
    shown.addAll(visible.filterNot { it in shown })
    val from = shown.indexOf(id)
    if (from < 0) return order
    val to = (from + delta).coerceIn(0, shown.lastIndex)
    shown.removeAt(from)
    shown.add(to, id)
    // Disabled systems retain a stable relative order, ready for re-enabling later.
    return shown + order.filterNot { it in shown }
}

/** Focus may leave the rail once the selected destination is on screen (spinner counts). */
internal fun sidebarContentFocusAllowed(
    contentFocusRequested: Boolean,
    activeRoute: String?,
    requestedContentRoute: String?,
): Boolean = contentFocusRequested &&
    (requestedContentRoute == null || activeRoute == requestedContentRoute)

/** Real content is usable; the overlay spinner can dismiss and focus can enter the destination. */
internal fun sidebarContentHandoffReady(
    contentFocusRequested: Boolean,
    contentReady: Boolean,
    activeRoute: String?,
    requestedContentRoute: String?,
): Boolean = sidebarContentFocusAllowed(contentFocusRequested, activeRoute, requestedContentRoute) &&
    contentReady

/**
 * Fullscreen players temporarily remove the browse/detail focus tree. When Back reveals the
 * previous entry again, Android may hand focus to the always-composed sidebar before that tree is
 * ready. That focus is incidental and must be reclaimed by the returning content destination.
 */
internal fun returnsFromFullscreenPlayer(previousRoute: String?, currentRoute: String?): Boolean =
    previousRoute in setOf(
        Destinations.PLAYER,
        Destinations.TVH_PLAYER,
        Destinations.YOUTUBE_PLAYER,
        Destinations.SPOTIFY_PLAYER,
    ) && currentRoute !in setOf(
        Destinations.PLAYER,
        Destinations.TVH_PLAYER,
        Destinations.YOUTUBE_PLAYER,
        Destinations.SPOTIFY_PLAYER,
    )

internal enum class SidebarBackAction {
    FOCUS_SETTINGS,
    LEAVE_NESTED_LEVEL,
    RETURN_TO_CONTENT,
}

/** Back leaves the inner menu level, then closes the rail to the current content. */
internal fun sidebarBackAction(
    miniPlayerHasFocus: Boolean,
    hasParentLevel: Boolean,
): SidebarBackAction = when {
    miniPlayerHasFocus -> SidebarBackAction.FOCUS_SETTINGS
    hasParentLevel -> SidebarBackAction.LEAVE_NESTED_LEVEL
    else -> SidebarBackAction.RETURN_TO_CONTENT
}


/**
 * Global Search is shown only when at least one backend [com.ambient.tv.ui.search.SearchViewModel]
 * can query. Files are excluded: [com.ambient.tv.data.files.FilesRepository.search] is a stub
 *. Spotify needs auth + native to actually return results.
 */
internal fun sidebarShowsGlobalSearch(
    plexConfigured: Boolean,
    jellyfinConfigured: Boolean,
    embyConfigured: Boolean,
    tvheadendConfigured: Boolean,
    youtubeEnabled: Boolean,
    spotifyCanSearch: Boolean,
): Boolean = plexConfigured || jellyfinConfigured || embyConfigured ||
    tvheadendConfigured || youtubeEnabled || spotifyCanSearch

/** Home rail entry: systems that contribute home-like rows (Files-only has no Home). */
internal fun sidebarShowsHome(
    plexConfigured: Boolean,
    jellyfinConfigured: Boolean,
    embyConfigured: Boolean,
    tvheadendConfigured: Boolean,
    youtubeEnabled: Boolean,
    spotifyEnabled: Boolean,
): Boolean = plexConfigured || jellyfinConfigured || embyConfigured ||
    tvheadendConfigured || youtubeEnabled || spotifyEnabled


internal data class SidebarNavSystem(
    val id: String,
    val label: String,
    val entries: List<MenuDestination>,
    val active: Boolean,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** One focusable row of the panel. */
internal sealed interface SidebarNavRow {
    /** Thin user-placed separator; never focusable. */
    data object Divider : SidebarNavRow
    data class Item(
        val destination: MenuDestination,
        val active: Boolean,
        val systemId: String? = null,
        /** Main-page system row: opens the system page instead of navigating. */
        val opensPage: Boolean = false,
    ) : SidebarNavRow
}

/** Main page: Search/Home, then one row per system (id "system:<id>"). */
internal fun sidebarMainRows(globals: List<MenuDestination>, systems: List<SidebarNavSystem>, activeRoute: String?): List<SidebarNavRow.Item> =
    globals.map { SidebarNavRow.Item(it, it.route == activeRoute) } +
        systems.filter { it.entries.isNotEmpty() }.map {
            SidebarNavRow.Item(MenuDestination("system:${it.id}", it.label, it.icon, ""), it.active, it.id, opensPage = true)
        }

/** System page: every entry of the system, then its settings shortcut. */
internal fun sidebarPageRows(systemId: String, entries: List<MenuDestination>, settings: MenuDestination?, activeRoute: String?): List<SidebarNavRow.Item> =
    (entries + listOfNotNull(settings)).map { SidebarNavRow.Item(it, it.isActive(activeRoute), systemId) }

/** Entering the panel from content focuses the active row; without one, the safe anchor. */
internal fun sidebarEntryTarget(activeId: String?, visibleIds: Collection<String>, safeAnchor: String): String =
    activeId?.takeIf { it in visibleIds } ?: safeAnchor

/** Active row of a page (null when nothing on it is active). */
internal fun sidebarActiveRowId(rows: List<SidebarNavRow.Item>): String? = rows.firstOrNull { it.active }?.destination?.id

/**
 * Up on the first row wraps to the last, Down on the last row wraps to the first. Null: normal
 * focus search (inner rows, and Down from the last row while the mini player sits below it).
 */
internal fun sidebarWrapTarget(order: List<String>, id: String, up: Boolean, miniPlayerBelow: Boolean): String? {
    val index = order.indexOf(id)
    if (index < 0) return null
    return when {
        up && index == 0 -> order.last()
        !up && index == order.lastIndex && !miniPlayerBelow -> order.first()
        else -> null
    }
}

/** Only a fresh Left press may open the panel; auto-repeat while scrolling a row never does. */
internal fun sidebarEntryArmed(left: Boolean, repeatCount: Int): Boolean = left && repeatCount == 0

/** Scroll so the focused row keeps [pad] px of room on both sides (clamped for tiny viewports). */
internal fun sidebarScrollDistance(offset: Float, size: Float, containerSize: Float, pad: Float): Float {
    val margin = minOf(pad, ((containerSize - size) / 2f).coerceAtLeast(0f))
    val leading = offset - margin
    val trailing = offset + size - (containerSize - margin)
    return when {
        leading < 0f -> leading
        trailing > 0f -> trailing
        else -> 0f
    }
}

/**
 * Panel width in dp from the widest label: row = 14 pad + 20 icon + 14 gap + text + 14 pad (+2 %
 * focus lift), panel padding 2×14; Settings row adds the 6 dp gap and a 48 dp reorder button;
 * the head needs mark 28 + 2 + word mark 133 (28 dp high) + 2×10. Clamped to 200 dp … 35 % of the screen.
 */
internal fun sidebarPanelWidthDp(widestLabelDp: Float, settingsLabelDp: Float, hasReorder: Boolean, screenWidthDp: Float): Float {
    fun row(text: Float) = (14f + 20f + 14f + text + 14f) * 1.02f
    val content = maxOf(
        row(widestLabelDp),
        row(settingsLabelDp) + if (hasReorder) 6f + 48f else 0f,
        10f + 28f + 2f + 133f + 10f,
    )
    val panel = kotlin.math.ceil(content + 2 * 14f)
    return panel.coerceIn(200f, maxOf(200f, screenWidthDp * 0.35f))
}

/** Back/Left on a system page returns to the main page on that system's row. */
internal fun sidebarPageBackTarget(systemId: String): String = "system:$systemId"

/** Insert or remove the divider below [id]. */
internal fun sidebarToggleDivider(current: Set<String>, id: String): Set<String> =
    if (id in current) current - id else current + id

/**
 * Main-page rows with dividers. A divider marked below an entry shows between that entry's
 * visible row and the next visible row; when the marked entry itself is hidden right now (offline,
 * disabled) the divider stays at the same place in the stored order, i.e. above the next visible
 * system. Never after the last row, never twice in a row. Not an Item, so focus order skips it.
 */
internal fun sidebarRowsWithDividers(
    rows: List<SidebarNavRow.Item>,
    dividersAfter: Set<String>,
    storedOrder: List<String> = emptyList(),
): List<SidebarNavRow> = buildList {
    fun key(row: SidebarNavRow.Item) = row.systemId ?: row.destination.id
    rows.forEachIndexed { index, row ->
        add(row)
        if (index == rows.lastIndex) return@forEachIndexed
        val here = key(row)
        val next = key(rows[index + 1])
        val from = storedOrder.indexOf(here)
        val to = storedOrder.indexOf(next)
        val between = if (from >= 0 && to > from) storedOrder.subList(from, to) else listOf(here)
        if (between.any { it in dividersAfter }) add(SidebarNavRow.Divider)
    }
}

/**
 * A system's entries in the user's order: ids from [order] first (unknown ones skipped), entries
 * the order doesn't know yet (a new library) keep their place after them.
 */
internal fun <T> sidebarOrderedEntries(entries: List<T>, order: List<String>?, id: (T) -> String): List<T> {
    if (order.isNullOrEmpty()) return entries
    val byId = entries.associateBy(id)
    val known = order.mapNotNull { byId[it] }
    return known + entries.filterNot { id(it) in order }
}

/** Where focus goes when the wanted row can't take it: always a row on the visible page. */
internal fun sidebarFocusFallback(onPage: Boolean, pageRowIds: List<String>, lastFocused: String?, safeAnchor: String): String =
    if (onPage) pageRowIds.firstOrNull() ?: "settings"
    else lastFocused?.takeIf { it in pageRowIds || it == "settings" || it == "order" } ?: safeAnchor

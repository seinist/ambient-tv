package com.ambient.tv.ui.tvheadend

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.livetv.*
import com.ambient.tv.ui.common.*

private const val KIND = "kind"
private const val ACCESS = "access"
private const val GROUP = "group"
private const val EPG = "epg"

@Composable
internal fun guideFilterModel(options: GuideOptions, tags: List<String>, onChange: (GuideOptions) -> Unit): FilterBarModel {
    val all = stringResource(R.string.library_filter_all)
    val groups = buildList {
        add(PanelOptionGroup(KIND, stringResource(R.string.guide_kind), listOf(
            PanelOption("ALL", all),
            PanelOption("TV", stringResource(R.string.guide_tv)), PanelOption("RADIO", stringResource(R.string.guide_radio)),
            PanelOption("UNKNOWN", stringResource(R.string.guide_unknown))), options.kind?.name ?: "ALL"))
        add(PanelOptionGroup(ACCESS, stringResource(R.string.guide_access), listOf(
            PanelOption("ALL", all),
            PanelOption("FTA", stringResource(R.string.guide_fta)), PanelOption("ENCRYPTED", stringResource(R.string.guide_encrypted)),
            PanelOption("UNKNOWN", stringResource(R.string.guide_unknown))), options.access?.name ?: "ALL"))
        if (tags.isNotEmpty() || options.tag != null) add(PanelOptionGroup(GROUP, stringResource(R.string.guide_group),
            listOf(PanelOption("", all)) + (tags + listOfNotNull(options.tag)).distinct().sorted().map { PanelOption(it, it) },
            options.tag ?: ""))
        add(PanelOptionGroup(EPG, stringResource(R.string.guide_epg), listOf(
            PanelOption("ALL", stringResource(R.string.guide_all_channels)), PanelOption("EPG", stringResource(R.string.guide_with_epg))),
            if (options.onlyWithEpg) "EPG" else "ALL"))
    }
    val orderTitles = if (options.sort == GuideSort.NAME) {
        stringResource(R.string.library_sort_az) to stringResource(R.string.library_sort_za)
    } else {
        stringResource(R.string.library_sort_lowest) to stringResource(R.string.library_sort_highest)
    }
    return FilterBarModel(
        groups = groups,
        onSelect = { id, key -> onChange(when (id) {
            KIND -> options.copy(kind = key.takeUnless { it == "ALL" }?.let(LiveTvChannelKind::valueOf))
            ACCESS -> options.copy(access = key.takeUnless { it == "ALL" }?.let(LiveTvChannelAccess::valueOf))
            GROUP -> options.copy(tag = key.takeIf(String::isNotEmpty))
            else -> options.copy(onlyWithEpg = key == "EPG")
        }) },
        sorts = listOf(PanelOption("NUMBER", stringResource(R.string.guide_sort_number)), PanelOption("NAME", stringResource(R.string.guide_sort_name))),
        selectedSort = options.sort.name,
        onSort = { onChange(options.copy(sort = GuideSort.valueOf(it), descending = false)) },
        sortOrders = sortDirectionOptions(orderTitles.first, orderTitles.second),
        selectedSortOrder = if (options.descending) "DESCENDING" else "ASCENDING",
        onSortOrder = { onChange(options.copy(descending = it == "DESCENDING")) },
        onReset = { onChange(GuideOptions(sort = options.sort, descending = options.descending)) },
    )
}

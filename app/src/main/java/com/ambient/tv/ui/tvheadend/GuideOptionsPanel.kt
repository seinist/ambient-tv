package com.ambient.tv.ui.tvheadend

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.livetv.*
import com.ambient.tv.ui.common.*

@Composable
internal fun GuideOptionsPanel(options: GuideOptions, tags: List<String>, onChange: (GuideOptions) -> Unit, title: String = stringResource(R.string.guide_options), onClose: () -> Unit) {
    RightSidePanel(title = title, onBack = onClose, closeInsteadOfBack = true,
        subtitle = stringResource(R.string.guide_options_hint)) {
        GuideSelect(stringResource(R.string.library_sort), listOf(
            "NUMBER" to stringResource(R.string.guide_sort_number), "NAME" to stringResource(R.string.guide_sort_name)), options.sort.name) {
            onChange(options.copy(sort = GuideSort.valueOf(it), descending = false))
        }
        val orderTitles = if (options.sort == GuideSort.NAME) {
            stringResource(R.string.library_sort_az) to stringResource(R.string.library_sort_za)
        } else {
            stringResource(R.string.library_sort_lowest) to stringResource(R.string.library_sort_highest)
        }
        RightPanelSectionTitle(stringResource(R.string.library_sort_order))
        SortOrderToggle(
            orders = sortDirectionOptions(orderTitles.first, orderTitles.second),
            selectedKey = if (options.descending) "DESCENDING" else "ASCENDING",
            onSelect = { onChange(options.copy(descending = it == "DESCENDING")) },
        )
        GuideSelect(stringResource(R.string.guide_kind), listOf(
            "ALL" to stringResource(R.string.library_filter_all),
            "TV" to stringResource(R.string.guide_tv), "RADIO" to stringResource(R.string.guide_radio),
            "UNKNOWN" to stringResource(R.string.guide_unknown)), options.kind?.name ?: "ALL") {
            onChange(options.copy(kind = it.takeUnless { it == "ALL" }?.let(LiveTvChannelKind::valueOf)))
        }
        GuideSelect(stringResource(R.string.guide_access), listOf(
            "ALL" to stringResource(R.string.library_filter_all),
            "FTA" to stringResource(R.string.guide_fta), "ENCRYPTED" to stringResource(R.string.guide_encrypted),
            "UNKNOWN" to stringResource(R.string.guide_unknown)), options.access?.name ?: "ALL") {
            onChange(options.copy(access = it.takeUnless { it == "ALL" }?.let(LiveTvChannelAccess::valueOf)))
        }
        if (tags.isNotEmpty() || options.tag != null) GuideSelect(stringResource(R.string.guide_group),
            listOf("" to stringResource(R.string.library_filter_all)) + (tags + listOfNotNull(options.tag)).distinct().sorted().map { it to it },
            options.tag ?: "") { onChange(options.copy(tag = it.takeIf(String::isNotEmpty))) }
        GuideSelect(stringResource(R.string.guide_epg), listOf(
            "ALL" to stringResource(R.string.guide_all_channels), "EPG" to stringResource(R.string.guide_with_epg)),
            if (options.onlyWithEpg) "EPG" else "ALL") { onChange(options.copy(onlyWithEpg = it == "EPG")) }
        RightPanelChoice(stringResource(R.string.library_filter_reset), false, { onChange(GuideOptions(sort = options.sort, descending = options.descending)) })
    }
}

@Composable
private fun GuideSelect(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    PlayerDropUpSelector(label = label, value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
        options = options, selectedKey = selected, dropUp = false, onSelected = onSelect)
}

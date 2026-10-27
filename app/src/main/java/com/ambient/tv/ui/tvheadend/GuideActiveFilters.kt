package com.ambient.tv.ui.tvheadend

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.LiveTvChannelAccess
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.ui.common.ActiveFilterChips

@Composable
internal fun guideActiveFilterLabels(options: GuideOptions): List<String> = buildList {
    options.kind?.let {
        val value = stringResource(when (it) {
            LiveTvChannelKind.TV -> R.string.guide_tv
            LiveTvChannelKind.RADIO -> R.string.guide_radio
            LiveTvChannelKind.UNKNOWN -> R.string.guide_unknown
        })
        add(stringResource(R.string.guide_filter_value, stringResource(R.string.guide_kind), value))
    }
    options.access?.let {
        val value = stringResource(when (it) {
            LiveTvChannelAccess.FTA -> R.string.guide_fta
            LiveTvChannelAccess.ENCRYPTED -> R.string.guide_encrypted
            LiveTvChannelAccess.UNKNOWN -> R.string.guide_unknown
        })
        add(stringResource(R.string.guide_filter_value, stringResource(R.string.guide_access), value))
    }
    options.tag?.let { add(stringResource(R.string.guide_filter_value, stringResource(R.string.guide_group), it)) }
    if (options.onlyWithEpg) add(stringResource(R.string.guide_with_epg))
}

/** Focusable chips sit on the D-pad path between header and channels; selecting one opens the options. */
@Composable
internal fun GuideActiveFilters(labels: List<String>, onClick: () -> Unit) {
    ActiveFilterChips(labels, onClick, Modifier.padding(bottom = 12.dp))
}

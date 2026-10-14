package com.ambient.tv.ui.tvheadend

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.LiveTvChannelAccess
import com.ambient.tv.livetv.LiveTvChannelKind

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

/** Non-focusable status keeps the existing header-to-channel D-pad path intact. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuideActiveFilters(labels: List<String>) {
    if (labels.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.guide_active_filters), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach { label ->
                val shape = RoundedCornerShape(8.dp)
                Text(label, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), shape)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .4f), shape)
                        .padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

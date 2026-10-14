package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.data.browse.SourceAvailability
import com.ambient.tv.data.browse.SourceHealth

@Composable
fun OfflineBrowseNotice(availability: SourceAvailability, providers: List<String>, modifier: Modifier = Modifier) {
    val revision = availability.changes.collectAsState().value
    val offline = androidx.compose.runtime.remember(revision, providers) {
        providers.filter { availability.state(it) == SourceHealth.OFFLINE }
    }
    if (offline.isNotEmpty()) Text(
        stringResource(com.ambient.tv.R.string.browse_offline),
        modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .94f), MaterialTheme.shapes.small).padding(8.dp),
        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium,
    )
}

package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.ui.theme.LocalUiTokens

/** The content hierarchy is independent of whether it is shown on a page or in a player panel. */
internal enum class SettingsHeadingLevel { SECTION, GROUP }

@Composable
internal fun SettingsHeading(
    title: String,
    level: SettingsHeadingLevel,
    modifier: Modifier = Modifier,
    description: String? = null,
    firstInContent: Boolean = false,
) {
    val tokens = LocalUiTokens.current
    val section = level == SettingsHeadingLevel.SECTION
    // The surrounding list already contributes its row gap. Add only the remaining
    // separation between sections; a group's title stays attached to its own controls.
    val rowGap = if (LocalSettingsPresentation.current == SettingsPresentation.Page) tokens.compactGap else tokens.itemGap
    val top = if (section && !firstInContent) (tokens.sectionGap - rowGap).coerceAtLeast(0.dp) else 0.dp
    Column(
        modifier = modifier.fillMaxWidth().padding(top = top),
        verticalArrangement = Arrangement.spacedBy(tokens.compactGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.compactGap),
        ) {
            if (section) Box(
                Modifier.size(3.dp, 16.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
            )
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = if (section) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        description?.takeIf(String::isNotBlank)?.let { SettingsHint(it) }
    }
}

/** An optional label inside a section or card, without a second section accent. */
@Composable
internal fun SettingsGroupHeader(title: String, modifier: Modifier = Modifier) =
    SettingsHeading(title, SettingsHeadingLevel.GROUP, modifier)

/** The shell owns this title: embedded controls must not repeat the same page title. */
@Composable
internal fun SettingsPageTitle(title: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        title,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

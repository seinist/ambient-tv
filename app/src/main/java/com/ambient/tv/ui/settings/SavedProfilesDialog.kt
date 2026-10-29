package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.data.local.VideoResolutionClass
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.SettingsSectionHeader
import com.ambient.tv.ui.common.TvActionButton
import java.text.DateFormat
import java.util.Date

/** In cascade order: the first category that holds a value for a setting wins. */
internal enum class SavedProfileCategory { TITLE, SEASON, SERIES, COLLECTION, RESOLUTION, TVHEADEND, DEFAULT }

/** Which preference map an entry lives in. */
internal enum class SavedProfileStore { GLOBAL, RESOLUTION, TITLE, SCOPED }

/** What a saved profile sets; the UI turns it into a short summary line. */
internal enum class SavedProfilePart { RENDERER, PICTURE, DEINTERLACE, OUTPUT, AUDIO, AUDIO_DELAY, SUBTITLE_DELAY }

internal data class SavedProfileEntry(
    val category: SavedProfileCategory,
    val store: SavedProfileStore,
    val key: String,
    /** Source system ("plex", "jellyfin", …) or null for width profiles and channels. */
    val source: String?,
    /** Width label, the stored display name, or the item id for profiles saved before names were kept. */
    val label: String,
    val renderer: String?,
    val parts: List<SavedProfilePart>,
    val savedAt: Long,
    /** Series/titles whose further changes are remembered in this layer ("remember here"). */
    val rememberedBy: Int = 0,
)

internal fun savedProfileEntries(
    resolution: Map<String, ResolutionVideoProfile>,
    titles: Map<String, TitleVideoSettings>,
    scoped: Map<String, TitleVideoSettings>,
    /** The playback settings every video falls back to; edited on the playback page. */
    global: ResolutionVideoProfile? = null,
    rememberTargets: Map<String, Int> = emptyMap(),
): List<SavedProfileEntry> {
    val defaults = listOfNotNull(global?.let {
        SavedProfileEntry(SavedProfileCategory.DEFAULT, SavedProfileStore.GLOBAL, "all", null, "", it.rendererKey, emptyList(), 0L)
    })
    val widths = resolution.filterKeys { it != "all" }.map { (key, profile) ->
        val live = key.startsWith("live:")
        SavedProfileEntry(
            category = if (live) SavedProfileCategory.TVHEADEND else SavedProfileCategory.RESOLUTION,
            store = SavedProfileStore.RESOLUTION, key = key, source = null,
            label = VideoResolutionClass.label(key.removePrefix("live:")),
            renderer = profile.rendererKey,
            parts = listOfNotNull(
                SavedProfilePart.PICTURE.takeIf { profile.mpv != null || profile.vlc != null || profile.media3 != null },
                SavedProfilePart.DEINTERLACE.takeIf { listOf(profile.mpvDeinterlaceMode, profile.mpvDeinterlaceFilter,
                    profile.vlcDeinterlaceMode, profile.vlcDeinterlaceFilter).any { it != null } },
                SavedProfilePart.OUTPUT.takeIf { listOf(profile.mpvOutput, profile.mpvDecoder, profile.vlcOutput).any { it != null } },
                SavedProfilePart.AUDIO.takeIf { profile.audio != null },
            ),
            savedAt = 0L,
        )
    }.sortedBy { it.label.substringBefore(' ').toIntOrNull() ?: Int.MAX_VALUE }
    val titleEntries = titles.map { (key, value) ->
        val channel = key.startsWith("live-channel:")
        settingsEntry(if (channel) SavedProfileCategory.TVHEADEND else SavedProfileCategory.TITLE, SavedProfileStore.TITLE,
            key, if (channel) null else key.substringBefore(':'), value.label ?: lastSegment(key), value)
    }
    val scopedEntries = scoped.mapNotNull { (key, value) ->
        val category = when (key.substringBefore(':')) {
            "collection" -> SavedProfileCategory.COLLECTION
            "series" -> SavedProfileCategory.SERIES
            "season" -> SavedProfileCategory.SEASON
            else -> return@mapNotNull null
        }
        settingsEntry(category, SavedProfileStore.SCOPED, key, key.split(':').getOrNull(1), value.label ?: lastSegment(key), value)
            .copy(rememberedBy = rememberTargets[key] ?: 0)
    }
    return defaults + widths + (titleEntries + scopedEntries).sortedByDescending { it.savedAt }
}

internal fun settingsEntry(
    category: SavedProfileCategory, store: SavedProfileStore, key: String, source: String?, label: String, value: TitleVideoSettings,
) = SavedProfileEntry(
    category = category, store = store, key = key, source = source, label = label,
    renderer = value.rendererKey,
    parts = listOfNotNull(
        SavedProfilePart.RENDERER.takeIf { value.rendererKey != null },
        SavedProfilePart.PICTURE.takeIf { value.mpv != null || value.vlc != null || value.media3 != null },
        SavedProfilePart.DEINTERLACE.takeIf { listOf(value.mpvDeinterlaceMode, value.mpvDeinterlaceFilter,
            value.vlcDeinterlaceMode, value.vlcDeinterlaceFilter).any { it != null } },
        SavedProfilePart.OUTPUT.takeIf { listOf(value.mpvOutput, value.mpvDecoder, value.vlcOutput).any { it != null } },
        SavedProfilePart.AUDIO.takeIf { value.audio != null },
        SavedProfilePart.AUDIO_DELAY.takeIf { (value.audioDelayMs ?: 0) != 0 },
        SavedProfilePart.SUBTITLE_DELAY.takeIf { (value.subtitleDelayMs ?: 0) != 0 },
    ),
    savedAt = value.savedAt,
)

/** Keys end in the item id ("plex:5:abcde:123", "season:jellyfin:3:srv:xyz"). */
private fun lastSegment(key: String) = key.substringAfterLast(':').ifBlank { key }

@Composable
internal fun SavedProfilesDialog(
    entries: List<SavedProfileEntry>,
    onDelete: (SavedProfileEntry) -> Unit,
    onEditLive: () -> Unit,
    onEditDefaults: () -> Unit,
    onForgetRememberTarget: (SavedProfileEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val present = SavedProfileCategory.entries.filter { category -> entries.any { it.category == category } }
    var selected by remember { mutableStateOf(present.firstOrNull()) }
    val category = selected?.takeIf { it in present } ?: present.firstOrNull()
    var confirmKey by remember { mutableStateOf<String?>(null) }
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.saved_profiles_title),
        hint = stringResource(R.string.saved_profiles_hint),
        fillHeight = true,
        widthFraction = 0.72f,
        maxWidth = 920.dp,
    ) {
        SettingsNavigationRow(title = stringResource(R.string.live_profiles_title), onClick = onEditLive)
        if (category == null) {
            Text(stringResource(R.string.settings_playback_overview_empty), style = MaterialTheme.typography.bodySmall)
            return@AppDialog
        }
        SettingsSelect(
            label = stringResource(R.string.settings_playback_profile_type),
            options = present.map { c ->
                SettingsSelectOption(
                    text = "${categoryName(c)} (${entries.count { it.category == c }})",
                    onClick = { selected = c; confirmKey = null },
                    selected = c == category,
                )
            },
        )
        SettingsSectionHeader(categoryName(category))
        entries.filter { it.category == category }.forEach { entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entryTitle(entry), style = MaterialTheme.typography.titleSmall)
                    Text(entrySummary(entry), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.rememberedBy > 0) Text(stringResource(R.string.saved_profiles_remembered_here, entry.rememberedBy),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                SettingsActionRow {
                    if (entry.rememberedBy > 0)
                        TvActionButton(text = stringResource(R.string.saved_profiles_stop_remembering), onClick = { onForgetRememberTarget(entry) })
                    if (entry.store == SavedProfileStore.GLOBAL)
                        TvActionButton(text = stringResource(R.string.saved_profiles_edit), onClick = onEditDefaults)
                    val confirming = confirmKey == entry.key
                    TvActionButton(
                        text = stringResource(when {
                            confirming -> R.string.saved_profiles_delete_confirm
                            entry.store == SavedProfileStore.GLOBAL -> R.string.playback_profile_reset
                            else -> R.string.saved_profiles_delete
                        }),
                        onClick = { if (confirming) { onDelete(entry); confirmKey = null } else confirmKey = entry.key },
                    )
                }
            }
        }
    }
}

@Composable
private fun categoryName(category: SavedProfileCategory) = stringResource(when (category) {
    SavedProfileCategory.DEFAULT -> R.string.saved_profiles_default
    SavedProfileCategory.RESOLUTION -> R.string.saved_profiles_resolution
    SavedProfileCategory.TVHEADEND -> R.string.saved_profiles_tvheadend
    SavedProfileCategory.COLLECTION -> R.string.saved_profiles_collections
    SavedProfileCategory.SERIES -> R.string.saved_profiles_series
    SavedProfileCategory.SEASON -> R.string.saved_profiles_seasons
    SavedProfileCategory.TITLE -> R.string.saved_profiles_titles
})

@Composable
private fun entryTitle(entry: SavedProfileEntry): String = when {
    entry.store == SavedProfileStore.GLOBAL -> stringResource(R.string.saved_profiles_default_title)
    entry.store == SavedProfileStore.RESOLUTION && entry.category == SavedProfileCategory.TVHEADEND ->
        stringResource(R.string.saved_profiles_live_width, entry.label)
    entry.store == SavedProfileStore.RESOLUTION -> entry.label
    entry.category == SavedProfileCategory.TVHEADEND -> if (entry.label == entry.key.substringAfterLast(':'))
        stringResource(R.string.saved_profiles_channel, entry.label) else entry.label
    else -> listOfNotNull(entry.source?.replaceFirstChar { it.uppercase() }, entry.label).joinToString(" · ")
}

@Composable
internal fun entrySummary(entry: SavedProfileEntry): String {
    val parts = buildList {
        entry.renderer?.takeIf { entry.store == SavedProfileStore.RESOLUTION || SavedProfilePart.RENDERER in entry.parts }
            ?.let { add(rendererName(it)) }
        entry.parts.filter { it != SavedProfilePart.RENDERER }.forEach { part ->
            add(stringResource(when (part) {
                SavedProfilePart.PICTURE -> R.string.saved_profiles_part_picture
                SavedProfilePart.DEINTERLACE -> R.string.saved_profiles_part_deinterlace
                SavedProfilePart.OUTPUT -> R.string.saved_profiles_part_output
                SavedProfilePart.AUDIO -> R.string.saved_profiles_part_audio
                SavedProfilePart.AUDIO_DELAY -> R.string.saved_profiles_part_audio_delay
                SavedProfilePart.SUBTITLE_DELAY -> R.string.saved_profiles_part_subtitle_delay
                SavedProfilePart.RENDERER -> R.string.settings_playback_overview_renderer
            }))
        }
        if (entry.savedAt > 0L) add(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.savedAt)))
    }
    return parts.joinToString(" · ")
}

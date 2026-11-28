package com.ambient.tv.ui.games

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.library.gameDisplayTitle
import com.ambient.tv.games.library.gameTitleLanguageLabel
import com.ambient.tv.games.library.gameTitleRegionLabel
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameMetadataCandidate
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.noSurfaceBorder
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.detail.DetailContextAction
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.animatedFocusSurface
import java.io.File
import kotlinx.coroutines.CancellationException

private sealed interface GameEditPage {
    data object Overview : GameEditPage
    data object Fields : GameEditPage
    data object Match : GameEditPage
    data class Artwork(val kind: GameArtworkKind) : GameEditPage
}

/** Same overview → match/artwork/fields structure and dialog chrome as the media editor. */
@Composable
internal fun GameMetadataEditor(
    game: LibraryGame,
    state: GameDetailUiState,
    model: GameDetailViewModel,
    onDismiss: () -> Unit,
) {
    var page by remember(game.fileId) { mutableStateOf<GameEditPage>(GameEditPage.Overview) }
    val back = {
        model.clearSearch()
        if (page == GameEditPage.Overview) onDismiss() else page = GameEditPage.Overview
    }
    val done: (Boolean) -> Unit = { success -> if (success) back() }
    val kind = (page as? GameEditPage.Artwork)?.kind
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && kind != null) model.importArtwork(kind, uri, done)
    }
    when (val current = page) {
        GameEditPage.Overview -> AppDialog(
            onDismiss = back,
            title = stringResource(R.string.item_edit_title, game.title),
            widthFraction = if (LocalHandheldUi.current) null else .5f,
            maxWidth = 640.dp,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailContextAction(stringResource(R.string.game_edit_match), Icons.Filled.Search,
                    onClick = { page = GameEditPage.Match })
                DetailContextAction(stringResource(R.string.game_edit_cover), Icons.Filled.Image,
                    onClick = { page = GameEditPage.Artwork(GameArtworkKind.COVER) })
                DetailContextAction(stringResource(R.string.game_edit_backdrop), Icons.Filled.Wallpaper,
                    onClick = { page = GameEditPage.Artwork(GameArtworkKind.BACKDROP) })
                DetailContextAction(stringResource(R.string.game_edit_fields), Icons.Filled.Edit,
                    onClick = { page = GameEditPage.Fields })
            }
        }
        GameEditPage.Fields -> GameMetadataFieldsDialog(game, state, onBack = back,
            onSave = { value, locked -> model.save(value, locked, done) })
        GameEditPage.Match, is GameEditPage.Artwork -> {
            val imageKind = (current as? GameEditPage.Artwork)?.kind
            var selectedProvider by remember(game.fileId, current) { mutableStateOf(state.metadata.provider) }
            val provider = selectedProvider.takeIf { it in state.providers }
                ?: state.providers.firstOrNull().orEmpty()
            var query by remember(game.fileId, current) { mutableStateOf(
                gameMetadataSearchTitle(game, state.metadata, provider)) }
            var queryEdited by remember(game.fileId, current) { mutableStateOf(false) }
            val canSearch = query.isNotBlank() && provider.isNotBlank() && !state.searching && !state.busy
            val submitSearch = { if (canSearch) model.search(query, provider, imageKind) }
            LaunchedEffect(provider, current) {
                if (!queryEdited && provider.isNotBlank()) query = gameMetadataSearchTitle(game, state.metadata, provider)
                if (imageKind != null && state.metadata.providerGameId.isNotBlank() &&
                    provider == state.metadata.provider) model.artworkChoices(imageKind)
                else if (provider.isNotBlank()) model.search(query, provider, imageKind)
            }
            AppDialog(
                onDismiss = back,
                title = stringResource(when (imageKind) {
                    GameArtworkKind.COVER -> R.string.game_edit_cover
                    GameArtworkKind.BACKDROP -> R.string.game_edit_backdrop
                    else -> R.string.game_edit_match
                }),
                hint = stringResource(if (imageKind == null) R.string.game_edit_search_hint else R.string.game_edit_artwork_hint),
                scrollable = false,
                // The search field owns initial focus; do not let the dialog's generic
                // endpoint traversal land on a provider, result or footer instead.
                captureInitialFocus = false,
                widthFraction = if (LocalHandheldUi.current) null else .65f,
                maxWidth = 880.dp,
                maxHeightFraction = .92f,
            ) {
                var queryInitiallyFocused by remember(game.fileId, current) { mutableStateOf(false) }
                val choices = remember(state.matches, imageKind) {
                    (if (imageKind == null) state.matches else state.matches.filter { it.metadata.artwork(imageKind) != null })
                        .distinctBy { gameMetadataChoiceKey(it, imageKind) }
                }
                // Keep the controls in the same scrollable viewport as the results. Fixed
                // controls can otherwise consume the whole dialog in landscape or above the IME.
                TvLazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    wrapVerticalFocus = false) {
                    item("query") {
                        GameMetadataSearchField(
                            query, { changed ->
                                val next = changed.take(256)
                                if (query != next) {
                                    query = next
                                    queryEdited = true
                                    model.clearSearch()
                                }
                            },
                            requestInitialFocus = !queryInitiallyFocused,
                            onInitialFocus = { queryInitiallyFocused = true },
                            onSearch = submitSearch.takeIf { canSearch },
                        )
                    }
                    item("providers") { DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.providers.forEach { id ->
                            TvChip(gameMetadataProviderName(id), selected = provider == id,
                                onClick = {
                                    if (provider != id) {
                                        query = gameMetadataProviderSearchText(game, state.metadata, id, query, queryEdited)
                                        selectedProvider = id
                                    }
                                }, enabled = !state.busy)
                        }
                    } }
                    item("actions") { DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvActionButton(stringResource(R.string.action_search),
                            onClick = submitSearch,
                            enabled = canSearch,
                            loading = state.searching,
                            icon = Icons.Filled.Search)
                        if (imageKind != null) TvActionButton(stringResource(R.string.game_edit_local_image),
                            onClick = { picker.launch(arrayOf("image/*")) }, enabled = !state.busy,
                            icon = Icons.Filled.Image)
                        if (imageKind != null && imageKind.key in state.metadata.lockedFields) {
                            TvActionButton(stringResource(R.string.game_edit_artwork_auto),
                                onClick = { model.save(state.metadata, state.metadata.lockedFields - imageKind.key, done) },
                                enabled = !state.busy, icon = Icons.Filled.LockOpen)
                        }
                    } }
                    if (state.searching || state.busy) item("loading") {
                        Text(stringResource(R.string.player_loading), style = MaterialTheme.typography.bodySmall)
                    }
                    state.error?.let { error -> item("error") {
                        Text(error, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    } }
                    if (state.searchCompleted && choices.isEmpty() && !state.searching) item("empty") {
                        Text(stringResource(if (imageKind == null) R.string.game_edit_no_matches else R.string.game_edit_no_artwork),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    items(choices, key = { gameMetadataChoiceKey(it, imageKind) }) { match ->
                        GameMetadataMatchChoice(match, imageKind, model, enabled = !state.busy,
                            onClick = {
                                if (imageKind == null) model.applyMatch(match, done)
                                else model.setArtwork(imageKind, match, done)
                            })
                    }
                }
            }
        }
    }
}

/** The query is the first focus target on TV, without automatically opening the IME. */
@Composable
internal fun GameMetadataSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = true,
    onInitialFocus: () -> Unit = {},
    onSearch: (() -> Unit)? = null,
) {
    val handheld = LocalHandheldUi.current
    val requester = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // A lazy item can be disposed when scrolled off screen. Do not steal focus on return:
    // initial focus belongs to the dialog instance, not to the lazy item.
    TvTextField(query, onQueryChange, stringResource(R.string.action_search),
        modifier.fillMaxWidth().focusRequester(requester),
        requireExplicitEditing = !handheld,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
            onSearch?.let { submit -> keyboard?.hide(); submit() }
        }),
    )
    if (!handheld && requestInitialFocus) {
        LaunchedEffect(requester) {
            repeat(10) {
                androidx.compose.runtime.withFrameNanos { }
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                    onInitialFocus()
                    return@LaunchedEffect
                }
            }
        }
    }
}

/** Suggest the current verified/provider name without its regional display annotations. */
internal fun gameMetadataSearchTitle(
    game: LibraryGame,
    metadata: GameMetadata,
    provider: String = metadata.provider.ifBlank { com.ambient.tv.games.metadata.GameMetadataProviderIds.LIBRETRO },
): String {
    val request = com.ambient.tv.games.metadata.gameMetadataQuery(game, metadata, "", "", null, provider = provider)
    return if (request.canonicalTitle != null)
        gameDisplayTitle(request.title)
    else com.ambient.tv.games.metadata.gameMetadataSearchName(request.title)
}

/** Provider changes may replace a suggested query, but never text the user entered. */
internal fun gameMetadataProviderSearchText(
    game: LibraryGame, metadata: GameMetadata, provider: String, query: String, edited: Boolean,
): String = if (edited) query else gameMetadataSearchTitle(game, metadata, provider)

/** DAT editions retain distinct region labels even when their primary titles look identical. */
internal fun gameMetadataChoiceRegion(metadata: GameMetadata): String? =
    gameTitleRegionLabel(metadata.title) ?: gameTitleRegionLabel(metadata.canonicalTitle)
        ?: metadata.region.takeIf(String::isNotBlank)

/** Provider response language is a search preference; only ROM name tags identify its editions. */
internal fun gameMetadataChoiceLanguages(metadata: GameMetadata): String? =
    gameTitleLanguageLabel(metadata.title) ?: gameTitleLanguageLabel(metadata.canonicalTitle)

@Composable
private fun GameMetadataMatchChoice(
    match: GameMetadataCandidate,
    imageKind: GameArtworkKind?,
    model: GameDetailViewModel,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Lazy rows request previews only while composed; provider credentials never reach Coil.
    val preview by produceState<String?>(null, match, imageKind) {
        value = try { model.previewArtwork(match, imageKind ?: GameArtworkKind.COVER) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    GameMetadataMatchRow(match, imageKind, preview, enabled, onClick)
}

/** Identical match/artwork controls for touch, remote OK and physical keyboards. */
@Composable
internal fun GameMetadataMatchRow(
    match: GameMetadataCandidate,
    imageKind: GameArtworkKind?,
    preview: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val provider = imageKind?.let { match.metadata.artworkProviders[it.key] }
        ?: match.metadata.provider
    val source = imageKind?.let { match.metadata.artworkSources[it.key] }
        ?: match.metadata.sourceUrl
    val displayTitle = gameDisplayTitle(match.metadata.title)
    val sourceHost = remember(source) {
        source?.takeIf { it.startsWith("https://") }?.let {
            runCatching { java.net.URI(it).host }.getOrNull()
        }
    }
    // Keep the focus outline outside Surface's disabled layers while its saving content dims.
    Surface(onClick = onClick, enabled = enabled,
        colors = transparentSurfaceColors(), scale = noSurfaceScale(),
        border = noSurfaceBorder(), shape = ClickableSurfaceDefaults.shape(shape),
        modifier = modifier.fillMaxWidth().activateOnTvKey(enabled, onClick)
            .focusProperties { canFocus = enabled || focused }
            .onFocusChanged { focused = it.isFocused }
            .animatedFocusSurface(focused = focused, shape = shape,
                focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = .19f),
                unfocusedBackground = Color.White.copy(alpha = .045f),
                focusedBorder = MaterialTheme.colorScheme.primary,
                unfocusedBorder = Color.White.copy(alpha = .10f), borderWidth = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ArtworkImage(preview?.let(::File), displayTitle,
                modifier = Modifier.width(if (imageKind == GameArtworkKind.BACKDROP) 120.dp else 70.dp)
                    .aspectRatio(if (imageKind == GameArtworkKind.BACKDROP) 16f / 9f else 2f / 3f),
                contentScale = ContentScale.Fit)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayTitle, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(match.metadata.year?.toString(),
                    gameMetadataChoiceRegion(match.metadata),
                    gameMetadataChoiceLanguages(match.metadata),
                    gameMetadataProviderName(provider)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                sourceHost?.let { Text(stringResource(R.string.game_detail_source, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                match.metadata.summary.takeIf(String::isNotBlank)?.let {
                    Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Pure validation keeps an invalid year from silently becoming a destructive empty edit. */
internal fun gameMetadataYearIsValid(value: String): Boolean =
    value.isBlank() || value.trim().toIntOrNull()?.let { it in 1800..2200 } == true

/** Several covers of the same identified game must remain separate, stable lazy-list items. */
internal fun gameMetadataChoiceKey(candidate: GameMetadataCandidate, kind: GameArtworkKind?): String =
    listOf(candidate.metadata.provider, candidate.metadata.providerGameId, candidate.metadata.canonicalTitle,
        candidate.metadata.artwork(kind ?: GameArtworkKind.COVER).orEmpty()).joinToString("\u0000")

@Composable
private fun GameMetadataFieldsDialog(
    game: LibraryGame,
    state: GameDetailUiState,
    onBack: () -> Unit,
    onSave: (GameMetadata, Set<String>) -> Unit,
) {
    val initial = remember(game.fileId) {
        state.metadata.copy(title = state.metadata.title.ifBlank { game.title })
    }
    var value by remember(game.fileId) { mutableStateOf(initial) }
    var year by remember(game.fileId) { mutableStateOf(initial.year?.toString().orEmpty()) }
    var genres by remember(game.fileId) { mutableStateOf(initial.genres.joinToString(", ")) }
    var locks by remember(game.fileId) { mutableStateOf(initial.lockedFields) }
    val validYear = gameMetadataYearIsValid(year)
    val field: @Composable (String, String, Int, Boolean, (String) -> Unit) -> Unit = { key, text, label, multiline, update ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvTextField(text, { changed ->
                update(changed)
                locks = locks + key
            }, stringResource(label), Modifier.weight(1f).then(if (multiline) Modifier.heightIn(max = 190.dp) else Modifier),
                singleLine = !multiline)
            TvActionButton(stringResource(if (key in locks) R.string.game_edit_locked else R.string.game_edit_unlocked),
                onClick = { locks = if (key in locks) locks - key else locks + key },
                selected = key in locks,
                icon = if (key in locks) Icons.Filled.Lock else Icons.Filled.LockOpen)
        }
    }
    AppDialog(
        onDismiss = onBack,
        title = stringResource(R.string.game_edit_fields),
        hint = stringResource(R.string.game_edit_lock_hint),
        maxWidth = 900.dp, maxHeightFraction = .92f,
        footer = {
            TvActionButton(stringResource(R.string.files_cancel), onClick = onBack)
            TvActionButton(stringResource(R.string.files_save), enabled = validYear && !state.busy,
                onClick = {
                    val edited = value.copy(year = year.trim().toIntOrNull(),
                        genres = genres.split(',').map(String::trim).filter(String::isNotBlank)).bounded()
                    onSave(edited, locks)
                })
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            field("title", value.title, R.string.game_edit_title, false) { value = value.copy(title = it.take(256)) }
            field("summary", value.summary, R.string.game_edit_summary, true) { value = value.copy(summary = it.take(16_000)) }
            field("year", year, R.string.game_edit_year, false) { year = it.take(5) }
            if (!validYear) Text(stringResource(R.string.game_edit_year_invalid), color = MaterialTheme.colorScheme.error)
            field("genres", genres, R.string.game_edit_genres, false) { genres = it.take(1600) }
            field("developer", value.developer, R.string.game_edit_developer, false) { value = value.copy(developer = it.take(256)) }
            field("publisher", value.publisher, R.string.game_edit_publisher, false) { value = value.copy(publisher = it.take(256)) }
            field("players", value.players, R.string.game_edit_players, false) { value = value.copy(players = it.take(80)) }
            field("region", value.region, R.string.game_edit_region, false) { value = value.copy(region = it.take(80)) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

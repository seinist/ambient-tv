package com.ambient.tv.ui.detail

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.ArtworkChoice
import com.ambient.tv.domain.model.ArtworkKind
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MetadataEditSupport
import com.ambient.tv.domain.model.MetadataField
import com.ambient.tv.domain.model.MetadataFields
import com.ambient.tv.domain.model.MetadataMatch
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.formatResolution
import com.ambient.tv.ui.common.noSurfaceBorder
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Server answer for one edit page: null before the first request. */
sealed interface EditChoices<out T> {
    data object Loading : EditChoices<Nothing>
    data object Failed : EditChoices<Nothing>
    data class Loaded<T>(val items: List<T>) : EditChoices<T>
}

/** Fix match, artwork and text fields of the detail item; only reachable for server administrators. */
@HiltViewModel
class ItemEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
) : ViewModel() {
    private val ratingKey: String = checkNotNull(savedStateHandle["ratingKey"])
    private val _matches = MutableStateFlow<EditChoices<MetadataMatch>?>(null)
    val matches: StateFlow<EditChoices<MetadataMatch>?> = _matches.asStateFlow()
    private val _artwork = MutableStateFlow<EditChoices<ArtworkChoice>?>(null)
    val artwork: StateFlow<EditChoices<ArtworkChoice>?> = _artwork.asStateFlow()
    private val _fields = MutableStateFlow<EditChoices<MetadataFields>?>(null)
    /** One entry once loaded: the item's current text fields. */
    val fields: StateFlow<EditChoices<MetadataFields>?> = _fields.asStateFlow()
    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()
    private var loadJob: Job? = null
    /** Physical items an edit goes to: the opened one, or the versions picked for a merged title. */
    private var targets: List<String> = listOf(ratingKey)
    private val primary get() = targets.first()

    fun selectTargets(keys: List<String>) {
        targets = keys.ifEmpty { listOf(ratingKey) }
    }

    fun searchMatches(title: String?, year: Int?) = load(_matches) { repository.searchMatches(primary, title, year) }
    fun loadArtwork(kind: ArtworkKind) = load(_artwork) { repository.artworkChoices(primary, kind) }
    fun loadFields() = load(_fields) { repository.metadataFields(primary).map(::listOf) }

    fun applyMatch(match: MetadataMatch, onDone: (Boolean) -> Unit) = applyToTargets(onDone) { repository.applyMatch(it, match) }
    fun setArtwork(kind: ArtworkKind, choice: ArtworkChoice, onDone: (Boolean) -> Unit) =
        apply(onDone) { repository.setArtwork(primary, kind, choice) }
    fun saveFields(current: MetadataFields, edited: MetadataFields, onDone: (Boolean) -> Unit) =
        applyToTargets(onDone) { repository.updateMetadataFields(it, current, edited) }

    fun reset() {
        loadJob?.cancel()
        _matches.value = null
        _artwork.value = null
        _fields.value = null
    }

    private fun <T> load(target: MutableStateFlow<EditChoices<T>?>, request: suspend () -> Result<List<T>>) {
        loadJob?.cancel()
        target.value = EditChoices.Loading
        loadJob = viewModelScope.launch {
            target.value = request().fold({ EditChoices.Loaded(it) }, { EditChoices.Failed })
        }
    }

    /** Stops at the first refusal; versions written before it keep their change. */
    private fun applyToTargets(onDone: (Boolean) -> Unit, request: suspend (String) -> Result<Unit>) = apply(onDone) {
        targets.fold(Result.success(Unit)) { result, key -> result.mapCatching { request(key).getOrThrow() } }
    }

    private fun apply(onDone: (Boolean) -> Unit, request: suspend () -> Result<Unit>) {
        if (_applying.value) return
        _applying.value = true
        viewModelScope.launch {
            val success = request().isSuccess
            // Also after a partial failure: some versions may already carry the change.
            repository.forgetMerged(targets.toSet() + ratingKey)
            _applying.value = false
            onDone(success)
        }
    }
}

private sealed interface EditPage {
    data object Overview : EditPage
    data object Match : EditPage
    data class Artwork(val kind: ArtworkKind) : EditPage
    data object Fields : EditPage
    /** Version choice of a merged title before [next]; artwork can only go to one version. */
    data class Versions(val next: EditPage) : EditPage
}

/** One physical item of a merged title, as the version choice shows it. */
internal data class EditVersion(val ratingKey: String, val fileName: String?, val details: String?)

internal fun editVersions(item: MediaItem): List<EditVersion> = item.allRatingKeys.map { key ->
    val media = item.mediaVersions.filter { (it.sourceRatingKey ?: item.ratingKey) == key }
    EditVersion(
        ratingKey = key,
        fileName = media.firstNotNullOfOrNull { it.fileName?.takeIf(String::isNotBlank) },
        details = media.mapNotNull { version -> version.resolution?.takeIf(String::isNotBlank)?.let(::formatResolution) }
            .distinct().joinToString(" · ").ifEmpty { null },
    )
}

/** Starts loading [next] for [keys] and returns it as the page to show. */
private fun ItemEditViewModel.open(next: EditPage, keys: List<String>): EditPage {
    selectTargets(keys)
    when (next) {
        EditPage.Match -> searchMatches(null, null)
        is EditPage.Artwork -> loadArtwork(next.kind)
        EditPage.Fields -> loadFields()
        EditPage.Overview, is EditPage.Versions -> Unit
    }
    return next
}

/** Entry point of the detail page's "Edit…" action. [onEdited] gets whether the server accepted a change. */
@Composable
internal fun ItemEditDialog(
    item: MediaItem,
    support: MetadataEditSupport,
    onDismiss: () -> Unit,
    onEdited: (Boolean) -> Unit,
    viewModel: ItemEditViewModel = hiltViewModel(),
) {
    var page by remember(item.ratingKey) { mutableStateOf<EditPage>(EditPage.Overview) }
    val back: () -> Unit = {
        viewModel.reset()
        if (page == EditPage.Overview) onDismiss() else page = EditPage.Overview
    }
    val finish: (Boolean) -> Unit = { success ->
        viewModel.reset()
        onDismiss()
        onEdited(success)
    }
    when (val current = page) {
        EditPage.Overview -> EditOverview(item, support, onBack = back) { next ->
            if (item.allRatingKeys.size > 1) page = EditPage.Versions(next) else page = viewModel.open(next, listOf(item.ratingKey))
        }
        is EditPage.Versions -> VersionsPage(
            item = item,
            allowAll = current.next !is EditPage.Artwork,
            onBack = back,
        ) { keys -> page = viewModel.open(current.next, keys) }
        EditPage.Match -> MatchPage(item, viewModel, onBack = back, onApplied = finish)
        is EditPage.Artwork -> ArtworkPage(item, current.kind, viewModel, onBack = back, onApplied = finish)
        EditPage.Fields -> FieldsPage(viewModel, onBack = back, onApplied = finish)
    }
}

@Composable
private fun EditOverview(item: MediaItem, support: MetadataEditSupport, onBack: () -> Unit, onOpen: (EditPage) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { firstFocus.requestFocus() } }
    AppDialog(
        onDismiss = onBack,
        title = stringResource(R.string.item_edit_title, item.title),
        hint = stringResource(R.string.item_edit_hint),
        captureInitialFocus = false,
        widthFraction = if (LocalHandheldUi.current) null else 0.5f,
        maxWidth = 640.dp,
    ) {
        val actions = buildList {
            if (support.match) add(Triple(R.string.item_edit_match, Icons.AutoMirrored.Filled.ManageSearch, EditPage.Match))
            if (ArtworkKind.POSTER in support.artwork) add(Triple(R.string.item_edit_poster, Icons.Filled.Image, EditPage.Artwork(ArtworkKind.POSTER)))
            if (ArtworkKind.BACKDROP in support.artwork) add(Triple(R.string.item_edit_backdrop, Icons.Filled.Wallpaper, EditPage.Artwork(ArtworkKind.BACKDROP)))
            if (support.fields) add(Triple(R.string.item_edit_fields, Icons.Filled.Edit, EditPage.Fields))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEachIndexed { index, (label, icon, target) ->
                DetailContextAction(
                    text = stringResource(label),
                    icon = icon,
                    onClick = { onOpen(target) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun VersionsPage(item: MediaItem, allowAll: Boolean, onBack: () -> Unit, onPick: (List<String>) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { firstFocus.requestFocus() } }
    val versions = remember(item) { editVersions(item) }
    AppDialog(
        onDismiss = onBack,
        title = stringResource(R.string.item_edit_versions_title),
        hint = stringResource(R.string.item_edit_versions_hint),
        captureInitialFocus = false,
        widthFraction = if (LocalHandheldUi.current) null else 0.6f,
        maxWidth = 820.dp,
        maxHeightFraction = 0.9f,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allowAll) {
                DetailContextAction(
                    text = stringResource(R.string.item_edit_versions_all),
                    icon = Icons.Filled.LibraryAddCheck,
                    onClick = { onPick(versions.map { it.ratingKey }) },
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            versions.forEachIndexed { index, version ->
                EditChoiceSurface(
                    onClick = { onPick(listOf(version.ratingKey)) },
                    modifier = Modifier.fillMaxWidth().then(if (!allowAll && index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                ) { _ ->
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            version.fileName ?: stringResource(R.string.item_edit_version_n, index + 1),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        version.details?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchPage(item: MediaItem, viewModel: ItemEditViewModel, onBack: () -> Unit, onApplied: (Boolean) -> Unit) {
    val matches by viewModel.matches.collectAsState()
    val applying by viewModel.applying.collectAsState()
    var title by remember { mutableStateOf(item.title) }
    var year by remember { mutableStateOf(item.year?.toString().orEmpty()) }
    var pending by remember { mutableStateOf<MetadataMatch?>(null) }
    val firstResult = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(matches) {
        val loaded = matches as? EditChoices.Loaded ?: return@LaunchedEffect
        delay(60)
        runCatching { if (loaded.items.isNotEmpty()) firstResult.requestFocus() else searchFocus.requestFocus() }
    }
    AppDialog(
        onDismiss = onBack,
        title = stringResource(R.string.item_edit_match),
        hint = stringResource(R.string.item_edit_match_hint),
        // Error or empty pages have only Back to focus; loaded content takes focus explicitly.
        captureInitialFocus = true,
        widthFraction = if (LocalHandheldUi.current) null else 0.68f,
        maxWidth = 860.dp,
        maxHeightFraction = 0.9f,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvTextField(title, { title = it }, stringResource(R.string.item_edit_search_title), Modifier.weight(1f))
            TvTextField(
                year,
                { value -> year = value.filter(Char::isDigit).take(4) },
                stringResource(R.string.item_edit_search_year),
                Modifier.width(deviceDp(120.dp, 88.dp)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TvActionButton(
                text = stringResource(R.string.item_edit_search),
                onClick = { viewModel.searchMatches(title.trim(), year.toIntOrNull()) },
                loading = matches == EditChoices.Loading,
                modifier = Modifier.focusRequester(searchFocus),
            )
        }
        when (val state = matches) {
            null, EditChoices.Loading -> EditMessage(stringResource(R.string.player_loading))
            EditChoices.Failed -> EditMessage(stringResource(R.string.item_edit_load_failed))
            is EditChoices.Loaded -> if (state.items.isEmpty()) {
                EditMessage(stringResource(R.string.item_edit_no_matches))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.items.forEachIndexed { index, match ->
                        MatchRow(
                            match = match,
                            onClick = { pending = match },
                            modifier = if (index == 0) Modifier.focusRequester(firstResult) else Modifier,
                        )
                    }
                }
            }
        }
    }
    pending?.let { match ->
        val label = listOfNotNull(match.title, match.year?.toString()).joinToString(" · ")
        AppDialog(
            onDismiss = { if (!applying) pending = null },
            title = stringResource(R.string.item_edit_apply_match, label),
            hint = stringResource(if (applying) R.string.item_edit_updating else R.string.item_edit_apply_match_body),
            footer = {
                TvActionButton(text = stringResource(R.string.back), onClick = { pending = null }, enabled = !applying)
                TvActionButton(
                    text = stringResource(R.string.item_edit_apply),
                    onClick = { viewModel.applyMatch(match) { success -> pending = null; onApplied(success) } },
                    loading = applying,
                )
            },
        )
    }
}

@Composable
private fun MatchRow(match: MetadataMatch, onClick: () -> Unit, modifier: Modifier = Modifier) {
    EditChoiceSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) { _ ->
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                model = match.thumbUrl,
                title = match.title,
                modifier = Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = listOfNotNull(match.title, match.year?.let { "($it)" }).joinToString(" "),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(match.source, match.score?.let { "$it %" }).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                match.overview?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtworkPage(item: MediaItem, kind: ArtworkKind, viewModel: ItemEditViewModel, onBack: () -> Unit, onApplied: (Boolean) -> Unit) {
    val artwork by viewModel.artwork.collectAsState()
    val applying by viewModel.applying.collectAsState()
    var focused by remember { mutableStateOf<ArtworkChoice?>(null) }
    val firstChoice = remember { FocusRequester() }
    LaunchedEffect(artwork) {
        if ((artwork as? EditChoices.Loaded)?.items?.isNotEmpty() == true) { delay(60); runCatching { firstChoice.requestFocus() } }
    }
    val poster = kind == ArtworkKind.POSTER
    val cardWidth = if (poster) deviceDp(120.dp, 96.dp) else deviceDp(224.dp, 160.dp)
    val cardHeight = if (poster) cardWidth * 1.5f else cardWidth * 9f / 16f
    AppDialog(
        onDismiss = { if (!applying) onBack() },
        title = stringResource(if (poster) R.string.item_edit_poster else R.string.item_edit_backdrop),
        hint = if (applying) stringResource(R.string.item_edit_updating) else focused?.let(::artworkDetails)?.takeIf(String::isNotBlank) ?: item.title,
        // Error or empty pages have only Back to focus; loaded content takes focus explicitly.
        captureInitialFocus = true,
        widthFraction = if (LocalHandheldUi.current) null else 0.8f,
        maxWidth = 1080.dp,
        maxHeightFraction = 0.9f,
    ) {
        when (val state = artwork) {
            null, EditChoices.Loading -> EditMessage(stringResource(R.string.player_loading))
            EditChoices.Failed -> EditMessage(stringResource(R.string.item_edit_load_failed))
            is EditChoices.Loaded -> if (state.items.isEmpty()) {
                EditMessage(stringResource(R.string.item_edit_no_artwork))
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.items.forEachIndexed { index, choice ->
                        EditChoiceSurface(
                            onClick = { viewModel.setArtwork(kind, choice, onApplied) },
                            enabled = !applying,
                            modifier = Modifier
                                .width(cardWidth)
                                .height(cardHeight)
                                .then(if (index == 0) Modifier.focusRequester(firstChoice) else Modifier)
                                .onFocusChanged { if (it.isFocused) focused = choice },
                        ) { _ ->
                            Box(Modifier.fillMaxSize()) {
                                ArtworkImage(model = choice.previewUrl, title = item.title, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                                if (choice.selected) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(6.dp)
                                            .background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(stringResource(R.string.item_edit_current), style = MaterialTheme.typography.labelSmall, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldsPage(viewModel: ItemEditViewModel, onBack: () -> Unit, onApplied: (Boolean) -> Unit) {
    val state by viewModel.fields.collectAsState()
    val applying by viewModel.applying.collectAsState()
    val current = (state as? EditChoices.Loaded)?.items?.firstOrNull()
    var edited by remember(current) { mutableStateOf(current) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(current) { if (current != null) { delay(60); runCatching { firstField.requestFocus() } } }
    AppDialog(
        onDismiss = { if (!applying) onBack() },
        title = stringResource(R.string.item_edit_fields),
        hint = stringResource(if (applying) R.string.item_edit_updating else R.string.item_edit_fields_hint),
        // Error or empty pages have only Back to focus; loaded content takes focus explicitly.
        captureInitialFocus = true,
        widthFraction = if (LocalHandheldUi.current) null else 0.64f,
        maxWidth = 860.dp,
        maxHeightFraction = 0.9f,
        footer = {
            TvActionButton(text = stringResource(R.string.back), onClick = onBack, enabled = !applying)
            val draft = edited
            TvActionButton(
                text = stringResource(R.string.item_edit_save),
                onClick = { if (current != null && draft != null) viewModel.saveFields(current, draft, onApplied) },
                enabled = current != null && draft != null && draft != current && draft[MetadataField.TITLE].isNotBlank(),
                loading = applying,
            )
        },
    ) {
        val draft = edited
        when {
            state == EditChoices.Failed -> EditMessage(stringResource(R.string.item_edit_load_failed))
            current == null || draft == null -> EditMessage(stringResource(R.string.player_loading))
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FIELD_LABELS.forEach { (field, label) ->
                    FieldRow(
                        field = field,
                        label = stringResource(label),
                        fields = draft,
                        original = current,
                        modifier = if (field == MetadataField.TITLE) Modifier.focusRequester(firstField) else Modifier,
                    ) { edited = it }
                }
            }
        }
    }
}

private val FIELD_LABELS = listOf(
    MetadataField.TITLE to R.string.item_edit_search_title,
    MetadataField.SORT_TITLE to R.string.item_edit_field_sort_title,
    MetadataField.ORIGINAL_TITLE to R.string.item_edit_field_original_title,
    MetadataField.YEAR to R.string.item_edit_search_year,
    MetadataField.SUMMARY to R.string.item_edit_field_summary,
)

@Composable
private fun FieldRow(
    field: MetadataField,
    label: String,
    fields: MetadataFields,
    original: MetadataFields,
    modifier: Modifier = Modifier,
    onChange: (MetadataFields) -> Unit,
) {
    val year = field == MetadataField.YEAR
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTextField(
            value = fields[field],
            onValueChange = { raw ->
                val value = if (year) raw.filter(Char::isDigit).take(4) else raw
                // A typed value is locked right away, otherwise the next refresh would undo it;
                // typing the original value back restores the original lock as well.
                val locked = when {
                    field !in fields.lockable -> fields.locked
                    value.trim() == original[field].trim() -> if (field in original.locked) fields.locked + field else fields.locked - field
                    else -> fields.locked + field
                }
                onChange(fields.copy(values = fields.values + (field to value), locked = locked))
            },
            label = label,
            modifier = modifier.weight(1f),
            singleLine = field != MetadataField.SUMMARY,
            keyboardOptions = if (year) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        )
        if (field in fields.lockable) {
            val locked = field in fields.locked
            val toggle = { onChange(fields.copy(locked = if (locked) fields.locked - field else fields.locked + field)) }
            EditChoiceSurface(onClick = toggle, modifier = Modifier.size(48.dp)) { focused ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = stringResource(R.string.item_edit_lock),
                        modifier = Modifier.size(20.dp),
                        tint = if (locked || focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun artworkDetails(choice: ArtworkChoice): String = listOfNotNull(
    choice.provider,
    choice.language?.uppercase(),
    if (choice.width != null && choice.height != null) "${choice.width}×${choice.height}" else null,
    choice.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
).joinToString(" · ")

@Composable
private fun EditMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

/** Focusable card of the edit pages, styled like the detail context actions. */
@Composable
private fun EditChoiceSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    androidx.tv.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        border = noSurfaceBorder(),
        modifier = modifier
            .activateOnTvKey(enabled, onClick)
            .focusProperties { canFocus = enabled || focused }
            .onFocusChanged { focused = it.isFocused },
    ) {
        Box(
            modifier = Modifier
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.19f) else Color.White.copy(alpha = 0.045f), shape)
                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.10f), shape)
                .padding(2.dp),
        ) { content(focused) }
    }
}

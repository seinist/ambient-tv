package com.ambient.tv.ui.dlna

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.R
import com.ambient.tv.data.dlna.DlnaBrowsePage
import com.ambient.tv.data.dlna.DlnaDiscovery
import com.ambient.tv.data.dlna.DlnaNode
import com.ambient.tv.data.dlna.DlnaRepository
import com.ambient.tv.data.dlna.DlnaServer
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.*
import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DlnaManageUiState(
    val saved: List<DlnaServer> = emptyList(),
    val found: List<DlnaServer> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val searchRevision: Long = 0,
    val error: OperationFeedback? = null,
)

@HiltViewModel
class DlnaManageViewModel @Inject constructor(
    private val store: DlnaStore,
    private val discovery: DlnaDiscovery,
) : ViewModel() {
    val feedback = OperationFeedbackReporter()
    private var discovered = emptyList<DlnaServer>()
    private val _state = MutableStateFlow(DlnaManageUiState(saved = store.servers()))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.serverRevision.collect {
                val saved = store.servers()
                _state.update { it.copy(saved = saved, found = discovered.filterNot { server -> saved.any { savedServer -> savedServer.udn == server.udn } }) }
            }
        }
    }

    fun search() {
        if (_state.value.searching) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searched = false, found = emptyList(), error = null) }
            val ticket = feedback.begin("dlna:discovery")
            suspendResult { discovery.discover() }.fold(
                onSuccess = { servers ->
                    store.refreshSaved(servers)
                    discovered = servers
                    val saved = store.servers()
                    _state.update {
                        it.copy(
                            saved = saved,
                            found = servers.filterNot { server -> saved.any { savedServer -> savedServer.udn == server.udn } },
                            searching = false,
                            searched = true,
                            searchRevision = it.searchRevision + 1,
                        )
                    }
                },
                onFailure = { error ->
                    val message = feedback.complete(ticket, OperationFeedback(
                        key = "dlna:discovery",
                        scope = "discovery",
                        system = "dlna",
                        subject = "DLNA",
                        kind = NoticeKind.CONNECTION,
                        message = R.string.dlna_notification_search_failed,
                        detail = feedbackFailureDetail(error),
                    )) ?: return@launch
                    _state.update { it.copy(searching = false, searched = true, error = message) }
                },
            )
        }
    }

    fun add(server: DlnaServer) = changeServer(server, adding = true)
    fun remove(server: DlnaServer) = changeServer(server, adding = false)

    private fun changeServer(server: DlnaServer, adding: Boolean) {
        val ticket = feedback.begin("dlna:server:${server.id}")
        val result = runCatching { if (adding) store.save(server) else store.remove(server.id) }
        val message = feedback.complete(ticket, OperationFeedback(ticket.key, "discovery",
            if (result.isFailure) R.string.notice_failed else if (adding) R.string.feedback_server_added else R.string.feedback_server_removed,
            if (result.isSuccess) FeedbackPhase.SUCCESS else FeedbackPhase.ERROR,
            detail = result.exceptionOrNull()?.let(::feedbackFailureDetail), system = "dlna", subject = server.name,
            delivery = FeedbackDelivery.ACTION))
        _state.update { it.copy(error = message.takeIf { result.isFailure }) }
    }
}

enum class DlnaViewMode { LIST, POSTER, LANDSCAPE }

internal fun dlnaViewMode(stored: String?): DlnaViewMode =
    DlnaViewMode.entries.firstOrNull { it.name == stored } ?: DlnaViewMode.LIST

data class DlnaBrowserLocation(val objectId: String, val title: String)
data class DlnaBrowserUiState(
    val server: DlnaServer? = null,
    val path: List<DlnaBrowserLocation> = emptyList(),
    val page: DlnaBrowsePage? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val nextIndex: Int = 0,
    val pageSize: Int = DlnaRepository.PAGE_SIZE,
    val pageError: OperationFeedback? = null,
    val hasMore: Boolean = false,
    val contentRevision: Long = 0,
    val viewMode: DlnaViewMode = DlnaViewMode.LIST,
    val gridColumns: Int = 8,
    val listRows: Int = 7,
    val error: OperationFeedback? = null,
)

@HiltViewModel
class DlnaBrowserViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val store: DlnaStore,
    private val repository: DlnaRepository,
    private val preferences: AppPreferences,
    private val snapshots: com.ambient.tv.data.browse.DisplaySnapshots,
) : ViewModel() {
    val feedback = OperationFeedbackReporter()
    private val folderFocus = mutableMapOf<String, String>()
    private val folderScroll = mutableMapOf<String, Pair<Int, Int>>()
    fun focus(location: String): String? = folderFocus[location]
    fun rememberFocus(location: String, key: String) { folderFocus[location] = key }
    fun scroll(location: String): Pair<Int, Int> = folderScroll[location] ?: (0 to 0)
    fun rememberScroll(location: String, index: Int, offset: Int) { folderScroll[location] = index to offset }
    private val serverId: String = checkNotNull(saved["serverId"])
    private val _state = MutableStateFlow(DlnaBrowserUiState(
        server = store.server(serverId),
        gridColumns = preferences.libraryGridColumns,
        listRows = preferences.libraryListRows,
    ))
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var stagedNodes = linkedMapOf<String, DlnaNode>()
    private var storedPage: DlnaBrowsePage? = null
    private var refreshTotal: Int? = null
    private var snapshotNamespace: String? = null
    private fun snapshotKey(path: List<DlnaBrowserLocation>) = "container:${path.lastOrNull()?.objectId ?: "0"}"
    private fun publishPage(page: DlnaBrowsePage, complete: Boolean, path: List<DlnaBrowserLocation>): DlnaBrowsePage {
        val result = if (complete) page.copy(nodes = stagedNodes.values.toList(), numberReturned = stagedNodes.size) else {
            val old = storedPage?.nodes.orEmpty()
            page.copy(nodes = stagedNodes.values.toList() + old.filterNot { it.key in stagedNodes })
        }
        if (complete) {
            storedPage = result
            snapshotNamespace?.let { namespace ->
                snapshots.write("dlna:$serverId", namespace, snapshotKey(path), result.copy(nodes = result.nodes.map { it.copy(resources = emptyList()) }))
            }
        }
        return result
    }
    private var openingFolder = false

    init { refresh() }

    fun refresh() = load(_state.value.path)

    fun setViewMode(mode: DlnaViewMode) {
        preferences.setSectionViewMode(viewKey(_state.value.path), mode.name)
        _state.update { it.copy(viewMode = mode) }
    }

    fun setGridColumns(columns: Int) {
        preferences.libraryGridColumns = columns
        _state.update { it.copy(gridColumns = preferences.libraryGridColumns) }
    }

    fun setListRows(rows: Int) {
        preferences.libraryListRows = rows
        _state.update { it.copy(listRows = preferences.libraryListRows) }
    }

    private fun viewKey(path: List<DlnaBrowserLocation>) =
        DlnaKeys.key(serverId, path.lastOrNull()?.objectId ?: DlnaRepository.ROOT_ID)

    fun retryMore() {
        if (_state.value.pageError == null) return
        _state.update { it.copy(hasMore = true) }
        more()
    }

    fun more() {
        val state = _state.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        val objectId = state.path.lastOrNull()?.objectId ?: "0"
        job = viewModelScope.launch {
            val ticket = feedback.begin("browse")
            _state.update { it.copy(loadingMore = true) }
            repository.browse(serverId, objectId, start = state.nextIndex, count = state.pageSize)
                .mapCatching { page ->
                    check(refreshTotal == null || page.totalMatches < 0 || refreshTotal == page.totalMatches) { "DLNA total changed during refresh" }
                    check(page.nodes.isNotEmpty() || page.totalMatches == state.nextIndex) { "Incomplete DLNA page" }
                    check(page.nodes.isEmpty() || page.nodes.any { it.key !in stagedNodes }) { "Repeated DLNA page" }
                    check(stagedNodes.size + page.nodes.size <= 100_000) { "DLNA browse limit reached" }
                    page
                }
                .also { currentCoroutineContext().ensureActive() }.fold(
                onSuccess = { next ->
                    _state.update { current ->
                        next.nodes.forEach { stagedNodes[it.key] = it }
                        val nodes = stagedNodes.values.toList()
                        val nextIndex = current.nextIndex + next.numberReturned
                        val total = next.totalMatches.takeIf { it >= 0 } ?: current.page?.totalMatches ?: -1
                        current.copy(
                            page = publishPage(DlnaBrowsePage(nodes, total, nodes.size, next.requestedCount), !hasMore(total, next.numberReturned, nextIndex, next.requestedCount), state.path),
                            loadingMore = false,
                            pageError = null,
                            nextIndex = nextIndex,
                            pageSize = next.requestedCount,
                            hasMore = hasMore(total, next.numberReturned, nextIndex, next.requestedCount),
                        )
                    }
                },
                onFailure = { error ->
                    val message = feedback.complete(ticket, browseFailure(state.path, "page", error)) ?: return@launch
                    _state.update {
                        // Keep the loaded page and retry the same offset only on user request.
                        it.copy(loadingMore = false, hasMore = false, pageError = message)
                    }
                },
            )
        }
    }

    fun open(node: DlnaNode) {
        if (!node.container || _state.value.loading || openingFolder) return
        val nextPath = _state.value.path + DlnaBrowserLocation(node.objectId, node.title)
        openingFolder = true
        viewModelScope.launch {
            load(nextPath)
            openingFolder = false
        }
    }

    fun up(): Boolean {
        val path = _state.value.path
        if (path.isEmpty()) return false
        load(path.dropLast(1))
        return true
    }

    fun breadcrumb(index: Int) {
        if (_state.value.loading) return
        val target = if (index < 0) emptyList() else _state.value.path.take(index + 1)
        if (target != _state.value.path) load(target)
    }

    private fun load(path: List<DlnaBrowserLocation>) {
        job?.cancel()
        feedback.cancel("browse")
        val server = store.server(serverId)
        if (server == null) {
            _state.value = DlnaBrowserUiState(
                loading = false,
                gridColumns = preferences.libraryGridColumns,
                listRows = preferences.libraryListRows,
                error = OperationFeedback(
                    "dlna:removed:$serverId", "browse", R.string.feedback_source_removed, delivery = FeedbackDelivery.INLINE,
                ),
            )
            return
        }
        _state.update { it.copy(server = server, path = path, viewMode = dlnaViewMode(preferences.sectionViewMode(viewKey(path))), page = it.page.takeIf { _ -> it.path == path }, loading = true, loadingMore = false, nextIndex = 0, pageSize = DlnaRepository.PAGE_SIZE, pageError = null, hasMore = false, error = null) }
        job = viewModelScope.launch {
            stagedNodes.clear()
            refreshTotal = null
            val namespace = snapshots.namespace("dlna:$serverId", "${server.udn}|${server.contentDirectoryUrl}")
            snapshotNamespace = namespace
            storedPage = snapshots.read<DlnaBrowsePage>(namespace, snapshotKey(path))
            storedPage?.let { cached -> _state.update { it.copy(page = cached, loading = false) } }
            val ticket = feedback.begin("browse")
            repository.browse(serverId, path.lastOrNull()?.objectId ?: "0")
                .mapCatching { page ->
                    check(page.totalMatches <= 0 || page.nodes.isNotEmpty()) { "Incomplete DLNA first page" }
                    refreshTotal = page.totalMatches.takeIf { it >= 0 }
                    page
                }
                .also { currentCoroutineContext().ensureActive() }.fold(
                onSuccess = { page ->
                    page.nodes.forEach { stagedNodes[it.key] = it }
                    val nextIndex = page.numberReturned
                    _state.update {
                        it.copy(
                            page = publishPage(page, !hasMore(page.totalMatches, page.numberReturned, nextIndex, page.requestedCount), path),
                            loading = false,
                            nextIndex = nextIndex,
                            pageSize = page.requestedCount,
                            hasMore = hasMore(page.totalMatches, page.numberReturned, nextIndex, page.requestedCount),
                            contentRevision = it.contentRevision + 1,
                        )
                    }
                },
                onFailure = { error ->
                    val message = feedback.complete(ticket, browseFailure(path, "folder", error)) ?: return@launch
                    _state.update { it.copy(loading = false, error = message) }
                },
            )
        }
    }

    private fun browseFailure(path: List<DlnaBrowserLocation>, operation: String, error: Throwable): OperationFeedback {
        val objectId = path.lastOrNull()?.objectId ?: DlnaRepository.ROOT_ID
        return OperationFeedback(
            key = "dlna:browse:$operation:$serverId:$objectId",
            scope = "browse",
            system = "dlna",
            subject = path.lastOrNull()?.title ?: _state.value.server?.name,
            kind = NoticeKind.LIBRARY,
            message = if (operation == "page") R.string.dlna_page_failed else R.string.dlna_notification_browse_failed,
            detail = feedbackFailureDetail(error),
            delivery = FeedbackDelivery.INLINE,
        )
    }

    private fun hasMore(total: Int, returned: Int, nextIndex: Int, requested: Int): Boolean =
        returned > 0 && if (total >= 0) nextIndex < total else returned >= requested
}

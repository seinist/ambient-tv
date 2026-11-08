package com.ambient.tv.ui.home

import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.NavigationPlaybackReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomePlaybackReturnPreparationTest {
    private fun row(id: String, vararg keys: String) = ContentRowData(id = id, title = id,
        items = keys.map { homeTrailerTestItem(it) })

    @Test fun firstLayoutAlreadyContainsThePlayedSourceFocusAndHero() {
        val first = row("trailers.FIRST", "first")
        val source = row("trailers.CINEMA", "a", "b", "last")
        val current = HomeUiState.Loaded(
            rows = listOf(first, source.copy(items = listOf(homeTrailerTestItem("new")))),
            rowKeys = listOf(first.id, source.id), focusedRatingKey = first.items.first().ratingKey,
            focusedRowId = first.id, initialFocusTarget = HomeInitialFocusTarget.CONTENT,
        )
        val request = HomePlaybackReturnRequest(HomePlaybackProvider.TRAILERS, "home-trailers:home", 8,
            source, HomeFocusSnapshot("trailer-video:last", source.id))
        val prepared = prepareHomePlaybackReturn(current, request)!!
        assertSame(source, prepared.rows[1])
        assertEquals(current.rowKeys, prepared.rowKeys)
        assertEquals("trailer-video:last", prepared.focusedRatingKey)
        assertEquals(source.id, prepared.focusedRowId)
        assertEquals("last", prepared.hero?.title)
        assertEquals(HomeInitialFocusTarget.NONE, prepared.initialFocusTarget)
        assertEquals(1, prepared.playbackReturnRevision)
        assertEquals(request.key, prepared.playbackReturnToken)
    }

    @Test fun previewAndCommitUseOneEpochEvenWhenProviderRevisionsHaveTheSameNumber() {
        val source = row("trailers.CINEMA", "a")
        val current = HomeUiState.Loaded(listOf(source), rowKeys = listOf(source.id))
        val request = HomePlaybackReturnRequest(HomePlaybackProvider.TRAILERS, "home:row", 4,
            source, HomeFocusSnapshot("trailer-video:a", source.id))
        val prepared = prepareHomePlaybackReturn(current, request)!!
        assertEquals(prepared, prepareHomePlaybackReturn(prepared, request))
        val anotherProvider = request.copy(provider = HomePlaybackProvider.TELEGRAM)
        val next = prepareHomePlaybackReturn(prepared, anotherProvider)!!
        assertEquals(prepared.playbackReturnRevision + 1, next.playbackReturnRevision)
        val repeatedVideo = prepareHomePlaybackReturn(next, anotherProvider.copy(contextRevision = 5))!!
        assertEquals(next.playbackReturnRevision + 1, repeatedVideo.playbackReturnRevision)
    }

    @Test fun anUnavailableOrWrongSourceCannotCreateAFocusAddress() {
        val source = row("trailers.CINEMA", "a")
        val current = HomeUiState.Loaded(listOf(source))
        val request = HomePlaybackReturnRequest(HomePlaybackProvider.TRAILERS, "home:row", 1,
            source, HomeFocusSnapshot("trailer-video:missing", source.id))
        assertNull(prepareHomePlaybackReturn(current, request))
        assertNull(prepareHomePlaybackReturn(current, request.copy(target = HomeFocusSnapshot("trailer-video:a", "other-row"))))
    }

    @Test fun ordinaryPlayerReturnKeepsTheOriginRowWhenTheVideoOccursTwice() {
        val first = row("plex.first", "shared")
        val second = row("plex.second", "shared", "b")
        val state = HomeUiState.Loaded(listOf(first, second), focusedRowId = second.id)
        val event = NavigationPlaybackReturn("home-entry", "trailer-video:shared", 9)
        val request = homeNavigationPlaybackReturn(state, event)!!
        assertEquals(HomePlaybackProvider.MEDIA, request.provider)
        assertEquals(second.id, request.target.rowId)
        assertEquals(event.entryId, request.scope)
        assertNull(homeNavigationPlaybackReturn(state, event.copy(itemId = "missing")))
        assertNull(homeNavigationPlaybackReturn(HomeUiState.LoadingLibrary, event))
    }

    @Test fun nonTrailerRowsUseTheirExistingItemKeysAndThePreparedEpoch() {
        val source = row("telegram.recent", "a").let {
            it.copy(items = it.items.map { item -> item.copy(ratingKey = "telegram:42:7") })
        }
        val memory = HomeTrailerPlaybackReturn()
        memory.startItem(source, "telegram:42:7")
        assertEquals(HomeFocusSnapshot("telegram:42:7", source.id),
            memory.restoreItem(source.id, "telegram:42:7", returnRevision = 17))
        assertEquals(17, memory.revision)
        memory.enter()
        memory.focused(source.id, "telegram:42:7")
        assertNull(memory.pendingTarget)
        assertSame(source, memory.row(source.id, null))
        memory.enter()
        assertNull(memory.row(source.id, null))
    }
}

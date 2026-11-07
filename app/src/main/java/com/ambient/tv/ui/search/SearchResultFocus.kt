package com.ambient.tv.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.ui.common.BrowseFocusSlot

/** Session memory changes on every D-pad move without publishing another SearchUiState. */
internal class SearchResultFocus(initialKey: String? = null) {
    private var latestKey = initialKey

    fun focused(key: String) { latestKey = key }
    fun clear() { latestKey = null }
    fun request() = SearchFocusRequest(latestKey)
}

/** One entry/view change captures a target; later navigation never rewrites that request. */
internal class SearchFocusRequest(val itemKey: String?) {
    var pending by mutableStateOf(true)
        private set
    private var scrollPending = itemKey != null

    fun contentFocused() {
        pending = false
        scrollPending = false
    }

    /** A late provider may supply the target, but subsequent result/status updates cannot scroll again. */
    suspend fun restore(slots: List<BrowseFocusSlot>, scrollToItem: suspend (Int) -> Unit) {
        if (!pending || !scrollPending) return
        val index = slots.firstOrNull { it.key.item == itemKey }?.index ?: return
        // A new provider snapshot may cancel an in-flight effect. Only a completed scroll
        // consumes restoration; its replacement effect can retry at the new physical index.
        scrollToItem(index)
        scrollPending = false
    }
}

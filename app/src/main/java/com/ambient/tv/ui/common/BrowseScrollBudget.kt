package com.ambient.tv.ui.common

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.ambient.tv.data.browse.BrowseWorkBudget
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest

/** Includes inertial touch scrolling after the last MotionEvent. Observes start/stop, not offsets. */
@Composable
internal fun BrowseScrollBudget(state: ScrollableState) {
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                BrowseWorkBudget.scrolling.incrementAndGet()
                BrowseWorkBudget.interact()
                try { awaitCancellation() } finally {
                    BrowseWorkBudget.scrolling.decrementAndGet()
                    BrowseWorkBudget.interact()
                }
            }
        }
    }
}

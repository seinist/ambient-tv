package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ambient.tv.AmbientTvApplication
import com.ambient.tv.network.ArtworkNetworkState
import com.ambient.tv.network.artworkNetworkAccessChanges
import kotlinx.coroutines.flow.collect

/** Only this image's blocked/recovered state can recompose it; other health reports are ignored. */
@Composable
internal fun rememberArtworkNetworkAccess(model: Any?, headers: Map<String, String> = emptyMap()): ArtworkNetworkState {
    val access = (LocalContext.current.applicationContext as? AmbientTvApplication)?.mediaArtworkNetworkAccess
    val state = remember(model, headers, access) {
        mutableStateOf(ArtworkNetworkState(blocked = access?.blocked(model, headers) == true))
    }
    LaunchedEffect(model, headers, access) {
        if (access != null) {
            artworkNetworkAccessChanges(access.changes) { access.blocked(model, headers) }
                .collect { blocked -> state.value = state.value.update(blocked) }
        }
    }
    return state.value
}

/** A revision of another post does not invalidate this tile's composition. */
@Composable
internal fun rememberMonogramArtworkKey(model: String?): String? {
    val context = LocalContext.current
    val repository = remember(context, model != null) {
        if (model != null) (context.applicationContext as? AmbientTvApplication)?.telegramRepository?.get() else null
    }
    val state = remember(model, repository) {
        mutableStateOf(model?.let { repository?.artworkCacheKey(it) })
    }
    LaunchedEffect(model, repository) {
        if (model != null && repository != null) {
            repository.artworkRevision.collect {
                val key = repository.artworkCacheKey(model)
                if (state.value != key) state.value = key
            }
        }
    }
    return state.value
}

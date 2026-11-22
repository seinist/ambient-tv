package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ambient.tv.AmbientTvApplication
import com.ambient.tv.network.ArtworkNetworkState
import kotlinx.coroutines.flow.collect

/** Only this image's blocked/recovered state can recompose it; other health reports are ignored. */
@Composable
internal fun rememberArtworkNetworkAccess(model: Any?, headers: Map<String, String> = emptyMap()): ArtworkNetworkState {
    val access = (LocalContext.current.applicationContext as? AmbientTvApplication)?.mediaArtworkNetworkAccess
    val state = remember(model, headers, access) {
        mutableStateOf(ArtworkNetworkState(blocked = access?.blocked(model, headers) == true))
    }
    LaunchedEffect(model, headers, access) {
        access?.changes?.collect { state.value = state.value.update(access.blocked(model, headers)) }
    }
    return state.value
}

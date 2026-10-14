package com.ambient.tv.ui.youtube

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import coil3.compose.AsyncImage
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip

@Composable
internal fun YouTubeAccountButton(
    state: YouTubeUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val accountFocus = remember { FocusRequester() }
    val logoutFocus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val name = state.accountProfile?.name ?: stringResource(R.string.youtube_account)
    val dismiss = {
        menuOpen = false
        restoreFocus = true
    }
    LaunchedEffect(state.signedIn) {
        if (!state.signedIn && menuOpen) dismiss()
    }
    LaunchedEffect(menuOpen, restoreFocus) {
        if (menuOpen || restoreFocus) {
            withFrameNanos { }
            runCatching { (if (menuOpen) logoutFocus else accountFocus).requestFocus() }
            restoreFocus = false
        }
    }
    if (state.signedIn) {
        TvChip(
            text = name,
            modifier = Modifier.widthIn(max = 240.dp).focusRequester(accountFocus),
            loading = state.authBusy,
            textMaxLines = 1,
            leadingContent = {
                // Keep a fallback visible for missing URLs, pending loads and image failures.
                Box(Modifier.size(28.dp).clip(CircleShape)) {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(28.dp))
                    state.accountProfile?.avatarUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            },
            onClick = { menuOpen = true },
        )
    } else {
        TvChip(
            text = stringResource(if (state.authBusy) R.string.action_signing_in else R.string.action_sign_in),
            modifier = Modifier.focusRequester(accountFocus),
            icon = Icons.AutoMirrored.Filled.Login,
            // Round like search and view on phones, so all three fit beside the title.
            iconOnly = com.ambient.tv.ui.mobile.LocalHandheldUi.current,
            loading = state.authBusy,
            onClick = onSignIn,
        )
    }
    if (menuOpen && state.signedIn) {
        AppDialog(
            onDismiss = dismiss,
            title = name,
            maxWidth = 420.dp,
            captureInitialFocus = false,
            footer = {
                TvActionButton(
                    text = stringResource(R.string.action_sign_out),
                    modifier = Modifier.focusRequester(logoutFocus),
                    onClick = {
                        dismiss()
                        onSignOut()
                    },
                )
                TvActionButton(text = stringResource(R.string.back), onClick = dismiss)
            },
        )
    }
}

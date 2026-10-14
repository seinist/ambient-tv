package com.ambient.tv.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.util.LocalNetworkAccess
import com.ambient.tv.util.shouldIntroduceLocalNetworkAccess

private data class LocalNetworkPermissionUi(val granted: Boolean, val request: () -> Unit)
private val LocalPermissionUi = staticCompositionLocalOf<LocalNetworkPermissionUi?> { null }

/** The OS remains authoritative. A declined prompt never blocks cloud or local-file use. */
@Composable
fun LocalNetworkPermissionHost(activity: ComponentActivity, hasConfiguredService: Boolean, onGranted: () -> Unit, content: @Composable () -> Unit) {
    if (!LocalNetworkAccess.isRequired(activity)) { content(); return }
    // UI decision only, deliberately excluded from exported app settings.
    val preferences = remember(activity) { activity.getSharedPreferences("network_permission_ui", Context.MODE_PRIVATE) }
    var granted by remember { mutableStateOf(LocalNetworkAccess.isGranted(activity)) }
    var requesting by remember { mutableStateOf(false) }
    var showIntroduction by rememberSaveable { mutableStateOf(shouldIntroduceLocalNetworkAccess(
        required = true, granted = granted, configured = hasConfiguredService,
        introduced = preferences.getBoolean("introduced", false),
    )) }
    val grantedCallback by rememberUpdatedState(onGranted)
    fun updatePermission() {
        val current = LocalNetworkAccess.isGranted(activity)
        val becameGranted = current && !granted
        granted = current
        if (current) showIntroduction = false
        if (becameGranted) grantedCallback()
    }
    fun continueWithoutPermission() {
        preferences.edit().putBoolean("introduced", true).apply()
        showIntroduction = false
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requesting = false
        preferences.edit().putBoolean("introduced", true).apply()
        updatePermission()
        showIntroduction = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updatePermission() }
    val request: () -> Unit = {
        if (!requesting) {
            val openSettings = granted || (preferences.getBoolean("requested", false) &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK))
            if (openSettings) {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            } else {
                preferences.edit().putBoolean("requested", true).apply()
                requesting = true
                launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
    }
    CompositionLocalProvider(LocalPermissionUi provides LocalNetworkPermissionUi(granted, request)) {
        if (showIntroduction) {
            val initialFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { initialFocus.requestFocus() } }
            BackHandler { continueWithoutPermission() }
            Column(Modifier.fillMaxSize().padding(40.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
                Text(stringResource(R.string.local_network_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.local_network_explanation), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvActionButton(stringResource(R.string.local_network_allow), request, modifier = Modifier.focusRequester(initialFocus))
                    TvActionButton(stringResource(R.string.local_network_continue), ::continueWithoutPermission)
                }
            }
        } else content()
    }
}

/** Keep the action mounted after grant so a remote-control focus target never disappears. */
@Composable
fun LocalNetworkPermissionSetting(modifier: Modifier = Modifier, onGranted: () -> Unit = {}) {
    val permission = LocalPermissionUi.current ?: return
    var previouslyGranted by remember { mutableStateOf(permission.granted) }
    val grantedCallback by rememberUpdatedState(onGranted)
    LaunchedEffect(permission.granted) {
        if (permission.granted && !previouslyGranted) grantedCallback()
        previouslyGranted = permission.granted
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.local_network_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (permission.granted) R.string.local_network_granted else R.string.local_network_missing), style = MaterialTheme.typography.bodySmall)
        }
        TvActionButton(stringResource(if (permission.granted) R.string.local_network_manage else R.string.local_network_allow), permission.request)
    }
}

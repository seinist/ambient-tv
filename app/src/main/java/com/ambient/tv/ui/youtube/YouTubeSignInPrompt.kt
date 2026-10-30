package com.ambient.tv.ui.youtube

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.youtube.YouTubeSignInLink

/**
 * Phone sign-in: a QR on the phone's own screen cannot be scanned, so the address and code are
 * shown with buttons that open the activation page in a browser or copy the code.
 */
@Composable
fun YouTubeSignInDialog(code: String, onDismiss: () -> Unit) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.action_sign_in),
        footer = { TvActionButton(stringResource(R.string.action_close), onDismiss) },
    ) {
        Text(stringResource(R.string.youtube_sign_in_browser_hint), style = MaterialTheme.typography.bodyMedium)
        YouTubeSignInPanel(code)
    }
}

/**
 * Shared pending-account prompt for setup, Settings and the SmartTube browse screen.
 * TV shows the scannable activation QR; handheld opens the browser/copy-code dialog instead.
 */
@Composable
fun YouTubeSignInPrompt(code: String, onDismiss: () -> Unit) {
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
        YouTubeSignInDialog(code, onDismiss)
    } else {
        com.ambient.tv.ui.common.QrCodeDialog(
            title = stringResource(R.string.action_sign_in),
            hint = stringResource(R.string.youtube_activate),
            payload = YouTubeSignInLink.qrPayload(code),
            code = code,
            onDismiss = onDismiss,
            footer = {
                TvActionButton(
                    text = stringResource(R.string.action_close),
                    onClick = onDismiss,
                )
            },
        )
    }
}

/** Shared phone login controls for initial setup, settings and the YouTube screen. */
@Composable
fun YouTubeSignInPanel(code: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(YouTubeSignInLink.ACTIVATE_ADDRESS, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        YouTubeSignInBrowserActions(code, Modifier.fillMaxWidth())
    }
}

/** "Open in browser" and "Copy code" for a pending sign-in code. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YouTubeSignInBrowserActions(code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvActionButton(stringResource(R.string.youtube_sign_in_open_browser), icon = Icons.AutoMirrored.Filled.OpenInNew, onClick = {
            // The code is prefilled via user_code; keep it ready to paste in case it is not.
            copySignInCode(context, code, toast = false)
            if (!openInBrowser(context, YouTubeSignInLink.browserUrl(code))) {
                Toast.makeText(context, R.string.youtube_sign_in_no_browser, Toast.LENGTH_LONG).show()
            }
        })
        TvActionButton(stringResource(R.string.youtube_sign_in_copy_code), icon = Icons.Filled.ContentCopy, onClick = {
            copySignInCode(context, code, toast = true)
        })
    }
}

private fun copySignInCode(context: Context, code: String, toast: Boolean) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("YouTube", code))
    if (toast) Toast.makeText(context, R.string.youtube_sign_in_code_copied, Toast.LENGTH_SHORT).show()
}

/** Restrict the URL intent to browser packages, including the chooser and every fallback. */
@Suppress("DEPRECATION")
private fun openInBrowser(context: Context, url: String): Boolean = runCatching {
    val packages = context.packageManager
    val browserSelector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
    val browserPackages = packages.queryIntentActivities(browserSelector, 0)
        .map { it.activityInfo.packageName }.distinct()
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val browserIntents = browserPackages.map { Intent(view).setPackage(it) }
        .filter { packages.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
    if (browserIntents.isEmpty()) return@runCatching false
    val defaultPackage = packages.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    val preferred = browserIntents.firstOrNull { it.`package` == defaultPackage }
    if (preferred != null && runCatching { context.startActivity(preferred) }.isSuccess) {
        return@runCatching true
    }
    val target = if (browserIntents.size == 1) browserIntents.first() else {
        Intent.createChooser(browserIntents.first(), context.getString(R.string.youtube_sign_in_open_browser))
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, browserIntents.drop(1).toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(target)
    true
}.getOrDefault(false)

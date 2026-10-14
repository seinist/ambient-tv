package com.ambient.tv.spotify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.MainActivity
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.LoginQrCode
import com.ambient.tv.ui.common.qrScreenInset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import com.ambient.tv.ui.mobile.HandheldActivity
import com.ambient.tv.ui.mobile.isTelevision
import com.ambient.tv.ui.theme.AmbientTvTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * In-app Spotify sign-in. On TV it is the only sign-in path: a QR code for signing in on the
 * phone (see [SpotifyQrLoginServer]) plus the Spotify login page in an embedded WebView. On
 * handhelds it is the fallback when no browser Activity exists (WebView only).
 */
@AndroidEntryPoint
class SpotifyWebAuthActivity : ComponentActivity() {
    @Inject lateinit var authManager: SpotifyAuthManager
    @Inject lateinit var preferences: AppPreferences
    private var completing = false
    private var webView: WebView? = null

    private var webAuthUrl by mutableStateOf<String?>(null)
    private var qrUrl by mutableStateOf<String?>(null)
    private var showWeb by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTelevision(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        webAuthUrl = intent?.getStringExtra(EXTRA_AUTH_URL)
        qrUrl = intent?.getStringExtra(EXTRA_QR_URL)
        if (webAuthUrl.isNullOrBlank()) {
            finish()
            return
        }
        showWeb = qrUrl.isNullOrBlank()

        lifecycleScope.launch {
            authManager.qrAuthorizationCompleted.collect { finishSignIn() }
        }

        setContent {
            AmbientTvTheme(
                uiScale = preferences.uiScale,
                accentColorKey = preferences.accentColorKey,
                customAccentHex = preferences.customAccentHex,
                customPlaybackAccentHex = preferences.customPlaybackAccentHex,
                complementaryPlayback = preferences.dualAccentEnabled,
                themeModeKey = preferences.themeModeKey,
            ) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val qr = qrUrl
                    if (showWeb || qr == null) {
                        WebSignIn()
                    } else {
                        QrSignIn(qr)
                    }
                }
            }
        }
    }

    @Composable
    private fun WebSignIn() {
        // Back steps through the Spotify pages first (e.g. "forgot password"); on the first page it
        // returns to the QR screen, or cancels when there is none.
        BackHandler {
            val view = webView
            when {
                view != null && view.canGoBack() -> view.goBack()
                qrUrl != null -> showWeb = false
                else -> finish()
            }
        }
        val url = webAuthUrl ?: return
        AndroidView(
            factory = { context ->
                (webView ?: createWebView(context)).also { view ->
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    if (view.url == null) view.loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Composable
    private fun QrSignIn(qr: String) {
        val auth by authManager.state.collectAsState()
        val focus = remember { FocusRequester() }
        val television = isTelevision(LocalContext.current)
        BackHandler { finish() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val slotWidth = maxWidth
            val slotHeight = maxHeight
            val inset = qrScreenInset(minOf(slotWidth, slotHeight), television)
            val details: @Composable () -> Unit = {
                Text(stringResource(R.string.spotify_qr_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.spotify_qr_steps), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.spotify_qr_same_network),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                auth.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(
                        text = stringResource(R.string.spotify_qr_on_device),
                        onClick = { showWeb = true },
                        modifier = Modifier.focusRequester(focus),
                    )
                    TvActionButton(text = stringResource(R.string.spotify_qr_refresh), onClick = ::refreshQr)
                }
            }
            // The code sits in a non-scrolling slot. Copy may scroll; the QR itself never does.
            if (slotWidth >= slotHeight && slotWidth >= 640.dp) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(inset),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        LoginQrCode(qr, Modifier.fillMaxSize())
                    }
                    Column(
                        modifier = Modifier.weight(1.15f).widthIn(max = 520.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        content = { details() },
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().padding(inset)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LoginQrCode(qr, Modifier.fillMaxSize())
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = slotHeight * 0.46f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = { details() },
                    )
                }
            }
        }
    }

    /** New session (new QR + WebView URL), e.g. after the code expired or the network changed. */
    private fun refreshQr() {
        runCatching { authManager.prepareTvAuthorization() }.onSuccess { tv ->
            webView?.let { view ->
                view.stopLoading()
                view.loadUrl(tv.webAuthUrl)
                view.clearHistory()
            }
            webAuthUrl = tv.webAuthUrl
            qrUrl = tv.qrUrl
            if (tv.qrUrl == null) showWeb = true
        }
    }

    private fun createWebView(context: android.content.Context): WebView = WebView(context).also { view ->
        webView = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        // OAuth pages never need local file or content URLs; keep the WebView locked down.
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        view.settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        view.settings.allowUniversalAccessFromFileURLs = false
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        view.settings.userAgentString = view.settings.userAgentString + " Ambient TV"
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                handlePossibleRedirect(request?.url)

            @Suppress("OVERRIDE_DEPRECATION") // Legacy WebView callback
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                handlePossibleRedirect(url?.let(Uri::parse))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val landscapeHandheld = !com.ambient.tv.ui.mobile.isTelevision(v.context) &&
                v.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val padding = if (!landscapeHandheld) bars else {
                // Same rule as the handheld shell: the portrait status bar / cutout is reported
                // on the side in landscape and must not letterbox the page. Television keeps
                // the full inset box above.
                val edge = com.ambient.tv.ui.mobile.handheldContentEdgeInsets(
                    landscape = true,
                    status = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).toEdgeInsets(),
                    cutout = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout()).toEdgeInsets(),
                    navigation = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).toEdgeInsets(),
                    ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).toEdgeInsets(),
                )
                androidx.core.graphics.Insets.of(edge.left, edge.top, edge.right, edge.bottom)
            }
            v.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        // D-pad/remote input must reach the page immediately on TV.
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.post {
            androidx.core.view.ViewCompat.requestApplyInsets(view)
            view.requestFocus()
        }
    }

    override fun onStart() {
        super.onStart()
        SpotifyPlaybackTakeoverUiPresence.onActivityStarted()
    }

    override fun onStop() {
        SpotifyPlaybackTakeoverUiPresence.onActivityStopped()
        super.onStop()
    }

    private fun handlePossibleRedirect(uri: Uri?): Boolean {
        if (uri == null || !authManager.isRedirect(uri)) return false
        if (completing) return true
        completing = true
        lifecycleScope.launch {
            try {
                authManager.handleRedirect(uri)
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            finishSignIn()
        }
        return true
    }

    private fun finishSignIn() {
        completing = true
        val target = if (isTelevision(this)) MainActivity::class.java else HandheldActivity::class.java
        startActivity(Intent(this, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        if (isFinishing && !completing) authManager.cancelAuthorization()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_AUTH_URL = "spotify_auth_url"
        const val EXTRA_QR_URL = "spotify_qr_url"
    }
}

private fun androidx.core.graphics.Insets.toEdgeInsets() =
    com.ambient.tv.ui.mobile.EdgeInsets(left, top, right, bottom)

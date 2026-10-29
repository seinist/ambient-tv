@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ambient.tv.ui.player.teletext

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.livetv.teletext.HbbtvApplication
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackVideoWindow
import com.ambient.tv.ui.player.VideoWindowFraction
import com.ambient.tv.ui.player.videoImagePreferences
import com.ambient.tv.ui.theme.LocalUiTokens
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The HbbTV ("red button") application of the live channel, opened on request. */
object HbbtvUi {
    var open by mutableStateOf<HbbtvApplication?>(null)
        private set
    internal var pendingConsent by mutableStateOf<HbbtvApplication?>(null)

    fun request(context: Context, app: HbbtvApplication) {
        if (videoImagePreferences(context).hbbtvConsent) open = app else pendingConsent = app
    }

    internal fun accept(context: Context) {
        videoImagePreferences(context).hbbtvConsent = true
        open = pendingConsent
        pendingConsent = null
    }

    fun close() {
        open = null
        pendingConsent = null
    }
}

@Composable
internal fun HbbtvHost(channelName: String?, channelKey: String) {
    val context = LocalContext.current
    HbbtvUi.pendingConsent?.let {
        AppDialog(
            onDismiss = HbbtvUi::close,
            title = stringResource(R.string.player_hbbtv),
            hint = stringResource(R.string.player_hbbtv_consent),
            footer = {
                TvActionButton(stringResource(R.string.player_hbbtv_open), { HbbtvUi.accept(context) }, selected = true)
                TvActionButton(stringResource(R.string.back), HbbtvUi::close)
            },
        )
    }
    HbbtvUi.open?.let { app ->
        key(channelKey, app) { HbbtvViewer(app, channelName, channelKey, onClose = HbbtvUi::close) }
    }
}

private enum class HbbtvPageState { LOADING, CONTENT, ERROR }
private val hbbtvVideoWindow = HbbtvWindowOwnership<VideoWindowFraction> { PlaybackVideoWindow.window = it }

@Composable
private fun HbbtvViewer(app: HbbtvApplication, channelName: String?, channelKey: String, onClose: () -> Unit) {
    var browser by remember { mutableStateOf<HbbtvWebView?>(null) }
    var controlsVisible by remember { mutableStateOf(false) }
    var pageState by remember { mutableStateOf(HbbtvPageState.LOADING) }
    var colorKeys by remember { mutableIntStateOf(HBBTV_DEFAULT_COLOR_KEYS) }
    var focusedColor by remember { mutableStateOf<Int?>(null) }
    var returnAfterColorRemoved by remember { mutableStateOf(false) }
    var browserVersion by remember { mutableIntStateOf(0) }
    var startUrl by remember { mutableStateOf(app.url) }
    var showHint by remember { mutableStateOf(true) }
    var launcherFocused by remember { mutableStateOf(false) }
    val showControls = {
        browser?.cancelBackPress()
        controlsVisible = true
    }
    Dialog(
        onDismissRequest = {
            when {
                controlsVisible -> controlsVisible = false
                pageState == HbbtvPageState.ERROR -> onClose()
                else -> browser?.pageBack()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val focusManager = LocalFocusManager.current
        val handheld = LocalHandheldUi.current
        val returnFocus = remember { FocusRequester() }
        val retryFocus = remember { FocusRequester() }
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
            onDispose { }
        }
        val language = remember { runCatching { java.util.Locale.getDefault().isO3Language }.getOrDefault("deu").ifBlank { "deu" } }
        // A late callback or disposal of the old sender must never change a newly tuned programme.
        val currentChannel = remember(channelKey) { { BackgroundPlaybackSession.snapshot.value.metadata?.route == channelKey } }
        val broadcast = remember(channelKey) {
            HbbtvBroadcastControl(
                wantsToPlay = { currentChannel() && BackgroundPlaybackSession.snapshot.value.wantsToPlay },
                pause = { if (currentChannel()) BackgroundPlaybackSession.pause() },
                play = { if (currentChannel()) BackgroundPlaybackSession.play() },
            )
        }
        val windowOwner = remember(channelKey) { Any() }
        DisposableEffect(broadcast) {
            hbbtvVideoWindow.claim(windowOwner)
            onDispose {
                browser?.deactivate()
                broadcast.resume()
                hbbtvVideoWindow.release(windowOwner)
            }
        }
        val restart: (String) -> Unit = { url ->
            // Replacing a browser also replaces all in-flight callbacks and the old document bridge.
            browser?.deactivate()
            broadcast.resume()
            hbbtvVideoWindow.update(windowOwner, null)
            startUrl = url
            pageState = HbbtvPageState.LOADING
            colorKeys = HBBTV_DEFAULT_COLOR_KEYS
            controlsVisible = false
            showHint = true
            browserVersion++
        }
        LaunchedEffect(controlsVisible) {
            withFrameNanos { }
            if (controlsVisible) {
                runCatching { returnFocus.requestFocus() }
            } else if (pageState == HbbtvPageState.ERROR) {
                runCatching { retryFocus.requestFocus() }
            } else {
                focusManager.clearFocus(force = true)
                browser?.focusPage()
            }
        }
        LaunchedEffect(pageState) {
            if (pageState == HbbtvPageState.ERROR && !controlsVisible) {
                withFrameNanos { }
                if (pageState == HbbtvPageState.ERROR && !controlsVisible) runCatching { retryFocus.requestFocus() }
            }
            if (pageState == HbbtvPageState.CONTENT && showHint) {
                delay(6_000)
                showHint = false
            }
        }
        LaunchedEffect(returnAfterColorRemoved, controlsVisible) {
            if (returnAfterColorRemoved && controlsVisible) {
                withFrameNanos { }
                if (returnAfterColorRemoved && controlsVisible) runCatching { returnFocus.requestFocus() }
            }
            returnAfterColorRemoved = false
        }
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (controlsVisible && native.keyCode == KeyEvent.KEYCODE_BACK) {
                if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) controlsVisible = false
                true
            } else false
        }) {
            key(browserVersion) {
                AndroidView(
                    factory = { context ->
                        HbbtvWebView(
                            context, channelName.orEmpty(), language, broadcast, currentChannel,
                            onVideoWindow = { hbbtvVideoWindow.update(windowOwner, it) },
                            onClose = onClose,
                            onControls = showControls,
                            onState = { pageState = it },
                            onColorKeys = { requested ->
                                if (controlsVisible && focusedColor?.let { it !in hbbtvColorKeys(requested) } == true) {
                                    returnAfterColorRemoved = true
                                }
                                colorKeys = requested
                            },
                            allowPageFocus = { !controlsVisible && pageState != HbbtvPageState.ERROR },
                        ).also { browser = it; it.open(startUrl) }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.destroy() },
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                val toolbarMaxHeight = maxHeight
                if (pageState != HbbtvPageState.CONTENT && !controlsVisible) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        BroadcastToolbar(Modifier.widthIn(max = 620.dp).heightIn(max = toolbarMaxHeight)
                            .verticalScroll(rememberScrollState())) {
                            if (pageState == HbbtvPageState.LOADING) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                                Text(stringResource(R.string.player_hbbtv_loading), fontSize = 20.sp)
                            } else {
                                Text(stringResource(R.string.player_hbbtv_error), fontSize = 20.sp)
                                Text(stringResource(R.string.player_hbbtv_error_hint), fontSize = 16.sp)
                            }
                            BroadcastControlRow {
                                if (pageState == HbbtvPageState.ERROR) TvChip(
                                    text = stringResource(R.string.home_retry), icon = Icons.Filled.Refresh,
                                    modifier = Modifier.focusRequester(retryFocus),
                                    onClick = { restart(browser?.pageUrl ?: startUrl) },
                                )
                                TvChip(stringResource(R.string.player_hbbtv_close), icon = Icons.Filled.Close, onClick = onClose)
                            }
                        }
                    }
                }
                if (!controlsVisible && (handheld || showHint || launcherFocused || pageState != HbbtvPageState.CONTENT)) {
                    Column(Modifier.align(Alignment.TopEnd).widthIn(max = 520.dp), horizontalAlignment = Alignment.End) {
                        TvChip(stringResource(R.string.player_hbbtv_controls), icon = Icons.Filled.SettingsRemote,
                            modifier = Modifier.onFocusChanged { launcherFocused = it.isFocused }, onClick = showControls)
                        if (showHint) Text(stringResource(R.string.player_hbbtv_controls_hint), color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(LocalUiTokens.current.controlRadius))
                                .padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                } else if (controlsVisible) {
                    BroadcastToolbar(Modifier.align(Alignment.BottomCenter).heightIn(max = toolbarMaxHeight)
                        .verticalScroll(rememberScrollState())) {
                        Text(app.name?.takeIf(String::isNotBlank) ?: channelName ?: stringResource(R.string.player_hbbtv), fontSize = 20.sp)
                        if (pageState == HbbtvPageState.CONTENT && hbbtvColorKeys(colorKeys).isNotEmpty()) {
                            val labels = listOf(R.string.player_broadcast_color_red, R.string.player_broadcast_color_green,
                                R.string.player_broadcast_color_yellow, R.string.player_broadcast_color_blue)
                            BroadcastControlRow {
                                hbbtvColorKeys(colorKeys).forEach { index ->
                                    key(index) {
                                        BroadcastColorButton(index, stringResource(labels[index]),
                                            modifier = Modifier.onFocusChanged {
                                                if (it.isFocused) focusedColor = index
                                                else if (focusedColor == index) focusedColor = null
                                            },
                                            onClick = {
                                                browser?.colorKey(index)
                                                controlsVisible = false
                                            })
                                    }
                                }
                            }
                        }
                        BroadcastControlRow {
                            TvChip(stringResource(R.string.player_hbbtv_return_to_page), icon = Icons.Filled.Tv,
                                modifier = Modifier.focusRequester(returnFocus), onClick = { controlsVisible = false })
                            if (pageState == HbbtvPageState.CONTENT) TvChip(
                                stringResource(R.string.player_hbbtv_back_to_page), icon = Icons.AutoMirrored.Filled.ArrowBack,
                                onClick = { browser?.pageBack(); controlsVisible = false },
                            )
                            TvChip(stringResource(R.string.player_hbbtv_home), icon = Icons.Filled.Home, onClick = { restart(app.url) })
                            TvChip(stringResource(R.string.player_hbbtv_reload), icon = Icons.Filled.Refresh,
                                onClick = { restart(browser?.pageUrl ?: startUrl) })
                            TvChip(stringResource(R.string.player_hbbtv_close), icon = Icons.Filled.Close, onClick = onClose)
                        }
                        Text(stringResource(R.string.player_hbbtv_controls_hint), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** Browser keys remain with the sender; a held Back opens the native controls. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class HbbtvWebView(
    context: Context,
    private val channelName: String,
    private val language: String,
    private val broadcast: HbbtvBroadcastControl,
    private val currentChannel: () -> Boolean,
    private val onVideoWindow: (VideoWindowFraction?) -> Unit,
    private val onClose: () -> Unit,
    private val onControls: () -> Unit,
    private val onState: (HbbtvPageState) -> Unit,
    private val onColorKeys: (Int) -> Unit,
    private val allowPageFocus: () -> Boolean,
) : WebView(context) {
    // Read WebView settings only here, on its UI thread. The interceptor only reads this String.
    private val userAgent = settings.userAgentString + " " + HBBTV_USER_AGENT
    private val released = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val documents = HbbtvDocumentSession()
    private val backPress = HbbtvBackPress()
    private val activationKeys = HbbtvActivationKeys()
    private var initialized = false
    private val showHeldBack = Runnable {
        if (active() && backPress.held(SystemClock.uptimeMillis()) == HbbtvBackPress.Action.CONTROLS) onControls()
    }
    private var failed = false
    var pageUrl: String? = null
        private set
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build()

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.userAgentString = userAgent
        CookieManager.getInstance().setAcceptCookie(true)
        addJavascriptInterface(Bridge(), "AmbientHbbtv")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.isForMainFrame && request.method == "GET" && request.url.scheme in listOf("http", "https")) document(request) else null

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!active()) return
                pageUrl = url
                failed = false
                onColorKeys(HBBTV_DEFAULT_COLOR_KEYS)
                onState(HbbtvPageState.LOADING)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (active() && url == pageUrl && !failed) onState(HbbtvPageState.CONTENT)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail(request.url.toString(), "network ${error.errorCode}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (!request.isForMainFrame) return
                val documentId = errorResponse.responseHeaders?.entries?.firstOrNull {
                    it.key.equals(DOCUMENT_HEADER, ignoreCase = true)
                }?.value?.toLongOrNull()
                if (documentId != null && !documents.accepts(documentId)) return
                fail(request.url.toString(), "HTTP ${errorResponse.statusCode}")
            }
        }
        isFocusable = true
        isFocusableInTouchMode = true
        initialized = true
    }

    private fun active(): Boolean = !released.get() && currentChannel()

    fun open(url: String) {
        if (!active()) return
        pageUrl = url
        loadUrl(url)
        post { focusPage() }
    }

    fun focusPage() { if (active() && allowPageFocus()) requestFocus() }
    fun pageBack() = sendKey(461)
    fun colorKey(index: Int) { if (index in 0..3) sendKey(403 + index) }

    fun cancelBackPress() {
        removeCallbacks(showHeldBack)
        backPress.cancel()
    }

    /** Stops accepting old page callbacks immediately, before Compose removes the view. */
    fun deactivate() {
        if (!released.compareAndSet(false, true)) return
        documents.close()
        cancelBackPress()
        activationKeys.clear()
        client.dispatcher.cancelAll()
        stopLoading()
        removeJavascriptInterface("AmbientHbbtv")
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        deactivate()
        super.destroy()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        if (initialized && !gainFocus) {
            cancelBackPress()
            activationKeys.clear()
        }
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    private fun fail(url: String, reason: String) {
        if (!active() || pageUrl != url || failed) return
        failed = true
        Timber.w("HbbTV main document failed (%s)", reason)
        onState(HbbtvPageState.ERROR)
    }

    /** Runs on WebView's worker thread: no settings, focus, or other WebView state is read here. */
    private fun document(request: WebResourceRequest): WebResourceResponse {
        val document = documents.begin(request.url.toString()) ?: return errorResponse(null, 503)
        return try {
            val builder = Request.Builder().url(document.url).header("User-Agent", userAgent)
            // Let OkHttp decode compression and fetch a full body; WebView owns a different cache.
            hbbtvDocumentRequestHeaders(request.requestHeaders).forEach { (k, v) -> builder.header(k, v) }
            CookieManager.getInstance().getCookie(document.url)?.let { builder.header("Cookie", it) }
            client.newCall(builder.build()).execute().use { response ->
                response.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(document.url, it) }
                val finalUrl = response.request.url.toString()
                if (finalUrl != document.url) {
                    // A real navigation gives relative links the redirect destination as their base.
                    return@use WebResourceResponse("text/html", "utf-8", "<script>location.replace(${jsString(finalUrl)})</script>".byteInputStream())
                }
                if (!response.isSuccessful) {
                    documentFailure(document, "HTTP ${response.code}")
                    return@use errorResponse(document, response.code)
                }
                val type = response.header("Content-Type").orEmpty()
                if (!hbbtvDocumentType(type)) {
                    documentFailure(document, "unsupported document type")
                    return@use errorResponse(document, 415)
                }
                val charsetName = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1) ?: "utf-8"
                val body = response.body.bytes().toString(runCatching { charset(charsetName) }.getOrDefault(Charsets.UTF_8))
                val injected = injectHbbtvShim(body, hbbtvShim(channelName, language, document.id))
                WebResourceResponse("text/html", "utf-8", injected.byteInputStream(Charsets.UTF_8))
            }
        } catch (error: Exception) {
            documentFailure(document, error.javaClass.simpleName)
            errorResponse(document, 503)
        }
    }

    private fun documentFailure(document: HbbtvDocumentSession.Document, reason: String) {
        if (released.get() || !documents.accepts(document)) return
        post { if (documents.accepts(document)) fail(document.url, reason) }
    }

    private fun errorResponse(document: HbbtvDocumentSession.Document?, status: Int) = WebResourceResponse(
        "text/html", "utf-8", status.takeIf { it in 400..599 } ?: 502, "Service unavailable",
        document?.let { mapOf(DOCUMENT_HEADER to it.id.toString()) }.orEmpty(),
        "<html><body></body></html>".byteInputStream(),
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!active()) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    backPress.down(event.eventTime)
                    postDelayed(showHeldBack, 700L)
                }
                KeyEvent.ACTION_UP -> {
                    removeCallbacks(showHeldBack)
                    when (backPress.up(event.eventTime, event.isCanceled)) {
                        HbbtvBackPress.Action.PAGE_BACK -> pageBack()
                        HbbtvBackPress.Action.CONTROLS -> onControls()
                        HbbtvBackPress.Action.NONE -> Unit
                    }
                }
            }
            return true
        }
        val code = hbbtvKeyCode(event.keyCode)
        if (code != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) sendKey(code)
            return true
        }
        if (event.keyCode in ACTIVATION_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && !activationKeys.down(event.keyCode, repeated = event.repeatCount > 0)) return true
            else if (event.action == KeyEvent.ACTION_UP && !activationKeys.up(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sendKey(code: Int) {
        if (!active()) return
        evaluateJavascript(
            "(function(k){function f(t){var e=document.createEvent('Event');e.initEvent(t,true,true);" +
                "try{Object.defineProperty(e,'keyCode',{get:function(){return k}});Object.defineProperty(e,'which',{get:function(){return k}});}catch(x){e.keyCode=k;}" +
                "(document.activeElement||document).dispatchEvent(e);}f('keydown');f('keyup');})($code)", null,
        )
    }

    private fun fromDocument(documentId: Long, action: () -> Unit) {
        if (released.get() || !documents.accepts(documentId)) return
        post { if (active() && isAttachedToWindow && documents.accepts(documentId)) action() }
    }

    private inner class Bridge {
        @JavascriptInterface fun close(documentId: Long) = fromDocument(documentId, onClose)
        @JavascriptInterface fun show(documentId: Long) = Unit
        @JavascriptInterface fun keyset(value: Int, documentId: Long) = fromDocument(documentId) { onColorKeys(value and 15) }
        @JavascriptInterface fun videoWindow(value: String, documentId: Long) = fromDocument(documentId) {
            onVideoWindow(com.ambient.tv.ui.player.parseVideoWindow(value))
        }
        @JavascriptInterface fun broadcast(on: Boolean, documentId: Long) = fromDocument(documentId) {
            if (on) broadcast.resume() else broadcast.stop()
        }
    }

    companion object {
        const val HBBTV_USER_AGENT = "HbbTV/1.5.1 (+DRM;Ambient;AmbientTV;1.0;1.0;) FVC/3.0"
        private const val DOCUMENT_HEADER = "X-Ambient-Hbbtv-Document"
        private val ACTIVATION_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)
    }
}

/** Android key → HbbTV key; arrows, OK and digits keep the WebView's native mapping. */
internal fun hbbtvKeyCode(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_PROG_RED -> 403
    KeyEvent.KEYCODE_PROG_GREEN -> 404
    KeyEvent.KEYCODE_PROG_YELLOW -> 405
    KeyEvent.KEYCODE_PROG_BLUE -> 406
    KeyEvent.KEYCODE_BACK -> 461
    KeyEvent.KEYCODE_MEDIA_PLAY -> 415
    KeyEvent.KEYCODE_MEDIA_PAUSE -> 19
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> 402
    KeyEvent.KEYCODE_MEDIA_STOP -> 413
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 417
    KeyEvent.KEYCODE_MEDIA_REWIND -> 412
    else -> null
}

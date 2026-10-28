package com.ambient.tv.ui.player.teletext

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.ambient.tv.ui.player.videoImagePreferences
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The HbbTV ("red button") application of the live channel, opened on request. */
object HbbtvUi {
    /** Application on screen, null when closed. */
    var open by mutableStateOf<HbbtvApplication?>(null)
        private set
    /** Waiting for the one-time privacy notice. */
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
internal fun HbbtvHost(channelName: String?) {
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
    HbbtvUi.open?.let { app -> HbbtvViewer(app, channelName, onClose = HbbtvUi::close) }
}

@Composable
private fun HbbtvViewer(app: HbbtvApplication, channelName: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, dismissOnBackPress = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
            onDispose { }
        }
        val language = remember { java.util.Locale.getDefault().isO3Language.ifBlank { "deu" } }
        var hint by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(4_000); hint = false }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> HbbtvWebView(context, channelName.orEmpty(), language, onClose).also { it.open(app.url) } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.destroy() },
            )
            if (hint) Text(stringResource(R.string.player_hbbtv_close_hint), color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/**
 * WebView with what HbbTV pages expect of a TV browser: an HbbTV user agent, HbbTV documents shown
 * as HTML, the remote's colour, media and Back keys as HbbTV key codes, and minimal OIPF objects
 * (application manager, broadcast video, configuration) so the usual start-up code runs. The live
 * picture shows through the transparent page. Back twice closes it, as does destroyApplication().
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class HbbtvWebView(context: Context, channelName: String, language: String, private val onClose: () -> Unit) : WebView(context) {
    private val shim = hbbtvShim(channelName, language)
    private var lastBack = 0L
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = settings.userAgentString + " " + HBBTV_USER_AGENT
        CookieManager.getInstance().setAcceptCookie(true)
        addJavascriptInterface(Bridge(), "AmbientHbbtv")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.isForMainFrame && request.method == "GET") document(request) else null
        }
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun open(url: String) {
        loadUrl(url)
        // Focus once attached, so the remote's keys reach the page.
        post { requestFocus() }
    }

    /** Fetches the page itself: HbbTV MIME types become HTML, and the OIPF shim runs before the page's scripts. */
    private fun document(request: WebResourceRequest): WebResourceResponse? = runCatching {
        val url = request.url.toString()
        val builder = Request.Builder().url(url).header("User-Agent", settings.userAgentString)
        request.requestHeaders.forEach { (k, v) -> if (!k.equals("User-Agent", true)) builder.header(k, v) }
        CookieManager.getInstance().getCookie(url)?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            response.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(url, it) }
            val finalUrl = response.request.url.toString()
            if (finalUrl != url) {
                // Redirected: let the WebView load the final URL, so relative links resolve there.
                val html = "<script>location.replace(${jsString(finalUrl)})</script>"
                return@runCatching WebResourceResponse("text/html", "utf-8", html.byteInputStream())
            }
            val type = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful || !hbbtvDocumentType(type)) return@runCatching null
            val charsetName = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1) ?: "utf-8"
            val body = response.body.bytes().toString(runCatching { charset(charsetName) }.getOrDefault(Charsets.UTF_8))
            val injected = injectHbbtvShim(body, shim)
            WebResourceResponse("text/html", "utf-8", injected.byteInputStream(Charsets.UTF_8))
        }
    }.getOrNull()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = hbbtvKeyCode(event.keyCode)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                if (now - lastBack < 800L) onClose() else sendKey(code!!)
                lastBack = now
            }
            return true
        }
        if (code != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) sendKey(code)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sendKey(code: Int) = evaluateJavascript(
        "(function(k){function f(t){var e=document.createEvent('Event');e.initEvent(t,true,true);" +
            "try{Object.defineProperty(e,'keyCode',{get:function(){return k}});Object.defineProperty(e,'which',{get:function(){return k}});}catch(x){e.keyCode=k;}" +
            "(document.activeElement||document).dispatchEvent(e);}f('keydown');f('keyup');})($code)", null)

    private inner class Bridge {
        @JavascriptInterface fun close() = post { onClose() }
        @JavascriptInterface fun show() = Unit
    }

    companion object {
        const val HBBTV_USER_AGENT = "HbbTV/1.5.1 (+DRM;Ambient;AmbientTV;1.0;1.0;) FVC/3.0"
    }
}

/** HbbTV documents are (X)HTML under their own MIME types, which a plain WebView would download. */
internal fun hbbtvDocumentType(contentType: String): Boolean {
    val type = contentType.substringBefore(';').trim().lowercase()
    return type in setOf("application/vnd.hbbtv.xhtml+xml", "application/xhtml+xml", "text/html", "application/ce-html+xml")
}

/** The shim goes first into <head> (or the start of the document), ahead of the page's own scripts. */
internal fun injectHbbtvShim(html: String, shim: String): String {
    val tag = "<script type=\"text/javascript\">$shim</script>"
    val head = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
    return if (head != null) html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1)
    else tag + html
}

/** Android key → HbbTV (CEA-2014) key code; null for keys the WebView maps itself (arrows, OK, digits). */
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

/** Key constants and the OIPF objects HbbTV start-up code calls; no real broadcast control behind them. */
internal fun hbbtvShim(channelName: String, language: String): String {
    val name = jsString(channelName)
    val lang = jsString(language)
    return """(function(){if(window.__ambientHbbtv)return;window.__ambientHbbtv=true;
var K={VK_RED:403,VK_GREEN:404,VK_YELLOW:405,VK_BLUE:406,VK_LEFT:37,VK_UP:38,VK_RIGHT:39,VK_DOWN:40,VK_ENTER:13,VK_BACK:461,
VK_0:48,VK_1:49,VK_2:50,VK_3:51,VK_4:52,VK_5:53,VK_6:54,VK_7:55,VK_8:56,VK_9:57,VK_PLAY:415,VK_PAUSE:19,VK_PLAY_PAUSE:402,
VK_STOP:413,VK_FAST_FWD:417,VK_REWIND:412};window.KeyEvent=window.KeyEvent||{};
for(var k in K){try{window[k]=K[k];window.KeyEvent[k]=K[k];}catch(e){}}
var keyset={RED:1,GREEN:2,YELLOW:4,BLUE:8,NAVIGATION:16,VCR:32,SCROLL:64,INFO:128,NUMERIC:256,ALPHA:512,OTHER:1024,value:0,setValue:function(v){this.value=v;return v;}};
var channel={name:$name,ccid:'ccid:1',onid:1,tsid:1,sid:1,channelType:0,idType:12};
var app={privateData:{keyset:keyset,currentChannel:channel,getFreeMem:function(){return 0;}},show:function(){AmbientHbbtv.show();return true;},hide:function(){return true;},
destroyApplication:function(){AmbientHbbtv.close();},createApplication:function(u){location.href=u;},activate:function(){},deactivate:function(){}};
var P=HTMLObjectElement.prototype;P.getOwnerApplication=function(){return app;};
P.bindToCurrentChannel=function(){return channel;};P.setFullScreen=function(){};P.setChannel=function(){};P.prevChannel=function(){};
P.nextChannel=function(){};P.release=function(){};
P.getChannelConfig=function(){return {channelList:{length:1,item:function(){return channel;},getChannel:function(){return channel;}}};};
try{Object.defineProperty(P,'currentChannel',{get:function(){return channel;},configurable:true});}catch(e){}
try{Object.defineProperty(P,'configuration',{get:function(){return {preferredAudioLanguage:$lang,preferredSubtitleLanguage:$lang,preferredUILanguage:$lang,countryId:'DEU'};},configurable:true});}catch(e){}
window.oipfObjectFactory={isObjectSupported:function(t){return /oipfApplicationManager|oipfConfiguration|video\/broadcast/.test(t);},
createApplicationManagerObject:function(){return document.createElement('object');},createVideoBroadcastObject:function(){return document.createElement('object');},
createConfigurationObject:function(){return document.createElement('object');}};})();"""
}

/** A JavaScript string literal for [value] (quotes, backslashes, line breaks and "</" escaped). */
internal fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '<' -> append("\\u003c")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

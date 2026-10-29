package com.ambient.tv.ui.player.teletext

/** A newly activated HbbTV application must explicitly request its remote keys. */
internal const val HBBTV_DEFAULT_COLOR_KEYS = 0

internal fun hbbtvColorKeys(keyset: Int): List<Int> = (0..3).filter { keyset and (1 shl it) != 0 }

/** The manual fetch needs a complete decoded document, not WebView's compressed or cached body. */
internal fun hbbtvDocumentRequestHeaders(headers: Map<String, String>): Map<String, String> = headers.filterKeys {
    it.lowercase() !in setOf("user-agent", "accept-encoding", "if-none-match", "if-modified-since", "if-range", "range")
}

/** Only a key pressed in the page may deliver its release there after a native focus transfer. */
internal class HbbtvActivationKeys {
    private val pressed = mutableSetOf<Int>()
    fun down(key: Int, repeated: Boolean = false): Boolean {
        if (repeated) return key in pressed
        pressed += key
        return true
    }
    fun up(key: Int): Boolean = pressed.remove(key)
    fun clear() { pressed.clear() }
}

/** Releasing an old viewer clears its own video window, without clearing a newer viewer's. */
internal class HbbtvWindowOwnership<T>(private val applyValue: (T?) -> Unit) {
    private var owner: Any? = null
    fun claim(token: Any) { owner = token; applyValue(null) }
    fun update(token: Any, value: T?) { if (owner === token) applyValue(value) }
    fun release(token: Any) {
        if (owner !== token) return
        owner = null
        applyValue(null)
    }
}

/** One press sends Back on release, or opens native controls once it has been held. */
internal class HbbtvBackPress(private val holdMs: Long = 700L) {
    enum class Action { NONE, PAGE_BACK, CONTROLS }
    private var downAt: Long? = null
    private var held = false

    fun down(now: Long) { downAt = now; held = false }

    fun held(now: Long): Action {
        val start = downAt ?: return Action.NONE
        if (held || now - start < holdMs) return Action.NONE
        held = true
        return Action.CONTROLS
    }

    fun up(now: Long, cancelled: Boolean): Action {
        val start = downAt
        val alreadyHeld = held
        cancel()
        return when {
            start == null || cancelled || alreadyHeld -> Action.NONE
            now - start >= holdMs -> Action.CONTROLS
            else -> Action.PAGE_BACK
        }
    }

    fun cancel() { downAt = null; held = false }
}

/** Each fetched main document owns its bridge; old pages and released viewers cannot send updates. */
internal class HbbtvDocumentSession {
    data class Document(val id: Long, val url: String)
    private var nextId = 0L
    private var current: Document? = null
    private var active = true

    @Synchronized fun begin(url: String): Document? {
        if (!active) return null
        return Document(++nextId, url).also { current = it }
    }

    @Synchronized fun accepts(id: Long): Boolean = active && current?.id == id
    @Synchronized fun accepts(document: Document): Boolean = active && current === document
    @Synchronized fun close() { active = false; current = null }
}

/** HbbTV documents are (X)HTML under their own MIME types, which a plain WebView would download. */
internal fun hbbtvDocumentType(contentType: String): Boolean {
    val type = contentType.substringBefore(';').trim().lowercase()
    return type in setOf("application/vnd.hbbtv.xhtml+xml", "application/xhtml+xml", "text/html", "application/ce-html+xml")
}

/** The shim goes first into <head> (or the start of the document), ahead of the page's own scripts. */
internal fun injectHbbtvShim(html: String, shim: String): String {
    // HbbTV pages assume a 1280 × 720 screen; pages with their own viewport keep it.
    val viewport = if (Regex("<meta[^>]+name=[\"']?viewport", RegexOption.IGNORE_CASE).containsMatchIn(html)) ""
        else "<meta name=\"viewport\" content=\"width=1280, user-scalable=no\">"
    val tag = "$viewport<script type=\"text/javascript\">$shim</script>"
    val head = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
    return if (head != null) html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1)
    else tag + html
}

/** OIPF compatibility objects; native callbacks carry the document identity to reject stale pages. */
internal fun hbbtvShim(channelName: String, language: String, documentId: Long = 0L): String {
    val name = jsString(channelName)
    val lang = jsString(language)
    return """(function(){if(window.__ambientHbbtv)return;window.__ambientHbbtv=true;
var nativeBridge=window.AmbientHbbtv,documentId=$documentId;
var AmbientHbbtv={close:function(){nativeBridge.close(documentId);},show:function(){nativeBridge.show(documentId);},
videoWindow:function(v){nativeBridge.videoWindow(v,documentId);},broadcast:function(v){nativeBridge.broadcast(v,documentId);},
keyset:function(v){nativeBridge.keyset(v,documentId);}};
var K={VK_RED:403,VK_GREEN:404,VK_YELLOW:405,VK_BLUE:406,VK_LEFT:37,VK_UP:38,VK_RIGHT:39,VK_DOWN:40,VK_ENTER:13,VK_BACK:461,
VK_0:48,VK_1:49,VK_2:50,VK_3:51,VK_4:52,VK_5:53,VK_6:54,VK_7:55,VK_8:56,VK_9:57,VK_PLAY:415,VK_PAUSE:19,VK_PLAY_PAUSE:402,
VK_STOP:413,VK_FAST_FWD:417,VK_REWIND:412};window.KeyEvent=window.KeyEvent||{};
for(var k in K){try{window[k]=K[k];window.KeyEvent[k]=K[k];}catch(e){}}
var keyset={RED:1,GREEN:2,YELLOW:4,BLUE:8,NAVIGATION:16,VCR:32,SCROLL:64,INFO:128,NUMERIC:256,ALPHA:512,OTHER:1024,value:0,setValue:function(v){this.value=(Number(v)||0)&2047;AmbientHbbtv.keyset(this.value);return this.value;}};
var channel={name:$name,ccid:'ccid:1',onid:1,tsid:1,sid:1,channelType:0,idType:12};
var app={privateData:{keyset:keyset,currentChannel:channel,getFreeMem:function(){return 0;}},show:function(){AmbientHbbtv.show();return true;},hide:function(){return true;},
destroyApplication:function(){AmbientHbbtv.close();},createApplication:function(u){location.href=u;},activate:function(){},deactivate:function(){}};
var P=HTMLObjectElement.prototype;P.getOwnerApplication=function(){return app;};
function bc(o){return /video\/broadcast/i.test((o&&o.type)||'');}
var fullScreen=false,lastWindow='';
function videoWindow(){var o=null,l=document.getElementsByTagName('object');for(var i=0;i<l.length;i++){if(bc(l[i])){o=l[i];break;}}
var w='full';if(o&&!fullScreen){var st=window.getComputedStyle(o),r=o.getBoundingClientRect(),W=window.innerWidth,H=window.innerHeight;
if(st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&W>0&&H>0)w=[r.left/W,r.top/H,r.width/W,r.height/H].map(function(v){return v.toFixed(4);}).join(',');}
if(w!==lastWindow){lastWindow=w;AmbientHbbtv.videoWindow(w);}}
setInterval(videoWindow,400);
P.bindToCurrentChannel=function(){if(bc(this)){AmbientHbbtv.broadcast(true);setTimeout(videoWindow,0);}return channel;};
P.setFullScreen=function(f){if(bc(this)){fullScreen=!!f;videoWindow();}};
P.setChannel=function(c){if(bc(this))AmbientHbbtv.broadcast(c!==null&&c!==undefined);};P.prevChannel=function(){};
P.nextChannel=function(){};P.stop=function(){if(bc(this))AmbientHbbtv.broadcast(false);};
P.release=function(){if(bc(this))AmbientHbbtv.broadcast(false);};
document.addEventListener('play',function(e){var t=e.target&&e.target.tagName;if(t==='VIDEO'||t==='AUDIO')AmbientHbbtv.broadcast(false);},true);
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

/**
 * Pauses the live programme while the HbbTV application plays its own video (it stops the
 * broadcast), and resumes it when the application binds the broadcast again or closes. Only a
 * programme this control paused is resumed: one the viewer paused stays paused.
 */
internal class HbbtvBroadcastControl(
    private val wantsToPlay: () -> Boolean,
    private val pause: () -> Unit,
    private val play: () -> Unit,
) {
    private var pausedByApp = false

    fun stop() {
        if (pausedByApp || !wantsToPlay()) return
        pausedByApp = true
        pause()
    }

    fun resume() {
        if (!pausedByApp) return
        pausedByApp = false
        play()
    }
}

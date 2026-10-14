package com.ambient.tv.companion

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.Lifecycle
import com.ambient.tv.ui.MainActivity
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import kotlinx.coroutines.*
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Real production service and activity, with a second pinned identity as the phone. */
class CompanionReceiverAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private val context get() = instrumentation.targetContext
    private suspend fun await(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        withTimeout(timeout) { while (!predicate()) delay(100) }
        log("PASS $label")
    }
    fun run(clipPath: String) = runBlocking {
        check(context.packageName.endsWith(".companionaudit"))
        check(android.provider.Settings.canDrawOverlays(context)) { "Grant SYSTEM_ALERT_WINDOW to the isolated audit app" }
        val data = File(clipPath).readBytes()
        val http = ServerSocket(0)
        thread(isDaemon = true, name = "companion-fixture") {
            while (!http.isClosed) {
                val socket = runCatching { http.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching { socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val first = reader.readLine().orEmpty()
                        var offset = 0
                        var ranged = false
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", true)) {
                                offset = line.substringAfter("bytes=").substringBefore('-').toIntOrNull() ?: 0
                                ranged = true
                            }
                        }
                        offset = offset.coerceIn(0, data.lastIndex)
                        val headers = buildString {
                            append(if (ranged) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                            append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nConnection: close\r\nContent-Length: ${data.size - offset}\r\n")
                            if (ranged) append("Content-Range: bytes $offset-${data.lastIndex}/${data.size}\r\n")
                            append("\r\n")
                        }
                        it.getOutputStream().write(headers.toByteArray())
                        if (!first.startsWith("HEAD")) it.getOutputStream().write(data, offset, data.size - offset)
                    } }
                }
            }
        }
        var latestActivity: MainActivity? = null
        val application = context.applicationContext as android.app.Application
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) { if (activity is MainActivity) latestActivity = activity }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val tv = activity.companionManager
        val phoneContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("receiver_audit_phone", mode)
        }
        phoneContext.getSharedPreferences("unused", 0).edit().clear().commit()
        val phone = CompanionManager(phoneContext, "ambient_receiver_audit_phone_v1")
        try {
            await("production TV activity is started") { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            withContext(Dispatchers.Main) { tv.openPairing(); phone.foreground(true, false) }
            await("production TV publishes pairing invitation") { tv.state.value.invitation != null }
            val tvHost = CompanionInvitation.parse(tv.state.value.invitation!!).host
            phone.pair(tv.state.value.invitation!!)
            await("both real TLS peers require consent") { tv.state.value.prompt != null && phone.state.value.prompt != null }
            withContext(Dispatchers.Main) { tv.confirm(true); phone.confirm(true) }
            await("phone authenticates to production TV") { phone.state.value.online }
            val store = CompanionConnectionStore(phoneContext)
            val pin = phone.state.value.paired.single().fingerprint
            store.learnMac(pin, "02:00:00:00:00:00")
            check(store.get(pin).mac.isEmpty())
            store.learnMac(pin, "A0:B1:C2:D3:E4:F5")
            check(!store.get(pin).wake)
            store.put(pin, store.get(pin).copy(wireGuard = true, vpnHost = "192.168.50.9"))
            check(CompanionConnectionStore(phoneContext).get(pin).vpnHost == "192.168.50.9")
            store.learnMac(pin, "A0:B1:C2:D3:E4:F6")
            check(store.get(pin).mac == "A0:B1:C2:D3:E4:F5")
            store.remove(pin)
            log("PASS per-TV settings survive recreation and MAC learning preserves manual values and wake opt-in")
            withContext(Dispatchers.Main) { tv.enableBackgroundReceiver(true) }
            delay(1000)
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            await("TV UI leaves foreground") { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            delay(1500)
            check(phone.state.value.online)
            phone.disconnect()
            phone.connect(phone.state.value.paired.single())
            await("idle receiver service accepts a fresh authenticated connection") { phone.state.value.online }
            val request = CompanionFrame(type = "start", startRequestId = "receiver-background-audit", media = CompanionMedia("url", "http://$tvHost:${http.localPort}/fixture.mp4"))
            phone.send(request)
            await("service launches the actual TV activity from Home") { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            await("production player starts the requested video", 60_000) {
                BackgroundPlaybackSession.snapshot.value.let { it.playing && it.status.positionMs > 500 }
            }
            val generation = BackgroundPlaybackSession.snapshot.value.generation
            phone.send(request)
            delay(1500)
            check(BackgroundPlaybackSession.snapshot.value.generation == generation)
            log("PASS repeated start ID does not restart production playback")
            withContext(Dispatchers.Main) { BackgroundPlaybackSession.stop(); activity.finishAndRemoveTask() }
            await("TV task is removed while receiver remains enabled") { activity.isDestroyed }
            delay(1000)
            check(phone.state.value.online)
            phone.send(request.copy(startRequestId = "receiver-closed-task-audit"))
            await("receiver creates a new TV activity after task removal") { latestActivity !== activity && latestActivity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true }
            await("new TV activity starts video after task removal", 60_000) {
                BackgroundPlaybackSession.snapshot.value.let { it.playing && it.status.positionMs > 500 }
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "companion-background-playback.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            phone.disconnect(); phone.foreground(false, false)
            withContext(Dispatchers.Main) {
                BackgroundPlaybackSession.stop()
                tv.enableBackgroundReceiver(false)
                tv.state.value.paired.filter { it.name == android.os.Build.MODEL }.forEach(tv::revoke)
                latestActivity?.finish()
            }
            application.unregisterActivityLifecycleCallbacks(callbacks)
            http.close()
        }
    }
}

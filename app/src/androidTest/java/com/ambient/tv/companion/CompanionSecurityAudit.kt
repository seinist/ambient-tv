package com.ambient.tv.companion

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

/** Two isolated peers on one Android device: real Keystore, pinned TLS, consent and revocation. */
class CompanionSecurityAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit, private val testUi: Boolean = true) {
    private val base get() = instrumentation.targetContext
    private fun isolated(role: String): Context {
        base.getSharedPreferences("companion_audit_$role", Context.MODE_PRIVATE).edit().clear().commit()
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("companion_audit_$role", mode)
        }
    }
    private suspend fun await(label: String, condition: () -> Boolean) {
        try { withTimeout(15_000) { while (!condition()) delay(50) } }
        catch (failure: TimeoutCancellationException) { error("$label: timeout") }
        log("PASS $label")
    }

    fun run() = runBlocking {
        check(base.packageName.endsWith(".settingsaudit") || base.packageName.endsWith(".companionaudit") || base.packageName.endsWith(".companionfeaturesaudit")) { "Requires the isolated audit APK" }
        mediaMapping()
        queuePersistence()
        invalidVpnCandidate()
        boundWakePackets()
        val mappingPrefs = com.ambient.tv.data.local.AppPreferences(isolated("queue_mapper").getSharedPreferences("unused", 0))
        val auditMapper = CompanionMediaMapper(mappingPrefs, com.ambient.tv.data.dlna.DlnaStore(isolated("queue_dlna").getSharedPreferences("unused", 0)), com.ambient.tv.data.shared.SharedMediaRepository())
        val tv = CompanionManager(isolated("tv"), "ambient_companion_audit_tv_v1", auditMapper)
        val phone = CompanionManager(isolated("phone"), "ambient_companion_audit_phone_v1")
        val starts = AtomicInteger()
        val received = java.util.Collections.synchronizedList(mutableListOf<CompanionMedia>())
        var activity: com.ambient.tv.ui.catalog.UiCatalogActivity? = null
        try {
            check(CompanionIdentity("ambient_companion_audit_tv_v1").pin == CompanionIdentity("ambient_companion_audit_tv_v1").pin)
            check(CompanionIdentity("ambient_companion_audit_tv_v1").pin != CompanionIdentity("ambient_companion_audit_phone_v1").pin)
            log("PASS independent Android Keystore identities survive recreation")
            tv.attach { received += requireNotNull(it.media); starts.incrementAndGet() }
            tv.foreground(true, true); phone.foreground(true, false)
            tv.openPairing()
            await("LAN receiver and expiring invitation") { tv.state.value.invitation != null }
            val invitation = CompanionInvitation.parse(tv.state.value.invitation!!)
            val peer = CompanionPeer(invitation.fingerprint, "Audit TV", invitation.host, invitation.port)
            tv.showPanel()
            tv.discover()
            tv.connect(peer)
            tv.pair(invitation.link())
            delay(300)
            check(tv.state.value.devices.isEmpty() && tv.state.value.target == null && !tv.state.value.busy)
            log("PASS TV panel cannot discover TVs or open a client connection")
            phone.discover()
            await("DNS-SD discovers the pinned TV identity") { phone.state.value.devices.any { it.fingerprint == peer.fingerprint } }

            phone.connect(peer.copy(fingerprint = "00".repeat(32)))
            await("wrong TV certificate rejected") { !phone.state.value.busy }
            check(!phone.state.value.online && phone.state.value.paired.isEmpty())
            phone.connect(peer)
            await("unknown phone without invitation rejected") { !phone.state.value.busy }
            check(!phone.state.value.online && tv.state.value.prompt == null && phone.state.value.remote == null)

            phone.pair(invitation.link())
            await("both consent prompts shown") { tv.state.value.prompt != null && phone.state.value.prompt != null }
            check(tv.state.value.prompt!!.code == phone.state.value.prompt!!.code)
            val canTestUi = testUi && base.getSystemService(android.os.PowerManager::class.java).isInteractive &&
                !base.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            if (canTestUi) {
            phone.showConnectionSettings() // Consent must take precedence over the connection editor.
            activity = instrumentation.startActivitySync(Intent(base, com.ambient.tv.ui.catalog.UiCatalogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as com.ambient.tv.ui.catalog.UiCatalogActivity
            val uiPrefs = isolated("ui").getSharedPreferences("unused", 0)
            val uiPreferences = com.ambient.tv.data.local.AppPreferences(uiPrefs)
            val vm = CompanionViewModel(phone, CompanionMediaMapper(uiPreferences, com.ambient.tv.data.dlna.DlnaStore(uiPrefs), com.ambient.tv.data.shared.SharedMediaRepository()),
                CompanionSettingsService(base, com.ambient.tv.data.local.SettingsBackupManager(uiPreferences, com.ambient.tv.data.local.TokenStore(uiPrefs, uiPreferences))))
            withContext(Dispatchers.Main) {
                activity.setContent {
                    CompositionLocalProvider(com.ambient.tv.ui.mobile.LocalHandheldUi provides true) {
                        com.ambient.tv.ui.theme.AmbientTvTheme { CompanionDialogs(vm) }
                    }
                }
            }
            delay(700)
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(base.filesDir, "companion-confirm.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val label = base.getString(com.ambient.tv.R.string.companion_codes_match)
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            var node: android.view.accessibility.AccessibilityNodeInfo? = null
            fun findLabel(root: android.view.accessibility.AccessibilityNodeInfo, depth: Int = 0): android.view.accessibility.AccessibilityNodeInfo? {
                if (root.text?.toString() == label) return root
                if (depth >= 32) return null
                for (index in 0 until root.childCount) {
                    root.getChild(index)?.let { child -> findLabel(child, depth + 1)?.let { return it } }
                }
                return null
            }
            await("confirmation action is accessible in the app window") {
                node = instrumentation.uiAutomation.windows.mapNotNull { it.root }
                    .filter { it.packageName?.toString() == base.packageName }
                    .firstNotNullOfOrNull { findLabel(it) }
                node != null
            }
            var button = checkNotNull(node) { "Pair confirmation button not visible" }
            while (!button.isClickable && button.parent != null) button = button.parent
            check(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await("production confirmation button accepts the phone prompt") { phone.state.value.prompt == null }
            phone.hideConnectionSettings()
            } else {
                log("SKIP visual confirmation-button test: headless audit requested or display locked/off")
                phone.confirm(true)
            }
            delay(300)
            check(!phone.state.value.online && tv.state.value.paired.isEmpty())
            log("PASS phone confirmation alone does not authorize control")
            tv.confirm(true)
            await("two-sided pairing succeeds over pinned TLS") { phone.state.value.online }
            check(tv.state.value.paired.size == 1 && phone.state.value.paired.size == 1)
            check(tv.state.value.invitation == null)
            phone.send(CompanionFrame(type = "start", media = CompanionMedia("youtube", "dQw4w9WgXcQ")))
            await("authenticated command dispatched exactly once") { starts.get() == 1 && phone.state.value.remote != null }
            delay(1_200); check(starts.get() == 1)

            phone.disconnect()
            phone.connect(peer)
            await("stored identity reconnects without pairing prompt") { phone.state.value.online }
            check(tv.state.value.prompt == null && phone.state.value.prompt == null)
            val sharedPeer = phone.state.value.paired.single()
            val shortcutId = CompanionShareTargets.id(sharedPeer)
            val shortcuts = base.getSystemService(android.content.pm.ShortcutManager::class.java)
            check(shortcuts.dynamicShortcuts.any { it.id == shortcutId && it.categories?.contains("com.ambient.tv.category.SHARE_TO_TV") == true })
            log("PASS paired TV is published as an Android sharing shortcut")
            fun share(url: String, id: String? = shortcutId) = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url).also { if (id != null) it.putExtra(Intent.EXTRA_SHORTCUT_ID, id) }
            phone.handleIntent(share("https://media.example.test/song.mp3?signature=test"))
            await("MP3 Direct Share dispatches once to the selected TV without another chooser") { starts.get() == 2 && phone.state.value.sendingTo == null }
            check(received.last() == CompanionMedia("url", "https://media.example.test/song.mp3?signature=test"))
            check(phone.state.value.sharedMedia == null && !phone.state.value.panel)
            phone.disconnect()
            phone.handleIntent(share("https://media.example.test/video.mp4"))
            await("video Direct Share resolves and reconnects the pinned TV") { starts.get() == 3 && phone.state.value.sendingTo == null }
            check(tv.state.value.prompt == null && phone.state.value.prompt == null)
            phone.handleIntent(share("https://media.example.test/song.flac", id = null))
            delay(500); check(starts.get() == 3 && phone.state.value.sharedMedia?.kind == "url")
            phone.sendSharedMediaTo(sharedPeer)
            await("normal sharing waits for the explicit named TV action") { starts.get() == 4 && phone.state.value.sendingTo == null }
            phone.handleIntent(share("https://media.example.test/song.mp3", id = "companion-tv-unknown"))
            phone.handleIntent(share("https://media.example.test/page.html"))
            delay(500); check(starts.get() == 4 && phone.state.value.sharedMedia == null)
            log("PASS unknown shortcuts and website shares never start playback")
            if (canTestUi) {
                playbackStatusAndControls(tv, phone)
                tv.attach { received += requireNotNull(it.media); starts.incrementAndGet() }
            }
            settingsAndControl(tv, phone, peer)
            oneWaySettings(tv, phone, activity.takeIf { canTestUi })
            tvToTv()
            tv.revoke(tv.state.value.paired.single())
            await("revocation closes existing connection") { !phone.state.value.online }
            check(!phone.send(CompanionFrame(type = "start", media = CompanionMedia("youtube", "dQw4w9WgXcQ"))))
            phone.connect(peer)
            await("revoked identity cannot reconnect") { !phone.state.value.busy }
            check(!phone.state.value.online && starts.get() == 4)
            log("PASS revoked device cannot issue further commands")
            if (canTestUi) {
                withContext(Dispatchers.Main) { phone.showPanel() }
                clickLabel(com.ambient.tv.R.string.companion_manage_devices)
                delay(400)
                screenshot("companion-offline-management")
                clickLabel(com.ambient.tv.R.string.companion_unpair)
                delay(400)
                screenshot("companion-offline-unpair")
                clickLabel(com.ambient.tv.R.string.companion_unpair)
                await("offline TV can be unpaired through the confirmation UI") { phone.state.value.paired.isEmpty() }
            } else phone.revoke(sharedPeer)
            check(shortcuts.dynamicShortcuts.none { it.id == shortcutId })
            phone.handleIntent(share("https://media.example.test/song.mp3"))
            delay(300); check(starts.get() == 4 && !phone.state.value.online)
            log("PASS forgetting a TV removes its shortcut and rejects stale shortcut intents")
        } finally {
            log("DIAGNOSTIC TV ${tv.diagnosticFailure}; phone ${phone.diagnosticFailure}")
            tv.foreground(false, true); phone.foreground(false, false)
            tv.detach()
            withContext(Dispatchers.Main) { activity?.finish() }
        }
    }

    /** Real pinned TLS round trip through the production session and receiver command dispatch. */
    private suspend fun playbackStatusAndControls(tv: CompanionManager, phone: CompanionManager) {
        val session = com.ambient.tv.ui.player.BackgroundPlaybackSession
        check(!session.snapshot.value.active)
        class Handle : com.ambient.tv.ui.player.PlaybackHandle {
            @Volatile var position = 5_000L
            @Volatile var playing = true
            @Volatile var seeks = 0
            @Volatile var ended = false
            override fun status() = com.ambient.tv.ui.player.PlaybackStatus(position, 80_000, playing, ended)
            override fun play() { playing = true }
            override fun pause() { playing = false }
            override fun seekTo(positionMs: Long) { position = positionMs; seeks++ }
            override fun release() = Unit
        }
        var handle = Handle()
        val media = CompanionMedia("library", "j:episode", "a".repeat(64))
        fun metadata(id: String, live: Boolean = false) = com.ambient.tv.ui.player.PlaybackMetadata(
            route = "audit/$id", title = "Audit $id", subtitle = "Season 1 · Episode 2", source = "audit", mediaId = id,
            durationMs = 100_000, timelineOffsetMs = 20_000, isLive = live, companionMedia = media.copy(kind = if (live) "live:channel" else "library", id = id),
            parentMediaIds = listOf("j:series", "j:season"))
        try {
            withContext(Dispatchers.Main) { session.acquire(base, metadata(media.id), "audit", Any()) { handle } }
            await("TV state exposes scoped item identity and absolute timeline") {
                phone.state.value.currentPlayback()?.let { it.media == media && it.position == 25_000L && it.duration == 100_000L && it.phase == "playing" } == true
            }
            check(phone.state.value.currentPlayback()!!.parentIds == listOf("j:series", "j:season"))
            check(phone.playbackCommand("pause"))
            await("phone pause controls the TV without losing the active item") { phone.state.value.currentPlayback()?.phase == "paused" }
            check(!handle.playing)
            check(phone.playbackCommand("seek", position = 40_000))
            await("absolute phone seek translates to the TV transcode timeline") { phone.state.value.currentPlayback()?.position == 40_000L }
            check(handle.position == 20_000L && handle.seeks == 1)
            check(phone.playbackCommand("play"))
            await("phone resumes the TV") { phone.state.value.currentPlayback()?.phase == "playing" }
            val owner = Any()
            var chosenQuality = "q1"; var chosenAudio = "a1"; var nextCount = 0; var previousCount = 0
            withContext(Dispatchers.Main) {
                com.ambient.tv.companion.CompanionPlaybackControls.update(owner, session.snapshot.value.metadata!!.route,
                    com.ambient.tv.companion.CompanionPlaybackControls.SourceControls(
                        quality = listOf(CompanionOption("q1", "1080p", true), CompanionOption("q2", "720p")), chooseQuality = { chosenQuality = it },
                        audio = listOf(CompanionOption("a1", "English", true), CompanionOption("a2", "Deutsch")), chooseAudio = { chosenAudio = it },
                        next = { nextCount++ }, previous = { previousCount++ }))
            }
            try {
                await("phone receives renderer options and source navigation capabilities") { phone.state.value.remote?.options?.any { it.kind == "quality" } == true && "next" in phone.state.value.remote!!.capabilities }
                check(phone.playbackCommand("select", optionGroup = "quality", optionKey = "q2"))
                await("quality selection invokes the current source adapter") { chosenQuality == "q2" }
                check(phone.playbackCommand("select", optionGroup = "audio", optionKey = "a2"))
                await("audio selection invokes the current source adapter") { chosenAudio == "a2" }
                check(phone.playbackCommand("select", optionGroup = "quality", optionKey = "missing"))
                await("TV rejects an unknown option instead of selecting another track") { phone.state.value.remote?.error == "unsupported" }
                check(chosenQuality == "q2")
                check(phone.playbackCommand("next")); await("semantic next reaches the active source") { nextCount == 1 }
                check(phone.playbackCommand("previous")); await("semantic previous reaches the active source") { previousCount == 1 }
            } finally { withContext(Dispatchers.Main) { com.ambient.tv.companion.CompanionPlaybackControls.remove(owner) } }
            val wrongDevice = phone.state.value.currentPlayback()!!.copy(receiverId = "b".repeat(64))
            check(!phone.playbackCommand("pause", wrongDevice))
            log("PASS a stale UI from another TV cannot control the selected receiver")
            val old = phone.state.value.currentPlayback()!!
            handle = Handle()
            withContext(Dispatchers.Main) { session.acquire(base, metadata("j:next"), "audit", Any()) { handle } }
            // Deliberately send an old command over TLS, bypassing the phone-side guard.
            check(phone.send(CompanionFrame(type = "seek", media = old.media, playbackSessionId = old.playbackSessionId, position = 70_000)))
            await("TV rejects a late seek after a title switch") { phone.state.value.remote?.let { it.media?.id == "j:next" && it.error == "unsupported" } == true }
            check(handle.position == 5_000L && handle.seeks == 0)
            check(!phone.playbackCommand("seek", old, 50_000))
            withContext(Dispatchers.Main) { session.acquire(base, metadata("channel", live = true), "audit", Any()) { handle } }
            await("live TV is identified without a VOD seek control") { phone.state.value.currentPlayback()?.let { it.isLive && it.duration == 0L && !it.seekable } == true }
            check(!phone.playbackCommand("seek", position = 30_000))
            phone.foreground(false, false)
            check(phone.state.value.currentPlayback() == null && phone.state.value.target != null)
            phone.foreground(true, false)
            check(!phone.state.value.online)
            phone.connectRemote(requireNotNull(phone.state.value.target))
            await("explicit remote action reconnects and opens controls after foreground return") { phone.state.value.currentPlayback()?.isLive == true && phone.state.value.remotePanel }
            withContext(Dispatchers.Main) {
                tv.attach { request ->
                    val reference = requireNotNull(request.media)
                    handle = Handle()
                    session.acquire(base, metadata(reference.id).copy(companionMedia = reference), "audit", Any()) { handle }
                }
            }
            val a = CompanionMedia("youtube", "dQw4w9WgXcQ")
            val b = CompanionMedia("youtube", "9bZkp7q19f0")
            check(phone.send(CompanionFrame(type = "queue_add", media = a, title = "First")))
            check(phone.send(CompanionFrame(type = "queue_add", media = b, title = "Second")))
            await("queue exposes stable rows without sending media URLs") { phone.state.value.remote?.let { it.queueTotal == 2 && it.queue.size == 2 && it.queue.all { row -> row.media == null } } == true }
            val queued = phone.state.value.remote!!
            check(phone.queueCommand("queue_move", "${queued.queue.last().token}|-1", queued))
            await("queue reorder retains the chosen stable row") { phone.state.value.remote?.queue?.firstOrNull()?.title == "Second" }
            check(phone.send(CompanionFrame(type = "queue_remove", payload = queued.queue.first().token, queueRevision = queued.queueRevision)))
            await("TV rejects a stale queue edit revision") { phone.state.value.remote?.error == "unsupported" }
            check(phone.state.value.remote!!.queueTotal == 2)
            check(phone.playbackCommand("next"))
            await("queued choice has priority over source next") { phone.state.value.currentPlayback()?.media == b && phone.state.value.remote?.queueTotal == 1 }
            handle.ended = true
            withContext(Dispatchers.Main) { session.tick() }
            await("end of item advances the remaining explicit queue exactly once") { phone.state.value.currentPlayback()?.media == a && phone.state.value.remote?.queueTotal == 0 }
            check(phone.playbackCommand("stop"))
            await("stop clears the phone playback state") { phone.state.value.online && phone.state.value.remote?.active == false }
            check(phone.state.value.currentPlayback() == null && !session.snapshot.value.active)

            val peer = requireNotNull(phone.state.value.target)
            var localStarts = 0
            withContext(Dispatchers.Main) {
                phone.requestPlayback(a, "Choose device") { localStarts++ }
                check(phone.playbackRequest.value != null && !session.snapshot.value.active)
            }
            fun containsText(node: android.view.accessibility.AccessibilityNodeInfo, text: String): Boolean {
                if (node.text?.toString() == text) return true
                return (0 until node.childCount).any { index -> node.getChild(index)?.let { containsText(it, text) } == true }
            }
            await("target chooser exposes phone and connected TV actions") {
                instrumentation.uiAutomation.windows.mapNotNull { it.root }.any { root ->
                    containsText(root, base.getString(com.ambient.tv.R.string.companion_play_here)) &&
                        containsText(root, base.getString(com.ambient.tv.R.string.companion_play_on, peer.name))
                }
            }
            delay(700) // Let platform dialog enter/exit transitions finish before visual inspection.
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(base.filesDir, "companion-target.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            withContext(Dispatchers.Main) {
                phone.cancelPlaybackRequest()
                check(localStarts == 0)
                phone.requestPlayback(a) { localStarts++ }
                phone.playRequestLocally()
            }
            check(localStarts == 1 && phone.state.value.online && phone.state.value.target == peer)
            log("PASS local selection and cancellation preserve the paired TV connection")
            withContext(Dispatchers.Main) {
                tv.attach { throw CompanionSourceUnavailableException() }
                phone.requestPlayback(a) { localStarts++ }
                phone.playRequestOn(peer)
            }
            await("missing TV source is reported in the target chooser") {
                phone.playbackRequest.value?.error == com.ambient.tv.R.string.companion_target_source_missing
            }
            check(localStarts == 1)
            withContext(Dispatchers.Main) { phone.playRequestLocally() }
            check(localStarts == 2 && phone.state.value.online)
            log("PASS failed TV start offers local playback without an automatic fallback")
            withContext(Dispatchers.Main) {
                tv.attach { request ->
                    val reference = requireNotNull(request.media)
                    session.beginSourceReplacement(metadata(reference.id).copy(companionMedia = reference), playWhenReadyOverride = true)
                }
                phone.requestPlayback(a, "Confirmed start") { localStarts++ }
                phone.playRequestOn(peer)
            }
            await("accepted TV start keeps the chooser waiting for actual playback") {
                phone.state.value.remote?.startStatus == "accepted" && phone.playbackRequest.value?.starting == true
            }
            delay(1_200)
            check(phone.playbackRequest.value?.starting == true && localStarts == 2)
            withContext(Dispatchers.Main) {
                handle = Handle()
                session.acquire(base, metadata(a.id).copy(companionMedia = a), "audit", Any()) { handle }
            }
            await("confirmed TV playback opens the phone controls") {
                phone.playbackRequest.value == null && phone.state.value.nowPlayingPanel
            }
            check(localStarts == 2 && phone.state.value.online)
            withContext(Dispatchers.Main) { phone.hideNowPlaying(); session.stop() }
        } finally {
            withContext(Dispatchers.Main) { phone.cancelPlaybackRequest(); session.stop() }
        }
    }

    private suspend fun settingsAndControl(tv: CompanionManager, phone: CompanionManager, peer: CompanionPeer) {
        val preferences = com.ambient.tv.data.local.AppPreferences(isolated("settings_target").getSharedPreferences("unused", 0))
        val tokens = com.ambient.tv.data.local.TokenStore(isolated("settings_tokens").getSharedPreferences("unused", 0), preferences)
        preferences.accentColorKey = "blue"
        preferences.upsertServerProfile(com.ambient.tv.domain.model.ServerProfile("tv-account", "TV account", "nas.test"))
        tokens.setTokenForServer("tv-account", "audit-tv-secret")
        val service = CompanionSettingsService(base, com.ambient.tv.data.local.SettingsBackupManager(preferences, tokens))
        var allowed = true
        tv.attachSettings(service) { allowed }
        phone.showRemote()
        await("paired sender can acquire the exclusive input lease") { phone.state.value.remote?.inputGranted == true }
        withContext(Dispatchers.Main) { tv.releaseRemoteControl() }
        await("local TV action revokes input control") { phone.state.value.remote?.inputGranted == false }
        val source = com.ambient.tv.data.local.SettingsBackup(exportedAtEpochMs = 1, accentColorKey = "red", uiScale = 0.7f,
            servers = listOf(com.ambient.tv.data.local.ServerBackup(com.ambient.tv.domain.model.ServerProfile("phone-account", "Remote account", "nas.test"), "unapproved-secret")))
        val payload = CompanionBackupPolicy.json.encodeToString(com.ambient.tv.data.local.SettingsBackup.serializer(), source)
        phone.sendSettings(payload, "settings", false)
        await("bounded encrypted backup transfer awaits local confirmation") { tv.state.value.backupOffer != null && phone.state.value.backupStatus == "pending" }
        check(preferences.accentColorKey == "blue" && tokens.tokenForServer("tv-account") == "audit-tv-secret")
        phone.send(CompanionFrame(type = "input_claim"))
        await("remote input cannot acquire control over import confirmation") { phone.state.value.remote?.error == "consent_pending" }
        phone.send(CompanionFrame(type = "input", input = CompanionInput("key", key = "ok")))
        delay(300)
        check(preferences.accentColorKey == "blue" && tv.state.value.backupOffer != null)
        log("PASS sender cannot click its own import approval")
        withContext(Dispatchers.Main) { tv.applyBackup(overwrite = false, deviceSettings = false) }
        check(tv.state.value.backupOffer != null && preferences.accentColorKey == "blue")
        allowed = false
        withContext(Dispatchers.Main) { tv.applyBackup(overwrite = true, deviceSettings = false) }
        check(tv.state.value.backupOffer != null && preferences.accentColorKey == "blue")
        log("PASS overwrite consent and playback/dirty-settings guards are required")
        allowed = true
        withContext(Dispatchers.Main) { tv.applyBackup(overwrite = true, deviceSettings = false) }
        await("receiver applies after local consent and acknowledges sender") { phone.state.value.backupStatus == "applied" }
        check(preferences.accentColorKey == "red" && preferences.uiScale != 0.7f)
        check(tokens.tokenForServer("tv-account") == "audit-tv-secret")
        val recovery = java.io.File(base.noBackupFilesDir, "companion-settings-rollback.bin").readBytes()
        check(!recovery.toString(Charsets.ISO_8859_1).contains("audit-tv-secret"))
        log("PASS hardware and missing credentials retained; recovery is encrypted")
        withContext(Dispatchers.Main) { tv.showRestore(); tv.restoreSettings() }
        await("local encrypted recovery restores the previous receiver settings") { tv.state.value.settingsRevision == 2 && !tv.state.value.backupBusy }
        check(preferences.accentColorKey == "blue" && tokens.tokenForServer("tv-account") == "audit-tv-secret")
        phone.sendSettings(payload, "accounts", false)
        await("second import waits without writing") { tv.state.value.backupOffer != null && phone.state.value.backupStatus == "pending" }
        withContext(Dispatchers.Main) { tv.rejectBackup() }
        await("local rejection reaches the sender") { phone.state.value.backupStatus == "rejected" }
        check(preferences.accentColorKey == "blue")
        phone.sendSettings(payload, "accounts", false)
        await("third import awaits consent") { tv.state.value.backupOffer != null }
        phone.disconnect()
        await("disconnect discards unapproved settings") { tv.state.value.backupOffer == null }
        check(preferences.accentColorKey == "blue")
        phone.connect(peer)
        await("connection recovers after cancelled settings transfer") { phone.state.value.online }
    }

    private suspend fun oneWaySettings(tv: CompanionManager, phone: CompanionManager, activity: com.ambient.tv.ui.catalog.UiCatalogActivity?) {
        val prefs = com.ambient.tv.data.local.AppPreferences(isolated("phone_settings").getSharedPreferences("unused", 0))
        val tokens = com.ambient.tv.data.local.TokenStore(isolated("phone_tokens").getSharedPreferences("unused", 0), prefs)
        val service = CompanionSettingsService(base, com.ambient.tv.data.local.SettingsBackupManager(prefs, tokens))
        phone.attachSettings(service) { true }
        val target = tv.state.value.paired.single()
        check(phone.state.value.online)
        check(tv.state.value.syncPeers.isEmpty())
        await("TV capabilities arrive after reconnect") { phone.state.value.remote != null }
        check("backup_request" !in phone.state.value.remote!!.capabilities)
        val source = com.ambient.tv.data.local.SettingsBackup(exportedAtEpochMs = 1,
            accentColorKey = "red", navSoundsEnabled = true, navSoundsVolume = .65f,
            navSoundBank = com.ambient.tv.ui.sound.NavSoundBank().encode(),
            servers = listOf(com.ambient.tv.data.local.ServerBackup(com.ambient.tv.domain.model.ServerProfile("reverse", "TV account", "reverse.test"), "reverse-secret")))
        for (mode in listOf("accounts", "settings", "preferences", "nav_sounds")) {
            check(!tv.sendSettings(CompanionBackupPolicy.prepare(source, false, mode), mode, false, target))
            check(!phone.requestBackupFromTv(mode, false))
            check(phone.state.value.backupOffer == null && tv.state.value.pullRequest == null)
        }
        // An old client bypassing the public API still cannot request an export over TLS.
        check(phone.send(CompanionFrame(type = "backup_request", mode = "accounts", transferId = java.util.UUID.randomUUID().toString())))
        delay(1_200)
        check(phone.state.value.online && phone.state.value.backupOffer == null && tv.state.value.pullRequest == null)
        check(tokens.tokenForServer("reverse").isNullOrEmpty() && prefs.accentColorKey != "red")
        log("PASS all TV-to-phone sync is blocked, including accounts, sources, settings and menu sounds")
        if (activity != null) {
            val tvVm = CompanionViewModel(tv, CompanionMediaMapper(prefs, com.ambient.tv.data.dlna.DlnaStore(isolated("tv_flow_dlna").getSharedPreferences("unused", 0)), com.ambient.tv.data.shared.SharedMediaRepository()), service)
            withContext(Dispatchers.Main) {
                activity.setContent {
                    CompositionLocalProvider(com.ambient.tv.ui.mobile.LocalHandheldUi provides false) {
                        com.ambient.tv.ui.theme.AmbientTvTheme { CompanionDialogs(tvVm) }
                    }
                }
                tv.showPanel()
            }
            delay(800)
            screenshot("companion-tv-devices")
            val windows = instrumentation.uiAutomation.windows.mapNotNull { it.root }
                .filter { it.packageName?.toString() == base.packageName }
            check(windows.isNotEmpty())
            for (res in listOf(com.ambient.tv.R.string.sync_title, com.ambient.tv.R.string.sync_direction_from_tv, com.ambient.tv.R.string.sync_phone_to_tv_only)) {
                check(windows.all { it.findAccessibilityNodeInfosByText(base.getString(res)).isEmpty() })
            }
            withContext(Dispatchers.Main) { tv.hidePanel() }
            log("PASS TV device panel has no sync action or sync explanation")
            val vm = CompanionViewModel(phone, CompanionMediaMapper(prefs, com.ambient.tv.data.dlna.DlnaStore(isolated("flow_dlna").getSharedPreferences("unused", 0)), com.ambient.tv.data.shared.SharedMediaRepository()), service)
            withContext(Dispatchers.Main) {
                activity.setContent {
                    CompositionLocalProvider(com.ambient.tv.ui.mobile.LocalHandheldUi provides true) {
                        com.ambient.tv.ui.theme.AmbientTvTheme { CompanionDialogs(vm) }
                    }
                }
                phone.showPanel()
            }
            delay(800)
            screenshot("companion-hub")
            clickLabel(com.ambient.tv.R.string.companion_remote_control)
            await("remote is one action from connected device hub") { phone.state.value.remotePanel && phone.state.value.remote?.inputGranted == true }
            delay(500)
            screenshot("companion-remote")
            withContext(Dispatchers.Main) { phone.showPanel() }
            clickLabel(com.ambient.tv.R.string.sync_title)
            await("transfer opens from connected device hub") { phone.state.value.settingsPanel }
            delay(800)
            screenshot("companion-transfer")
            clickLabel(com.ambient.tv.R.string.sync_send_action)
            await("default settings transfer works without selecting the connected TV again") { tv.state.value.backupOffer?.mode == "preferences" }
            check(tv.state.value.backupOffer!!.accounts == 0)
            withContext(Dispatchers.Main) { tv.rejectBackup() }
            await("UI transfer rejection completes") { phone.state.value.backupStatus == "rejected" }
            withContext(Dispatchers.Main) { phone.hideSettingsTransfer() }
        }
        phone.foreground(false, false)
        check(phone.state.value.backupOffer == null)
        await("phone disconnects in background") { !phone.state.value.online }
        phone.foreground(true, false)
        check(!phone.state.value.online)
        phone.connect(requireNotNull(phone.state.value.target))
        await("phone reconnects original TV on explicit request") { phone.state.value.online }
        tv.hideSettingsTransfer(); tv.disconnect()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(base.filesDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private suspend fun clickLabel(res: Int) {
        val label = base.getString(res)
        fun find(root: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (root.text?.toString() == label) return root
            for (index in 0 until root.childCount) root.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        var target: android.view.accessibility.AccessibilityNodeInfo? = null
        await("enabled action: $label") {
            target = instrumentation.uiAutomation.windows.mapNotNull { it.root }
                .filter { it.packageName?.toString() == base.packageName }.firstNotNullOfOrNull { find(it) }
            target?.let { node ->
                var button = node
                while (!button.isClickable && button.parent != null) button = button.parent
                target = button
                button.isEnabled && button.isClickable
            } == true
        }
        check(target!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }

    private suspend fun boundWakePackets() = withContext(Dispatchers.IO) {
        val context = isolated("bound_wake")
        val preferences = com.ambient.tv.data.local.AppPreferences(context.getSharedPreferences("unused", 0))
        val controller = com.ambient.tv.vpn.WireGuardRouteController(context, preferences,
            com.ambient.tv.data.local.TokenStore(isolated("wake_tokens").getSharedPreferences("unused", 0), preferences), CompanionConnectionStore(context))
        val manager = com.ambient.tv.wol.WolManager(context, preferences, controller)
        val connectivity = base.getSystemService(android.net.ConnectivityManager::class.java)
        val network = connectivity.allNetworks.first { candidate ->
            connectivity.getNetworkCapabilities(candidate)?.let {
                !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    (it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
            } == true && connectivity.getLinkProperties(candidate)?.linkAddresses?.any { it.address is java.net.Inet4Address && isPrivateV4(it.address.hostAddress.orEmpty()) } == true
        }
        val host = connectivity.getLinkProperties(network)!!.linkAddresses.first { it.address is java.net.Inet4Address && isPrivateV4(it.address.hostAddress.orEmpty()) }.address
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        // Capture on this emulator only: no wake broadcast to another device.
        java.net.DatagramSocket(0, host).use { listener ->
            listener.soTimeout = 2000
            val mac = "02:11:22:33:44:55"
            check(manager.sendWakeBurst(mac, host.hostAddress!!, listener.localPort, packetCount = 1,
                coalesceWindowMs = 0, network = network, routeAlive = alive::get).isSuccess)
            val packet = java.net.DatagramPacket(ByteArray(200), 200)
            listener.receive(packet)
            check(packet.data.copyOf(packet.length).contentEquals(com.ambient.tv.data.files.WakeOnLan.packet(mac)))
            alive.set(false)
            check(manager.sendWakeBurst(mac, host.hostAddress!!, listener.localPort, packetCount = 1,
                coalesceWindowMs = 0, network = network, routeAlive = alive::get).isFailure)
            check(runCatching { listener.receive(packet) }.exceptionOrNull() is java.net.SocketTimeoutException)
        }
        log("PASS WOL uses selected network and sends no packet after route loss")
    }

    private suspend fun invalidVpnCandidate() {
        val context = isolated("vpn_candidate")
        val preferences = com.ambient.tv.data.local.AppPreferences(context.getSharedPreferences("unused", 0))
        preferences.setWireGuardProfile(com.ambient.tv.vpn.WireGuardProfile(address = "10.20.0.2", peerPublicKey = "test",
            endpointHost = "192.168.50.1", allowedIps = listOf("192.168.50.0/24")))
        val store = CompanionConnectionStore(context)
        val controller = com.ambient.tv.vpn.WireGuardRouteController(context, preferences,
            com.ambient.tv.data.local.TokenStore(isolated("vpn_tokens").getSharedPreferences("unused", 0), preferences), store)
        val transport = CompanionConnectionTransport(context, store, controller, com.ambient.tv.data.files.WakeOnLan(directSend = { _, _, _ -> }))
        val pin = "ab".repeat(32)
        val original = CompanionConnectionSettings(wireGuard = true, vpnHost = "192.168.50.2")
        store.put(pin, original)
        val before = controller.status.value
        check(runCatching { transport.save(pin, original.copy(vpnHost = "192.168.40.2")) }.isFailure)
        check(store.get(pin) == original && controller.status.value == before)
        log("PASS out-of-profile TV route rejected before persistence or tunnel mutation")
    }

    private suspend fun tvToTv() {
        val a = CompanionManager(isolated("tv_sender"), "ambient_companion_audit_tv_sender")
        val b = CompanionManager(isolated("tv_receiver"), "ambient_companion_audit_tv_receiver")
        try {
            a.foreground(true, true); b.foreground(true, true)
            b.openPairing()
            await("second TV exposes invitation") { b.state.value.invitation != null }
            val invitation = CompanionInvitation.parse(b.state.value.invitation!!)
            val peer = CompanionPeer(invitation.fingerprint, "Second TV", invitation.host, invitation.port)
            a.showPanel(); a.discover(); a.pairWithCode(peer, b.state.value.pairCode); a.pair(invitation.link()); a.connect(peer)
            delay(400)
            check(a.state.value.devices.isEmpty() && a.state.value.target == null && !a.state.value.busy)
            check(a.state.value.paired.isEmpty() && b.state.value.prompt == null)
            // Bypass the client UI/manager to verify receiver-side role enforcement too.
            withContext(Dispatchers.IO) {
                val identity = CompanionIdentity("ambient_companion_audit_tv_sender")
                val socket = identity.clientContext(invitation.fingerprint).socketFactory
                    .createSocket(invitation.host, invitation.port) as javax.net.ssl.SSLSocket
                socket.use {
                    it.soTimeout = 5_000
                    it.startHandshake()
                    val input = java.io.DataInputStream(it.inputStream)
                    val output = java.io.DataOutputStream(it.outputStream)
                    val challenge = input.readFrame()
                    val bytes = identity.certificate.publicKey.encoded
                    output.writeFrame(CompanionFrame(type = "auth", role = COMPANION_TV, nonce = challenge.nonce,
                        name = "Other TV", invitation = invitation.token,
                        key = java.util.Base64.getEncoder().encodeToString(bytes),
                        signature = java.util.Base64.getEncoder().encodeToString(identity.sign(
                            authTranscript(challenge.nonce, invitation.fingerprint, fingerprint(bytes), COMPANION_TV, challenge.role)))))
                    val reply = input.readFrame()
                    check(reply.type == "error" && reply.error == "device_role")
                }
            }
            check(b.state.value.prompt == null && b.state.value.paired.isEmpty())
            log("PASS TVs cannot discover/connect/pair with TVs; receiver rejects a signed TV role before consent")
        } finally { a.foreground(false, true); b.foreground(false, true); a.enableReceiver(false); b.enableReceiver(false) }
    }

    private suspend fun queuePersistence() = withContext(Dispatchers.IO) {
        val file = java.io.File(base.noBackupFilesDir, "companion_queue_audit.json")
        val atomic = android.util.AtomicFile(file)
        atomic.delete()
        try {
            val media = CompanionMedia("youtube", "abcdefghijk")
            val first = CompanionQueueCheckpoint(revision = 40, entries = listOf(CompanionQueueItem("stable", media, "Queued video")))
            CompanionQueueStore(file).save(first)
            val recreated = CompanionQueueStore(file)
            check(recreated.load() == first)
            log("PASS queue checkpoint survives receiver store recreation")
            recreated.save(first.copy(revision = 39, entries = emptyList()))
            check(CompanionQueueStore(file).load() == first)
            log("PASS an older checkpoint cannot overwrite the current queue")
            // Leave the next AtomicFile write unfinished, as after process termination.
            atomic.startWrite().use { it.write("{unfinished".toByteArray()) }
            check(CompanionQueueStore(file).load() == first)
            log("PASS interrupted queue write retains the last complete checkpoint")
            val restored = CompanionPlaybackQueue().apply { restoreCheckpoint(requireNotNull(recreated.load())) }
            val priorRevision = restored.revision
            restored.remove("stable", priorRevision)
            recreated.save(restored.checkpoint())
            check(CompanionQueueStore(file).load()?.entries?.isEmpty() == true)
            log("PASS removed queue rows stay removed after recreation")
        } finally { atomic.delete() }
    }

    private fun mediaMapping() {
        val a = isolated("mapping_phone").getSharedPreferences("unused", 0)
        val b = isolated("mapping_tv").getSharedPreferences("unused", 0)
        val phonePrefs = com.ambient.tv.data.local.AppPreferences(a)
        val tvPrefs = com.ambient.tv.data.local.AppPreferences(b)
        val p = com.ambient.tv.domain.model.ServerProfile("phone-local-id", "Test", "192.168.1.2", machineIdentifier = "server-a")
        phonePrefs.upsertServerProfile(p)
        tvPrefs.upsertServerProfile(p.copy(id = "tv-local-id"))
        val phoneDlna = com.ambient.tv.data.dlna.DlnaStore(a)
        val tvDlna = com.ambient.tv.data.dlna.DlnaStore(b)
        val phone = CompanionMediaMapper(phonePrefs, phoneDlna, com.ambient.tv.data.shared.SharedMediaRepository())
        val sharedRepository = com.ambient.tv.data.shared.SharedMediaRepository()
        val tv = CompanionMediaMapper(tvPrefs, tvDlna, sharedRepository)
        val media = phone.fromRoute(com.ambient.tv.ui.navigation.Destinations.player("123", restart = true))
        check(tv.toRoute(media) == com.ambient.tv.ui.navigation.Destinations.player("123", restart = true))
        tvPrefs.upsertServerProfile(p.copy(id = "tv-local-id", machineIdentifier = "server-b"))
        check(runCatching { tv.toRoute(media) }.isFailure)
        check(tvPrefs.activeServerProfile()?.machineIdentifier == "server-b")
        log("PASS library mapping uses server identity and refuses a different TV server")
        val source = com.ambient.tv.data.files.FileSource(id = "phone-files", name = "NAS", kind = "smb", host = "192.168.1.3", share = "media")
        phonePrefs.fileSources = listOf(source)
        tvPrefs.fileSources = listOf(source.copy(id = "tv-files"))
        val file = phone.fromRoute(com.ambient.tv.ui.navigation.Destinations.player(com.ambient.tv.data.files.FileKeys.key(source.id, "Movies/Test.mkv")))
        check(tv.toRoute(file) == com.ambient.tv.ui.navigation.Destinations.player(com.ambient.tv.data.files.FileKeys.key("tv-files", "Movies/Test.mkv")))
        check(runCatching { tv.toRoute(file.copy(id = "../private.mkv")) }.isFailure)
        val dlnaServer = com.ambient.tv.data.dlna.DlnaServer("phone-dlna", "uuid:server", "DLNA", "http://192.168.1.3/desc", "http://192.168.1.3/control")
        phoneDlna.save(dlnaServer); tvDlna.save(dlnaServer.copy(id = "tv-dlna"))
        val dlnaMedia = phone.fromRoute(com.ambient.tv.ui.navigation.Destinations.player(com.ambient.tv.data.dlna.DlnaKeys.key("phone-dlna", "object/42")))
        check(tv.toRoute(dlnaMedia) == com.ambient.tv.ui.navigation.Destinations.player(com.ambient.tv.data.dlna.DlnaKeys.key("tv-dlna", "object/42")))
        val youtube = phone.fromRoute(com.ambient.tv.ui.navigation.Destinations.trailerPlayer("dQw4w9WgXcQ"))
        check(tv.toRoute(youtube) == com.ambient.tv.ui.navigation.Destinations.trailerPlayer("dQw4w9WgXcQ"))
        log("PASS file roots, DLNA UDNs and YouTube references map across local device IDs")
        val direct = CompanionMedia("url", "https://media.example.test/music.mp3?signature=private")
        val directRoute = tv.toRoute(direct)
        check(!directRoute.contains("signature") && !directRoute.contains("https"))
        check(tv.fromRoute(directRoute) == direct)
        check(runCatching { tv.toRoute(direct.copy(id = "file:///sdcard/music.mp3")) }.isFailure)
        log("PASS shared audio route uses an opaque local key and validates links on the receiver")
    }
}

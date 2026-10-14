package com.ambient.tv.spotify

import android.content.Context
import android.content.Intent
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.spotify.playback.SpotifyPlaybackEngine
import com.ambient.tv.spotify.playback.SpotifyPlaybackEngineConfig
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.mobile.isTelevision
import com.ambient.tv.util.LocalNetworkAccess
import com.ambient.tv.util.suspendResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spotify application facade.
 *
 * OAuth/catalog/library concerns use [SpotifyAuthManager] and [SpotifyWebApi]. Native in-app audio
 * goes exclusively through [SpotifyPlaybackEngine]. No caller outside the playback adapter needs to
 * know that the current engine is backed by librespot/Rusty.
 */
@Singleton
class SpotifyRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val engine: SpotifyPlaybackEngine,
    private val api: SpotifyWebApi,
    private val auth: SpotifyAuthManager,
    private val playbackAvailability: com.ambient.tv.data.browse.PlaybackAvailability,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val webPlayback = MutableStateFlow(SpotifyWebPlaybackOverlay())
    val feedback = com.ambient.tv.notifications.OperationFeedbackReporter()
    private val _operationFeedback = MutableStateFlow<com.ambient.tv.notifications.OperationFeedback?>(null)
    val operationFeedback = _operationFeedback.asStateFlow()

    private fun clearCommandFeedback() {
        _operationFeedback.value = null
        webPlayback.update { it.copy(error = null) }
    }

    val playback: StateFlow<SpotifyPlaybackState> = combine(engine.state, webPlayback) { native, web ->
        projectSpotifyPlaybackState(native, web)
    }.stateIn(scope, SharingStarted.Eagerly, projectSpotifyPlaybackState(engine.state.value, webPlayback.value))

    val authState: StateFlow<SpotifyAuthState> = auth.state
    val nativeAvailable: Boolean
        get() = engine.available
    val webAuthorized: Boolean
        get() = auth.state.value.authorized

    private val _playbackTakeover = MutableStateFlow<SpotifyPlaybackTakeoverPrompt?>(null)
    val playbackTakeover: StateFlow<SpotifyPlaybackTakeoverPrompt?> = _playbackTakeover.asStateFlow()
    private val playbackStartsInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    val shouldKeepReceiverInBackground: Boolean
        get() = playback.value.playing || playbackStartsInFlight.get() > 0

    init {
        // Keep Web API enrichments scoped to the current native session/track. This preserves the
        // immediate next-item fallback from librespot until the queue endpoint refreshes it.
        scope.launch {
            fun trackKey(state: SpotifyPlaybackState): Pair<String, String>? =
                state.current?.let { it.id to it.name }
            var previousTrackKey = trackKey(engine.state.value)
            engine.state.collect { state ->
                com.ambient.tv.data.browse.BrowseWorkBudget.musicPlaying = state.playing
                if (state.playing) com.ambient.tv.data.browse.BrowseWorkBudget.speculativeJob?.cancel()
                val currentTrackKey = trackKey(state)
                if (!state.connected) {
                    webPlayback.value = SpotifyWebPlaybackOverlay()
                } else if (currentTrackKey != previousTrackKey) {
                    _operationFeedback.value = null
                    webPlayback.update { it.copy(queue = null, positionMs = null, error = null) }
                } else {
                    webPlayback.update { it.copy(positionMs = null, error = null) }
                }
                previousTrackKey = currentTrackKey
            }
        }

        // A lost/revoked Web API authorization must also tear down the native receiver. Otherwise
        // the device can remain advertised in Spotify Connect after logout or refresh failure.
        scope.launch {
            var wasAuthorized = auth.state.value.authorized
            auth.state.collect { state ->
                if (wasAuthorized && !state.authorized) stop()
                wasAuthorized = state.authorized
            }
        }

        // Connect may arrive while Plex/Jellyfin/YouTube/TVHeadend/files are playing. In the
        // foreground we pause both sides and let the user decide. In the background there is no
        // safe visible dialog, so an actual Spotify PLAY command takes over deterministically.
        scope.launch {
            var previousConnected = playback.value.connected
            var previousPlaying = playback.value.playing
            playback.collect { state ->
                val background = BackgroundPlaybackSession.snapshot.value
                val backgroundWantsPlayback = background.active &&
                    (background.playing || BackgroundPlaybackSession.activeHandle()?.wantsToPlay() == true)
                val connectStarted = state.connected && !previousConnected
                val spotifyPlaybackStarted = state.playing && !previousPlaying
                val pending = _playbackTakeover.value

                when {
                    pending != null && !SpotifyPlaybackTakeoverUiPresence.foreground && state.playing -> {
                        _playbackTakeover.value = null
                        val current = BackgroundPlaybackSession.snapshot.value
                        if (ensureSpotifyPlaybackService()) {
                            if (current.active && current.generation == pending.backgroundGeneration) {
                                BackgroundPlaybackSession.stop()
                            }
                        } else {
                            engine.pause()
                            if (pending.resumeCurrentPlayback && current.active && current.generation == pending.backgroundGeneration) {
                                BackgroundPlaybackSession.play()
                            }
                        }
                    }
                    pending != null && !state.connected -> {
                        _playbackTakeover.value = null
                        val current = BackgroundPlaybackSession.snapshot.value
                        if (pending.resumeCurrentPlayback && current.active && current.generation == pending.backgroundGeneration) {
                            BackgroundPlaybackSession.play()
                        }
                    }
                    pending != null -> {
                        if (state.playing) engine.pause()
                    }
                    shouldPromptSpotifyTakeover(
                        background.active,
                        backgroundWantsPlayback,
                        SpotifyPlaybackTakeoverUiPresence.foreground,
                        connectStarted,
                        spotifyPlaybackStarted,
                    ) -> {
                        _playbackTakeover.value = SpotifyPlaybackTakeoverPrompt(
                            backgroundGeneration = background.generation,
                            title = background.metadata?.title.orEmpty(),
                            subtitle = background.metadata?.subtitle.orEmpty(),
                            resumeCurrentPlayback = backgroundWantsPlayback,
                        )
                        BackgroundPlaybackSession.pause()
                        engine.pause()
                    }
                    shouldAutoTakeOverInBackground(
                        background.active,
                        backgroundWantsPlayback,
                        SpotifyPlaybackTakeoverUiPresence.foreground,
                        spotifyPlaybackStarted,
                    ) -> {
                        if (ensureSpotifyPlaybackService()) {
                            BackgroundPlaybackSession.stop()
                        } else {
                            engine.pause()
                            BackgroundPlaybackSession.play()
                        }
                    }
                    background.active && !backgroundWantsPlayback && spotifyPlaybackStarted -> {
                        if (ensureSpotifyPlaybackService()) BackgroundPlaybackSession.stop() else engine.pause()
                    }
                    state.playing -> ensureSpotifyPlaybackService()
                }

                previousConnected = state.connected
                previousPlaying = state.playing
            }
        }
    }

    private fun ensureSpotifyPlaybackService(): Boolean {
        if (SpotifyPlaybackService.ensureRunning(context)) return true
        engine.pause()
        reportPlaybackError(R.string.spotify_background_start_denied)
        return false
    }

    private fun reportPlaybackError(message: Int) {
        // Do not expose provider exceptions, URLs, or credentials in UI feedback.
        webPlayback.update { it.copy(error = context.getString(message)) }
        val ticket = feedback.begin("spotify:command")
        _operationFeedback.value = feedback.complete(ticket, com.ambient.tv.notifications.OperationFeedback(
            ticket.key, "spotify:player", message, system = "spotify",
            kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK,
        ))
    }

    private data class ExplicitPlaybackHandoff(val generation: Long, val resumeOnFailure: Boolean)

    /**
     * Pause, but do not destroy, the current non-Spotify session while Spotify is validated.
     * The previous player is released only after Spotify accepts the start request.
     */
    private fun beginExplicitSpotifyPlayback(): ExplicitPlaybackHandoff? {
        _playbackTakeover.value = null
        val snapshot = BackgroundPlaybackSession.snapshot.value
        if (!snapshot.active) return null
        val wantsPlayback = snapshot.playing || BackgroundPlaybackSession.activeHandle()?.wantsToPlay() == true
        BackgroundPlaybackSession.pause()
        return ExplicitPlaybackHandoff(snapshot.generation, wantsPlayback)
    }

    private fun commitExplicitSpotifyPlayback(handoff: ExplicitPlaybackHandoff?) {
        if (handoff == null) return
        val current = BackgroundPlaybackSession.snapshot.value
        if (current.active && current.generation == handoff.generation) BackgroundPlaybackSession.stop()
    }

    private fun rollbackExplicitSpotifyPlayback(handoff: ExplicitPlaybackHandoff?) {
        if (handoff == null || !handoff.resumeOnFailure) return
        val current = BackgroundPlaybackSession.snapshot.value
        if (current.active && current.generation == handoff.generation) BackgroundPlaybackSession.play()
    }

    private suspend fun awaitSpotifyPlaybackStarted(timeoutMs: Long = PLAYBACK_START_TIMEOUT_MS): Boolean {
        if (playback.value.playing) return true
        return withTimeoutOrNull(timeoutMs) { playback.first { it.playing }; true } ?: false
    }

    private suspend fun awaitSpotifyReceiverConnected(timeoutMs: Long = RECEIVER_START_TIMEOUT_MS): Boolean {
        if (playback.value.connected) return true
        return withTimeoutOrNull(timeoutMs) { playback.first { it.connected }; true } ?: false
    }

    private fun failExplicitSpotifyPlayback(handoff: ExplicitPlaybackHandoff?, message: Int) {
        rollbackExplicitSpotifyPlayback(handoff)
        if (!playback.value.playing) context.stopService(Intent(context, SpotifyPlaybackService::class.java))
        reportPlaybackError(message)
    }

    private fun beginPlaybackStartAttempt() { playbackStartsInFlight.incrementAndGet() }
    private fun endPlaybackStartAttempt() {
        while (true) {
            val current = playbackStartsInFlight.get()
            if (current <= 0) return
            if (playbackStartsInFlight.compareAndSet(current, current - 1)) return
        }
    }

    fun continueCurrentPlaybackAfterSpotifyConnect() {
        val pending = _playbackTakeover.value ?: return
        _playbackTakeover.value = null
        engine.pause()
        val current = BackgroundPlaybackSession.snapshot.value
        if (pending.resumeCurrentPlayback && current.active && current.generation == pending.backgroundGeneration) {
            BackgroundPlaybackSession.play()
        }
    }

    fun acceptSpotifyPlaybackTakeover(): Boolean {
        val pending = _playbackTakeover.value ?: return false
        _playbackTakeover.value = null
        val handoff = ExplicitPlaybackHandoff(pending.backgroundGeneration, pending.resumeCurrentPlayback)
        beginPlaybackStartAttempt()
        if (!ensureSpotifyPlaybackService()) {
            rollbackExplicitSpotifyPlayback(handoff)
            endPlaybackStartAttempt()
            return false
        }
        if (!engine.play()) {
            failExplicitSpotifyPlayback(handoff, R.string.spotify_play_failed)
            endPlaybackStartAttempt()
            return false
        }
        scope.launch {
            try {
                if (awaitSpotifyPlaybackStarted()) commitExplicitSpotifyPlayback(handoff)
                else failExplicitSpotifyPlayback(handoff, R.string.spotify_play_failed)
            } finally {
                endPlaybackStartAttempt()
            }
        }
        return true
    }

    fun start(): Boolean {
        if (!prefs.spotifyEnabled || !webAuthorized || !LocalNetworkAccess.isGranted(context) || !engine.available) return false
        deviceName()
        return engine.start(
            SpotifyPlaybackEngineConfig(
                deviceName = prefs.spotifyDeviceName,
                deviceId = prefs.spotifyDeviceId,
                bitrateKbps = prefs.spotifyBitrateKbps,
            ),
        )
    }

    /** Persists a household-unique default once, so the Connect name stays stable afterwards. */
    fun deviceName(): String {
        if (prefs.spotifyDeviceNameStored) return prefs.spotifyDeviceName
        val label = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull() ?: android.os.Build.MODEL
        prefs.spotifyDeviceName = defaultSpotifyDeviceName(com.ambient.tv.branding.AppBrand.name, label)
        return prefs.spotifyDeviceName
    }

    /**
     * Keep Connect always ready on television, but only keep a phone receiver alive while it is
     * actively being used. Settings/import code calls this instead of blindly starting librespot.
     */
    fun reconcileReceiverForPlatform(): Boolean {
        val ready = prefs.spotifyEnabled && webAuthorized && engine.available && LocalNetworkAccess.isGranted(context)
        if (!ready) {
            stop()
            return false
        }
        if (isTelevision(context)) {
            val started = start()
            if (started && SpotifyPlaybackTakeoverUiPresence.foreground) ensureSpotifyPlaybackService()
            return started
        }
        if (!playback.value.playing) stop()
        return playback.value.playing
    }

    fun stop() {
        engine.stop()
        context.stopService(Intent(context, SpotifyPlaybackService::class.java))
    }

    suspend fun addToQueue(uri: String) {
        spotifyWrite { token -> api.addToQueue(token, requirePlaybackDevice(token), uri) }
        refreshQueue()
    }

    fun refreshQueue() {
        scope.launch {
            suspendResult { spotifyRead { token -> api.queue(token) } }
                .onSuccess { queue -> webPlayback.update { it.copy(queue = queue) } }
        }
    }

    fun play() {
        clearCommandFeedback()
        val handoff = beginExplicitSpotifyPlayback()
        beginPlaybackStartAttempt()
        scope.launch {
            try {
                if (!start() || !awaitSpotifyReceiverConnected()) {
                    failExplicitSpotifyPlayback(handoff, R.string.spotify_native_start_failed)
                    return@launch
                }
                if (!ensureSpotifyPlaybackService()) {
                    rollbackExplicitSpotifyPlayback(handoff)
                    return@launch
                }
                if (!engine.play()) {
                    failExplicitSpotifyPlayback(handoff, R.string.spotify_play_failed)
                    return@launch
                }
                if (awaitSpotifyPlaybackStarted()) commitExplicitSpotifyPlayback(handoff)
                else failExplicitSpotifyPlayback(handoff, R.string.spotify_play_failed)
            } finally {
                endPlaybackStartAttempt()
            }
        }
    }

    private fun nativeCommand(command: () -> Boolean) {
        clearCommandFeedback()
        if (!command()) reportPlaybackError(R.string.feedback_playback_command_failed)
    }

    fun pause() = nativeCommand(engine::pause)
    fun next() = nativeCommand(engine::next)
    fun previous() = nativeCommand(engine::previous)

    fun playUri(uri: String, offsetUri: String? = null, onResult: (Boolean) -> Unit = {}) = scope.launch {
        if (!playbackAvailability.network()) { onResult(false); return@launch }
        clearCommandFeedback()
        val handoff = beginExplicitSpotifyPlayback()
        beginPlaybackStartAttempt()
        try {
            suspendResult {
                if (!start() || !awaitSpotifyReceiverConnected()) error(context.getString(R.string.spotify_native_start_failed))
                spotifyWrite { token ->
                    val deviceId = requirePlaybackDevice(token)
                    if (!ensureSpotifyPlaybackService()) error(context.getString(R.string.spotify_background_start_denied))
                    api.startPlayback(token, deviceId, uri, offsetUri)
                }
                if (!awaitSpotifyPlaybackStarted()) error(context.getString(R.string.spotify_play_failed))
            }.onSuccess {
                commitExplicitSpotifyPlayback(handoff)
                onResult(true)
            }.onFailure { error ->
                failExplicitSpotifyPlayback(handoff, R.string.spotify_play_failed)
                onResult(false)
            }
        } finally {
            endPlaybackStartAttempt()
        }
    }

    fun playUris(uris: List<String>) = scope.launch {
        if (!playbackAvailability.network()) return@launch
        clearCommandFeedback()
        val handoff = beginExplicitSpotifyPlayback()
        beginPlaybackStartAttempt()
        try {
            suspendResult {
                if (!start() || !awaitSpotifyReceiverConnected()) error(context.getString(R.string.spotify_native_start_failed))
                spotifyWrite { token ->
                    val deviceId = requirePlaybackDevice(token)
                    if (!ensureSpotifyPlaybackService()) error(context.getString(R.string.spotify_background_start_denied))
                    api.startPlayback(token, deviceId, uris)
                }
                if (!awaitSpotifyPlaybackStarted()) error(context.getString(R.string.spotify_play_failed))
            }.onSuccess {
                commitExplicitSpotifyPlayback(handoff)
            }.onFailure { error ->
                failExplicitSpotifyPlayback(handoff, R.string.spotify_play_failed)
            }
        } finally {
            endPlaybackStartAttempt()
        }
    }

    private fun webCommand(key: String, message: Int, action: suspend () -> Unit) = scope.launch {
        clearCommandFeedback()
        val ticket = feedback.begin("spotify:$key")
        val result = suspendResult { action() }
        if (result.isSuccess) {
            feedback.complete(ticket, com.ambient.tv.notifications.OperationFeedback(ticket.key, "spotify:player",
                message, phase = com.ambient.tv.notifications.FeedbackPhase.SUCCESS,
                delivery = com.ambient.tv.notifications.FeedbackDelivery.INLINE))
        } else {
            val error = com.ambient.tv.notifications.OperationFeedback(ticket.key, "spotify:player", message,
                detail = result.exceptionOrNull()?.let { com.ambient.tv.notifications.feedbackFailureDetail(it) },
                system = "spotify", kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK)
            feedback.complete(ticket, error)?.let { _operationFeedback.value = it }
        }
    }

    fun seek(ms: Long) = webCommand("seek", R.string.spotify_seek_failed) {
        spotifyWrite { token -> api.seek(token, ms, requirePlaybackDevice(token)) }
        webPlayback.update { it.copy(positionMs = ms.coerceAtLeast(0)) }
    }

    fun setVolume(value: Int) = webCommand("volume", R.string.spotify_volume_failed) {
        val normalized = value.coerceIn(0, 100)
        spotifyWrite { token -> api.volume(token, normalized, requirePlaybackDevice(token)) }
        webPlayback.update { it.copy(volume = normalized) }
    }

    fun setShuffle(enabled: Boolean) = webCommand("shuffle", R.string.spotify_shuffle_failed) {
        spotifyWrite { token -> api.shuffle(token, enabled, requirePlaybackDevice(token)) }
        webPlayback.update { it.copy(shuffle = enabled) }
    }

    fun setRepeatMode(mode: Int) = webCommand("repeat", R.string.spotify_repeat_failed) {
        val normalized = mode.coerceIn(0, 2)
        spotifyWrite { token -> api.repeat(token, normalized, requirePlaybackDevice(token)) }
        webPlayback.update { it.copy(repeatMode = normalized) }
    }

    fun setAudioReactiveVisualsEnabled(enabled: Boolean): Boolean = engine.setPcmTapEnabled(enabled)

    private suspend fun <T> spotifyWrite(block: suspend (String) -> T): T {
        var token = auth.validAccessToken()
        return try {
            block(token)
        } catch (error: SpotifyApiException) {
            if (error.statusCode != 401) throw error
            token = auth.forceRefreshAccessToken()
            block(token)
        }
    }

    private suspend fun <T> spotifyRead(block: suspend (String) -> T): T {
        var token = auth.validAccessToken()
        var refreshed = false
        var rateRetried = false
        while (true) {
            try {
                return block(token)
            } catch (error: SpotifyApiException) {
                when {
                    error.statusCode == 401 && !refreshed -> {
                        token = auth.forceRefreshAccessToken()
                        refreshed = true
                    }
                    error.statusCode == 429 && !rateRetried -> {
                        val waitMs = ((error.retryAfterSeconds ?: 1L).coerceIn(1L, 10L) * 1000L)
                        delay(waitMs)
                        rateRetried = true
                    }
                    else -> throw error
                }
            }
        }
    }

    fun refreshPlaybackState() {
        scope.launch {
            suspendResult { spotifyRead { token -> api.currentPlayback(token) } }
                .onSuccess { remote ->
                    if (remote != null) {
                        webPlayback.update { current ->
                            current.copy(
                                positionMs = remote.positionMs ?: current.positionMs,
                                volume = remote.volume ?: current.volume,
                                supportsVolume = remote.supportsVolume ?: current.supportsVolume,
                                shuffle = remote.shuffle ?: current.shuffle,
                                repeatMode = remote.repeatMode ?: current.repeatMode,
                                error = null,
                            )
                        }
                    }
                }
        }
    }

    private suspend fun requirePlaybackDevice(token: String): String {
        repeat(8) { attempt ->
            api.resolvePlaybackDeviceId(token, prefs.spotifyDeviceId, prefs.spotifyDeviceName)?.let { return it }
            if (attempt < 7) delay(250L)
        }
        error(context.getString(R.string.spotify_device_not_visible, prefs.spotifyDeviceName))
    }

    suspend fun home(): List<SpotifyBrowseItem> = spotifyRead(api::home)
    suspend fun search(query: String): List<SpotifyBrowseItem> = spotifyRead { token -> api.search(token, query) }
    suspend fun librarySection(section: String): List<SpotifyBrowseItem> = spotifyRead { token -> api.librarySection(token, section) }
    suspend fun album(id: String): SpotifyAlbum = spotifyRead { token -> api.album(token, id) }
    suspend fun playlist(id: String): SpotifyPlaylist = spotifyRead { token -> api.playlist(token, id) }
    suspend fun artist(id: String): SpotifyArtistDetail = spotifyRead { token -> api.artist(token, id) }
    suspend fun profile(): SpotifyAccount = auth.refreshProfile()

    fun login(context: Context = this.context): Result<Unit> = auth.launchAuthorization(context)
    fun reloadAuthorization() = auth.reloadState()
    fun logoutWebApi() {
        com.ambient.tv.notifications.notifyLogin("spotify", com.ambient.tv.notifications.NoticePhase.INFO)
        auth.logout()
        stop()
    }

    private companion object {
        const val PLAYBACK_START_TIMEOUT_MS = 8_000L
        const val RECEIVER_START_TIMEOUT_MS = 10_000L
    }
}

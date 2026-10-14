package com.ambient.tv.ui.sound

import kotlinx.coroutines.launch
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.spotify.playback.SpotifyPlaybackEngine
import com.ambient.tv.ui.performance.PerformanceProfileResolver
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.ThemeMusicPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class UiSound {
    FOCUS,
    SELECT,
    BACK,
    TOGGLE_ON,
    TOGGLE_OFF,
    VALUE,
    SUCCESS,
    ERROR,
}

/**
 * Cached, synthesized navigation sounds via [SoundPool]. Opt-in, soft volume, no audio focus.
 * TV menu feedback follows the performance profile; explicit previews bypass that gate.
 * Handsets use media volume. Real playback / Spotify / audible theme music suppress sounds.
 */
@Singleton
class UiSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val performance: PerformanceProfileResolver,
    private val themeMusic: ThemeMusicPlayer,
    private val spotify: SpotifyPlaybackEngine,
) : UiSoundFeedback {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate +
        kotlinx.coroutines.CoroutineExceptionHandler { _, error -> android.util.Log.w("UiSoundPlayer", "Sound generation failed", error) })
    private val television = com.ambient.tv.ui.mobile.isTelevision(context)
    private val handheldMenu = HandheldNavSoundOutput(scope)
    private val handheldPreview = HandheldNavSoundOutput(scope)
    private val eventGate = UiSoundEventGate()
    private val actions = UiSoundActions { kind ->
        scope.launch { if (eventGate.accept(kind, SystemClock.elapsedRealtime())) playNow(kind) }
    }
    private var bankKey: String? = null
    private var bank = NavSoundBank()
    private var pool: SoundPool? = null
    private val ids = mutableMapOf<UiSound, Int>()
    private val earlyLoads = mutableMapOf<Int, Int>()
    private var loadGate = UiSoundLoadGate()
    private var previewGeneration = 0L
    private var previewPool: SoundPool? = null
    private var silenceWatch: kotlinx.coroutines.Job? = null
    private var lastSoundUntil = 0L

    private fun watchSilence() {
        lastSoundUntil = SystemClock.elapsedRealtime() + 8000
        if (silenceWatch?.isActive == true) return
        silenceWatch = scope.launch {
            while (SystemClock.elapsedRealtime() < lastSoundUntil) {
                if (!shouldPlay()) { pool?.autoPause(); handheldMenu.stop() }
                if (!shouldPlay(preview = true)) stopPreview()
                kotlinx.coroutines.delay(50)
            }
        }
    }


    override fun navigationInput(keyCode: Int, eventTime: Long) {
        eventGate.input(keyCode, eventTime, SystemClock.elapsedRealtime())
    }
    override fun play(kind: UiSound) = actions.request(kind)
    override fun perform(kind: UiSound, action: () -> Unit) = actions.perform(kind, action)
    override fun prepare() { scope.launch { if (television && shouldPlay()) ensureLoaded() } }

    private fun newPool(streams: Int) = SoundPool.Builder().setMaxStreams(streams)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(navSoundAudioUsage(television))
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()

    private fun playNow(kind: UiSound) {
        if (!shouldPlay()) return
        if (!television) {
            val design = NavSoundBank.decode(preferences.navSoundBank).design(kind)
            if (design.enabled) handheldMenu.play(kind, design, preferences.navSoundsVolume, ::shouldPlayMenu) {
                if (it) watchSilence()
            }
            return
        }
        ensureLoaded()
        if (!bank.design(kind).enabled) return
        loadGate.request(kind, SystemClock.elapsedRealtime())?.let(::playReady)
    }

    private fun playReady(kind: UiSound) {
        if (!shouldPlay()) return
        val id = ids[kind] ?: return
        val volume = preferences.navSoundsVolume
        pool?.play(id, volume, volume, 1, 0, 1f)
        watchSilence()
    }

    private fun shouldPlayMenu() = shouldPlay()

    private fun shouldPlay(preview: Boolean = false): Boolean = menuSoundAllowed(
        enabled = preview || preferences.navSoundsEnabled,
        profileAllows = navSoundProfileAllowed(television, preview, performance.current().allowUiSounds),
        playbackActive = BackgroundPlaybackSession.snapshot.value.active,
        spotifyPlaying = spotify.state.value.playing,
        themeMusicAudible = themeMusic.isAudible,
    )

    private fun ensureLoaded() {
        val key = preferences.navSoundBank
        if (pool != null && key == bankKey) return
        pool?.release()
        ids.clear(); earlyLoads.clear(); loadGate = UiSoundLoadGate()
        bankKey = key
        bank = NavSoundBank.decode(key)
        val currentBank = bank
        val currentPool = newPool(3)
        pool = currentPool
        currentPool.setOnLoadCompleteListener { _, id, status ->
            if (pool !== currentPool) return@setOnLoadCompleteListener
            // load() now runs off the main thread, so completion can arrive before ids is filled.
            val kind = ids.entries.firstOrNull { it.value == id }?.key
                ?: run { earlyLoads[id] = status; return@setOnLoadCompleteListener }
            loadGate.loaded(kind, status == 0, SystemClock.elapsedRealtime())?.let(::playReady)
        }
        scope.launch {
            for (kind in UiSound.entries) {
                if (!currentBank.design(kind).enabled) continue
                val file = renderFile(currentBank.design(kind), kind)
                try {
                    if (pool !== currentPool) return@launch
                    // SoundPool.load reads and decodes the file; keep it off the main thread so the
                    // first focus move does not stall on it.
                    val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { currentPool.load(file.absolutePath, 1) }
                    if (pool !== currentPool) return@launch
                    ids[kind] = id
                    earlyLoads.remove(id)?.let { status ->
                        loadGate.loaded(kind, status == 0, SystemClock.elapsedRealtime())?.let(::playReady)
                    }
                } finally { file.delete() }
            }
        }
    }

    private suspend fun renderFile(design: NavSoundDesign, kind: UiSound): java.io.File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            java.io.File.createTempFile("nav-sound-", ".wav", context.cacheDir).apply {
                try { writeBytes(NavSoundRenderer.wav(design, kind)) }
                catch (error: Throwable) { delete(); throw error }
            }
        }

    override fun preview(kind: UiSound, design: NavSoundDesign, volume: Float, onResult: (Boolean) -> Unit): Boolean {
        stopPreview()
        if (!shouldPlay(preview = true) || !design.enabled || design.volume == 0 || !volume.isFinite() || volume <= 0f) return false
        if (!television) {
            handheldPreview.play(kind, design, volume, { shouldPlay(preview = true) }) {
                if (it) watchSilence()
                onResult(it)
            }
            return true
        }
        val generation = previewGeneration
        scope.launch {
            var file: java.io.File? = null
            try {
                file = renderFile(design, kind)
                if (generation != previewGeneration || !shouldPlay(preview = true)) return@launch
                val currentPool = newPool(1)
                previewPool = currentPool
                currentPool.setOnLoadCompleteListener { _, id, status ->
                    if (status == 0 && generation == previewGeneration && shouldPlay(preview = true)) {
                        val gain = volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .3f
                        val started = currentPool.play(id, gain, gain, 1, 0, 1f) != 0
                        if (started) watchSilence()
                        onResult(started)
                    } else if (generation == previewGeneration) {
                        onResult(false)
                    }
                }
                if (currentPool.load(file.absolutePath, 1) == 0) onResult(false)
            } catch (error: Exception) {
                if (generation == previewGeneration) onResult(false)
                android.util.Log.w("UiSoundPlayer", "Preview audio failed", error)
            } finally { file?.delete() }
        }
        return true
    }

    override fun stopPreview() {
        handheldPreview.stop()
        previewGeneration++
        previewPool?.release()
        previewPool = null
    }

}

val LocalUiSoundPlayer = staticCompositionLocalOf<UiSoundFeedback?> { null }

/** Play [UiSound.FOCUS] when this node gains focus (D-Pad moves). */
fun Modifier.uiSoundOnFocus(player: UiSoundFeedback?): Modifier {
    if (player == null) return this
    return this.onFocusChanged { state ->
        if (state.isFocused) player.playFocus()
    }
}

/**
 * Whether a menu click may be heard right now. The setting is the first word: a disabled switch
 * means silence, whatever else is going on.
 */
internal fun menuSoundAllowed(
    enabled: Boolean,
    profileAllows: Boolean,
    playbackActive: Boolean,
    spotifyPlaying: Boolean,
    themeMusicAudible: Boolean,
): Boolean = enabled && profileAllows && !playbackActive && !spotifyPlaying && !themeMusicAudible

/**
 * Silences the framework's own navigation clicks for this Compose window.
 *
 * Android plays a click for every unconsumed D-pad key through the owning view, which neither the
 * "menu sounds" switch nor its volume could reach: with the switch off the clicks kept coming, and
 * with it on they sounded a second time at the system's volume next to this app's own click.
 * [UiSoundPlayer] is the single source of menu sounds, so every Compose window silences them —
 * each dialog owns its own view, so it needs this as much as the activity does.
 */
@Composable
fun SilenceSystemMenuSounds() {
    val view = LocalView.current
    val player = LocalUiSoundPlayer.current
    DisposableEffect(view, player) {
        player?.prepare()
        val previous = view.isSoundEffectsEnabled
        view.isSoundEffectsEnabled = false
        onDispose { view.isSoundEffectsEnabled = previous }
    }
}

internal fun navSoundAudioUsage(television: Boolean): Int =
    if (television) AudioAttributes.USAGE_ASSISTANCE_SONIFICATION else AudioAttributes.USAGE_MEDIA

internal fun navSoundProfileAllowed(television: Boolean, preview: Boolean, profileAllows: Boolean): Boolean =
    preview || !television || profileAllows

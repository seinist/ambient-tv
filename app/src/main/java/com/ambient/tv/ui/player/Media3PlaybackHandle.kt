@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.media3.common.Player
import androidx.media3.common.C
import android.view.SurfaceView
import com.ambient.tv.ui.display.SdrToHdrRequest
import com.ambient.tv.ui.display.SdrToHdrStatus
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class Media3PlaybackHandle(val player: ExoPlayer) : PlaybackHandle, VideoPresentationTarget {
    /**
     * Media3 only reads side-loaded subtitles when the item is prepared, so a subtitle found during
     * playback re-prepares the same item at the current position with it marked as default.
     */
    fun addExternalSubtitle(track: com.ambient.tv.domain.model.MediaTrack) {
        if (released) return
        val current = player.currentMediaItem ?: return
        val config = PlaybackEngineFactory.externalSubtitleConfiguration(track.copy(selected = true)) ?: return
        val existing = current.localConfiguration?.subtitleConfigurations.orEmpty()
            .filterNot { it.uri == config.uri }
            .map { it.buildUpon().setSelectionFlags(it.selectionFlags and C.SELECTION_FLAG_DEFAULT.inv()).build() }
        val position = player.currentPosition
        val playing = player.playWhenReady
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(track.languageCode ?: track.language).build()
        player.setMediaItem(current.buildUpon().setSubtitleConfigurations(existing + config).build(), position)
        player.playWhenReady = playing
        player.prepare()
    }

    private var surfaceView: SurfaceView? = null
    private var released = false
    private var outputError: String? = null
    private val hdrOutput = SdrHdrSurfaceOutput(
        disconnect = { if (!released) player.clearVideoSurface() },
        routeInput = { if (!released) player.setVideoSurface(it) },
        restore = { if (!released) player.setVideoSurfaceView(it) },
        unsafeRestore = {
            if (!released) {
                outputError = surfaceView?.context?.getString(com.ambient.tv.R.string.sdr_to_hdr_restore_failed)
                player.pause()
                player.clearVideoSurface()
            }
        },
    )
    private val conversionListener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            hdrOutput.discontinuity()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { hdrOutput.discontinuity() }
    }
    init { handles[player] = WeakReference(this); player.addListener(conversionListener) }
    internal fun attachVideoView(view: SurfaceView) {
        surfaceView = view
        hdrOutput.attach(view)
    }
    internal fun detachVideoView(view: SurfaceView?) {
        if (surfaceView !== view) return
        surfaceView = null
        hdrOutput.detach()
    }
    override fun updateSdrToHdr(request: SdrToHdrRequest?): SdrToHdrStatus {
        if (request == null || released) return hdrOutput.update(request, false, 0, 0, 0f)
        val format = player.videoFormat
        val state = player.playbackState
        // Start only while frames flow; a confirmed session survives pause, seek and rebuffering
        // so the TV does not resync HDMI to SDR and back around each of them.
        val running = (state == Player.STATE_READY && player.isPlaying) ||
            (hdrOutput.active && (state == Player.STATE_READY || state == Player.STATE_BUFFERING))
        val eligible = !released && outputError == null && running &&
            player.playerError == null && media3ImageEffectsAllowed(format) &&
            format?.colorInfo?.colorSpace == C.COLOR_SPACE_BT709 && format.rotationDegrees == 0 &&
            !Media3ImageProcessing.gpuInitialized(player) &&
            surfaceView?.context?.let { !reducedVideoEffects(it) } == true
        return hdrOutput.update(request, eligible, format?.width ?: 0, format?.height ?: 0, format?.frameRate ?: 0f)
    }
    override fun uiHdrStandard(): HdrStandard? = when {
        released || player.playerError != null || outputError != null -> null
        hdrOutput.active && player.playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING) -> HdrStandard.HDR10
        player.playbackState == Player.STATE_READY -> HdrSupport.sourceStandard(player.videoFormat)
        else -> null
    }
    override fun sdrToHdrPath(): com.ambient.tv.ui.display.SdrToHdrPath {
        val format = player.videoFormat
        val reduced = surfaceView?.context?.let { reducedVideoEffects(it) } == true
        val sdr = format != null && media3ImageEffectsAllowed(format) &&
            format.colorInfo?.colorSpace == C.COLOR_SPACE_BT709 && format.rotationDegrees == 0 &&
            !Media3ImageProcessing.gpuInitialized(player)
        return com.ambient.tv.ui.display.SdrToHdrPath(
            kind = com.ambient.tv.ui.display.SdrToHdrPlayerKind.MEDIA3,
            width = format?.width ?: 0,
            height = format?.height ?: 0,
            fps = format?.frameRate ?: 0f,
            blocked = released || outputError != null || player.playerError != null ||
                !hdrOutput.canOfferConversion || reduced || !sdr || surfaceView?.holder?.surfaceFrame?.let {
                !sdrHdrSizeAllowed(it.width(), it.height(), format.frameRate)
            } != false,
        )
    }
    private val mutableVideoPresentation = MutableStateFlow(VideoPresentation())
    val videoPresentation = mutableVideoPresentation.asStateFlow()

    fun setVideoPresentation(resizeMode: Int, aspectRatio: Float?, zoom: Float) {
        BackgroundPlaybackSession.setVideoPresentation(VideoPresentation(resizeMode, aspectRatio, zoom))
    }
    override fun applyVideoPresentation(presentation: VideoPresentation) {
        mutableVideoPresentation.value = presentation
    }
    override fun status() = PlaybackStatus(
        positionMs = player.currentPosition.coerceAtLeast(0),
        durationMs = player.duration.coerceAtLeast(0),
        playing = player.isPlaying,
        ended = player.playbackState == Player.STATE_ENDED,
        error = outputError ?: player.playerError?.localizedMessage,
    )
    override fun wantsToPlay(): Boolean = player.playWhenReady && player.playbackState != Player.STATE_ENDED
    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }
    override fun pause() = player.pause()
    private var outputMuteVolume: Float? = null
    override fun setOutputMuted(muted: Boolean) {
        if (muted) {
            if (outputMuteVolume == null) outputMuteVolume = player.volume
            player.volume = 0f
        } else {
            outputMuteVolume?.let { player.volume = it }
            outputMuteVolume = null
        }
    }
    override fun seekTo(positionMs: Long) {
        if (!Media3ResumePrimer.retarget(player, positionMs)) player.seekTo(positionMs)
    }
    override fun restoreAfterRendererSwitch(positionMs: Long, playWhenReady: Boolean, isLive: Boolean) {
        if (!isLive) seekTo(positionMs.coerceAtLeast(0L))
        player.playWhenReady = playWhenReady
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }
    override fun detachVideoOutput() {
        detachVideoView(surfaceView)
        player.clearVideoSurface()
    }
    override fun release() {
        com.ambient.tv.diagnostics.CrashBreadcrumbs.add("media3", "release")
        detachVideoOutput()
        released = true
        Media3ResumePrimer.release(player)
        player.removeListener(conversionListener)
        hdrOutput.release()
        handles.remove(player)
        Media3ImageProcessing.release(player)
        Media3AudioProcessing.release(player)
        // Live TVHeadend (and similar unbounded HTTP streams) keep the server subscription
        // open until the data source is cancelled. clearMediaItems() drops the media source
        // before release() so a renderer switch does not briefly hold two subscriptions.
        runCatching {
            player.playWhenReady = false
            player.clearMediaItems()
        }
        player.release()
    }
    companion object {
        private val handles = WeakHashMap<ExoPlayer, WeakReference<Media3PlaybackHandle>>()
        internal fun forPlayer(player: ExoPlayer): Media3PlaybackHandle? = handles[player]?.get()
    }
}

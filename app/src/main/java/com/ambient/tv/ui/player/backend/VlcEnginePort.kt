package com.ambient.tv.ui.player.backend

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.view.SurfaceView

/** Host ABI: no VLC Java types or engine-owned objects cross this boundary. */
internal interface VlcEnginePort {
    fun createLibrary(context: Context, options: ArrayList<String>): VlcLibraryPort
    fun createTracks(player: VlcPlayerPort): VlcTracksPort
}

internal interface VlcLibraryPort {
    fun createPlayer(): VlcPlayerPort
    fun createMedia(uri: Uri): VlcMediaPort
    fun release()
}

internal interface VlcMediaPort {
    fun addOption(option: String)
    fun addAudioSlave(uri: String)
    fun setHWDecoderEnabled(enabled: Boolean, force: Boolean)
    fun release()
}

internal enum class VlcEventKind {
    Buffering, Opening, Playing, Paused, Stopped, EndReached, EncounteredError,
    ESAdded, TimeChanged, PositionChanged, Other
}
internal data class VlcEvent(val type: VlcEventKind, val buffering: Float = 100f)
internal data class VlcChapter(val timeOffset: Long, val name: String?, val duration: Long)
internal data class VlcEqualizer(val preamp: Float, val bands: List<Float>)

internal interface VlcOutputPort {
    interface Callback {
        fun onSurfacesCreated()
        fun onSurfacesDestroyed()
    }
    fun addCallback(callback: Callback)
    fun removeCallback(callback: Callback)
    fun setVideoView(view: SurfaceView)
    fun setVideoSurface(surface: Surface)
    fun setWindowSize(width: Int, height: Int)
    fun areViewsAttached(): Boolean
    fun attachViews()
    fun detachViews()
}

internal interface VlcPlayerPort {
    val vlcVout: VlcOutputPort
    val isPlaying: Boolean
    val isSeekable: Boolean
    val time: Long
    val length: Long
    val volume: Int
    val rate: Float
    val title: Int
    fun setEventListener(listener: ((VlcEvent) -> Unit)?)
    fun setMedia(media: VlcMediaPort)
    fun play()
    fun pause()
    fun stop()
    fun release()
    fun setVolume(volume: Int): Int
    fun setRate(rate: Float)
    fun setTime(time: Long, fast: Boolean): Long
    fun setVideoTrackEnabled(enabled: Boolean)
    fun setAspectRatio(ratio: String)
    fun setScale(scale: Float)
    fun setAudioOutput(output: String): Boolean
    fun setAudioOutputDevice(device: String): Boolean
    fun setAudioDelay(delay: Long): Boolean
    fun setSpuDelay(delay: Long)
    fun setEqualizer(eq: VlcEqualizer?): Boolean
    fun addSubtitle(uri: Uri): Boolean
    fun getChapters(title: Int): List<VlcChapter>
    fun counters(): VlcRuntimeStats?
}

internal data class VlcTrack(val id: Int, val name: String?, val language: String? = null,
    val codec: String? = null, val channels: Int? = null)
internal data class VlcVideoTrack(val width: Int, val height: Int, val frameRateNum: Int,
    val frameRateDen: Int, val sarNum: Int, val sarDen: Int, val orientation: Int,
    val projection: Int, val bitrate: Long, val codec: String?)
internal data class VlcAudioTrack(val channels: Int, val rate: Int, val bitrate: Long, val codec: String?)

internal interface VlcTracksPort {
    val modern: Boolean
    val audioTrack: Int
    val videoTrack: Int
    val spuTrack: Int
    val currentVideoTrack: VlcVideoTrack?
    val currentAudioTrack: VlcAudioTrack?
    fun rebind(player: VlcPlayerPort)
    fun mediaTracks(type: Int): List<VlcTrack>
    fun getAudioTracks(): Array<VlcTrack>
    fun getVideoTracks(): Array<VlcTrack>
    fun getSpuTracks(): Array<VlcTrack>
    fun setAudioTrack(id: Int): Boolean
    fun setVideoTrack(id: Int): Boolean
    fun setSpuTrack(id: Int): Boolean
}
internal object VlcTrackType {
    const val Audio = 0
    const val Video = 1
    const val Text = 2
}

/** The bundled implementation stays selected until the DEX/JNI namespace candidate
 * has been validated. Keeping selection here prevents UI code depending on it. */
internal object VlcEngineRuntime {
    val engine: VlcEnginePort = VlcNativeEngine
}

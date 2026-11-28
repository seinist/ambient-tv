package com.ambient.tv.ui.player.backend

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.view.SurfaceView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.interfaces.IMedia

/** The only concrete LibVLC implementation used by production playback. */
internal object VlcNativeEngine : VlcEnginePort {
    override fun createLibrary(context: Context, options: ArrayList<String>): VlcLibraryPort =
        NativeLibrary(LibVLC(context, options))
    override fun createTracks(player: VlcPlayerPort): VlcTracksPort = VlcTrackAccess(player)
    override fun vulkanModules(): Boolean {
        // The helper exists in the patched custom AAR, not the Maven fallback.
        val type = try {
            Class.forName("org.videolan.libvlc.AmbientNativeLibraryLoader", false, javaClass.classLoader)
        } catch (_: ClassNotFoundException) {
            return false
        }
        return try {
            type.getMethod("vulkanModules").invoke(null) as? Boolean ?: false
        } catch (_: NoSuchMethodException) {
            // Legacy host-owned AAR only. Never search the host namespace when
            // this adapter is isolated; that could load a second bundled VLC.
            if (javaClass.classLoader !== VlcEnginePort::class.java.classLoader) false
            else VulkanNativeProbe.available && VulkanNativeProbe.vlcModules()
        }
    }

    private class NativeLibrary(private val raw: LibVLC) : VlcLibraryPort {
        override fun createPlayer(): VlcPlayerPort = NativePlayer(MediaPlayer(raw))
        override fun createMedia(uri: Uri): VlcMediaPort = NativeMedia(Media(raw, uri))
        override fun release() { raw.release() }
    }

    private class NativeMedia(val raw: Media) : VlcMediaPort {
        override fun addOption(option: String) { raw.addOption(option) }
        override fun addAudioSlave(uri: String) {
            raw.addSlave(IMedia.Slave(IMedia.Slave.Type.Audio, 4, uri))
        }
        override fun setHWDecoderEnabled(enabled: Boolean, force: Boolean) { raw.setHWDecoderEnabled(enabled, force) }
        override fun release() { raw.release() }
    }

    internal class NativePlayer(val raw: MediaPlayer) : VlcPlayerPort {
        override val vlcVout: VlcOutputPort = NativeOutput(raw.vlcVout)
        override val isPlaying get() = raw.isPlaying
        override val isSeekable get() = raw.isSeekable
        override val time get() = raw.time
        override val length get() = raw.length
        override val volume get() = raw.volume
        override val rate get() = raw.rate
        override val title get() = raw.title
        override fun setEventListener(listener: ((VlcEvent) -> Unit)?) {
            if (listener == null) raw.setEventListener(null)
            else raw.setEventListener { event ->
                val type = when (event.type) {
                    MediaPlayer.Event.Buffering -> VlcEventKind.Buffering
                    MediaPlayer.Event.Opening -> VlcEventKind.Opening
                    MediaPlayer.Event.Playing -> VlcEventKind.Playing
                    MediaPlayer.Event.Paused -> VlcEventKind.Paused
                    MediaPlayer.Event.Stopped -> VlcEventKind.Stopped
                    MediaPlayer.Event.EndReached -> VlcEventKind.EndReached
                    MediaPlayer.Event.EncounteredError -> VlcEventKind.EncounteredError
                    MediaPlayer.Event.ESAdded -> VlcEventKind.ESAdded
                    MediaPlayer.Event.TimeChanged -> VlcEventKind.TimeChanged
                    MediaPlayer.Event.PositionChanged -> VlcEventKind.PositionChanged
                    else -> VlcEventKind.Other
                }
                listener(VlcEvent(type, if (type == VlcEventKind.Buffering) event.buffering else 100f))
            }
        }
        override fun setMedia(media: VlcMediaPort) { raw.media = (media as NativeMedia).raw }
        override fun play() { raw.play() }
        override fun pause() { raw.pause() }
        override fun stop() { raw.stop() }
        override fun release() { raw.release() }
        override fun setVolume(volume: Int) = raw.setVolume(volume)
        override fun setRate(rate: Float) { raw.setRate(rate) }
        override fun setTime(time: Long, fast: Boolean) = raw.setTime(time, fast)
        override fun setVideoTrackEnabled(enabled: Boolean) { raw.setVideoTrackEnabled(enabled) }
        override fun setAspectRatio(ratio: String) { raw.setAspectRatio(ratio) }
        override fun setScale(scale: Float) { raw.setScale(scale) }
        override fun setAudioOutput(output: String) = raw.setAudioOutput(output)
        override fun setAudioOutputDevice(device: String) = raw.setAudioOutputDevice(device)
        override fun setAudioDelay(delay: Long) = raw.setAudioDelay(delay)
        override fun setSpuDelay(delay: Long) { raw.setSpuDelay(delay) }
        override fun setEqualizer(eq: VlcEqualizer?): Boolean {
            val native = eq?.let {
                MediaPlayer.Equalizer.create().apply {
                    setPreAmp(it.preamp)
                    repeat(minOf(it.bands.size, MediaPlayer.Equalizer.getBandCount())) { index ->
                        setAmp(index, it.bands[index])
                    }
                }
            }
            return raw.setEqualizer(native)
        }
        override fun addSubtitle(uri: Uri) = raw.addSlave(IMedia.Slave.Type.Subtitle, uri, false)
        override fun getChapters(title: Int): List<VlcChapter> = raw.getChapters(title)?.map {
            VlcChapter(it.timeOffset, it.name, it.duration)
        }.orEmpty()
        override fun counters(): VlcRuntimeStats? {
            val media = ReflectiveMembers.call(raw, "getMedia") ?: return null
            return try {
                val stats = ReflectiveMembers.call(media, "getStats") ?: return null
                fun number(name: String): Number? = ReflectiveMembers.value(stats, name) as? Number
                VlcRuntimeStats(
                    readBytes = number("readBytes")?.toLong(),
                    demuxReadBytes = number("demuxReadBytes")?.toLong(),
                    demuxCorrupted = number("demuxCorrupted")?.toInt(),
                    demuxDiscontinuity = number("demuxDiscontinuity")?.toInt(),
                    decodedVideo = number("decodedVideo")?.toInt(),
                    decodedAudio = number("decodedAudio")?.toInt(),
                    displayedPictures = number("displayedPictures")?.toInt(),
                    lostPictures = number("lostPictures")?.toInt(),
                    playedAudioBuffers = number("playedAbuffers")?.toInt(),
                    lostAudioBuffers = number("lostAbuffers")?.toInt(),
                )
            } finally { ReflectiveMembers.call(media, "release") }
        }
    }

    private class NativeOutput(private val raw: IVLCVout) : VlcOutputPort {
        private val callbacks = java.util.IdentityHashMap<VlcOutputPort.Callback, IVLCVout.Callback>()
        override fun addCallback(callback: VlcOutputPort.Callback) {
            if (callbacks.containsKey(callback)) return
            val bridge = object : IVLCVout.Callback {
                override fun onSurfacesCreated(vout: IVLCVout) = callback.onSurfacesCreated()
                override fun onSurfacesDestroyed(vout: IVLCVout) = callback.onSurfacesDestroyed()
            }
            raw.addCallback(bridge)
            callbacks[callback] = bridge
        }
        override fun removeCallback(callback: VlcOutputPort.Callback) {
            callbacks.remove(callback)?.let(raw::removeCallback)
        }
        override fun setVideoView(view: SurfaceView) { raw.setVideoView(view) }
        override fun setVideoSurface(surface: Surface) { raw.setVideoSurface(surface, null) }
        override fun setWindowSize(width: Int, height: Int) { raw.setWindowSize(width, height) }
        override fun areViewsAttached() = raw.areViewsAttached()
        override fun attachViews() { raw.attachViews() }
        override fun detachViews() { raw.detachViews() }
    }
}

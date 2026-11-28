package com.ambient.tv.ui.player.backend

import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/** LibVLC 4 uses opaque string track IDs; keep stable session-local IDs for the common UI.
 * The stable Maven VLC 3 fallback is supported by the same adapter. */
internal class VlcTrackAccess(player: VlcPlayerPort) : VlcTracksPort {
    private var player: MediaPlayer = (player as VlcNativeEngine.NativePlayer).raw
    override val modern = this.player.javaClass.methods.any { it.name == "getSelectedTrack" }
    private val ids = linkedMapOf<String, Int>()
    override fun rebind(player: VlcPlayerPort) { this.player = (player as VlcNativeEngine.NativePlayer).raw }
    private fun id(raw: Any?): Int = when (raw) {
        is Number -> raw.toInt()
        is String -> ids.getOrPut(raw) { ids.size }
        else -> -1
    }
    private fun field(value: Any, name: String): Any? = runCatching { value.javaClass.getField(name).get(value) }.getOrNull()
    private fun call(name: String, vararg args: Any): Any? = runCatching {
        player.javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(player, *args)
    }.getOrNull()
    private fun legacyName(type: Int) = when (type) { 0 -> "Audio"; 1 -> "Video"; else -> "Spu" }
    override fun mediaTracks(type: Int): List<VlcTrack> {
        if (modern) return (call("getTracks", type) as? Array<*>)?.filterNotNull()?.map(::track).orEmpty()
        val media = player.media ?: return emptyList()
        return try {
            val count = (media.javaClass.getMethod("getTrackCount").invoke(media) as Number).toInt()
            (0 until count).mapNotNull { media.javaClass.getMethod("getTrack", Int::class.javaPrimitiveType).invoke(media, it) }
                .filter { (field(it, "type") as? Number)?.toInt() == type }.map(::track)
        } catch (_: ReflectiveOperationException) { emptyList() }
        finally { media.release() }
    }
    private fun track(raw: Any): VlcTrack {
        val text = vlcTrackCodec(raw)
        return VlcTrack(id(field(raw, "id")), field(raw, "name") as? String ?: field(raw, "description") as? String,
            field(raw, "language") as? String, text, (field(raw, "channels") as? Number)?.toInt()?.takeIf { it > 0 })
    }
    private fun tracks(type: Int): Array<VlcTrack> = if (modern) mediaTracks(type).toTypedArray() else
        (call("get${legacyName(type)}Tracks") as? Array<*>)?.filterNotNull()?.map(::track)?.toTypedArray() ?: emptyArray()
    private fun selected(type: Int): Int = if (modern) call("getSelectedTrack", type)?.let { id(field(it, "id")) } ?: -1
        else (call("get${legacyName(type)}Track") as? Number)?.toInt() ?: -1
    private fun select(type: Int, selectedId: Int): Boolean {
        if (!modern) return call("set${legacyName(type)}Track", selectedId) as? Boolean ?: false
        if (selectedId < 0) { call("unselectTrackType", type); return true }
        val rawId = ids.entries.firstOrNull { it.value == selectedId }?.key ?: return false
        return call("selectTrack", rawId) as? Boolean ?: false
    }
    override val audioTrack get() = selected(0)
    override val videoTrack get() = selected(1)
    override val spuTrack get() = selected(2)
    override val currentVideoTrack: VlcVideoTrack? get() =
        ((if (modern) call("getSelectedTrack", 1) else call("getCurrentVideoTrack")) as? IMedia.VideoTrack)?.let {
            VlcVideoTrack(it.width, it.height, it.frameRateNum, it.frameRateDen, it.sarNum, it.sarDen,
                it.orientation, it.projection, it.bitrate.toLong(), vlcTrackCodec(it))
        }
    override val currentAudioTrack: VlcAudioTrack? get() =
        ((if (modern) call("getSelectedTrack", 0) else mediaAudioTrack()) as? IMedia.AudioTrack)?.let {
            VlcAudioTrack(it.channels, it.rate, it.bitrate.toLong(), vlcTrackCodec(it))
        }
    private fun mediaAudioTrack(): IMedia.AudioTrack? {
        val media = player.media ?: return null
        return try {
            val count = (ReflectiveMembers.call(media, "getTrackCount") as? Number)?.toInt() ?: 0
            (0 until count).asSequence().mapNotNull { index ->
                runCatching { media.javaClass.getMethod("getTrack", Int::class.javaPrimitiveType).invoke(media, index) as? IMedia.AudioTrack }.getOrNull()
            }.firstOrNull { id(field(it, "id")) == audioTrack }
        } finally { media.release() }
    }
    override fun getAudioTracks() = tracks(0)
    override fun getVideoTracks() = tracks(1)
    override fun getSpuTracks() = tracks(2)
    override fun setAudioTrack(id: Int) = select(0, id)
    override fun setVideoTrack(id: Int) = select(1, id)
    override fun setSpuTrack(id: Int) = select(2, id)
}

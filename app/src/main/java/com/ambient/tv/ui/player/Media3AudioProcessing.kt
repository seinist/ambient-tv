@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.ambient.tv.ui.player.backend.AudioSpectrumAnalyzer
import java.util.WeakHashMap
import java.lang.ref.WeakReference

object Media3AudioProcessing {
    private val sessions = WeakHashMap<ExoPlayer, AudioSession>()
    private val listeners = WeakHashMap<ExoPlayer, Player.Listener>()
    fun session(player: ExoPlayer): AudioSession? = sessions[player]

    fun attach(player: ExoPlayer, session: AudioSession, context: Context) {
        sessions[player] = session
        val playerRef = WeakReference(player)
        fun reconfigure() {
            val target = playerRef.get() ?: return
            if (target.mediaItemCount == 0) return
            val playing = target.playWhenReady
            val position = target.currentPosition
            val live = target.isCurrentMediaItemLive
            target.stop()
            if (!target.reopenTimeshiftAtLiveEdge()) {
                if (live) target.seekToDefaultPosition() else target.seekTo(position)
            }
            target.prepare()
            target.playWhenReady = playing
        }
        session.apply = update@ { old, next ->
            val target = playerRef.get() ?: return@update
            if (session.bitstreamPermitted) target.setPlaybackSpeed(1f)
            if (requiresAudioReconfiguration(old, next, session.sourcePassthrough)) reconfigure()
        }
        session.routeChanged = { _, _ -> reconfigure() }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val format = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.firstNotNullOfOrNull { group ->
                    (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
                } ?: playerRef.get()?.audioFormat ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }?.getTrackFormat(0)
                // Unsupported tracks are never selected. Keep their source codec/channels so
                // local recovery and diagnostics can distinguish E-AC-3 from a silent file.
                session.sourceCodec = format?.let { codecKey(it.sampleMimeType) ?: normalizeSourceCodec(it.codecs) ?: it.sampleMimeType }
                session.sourceChannels = format?.channelCount?.takeIf { it > 0 }
                if (tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    session.updateAudioOnly(tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO })
                }
            }
        }
        listeners[player] = listener
        player.addListener(listener)
        listener.onTracksChanged(player.currentTracks)
        session.startRouteMonitoring(context)
    }

    fun release(player: ExoPlayer) {
        listeners.remove(player)?.let(player::removeListener)
        sessions.remove(player)?.release()
    }

    internal fun requiresAudioReconfiguration(old: com.ambient.tv.data.local.AudioOptions, next: com.ambient.tv.data.local.AudioOptions, sourceDefault: Boolean): Boolean =
        old.permitsBitstream(sourceDefault) != next.permitsBitstream(sourceDefault) ||
            old.output != next.output || old.allowedCodecs != next.allowedCodecs || old.channels != next.channels ||
            old.upmix != next.upmix || old.localAc3 != next.localAc3 || old.localFallback != next.localFallback

    fun factory(context: Context, session: AudioSession, analyzer: AudioSpectrumAnalyzer): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
                // Deprecated without replacement; false keeps speed changes in the Sonic processor,
                // which bitstream passthrough and the profile DSP rely on.
                @Suppress("DEPRECATION")
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioProcessors(arrayOf(ProfileAudioProcessor(session), TeeAudioProcessor(analyzer)))
                    .build()
                return object : ForwardingAudioSink(sink) {
                    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
                    override fun getFormatSupport(format: Format): Int {
                        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) {
                            if (session.audioOnly) return AudioSink.SINK_FORMAT_UNSUPPORTED
                            val key = codecKey(format.sampleMimeType)
                            // Format support is queried on ExoPlayer's playback thread. Do not
                            // overwrite the monitored route here: that would hide the transition
                            // from the main-thread callback responsible for reconfiguring the sink.
                            val route = AudioPassthroughPolicy.probeCached(context).copy(mpvAvailable = session.route.mpvAvailable)
                            val plan = planAudioOutput(
                                session.options, key, route, session.backend, session.sourcePassthrough,
                                rejectedCodecs = AudioCodecRejects.snapshot(),
                            )
                            if (!plan.encoded || key == null || key !in plan.spdifCodecs) {
                                return AudioSink.SINK_FORMAT_UNSUPPORTED
                            }
                        }
                        return super.getFormatSupport(format)
                    }
                }
            }

            override fun buildAudioRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                // Separate sink: the ordinary sink deliberately rejects unsupported original
                // bitstreams. This renderer supplies locally encoded AC3 (or stereo PCM).
                if (com.ambient.tv.ui.player.audio.LocalAudioBridge.AVAILABLE) {
                    val localSink = DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(ProfileAudioProcessor(session), TeeAudioProcessor(analyzer)))
                        .build()
                    out.add(com.ambient.tv.ui.player.audio.LocalAudioRenderer(eventHandler, eventListener, localSink, session))
                }
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector,
                    enableDecoderFallback, audioSink, eventHandler, eventListener, out)
            }

            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
            ) {
                out.add(object : MediaCodecVideoRenderer(
                    MediaCodecVideoRenderer.Builder(context)
                        .setCodecAdapterFactory(codecAdapterFactory)
                        .setMediaCodecSelector(mediaCodecSelector)
                        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                        .setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler)
                        .setEventListener(eventListener)
                        .setMaxDroppedFramesToNotify(50),
                ) {
                    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                        // Shift only video scheduling relative to the audio master clock. Adding
                        // PCM silence would be cancelled by the sink clock and break seeks.
                        // Positive means sound later: display the video timeline further ahead.
                        super.render(videoPositionUs(positionUs, session.renderOptions.delayMs), elapsedRealtimeUs)
                    }
                })
            }
        }.setMediaCodecSelector(HardwareFirstMediaCodecSelector).setEnableDecoderFallback(true)

    internal fun videoPositionUs(positionUs: Long, delayMs: Int): Long = positionUs + delayMs.coerceIn(-2000, 2000) * 1000L

    internal fun codecKey(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "ac3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
        MimeTypes.AUDIO_DTS -> "dts"
        MimeTypes.AUDIO_DTS_HD -> "dts-hd"
        MimeTypes.AUDIO_TRUEHD -> "truehd"
        else -> null
    }
}

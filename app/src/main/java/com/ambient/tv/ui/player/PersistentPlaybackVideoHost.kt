@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import android.view.ViewGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ambient.tv.R
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.MpvVideoSurface
import com.ambient.tv.ui.player.backend.VlcPlaybackController
import com.ambient.tv.ui.player.backend.VlcVideoSurface
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** True while the root-level surface owns the active playback engine. */
val LocalPersistentPlaybackVideoHost = staticCompositionLocalOf { false }

/** True on browse screens that should reveal the playing video behind their chrome. */
val LocalVisibleBackgroundVideo = staticCompositionLocalOf { false }

@Composable
fun PersistentPlaybackVideoHost(modifier: Modifier = Modifier) {
    // The handle is Compose state and changes synchronously during acquisition. The snapshot
    // flow reaches this composition separately: keying by its generation can attach the NEW
    // handle under the OLD generation, then destroy its Surface again one frame later.
    // Keep the output keyed to its actual owner; collect only the optional geometry hint.
    val sourceAspectRatioHint by remember {
        BackgroundPlaybackSession.snapshot
            .map { it.metadata?.sourceAspectRatioHint }
            .distinctUntilChanged()
    }.collectAsState(initial = remember { BackgroundPlaybackSession.snapshot.value.metadata?.sourceAspectRatioHint })
    val handle = BackgroundPlaybackSession.activeHandle()
    // Ambient letterbox only on a portrait phone with a real picture; landscape stays black.
    val portraitPhone = com.ambient.tv.ui.mobile.LocalHandheldUi.current &&
        androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val ambient = portraitPhone && when (handle) {
        is Media3PlaybackHandle -> true
        is VlcPlaybackController -> !handle.audioSession.audioOnly
        is MpvPlaybackController -> !handle.audioSession.audioOnly
        else -> false
    }
    DisposableEffect(ambient) {
        AmbientVideoBackdrop.enabled = ambient
        onDispose { AmbientVideoBackdrop.enabled = false }
    }
    if (ambient) AmbientVideoBackdropLayer(modifier)
    key(handle) {
        when (handle) {
            is Media3PlaybackHandle -> PersistentMedia3Video(handle, sourceAspectRatioHint, modifier)
            is VlcPlaybackController -> if (!handle.audioSession.audioOnly || handle.audioVisualizationEnabled) VlcVideoSurface(handle, modifier, sourceAspectRatioHint)
            is MpvPlaybackController -> if (!handle.audioSession.audioOnly) MpvVideoSurface(handle, modifier, sourceAspectRatioHint)
            else -> Box(modifier.background(Color.Black))
        }
    }
}

@Composable
private fun PersistentMedia3Video(handle: Media3PlaybackHandle, sourceAspectRatioHint: Float?, modifier: Modifier) {
    val presentation by BackgroundPlaybackSession.videoPresentation.collectAsState()
    Box(if (AmbientVideoBackdrop.enabled) modifier else modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Media3VideoSurface(
            player = handle.player,
            resizeMode = presentation.resizeMode,
            aspectRatio = presentation.aspectRatio,
            zoom = presentation.zoom,
            modifier = Modifier.fillMaxSize(),
            sourceAspectRatioHint = sourceAspectRatioHint,
        )
    }
}

/** Media3 HDR-capable output backed by Media3's own SurfaceView lifecycle. */
@Composable
fun Media3VideoSurface(
    player: ExoPlayer,
    resizeMode: Int,
    aspectRatio: Float? = null,
    zoom: Float = 1f,
    modifier: Modifier = Modifier,
    sourceAspectRatioHint: Float? = null,
) {
    val hdrHeadroom = videoSurfaceHdrHeadroom()
    val context = androidx.compose.ui.platform.LocalContext.current
    val processing = remember(player) { Media3ImageProcessing.get(context, player) }
    // Like MPV/VLC GPU output: with the effect chain on, render at the physical HDMI mode so a
    // 1080p source behind a 1080p TV desktop on a 4K panel is upscaled by the chosen scaler.
    val renderAtPanel = processing.gpuEffectsSupported && processing.sdrEffectsAllowed && processing.options.enabled &&
        gpuRenderAtPanelResolution(videoImagePreferences(context).gpuRenderResolutionKey, gpuOutput = true,
            lowEndDevice = performanceProfile(context).lowEndDevice, userImageProcessing = true)
    AndroidView(
        factory = { context ->
            FormatPlayerView(context).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // Shell owns keep-screen-on; see MainActivity keepScreenOn policy.
                keepScreenOn = false
                setBackgroundColor(android.graphics.Color.BLACK)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                subtitleView?.applyAmbientSubtitleStyle()
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.setBackgroundColor(if (AmbientVideoBackdrop.enabled) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK)
            (view.videoSurfaceView as? android.view.SurfaceView)?.let {
                AmbientVideoBackdrop.attach(it)
                Media3PlaybackHandle.forPlayer(player)?.attachVideoView(it)
                it.applyUiHdrHeadroom(hdrHeadroom)
                it.renderAtPanelResolution(renderAtPanel)
            }
            val effective = VideoPresentation(resizeMode, aspectRatio, zoom).normalized()
            view.forcedAspectRatio = effective.aspectRatio
            view.resizeMode = effective.resizeMode
            val contentFrame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            val sourceSize = player.videoSize
            val sourceAspectRatio = if (sourceSize.width > 0 && sourceSize.height > 0) {
                sourceSize.width * sourceSize.pixelWidthHeightRatio / sourceSize.height
            } else {
                0f
            }
            contentFrame?.setAspectRatio(effective.aspectRatio ?: sourceAspectRatio.takeIf { it > 0f } ?: sourceAspectRatioHint ?: 0f)
            view.videoSurfaceView?.apply {
                isFocusable = false
                isFocusableInTouchMode = false
                pivotX = width / 2f
                pivotY = height / 2f
                scaleX = effective.zoom
                scaleY = effective.zoom
            }
        },
        onRelease = {
            AmbientVideoBackdrop.detach(it.videoSurfaceView as? android.view.SurfaceView)
            Media3PlaybackHandle.forPlayer(player)?.detachVideoView(it.videoSurfaceView as? android.view.SurfaceView)
            it.player = null
        },
        modifier = modifier.ignoreVideoSurfaceFocus(),
    )
}

/**
 * Fixes the SurfaceView buffer at the physical display mode while [enabled] (re-applied on
 * layout changes); otherwise the buffer follows the layout again.
 */
private fun android.view.SurfaceView.renderAtPanelResolution(enabled: Boolean) {
    val listener = getTag(R.id.media3_panel_render_listener) as? android.view.View.OnLayoutChangeListener
    if (!enabled) {
        if (listener != null) {
            removeOnLayoutChangeListener(listener)
            setTag(R.id.media3_panel_render_listener, null)
            holder.setSizeFromLayout()
        }
        return
    }
    fun apply() {
        val output = display ?: return
        val logical = android.graphics.Point().also(output::getRealSize)
        val mode = output.mode
        val rotated = output.rotation == android.view.Surface.ROTATION_90 || output.rotation == android.view.Surface.ROTATION_270
        val target = physicalVideoRenderSize(width, height, logical.x, logical.y,
            if (rotated) mode.physicalHeight else mode.physicalWidth,
            if (rotated) mode.physicalWidth else mode.physicalHeight) ?: return
        holder.setFixedSize(target.first, target.second)
    }
    if (listener == null) {
        val added = android.view.View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) apply()
        }
        addOnLayoutChangeListener(added)
        setTag(R.id.media3_panel_render_listener, added)
    }
    apply()
}

/** Native video planes must not become Compose/Android focus targets over the OSD. */
internal fun Modifier.ignoreVideoSurfaceFocus(): Modifier =
    focusProperties { canFocus = false }

@Composable
fun Media3VideoTexture(
    player: ExoPlayer,
    resizeMode: Int,
    aspectRatio: Float? = null,
    zoom: Float = 1f,
    modifier: Modifier = Modifier,
    sourceAspectRatioHint: Float? = null,
) = Media3VideoSurface(player, resizeMode, aspectRatio, zoom, modifier, sourceAspectRatioHint)

/**
 * The default follows the system caption style, which on most TVs means large white text on
 * black boxes. Use white text with a dark outline, no boxes, at a moderate size instead. Cue
 * styling (ASS/SSA colors, positions, italics) stays; only embedded font sizes are ignored since
 * they routinely blow subtitles up.
 */
internal fun androidx.media3.ui.SubtitleView.applyAmbientSubtitleStyle() {
    setApplyEmbeddedFontSizes(false)
    setStyle(
        androidx.media3.ui.CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD),
        ),
    )
    setFractionalTextSize(0.045f)
    setBottomPaddingFraction(0.06f)
}

/** PlayerView receives decoder format events independently of Compose updates. */
internal class FormatPlayerView(context: android.content.Context) : PlayerView(context) {
    var forcedAspectRatio: Float? = null

    override fun onContentAspectRatioChanged(contentFrame: AspectRatioFrameLayout?, aspectRatio: Float) {
        super.onContentAspectRatioChanged(contentFrame, forcedAspectRatio ?: aspectRatio)
    }

    // Registered after PlayerView's own listener, so these box-free cues replace the ones it set.
    private val boxlessCues = object : androidx.media3.common.Player.Listener {
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            subtitleView?.setCues(cueGroup.cues.map { it.withoutBackground() })
        }
    }

    override fun setPlayer(player: androidx.media3.common.Player?) {
        this.player?.removeListener(boxlessCues)
        super.setPlayer(player)
        player?.addListener(boxlessCues)
        player?.let { subtitleView?.setCues(it.currentCues.cues.map { cue -> cue.withoutBackground() }) }
    }
}

/** Keeps ASS/SSA styling but drops opaque boxes (BorderStyle=3, BackColour) behind the text. */
private fun androidx.media3.common.text.Cue.withoutBackground(): androidx.media3.common.text.Cue {
    val text = text
    val builder = buildUpon().clearWindowColor()
    if (text is android.text.Spanned) {
        val spans = text.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java)
        if (spans.isNotEmpty()) {
            val copy = android.text.SpannableString(text)
            spans.forEach(copy::removeSpan)
            builder.setText(copy)
        }
    }
    return builder.build()
}

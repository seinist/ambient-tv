@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
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
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // Shell owns keep-screen-on; see MainActivity keepScreenOn policy.
                keepScreenOn = false
                setBackgroundColor(android.graphics.Color.BLACK)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            (view.videoSurfaceView as? android.view.SurfaceView)?.let {
                Media3PlaybackHandle.forPlayer(player)?.attachVideoView(it)
                it.applyUiHdrHeadroom(hdrHeadroom)
            }
            view.resizeMode = resizeMode
            val contentFrame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            val sourceSize = player.videoSize
            val sourceAspectRatio = if (sourceSize.width > 0 && sourceSize.height > 0) {
                sourceSize.width * sourceSize.pixelWidthHeightRatio / sourceSize.height
            } else {
                0f
            }
            contentFrame?.setAspectRatio(aspectRatio ?: sourceAspectRatio.takeIf { it > 0f } ?: sourceAspectRatioHint ?: 0f)
            view.videoSurfaceView?.apply {
                isFocusable = false
                isFocusableInTouchMode = false
                pivotX = width / 2f
                pivotY = height / 2f
                scaleX = zoom.coerceIn(0.5f, 2f)
                scaleY = zoom.coerceIn(0.5f, 2f)
            }
        },
        onRelease = {
            Media3PlaybackHandle.forPlayer(player)?.detachVideoView(it.videoSurfaceView as? android.view.SurfaceView)
            it.player = null
        },
        modifier = modifier.ignoreVideoSurfaceFocus(),
    )
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

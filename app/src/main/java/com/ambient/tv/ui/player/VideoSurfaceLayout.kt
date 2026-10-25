@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import android.graphics.Rect
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.ui.display.UI_HDR_HEADROOM_CAP
import android.widget.FrameLayout

/**
 * Shared native video plane for every renderer.
 *
 * The SurfaceView keeps HDR metadata and the device's hardware video path intact. Geometry is
 * applied by laying out the actual video surface instead of relying on a TextureView sampling
 * matrix, which vendor MediaCodec implementations are free to replace while rendering.
 */
/**
 * True while fullscreen playback has hidden its controls. Background playback and a visible
 * OSD keep this false so the video plane is capped to the same headroom as the UI.
 */
internal object PlayerVideoChrome {
    var immersive by mutableStateOf(false)
}

/** 0 leaves the surface on the platform default. A positive value is the UI cap. */
@Composable
internal fun videoSurfaceHdrHeadroom(): Float {
    val ui = com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f
    return if (ui > 1f && !PlayerVideoChrome.immersive) UI_HDR_HEADROOM_CAP else 0f
}

internal fun SurfaceView.applyUiHdrHeadroom(headroom: Float) {
    if (Build.VERSION.SDK_INT < 35) return
    val desired = if (headroom.isFinite() && headroom > 1f) minOf(headroom, UI_HDR_HEADROOM_CAP) else 0f
    setDesiredHdrHeadroom(desired)
}

internal open class VideoSurfaceLayout(context: Context) : FrameLayout(context) {
    /** Only GPU renderers own buffer sizing. Never constrain MediaCodec's native video plane. */
    var renderAtDisplayResolution: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                // Back to the layout size: the GPU output renders at the logical window size again.
                fixedBufferSize = null
                videoSurface.holder.setSizeFromLayout()
            } else updateRenderTarget()
            requestLayout()
        }
    /** Buffer size currently forced at the physical mode, null while it follows the layout. */
    val forcedRenderSize: Pair<Int, Int>? get() = fixedBufferSize
    private var fixedBufferSize: Pair<Int, Int>? = null
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (display?.displayId == displayId) {
                // A refresh-only change need not generate surfaceChanged, and a physical
                // resolution change need not resize the logical Android TV desktop.
                updateRenderTarget()
                requestLayout()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        updateRenderTarget()
        // A replacement AndroidView can be laid out without another Compose draw. SurfaceView
        // creates its native surface in onPreDraw, so explicitly schedule the first window frame.
        post { if (isAttachedToWindow) rootView.postInvalidateOnAnimation() }
    }

    override fun onDetachedFromWindow() {
        displayManager.unregisterDisplayListener(displayListener)
        super.onDetachedFromWindow()
    }

    protected open fun onDisplayRefreshRateChanged(refreshRate: Float) = Unit

    @Suppress("DEPRECATION")
    private fun updateRenderTarget() {
        val output = display ?: return
        onDisplayRefreshRateChanged(output.refreshRate)
        if (!renderAtDisplayResolution) return
        val logical = Point().also(output::getRealSize)
        val mode = output.mode
        val rotated = output.rotation == Surface.ROTATION_90 || output.rotation == Surface.ROTATION_270
        val target = physicalVideoRenderSize(
            videoSurface.width, videoSurface.height, logical.x, logical.y,
            if (rotated) mode.physicalHeight else mode.physicalWidth,
            if (rotated) mode.physicalWidth else mode.physicalHeight,
        ) ?: return
        if (target == fixedBufferSize) return
        fixedBufferSize = target
        videoSurface.holder.setFixedSize(target.first, target.second)
    }
    private val surfaceClip = Rect()
    val videoSurface = SurfaceView(context).apply {
        // The decoded buffer lives in SurfaceView's separate surface below the app window.
        // An opaque background on this view covers that hole, so leave it unobscured.
        background = null
        isFocusable = false
        isFocusableInTouchMode = false
        // Keep-screen-on is owned by the app shell (MainActivity) so phone browse/mini-player
        // can sleep while the player page and TV video playback keep the display awake.
        keepScreenOn = false
        setZOrderOnTop(false)
    }

    var presentation: VideoPresentation = VideoPresentation()
        set(value) {
            field = value
            requestLayout()
        }

    var sourceAspectRatio: Float? = null
        set(value) {
            field = value
            requestLayout()
        }

    init {
        // Compose treats AndroidView as opaque, so a black Box behind this host never paints
        // the letterbox. Without a black fill the window background (#0A0C0F) shows through
        // as grey bars. SurfaceView still punches a hole in its own bounds for the picture.
        setBackgroundColor(android.graphics.Color.BLACK)
        clipChildren = true
        clipToPadding = true
        isFocusable = false
        isFocusableInTouchMode = false
        addView(videoSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        if (width <= 0 || height <= 0) {
            videoSurface.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            )
            return
        }

        val (childWidth, childHeight) = videoPresentationSize(width, height, presentation, sourceAspectRatio)
        videoSurface.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val childWidth = videoSurface.measuredWidth
        val childHeight = videoSurface.measuredHeight
        val childLeft = (width - childWidth) / 2
        val childTop = (height - childHeight) / 2
        videoSurface.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
        updateRenderTarget()
        surfaceClip.set(0, 0, width, height)
        clipBounds = surfaceClip
    }

}

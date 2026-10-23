@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.view.View
import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VideoPresentationViewTest {
    @Test fun media3DecoderUpdatesCannotReplaceExplicitFormat() {
        val view = FormatPlayerView(RuntimeEnvironment.getApplication())
        val frame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        val callback = FormatPlayerView::class.java.getDeclaredMethod(
            "onContentAspectRatioChanged", AspectRatioFrameLayout::class.java, Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        view.forcedAspectRatio = 4f / 3f
        callback.invoke(view, frame, 16f / 9f)
        assertEquals(1440 to 1080, measuredSize(frame))
        callback.invoke(view, frame, 21f / 9f)
        assertEquals(1440 to 1080, measuredSize(frame))
        view.forcedAspectRatio = null
        callback.invoke(view, frame, 21f / 9f)
        assertEquals(1920 to 822, measuredSize(frame))
    }

    private fun measuredSize(frame: AspectRatioFrameLayout): Pair<Int, Int> {
        frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        return frame.measuredWidth to frame.measuredHeight
    }

    @Test fun nativeSurfaceIsCenteredAndContainedDespiteStaleZoom() {
        val host = VideoSurfaceLayout(RuntimeEnvironment.getApplication())
        host.presentation = VideoPresentation(4, 4f / 3f, 2f)
        host.sourceAspectRatio = 21f / 9f
        host.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 1920, 1080)
        assertEquals(1440, host.videoSurface.width)
        assertEquals(1080, host.videoSurface.height)
        assertEquals(240, host.videoSurface.left)
        assertEquals(0, host.videoSurface.top)
    }
}

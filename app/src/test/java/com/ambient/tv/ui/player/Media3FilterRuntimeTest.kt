@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.ui.performance.PerformanceProfileResolver
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises the running processor and the effects handed to ExoPlayer, without compiling GL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Media3FilterRuntimeTest.PreferencesApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class Media3FilterRuntimeTest {
    class PreferencesApplication : Application(), dagger.hilt.internal.GeneratedComponentManager<Any> {
        val testPreferences by lazy {
            AppPreferences(getSharedPreferences("media3-filter-runtime", 0)).apply {
                performanceModeKey = "balanced"
                playbackPerformanceModeKey = "balanced"
            }
        }
        private val component by lazy {
            object : dagger.hilt.internal.GeneratedComponent, VideoImagePreferencesEntryPoint {
                private val resolver = PerformanceProfileResolver(this@PreferencesApplication, testPreferences)
                override fun preferences() = testPreferences
                override fun performanceProfileResolver() = resolver
            }
        }
        override fun generatedComponent(): Any = component
    }

    private class PlayerFixture(initialFormat: Format) {
        var format = initialFormat
        var playbackState = Player.STATE_READY
        var playWhenReady = true
        var positionMs = 90_000L
        var prepareCount = 0
        val effects = mutableListOf<List<Effect>>()
        val analyticsListeners = linkedSetOf<AnalyticsListener>()
        lateinit var processing: Media3ImageProcessing
        private val eventTime = AnalyticsListener.EventTime(
            0L, Timeline.EMPTY, 0, null, 0L,
            Timeline.EMPTY, 0, null, 0L, 0L,
        )
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "Media3 filter player fixture"
                "getApplicationLooper" -> Looper.getMainLooper()
                "getVideoFormat" -> format
                "addAnalyticsListener" -> { analyticsListeners += args!![0] as AnalyticsListener; null }
                "getMediaItemCount" -> 1
                "getCurrentMediaItem" -> MediaItem.EMPTY
                "getCurrentMediaItemIndex" -> 0
                "getCurrentPosition" -> positionMs
                "getPlaybackState" -> playbackState
                "getPlayWhenReady" -> playWhenReady
                "isPlaying" -> playWhenReady && playbackState == Player.STATE_READY
                "isCurrentMediaItemLive" -> false
                "setForegroundMode" -> null
                "stop" -> { playbackState = Player.STATE_IDLE; null }
                "setVideoEffects" -> {
                    @Suppress("UNCHECKED_CAST")
                    effects += (args!![0] as List<Effect>).toList()
                    null
                }
                "seekTo" -> { positionMs = args!!.last() as Long; null }
                "setPlayWhenReady" -> { playWhenReady = args!![0] as Boolean; null }
                "prepare" -> { playbackState = Player.STATE_READY; prepareCount++; null }
                else -> error("Unexpected player call: ${method.name}")
            }
        } as ExoPlayer

        fun surface(width: Int, height: Int) {
            analyticsListeners.toList().forEach { it.onSurfaceSizeChanged(eventTime, width, height) }
        }

        fun inputFormat(next: Format) {
            format = next
            analyticsListeners.toList().forEach { it.onVideoInputFormatChanged(eventTime, next, null) }
        }
    }

    private val fixtures = mutableListOf<PlayerFixture>()

    private fun sdr(width: Int = 3840, height: Int = 2160, fps: Float = 60f): Format =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(width).setHeight(height).setFrameRate(fps).build()

    private fun start(format: Format, options: Media3ImageOptions): PlayerFixture {
        val context = RuntimeEnvironment.getApplication() as PreferencesApplication
        context.testPreferences.media3ImageOptions = options
        return PlayerFixture(format).also { fixture ->
            fixtures += fixture
            fixture.processing = Media3ImageProcessing.get(context, fixture.player)
            fixture.surface(3840, 2160)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @After fun releaseProcessors() {
        fixtures.forEach { Media3ImageProcessing.release(it.player) }
        PlaybackOutputTransition.clear()
    }

    @Test fun uhd60SendsCasToPlayerAndReportsEachSkippedCleanupFilter() {
        val fixture = start(sdr(), Media3ImageOptions(
            enabled = true, sharpening = "cas", deblock = "light", denoise = "medium", deband = "strong",
        ))

        assertTrue(fixture.effects.single().single() is Media3SharpenEffect)
        assertTrue(fixture.processing.effectsActive)
        assertTrue(fixture.processing.gpuPipelineInitialized)
        assertEquals("sharpen CAS", fixture.processing.appliedProcessing().videoFilters)
        assertEquals(fixture.processing.appliedProcessing(), Media3ImageProcessing.peekApplied(fixture.player))
        val exclusions = fixture.processing.filterBudgetExclusions
        assertEquals(listOf(Media3Stage.Deblock("light"), Media3Stage.Denoise("medium"),
            Media3Stage.Deband("strong")), exclusions.map { it.stage })
        assertTrue(exclusions.all { it.width == 3840 && it.height == 2160 && it.frameRate == 60f })
        assertTrue(fixture.processing.filtersOverBudget)
    }

    @Test fun uhd60SkipsAdaptiveButStillSendsIndependentGrainEffect() {
        val fixture = start(sdr(), Media3ImageOptions(
            enabled = true, sharpening = "adaptive", filmGrain = "light",
        ))

        assertTrue(fixture.effects.single().single() is Media3FilmGrainEffect)
        assertEquals("film grain light", fixture.processing.appliedProcessing().videoFilters)
        assertEquals(listOf(Media3Stage.Sharpen("adaptive")),
            fixture.processing.filterBudgetExclusions.map { it.stage })
        assertEquals("adaptive", fixture.processing.options.sharpening)
        assertTrue(fixture.processing.effectsActive)
    }

    @Test fun nisFallbackRestoresSavedCasAndDoesNotClaimIntegratedSharpening() {
        val fixture = start(sdr(1920, 1080), Media3ImageOptions(
            enabled = true, scaler = "nis", sharpening = "cas",
        ))

        val effects = fixture.effects.single()
        assertEquals(2, effects.size)
        assertTrue(effects[0] is Media3CubicScaleEffect)
        assertTrue(effects[1] is Media3SharpenEffect)
        assertEquals("sharpen CAS", fixture.processing.appliedProcessing().videoFilters)
        assertEquals(media3ScalerLabel("catmull_rom"), fixture.processing.appliedProcessing().upscaler)
        assertNull(fixture.processing.integratedSharpening)
        assertTrue(fixture.processing.filterBudgetExclusions.isEmpty())
        assertTrue(fixture.processing.effectReductions.any {
            (it.requested as? Media3Stage.Scale)?.scaler == "nis" &&
                (it.applied as? Media3Stage.Scale)?.scaler == "catmull_rom"
        })
        assertEquals("nis", fixture.processing.options.scaler)
    }

    @Test fun protectedHdrAndDolbyVisionSourcesNeverInitializeTheGpuPipeline() {
        val formats = listOf(
            sdr().buildUpon().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build(),
            sdr().buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.07.06").build(),
            sdr().buildUpon().setColorInfo(ColorInfo.Builder()
                .setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build(),
            sdr().buildUpon().setColorInfo(ColorInfo.Builder()
                .setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_HLG).build()).build(),
        )
        for (format in formats) {
            val fixture = start(format, Media3ImageOptions(enabled = true, sharpening = "cas"))
            assertTrue(fixture.effects.isEmpty())
            assertFalse(fixture.processing.gpuPipelineInitialized)
            assertFalse(fixture.processing.effectsActive)
            assertNotNull(fixture.processing.sourceRestriction)
            assertEquals("off", fixture.processing.appliedProcessing().videoFilters)
            assertEquals(0, fixture.prepareCount)
        }
    }

    @Test fun adaptiveStreamFormatChangeReevaluatesTheRunningChainWithoutChangingSettings() {
        val options = Media3ImageOptions(enabled = true, sharpening = "adaptive",
            deband = "light", filmGrain = "light")
        val fixture = start(sdr(1920, 1080), options)
        assertEquals(3, fixture.effects.single().size)
        assertEquals(1, fixture.prepareCount)

        fixture.inputFormat(sdr())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, fixture.effects.size)
        assertTrue(fixture.effects.last().single() is Media3FilmGrainEffect)
        assertEquals("film grain light", fixture.processing.appliedProcessing().videoFilters)
        assertEquals(listOf(Media3Stage.Deband("light"), Media3Stage.Sharpen("adaptive")),
            fixture.processing.filterBudgetExclusions.map { it.stage })
        assertEquals(options, fixture.processing.options)
        // A source-format update replaces the live chain without a user-requested restart.
        assertEquals(1, fixture.prepareCount)
    }
}

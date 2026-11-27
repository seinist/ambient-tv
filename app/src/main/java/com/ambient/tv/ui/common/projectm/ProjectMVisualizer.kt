package com.ambient.tv.ui.common.projectm

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ambient.tv.R
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.player.backend.AudioPcmSink
import com.ambient.tv.ui.player.backend.AudioSpectrumAnalyzer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProjectMVisualizer(
    analyzer: AudioSpectrumAnalyzer?,
    intervalSeconds: Int,
    transitionSeconds: Int,
    qualityKey: String,
    categoryKey: String,
    showControls: Boolean,
    modifier: Modifier = Modifier,
    browser: ProjectMBrowser = rememberProjectMBrowser(categoryKey),
    nativeAudioSource: Int = 0,
) {
    val intervalSeconds = browser.intervalSeconds.takeIf { it > 0 } ?: intervalSeconds
    val transitionSeconds = browser.transitionSeconds
    val performance = LocalPerformanceProfile.current
    val requestedQuality = qualityKey
    val qualityKey = if (performance.throttleProjectM) {
        "economy"
    } else {
        browser.qualityKey.ifBlank { requestedQuality }
    }
    val throttleProjectM = performance.throttleProjectM
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val hostActive = remember { AtomicBoolean(resumed) }
    val nativeHandle = remember { AtomicLong(0L) }
    val nativeLifecycleLock = remember { ReentrantLock() }
    val nativeReleased = remember { AtomicBoolean(false) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val resolutionKey = browser.resolutionKey
    var adaptiveLevel by remember { mutableIntStateOf(0) }
    val adaptive = remember { ProjectMAdaptiveQuality() }
    val adaptiveEnabled = rememberUpdatedState(resumed && !throttleProjectM && resolutionKey == ProjectMRenderResolution.AUTO)
    com.ambient.tv.ui.display.RegisterAmbientHdr(windowOutput = false, priority = 1, eligible = !throttleProjectM) { request ->
        (glView as? ProjectMHdrView)?.updateHdr(request, !throttleProjectM && nativeHandle.get() != 0L)
            ?: if (request == null) com.ambient.tv.ui.display.SdrToHdrStatus.OFF
            else com.ambient.tv.ui.display.SdrToHdrStatus.UNAVAILABLE
    }
    var texturePath by remember { mutableStateOf<String?>(null) }
    val surfaceWidth = remember { AtomicInteger(0) }
    val surfaceHeight = remember { AtomicInteger(0) }
    val availablePresets = browser.presets()
    val current = browser.currentPath?.let(::ProjectMPreset)
    // AndroidView creates its Renderer once. Surface recreation must read the live browser,
    // including a later manual selection, instead of replaying a captured startup preset.
    val currentBrowser = rememberUpdatedState(browser)
    val currentQuality = rememberUpdatedState(qualityKey)
    val currentAudioSource = rememberUpdatedState(nativeAudioSource)
    val refreshRate = glView?.display?.refreshRate?.takeIf { it.isFinite() && it >= 24f } ?: 60f
    val targetFps = if (throttleProjectM) 24 else minOf(60, refreshRate.roundToInt())
    val currentFps = rememberUpdatedState(targetFps)
    val pacedRendering = throttleProjectM || refreshRate > 61f
    val loadGeneration = remember { AtomicLong() }

    // The MilkDrop texture pack can contain many files. Extract it off the GL thread so the
    // first visualizer frame is not blocked by APK I/O. Once ready, queue the path onto the
    // active GL context; the JNI bridge applies it safely on the next render frame.
    LaunchedEffect(browser) {
        val path = withContext(Dispatchers.IO) { browser.textureDirectory()?.absolutePath }
        texturePath = path
        if (path != null) {
            val handle = nativeLifecycleLock.withLock { nativeHandle.get() }
            val view = glView
            if (handle != 0L && view != null) {
                view.queueEvent {
                    if (nativeHandle.get() == handle) ProjectMNativeBridge.nativeSetTexturePath(handle, path)
                }
            }
        }
    }

    suspend fun load(preset: ProjectMPreset, smooth: Boolean) {
        val generation = loadGeneration.incrementAndGet()
        val data = withContext(Dispatchers.IO) { browser.presetData(preset) } ?: return
        if (loadGeneration.get() != generation) return
        browser.currentPath = preset.assetPath
        nativeLifecycleLock.withLock {
            val handle = nativeHandle.get()
            if (handle != 0L) ProjectMNativeBridge.nativeLoadPreset(handle, data, smooth)
        }
    }

    LaunchedEffect(browser, browser.pendingRevision) {
        val pending = browser.consumePending() ?: return@LaunchedEffect
        load(pending.first, pending.second)
    }

    LaunchedEffect(browser, browser.filter, browser.shuffle, availablePresets.isEmpty()) {
        // A non-null fallback to entry zero hid the uninitialized state and made shuffle's
        // startup branch unreachable. Publish the actual choice even before GL is ready;
        // onSurfaceCreated then loads that same preset rather than choosing a second one.
        if (browser.currentPath == null) browser.initialPreset()?.let { load(it, false) }
    }

    LaunchedEffect(browser.locked) {
        val view = glView
        val locked = browser.locked
        if (view == null) {
            nativeLifecycleLock.withLock {
                nativeHandle.get().takeIf { it != 0L }?.let { ProjectMNativeBridge.nativeSetLocked(it, locked) }
            }
        } else {
            view.queueEvent {
                nativeLifecycleLock.withLock {
                    nativeHandle.get().takeIf { it != 0L }?.let { ProjectMNativeBridge.nativeSetLocked(it, locked) }
                }
            }
        }
    }

    LaunchedEffect(intervalSeconds, transitionSeconds, qualityKey, glView) {
        val view = glView ?: return@LaunchedEffect
        val mesh = projectMMesh(qualityKey)
        val width = surfaceWidth.get()
        val height = surfaceHeight.get()
        view.queueEvent {
            nativeLifecycleLock.withLock {
                val handle = nativeHandle.get()
                if (handle == 0L) return@withLock
                ProjectMNativeBridge.nativeSetPresetDuration(handle, intervalSeconds.toDouble())
                ProjectMNativeBridge.nativeSetSoftCutDuration(handle, transitionSeconds.coerceIn(0, 10).toDouble())
                if (width > 0 && height > 0) {
                    ProjectMNativeBridge.nativeResize(handle, width, height, mesh.first, mesh.second)
                }
            }
        }
    }

    // Decouple the GL buffer from the layout: 4K panels with a 1080p UI only show 4K detail with a
    // larger buffer, and "auto" keeps large phone/tablet panels at 1080p. The resize reaches the
    // renderer through onSurfaceChanged, so projectM's viewport always matches the buffer.
    val hdr4kAllowed = browser.hdr4kAllowed
    LaunchedEffect(glView, hdr4kAllowed) {
        (glView as? ProjectMHdrView)?.hdrBudget = if (hdr4kAllowed) {
            com.ambient.tv.ui.player.SdrHdrSizeBudget.UHD
        } else {
            com.ambient.tv.ui.player.SdrHdrSizeBudget.FULL_HD
        }
    }

    LaunchedEffect(glView, nativeAudioSource, resumed, targetFps) {
        val view = glView ?: return@LaunchedEffect
        view.queueEvent {
            val handle = nativeHandle.get()
            if (handle != 0L) {
                ProjectMNativeBridge.nativeSetAudioSource(handle, if (hostActive.get()) currentAudioSource.value else 0)
                ProjectMNativeBridge.nativeSetFps(handle, targetFps)
            }
        }
    }

    LaunchedEffect(glView, viewSize, resolutionKey, throttleProjectM, adaptiveLevel) {
        val view = glView ?: return@LaunchedEffect
        val metrics = view.resources.displayMetrics
        val displayLines = view.display?.mode?.let { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
        val size = ProjectMRenderResolution.bufferSize(
            resolutionKey, viewSize.width, viewSize.height,
            metrics.widthPixels, metrics.heightPixels, displayLines, throttleProjectM,
            adaptiveCap = ProjectMAdaptiveQuality.capLines(adaptiveLevel),
        )
        if (size == null) view.holder.setSizeFromLayout() else view.holder.setFixedSize(size.first, size.second)
    }

    LaunchedEffect(browser, availablePresets.size, intervalSeconds, browser.locked, resumed) {
        if (availablePresets.isEmpty() || !resumed || browser.locked) return@LaunchedEffect
        while (true) {
            delay(intervalSeconds.coerceIn(10, 600) * 1000L)
            if (!browser.locked && availablePresets.isNotEmpty()) {
                browser.nextPreset()?.let { load(it, true) }
            }
        }
    }

    DisposableEffect(lifecycleOwner, glView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    hostActive.set(true)
                    glView?.onResume()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    hostActive.set(false)
                    glView?.queueEvent {
                        val handle = nativeHandle.get()
                        if (handle != 0L) ProjectMNativeBridge.nativeSetAudioSource(handle, 0)
                    }
                    glView?.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        hostActive.set(resumed)
        if (resumed) glView?.onResume() else glView?.onPause()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            val view = glView
            val handle = nativeLifecycleLock.withLock {
                nativeReleased.set(true)
                nativeHandle.getAndSet(0L)
            }
            if (handle != 0L) {
                if (view != null) view.queueEvent { ProjectMNativeBridge.nativeDestroy(handle) }
                else ProjectMNativeBridge.nativeDestroy(handle)
            }
            view?.onPause()
            (view as? ProjectMHdrView)?.releaseHdr()
        }
    }

    DisposableEffect(analyzer, resumed) {
        val sink = AudioPcmSink { samples ->
            // Never make the playback audio thread wait for surface creation or preset loading.
            if (hostActive.get() && nativeLifecycleLock.tryLock()) {
                try {
                    val handle = nativeHandle.get()
                    if (handle != 0L) ProjectMNativeBridge.nativeFeedPcm(handle, samples)
                } finally {
                    nativeLifecycleLock.unlock()
                }
            }
        }
        if (resumed) analyzer?.addPcmSink(sink)
        onDispose { analyzer?.removePcmSink(sink) }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it },
            factory = { ctx ->
                ProjectMHdrView(ctx).apply {
                    glView = this
                    setRenderer(object : GLSurfaceView.Renderer {
                        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                            if (nativeReleased.get() || !ProjectMAvailability.isAvailable) return
                            val handle = ProjectMNativeBridge.nativeCreate()
                            if (handle != 0L && hostActive.get() && currentAudioSource.value != 0)
                                ProjectMNativeBridge.nativeSetAudioSource(handle, currentAudioSource.value)
                            nativeLifecycleLock.withLock {
                                // nativeCreate may finish after disposal cleared the published
                                // handle. Destroy that late result here on its GL thread.
                                if (nativeReleased.get()) {
                                    if (handle != 0L) ProjectMNativeBridge.nativeDestroy(handle)
                                    return@withLock
                                }
                                val previous = nativeHandle.getAndSet(handle)
                                if (previous != 0L) ProjectMNativeBridge.nativeDestroy(previous)
                                if (handle != 0L) {
                                    val activeBrowser = currentBrowser.value
                                    texturePath?.let { ProjectMNativeBridge.nativeSetTexturePath(handle, it) }
                                    ProjectMNativeBridge.nativeSetFps(handle, currentFps.value)
                                    ProjectMNativeBridge.nativeSetPresetDuration(handle, activeBrowser.intervalSeconds.toDouble())
                                    ProjectMNativeBridge.nativeSetSoftCutDuration(handle, activeBrowser.transitionSeconds.coerceIn(0, 10).toDouble())
                                    ProjectMNativeBridge.nativeSetBeatSensitivity(handle, 1.0f)
                                    activeBrowser.currentPath?.let(::ProjectMPreset)?.let { preset ->
                                        activeBrowser.presetData(preset)?.let { ProjectMNativeBridge.nativeLoadPreset(handle, it, false) }
                                    }
                                    ProjectMNativeBridge.nativeSetLocked(handle, activeBrowser.locked)
                                }
                            }
                        }

                        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                            adaptive.resetTiming()
                            surfaceWidth.set(width)
                            surfaceHeight.set(height)
                            val handle = nativeHandle.get()
                            if (handle == 0L) return
                            val mesh = projectMMesh(currentQuality.value)
                            ProjectMNativeBridge.nativeResize(handle, width, height, mesh.first, mesh.second)
                        }

                        override fun onDrawFrame(gl: GL10?) {
                            val handle = nativeHandle.get()
                            if (handle != 0L) {
                                ProjectMNativeBridge.nativeRender(handle)
                                adaptive.observe(System.nanoTime(), currentFps.value, adaptiveEnabled.value)?.let { level ->
                                    post { if (!nativeReleased.get()) adaptiveLevel = level }
                                }
                            }
                        }
                    })
                    renderMode = if (pacedRendering) {
                        GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    } else {
                        GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                }
            },
        )

        LaunchedEffect(glView, pacedRendering, resumed, targetFps) {
            val view = glView ?: return@LaunchedEffect
            view.renderMode = if (pacedRendering || !resumed) {
                GLSurfaceView.RENDERMODE_WHEN_DIRTY
            } else {
                GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            if (!pacedRendering || !resumed) return@LaunchedEffect
            // Align requests with display frames, retain fractional cadence and avoid catch-up bursts.
            val period = 1_000_000_000L / targetFps
            var next = 0L
            while (true) {
                withFrameNanos { now ->
                    if (next == 0L || now + 500_000L >= next) {
                        view.requestRender()
                        next = if (next == 0L || now - next >= period) now + period else next + period
                    }
                }
            }
        }

        if (availablePresets.isEmpty()) {
            Text(
                stringResource(R.string.projectm_no_presets),
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            ProjectMPresetNameOverlay(
                preset = current,
                enabled = browser.showPresetName,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(horizontal = 24.dp)
                    .padding(top = if (showControls) 80.dp else 24.dp),
            )
        }
    }
}

private fun projectMMesh(qualityKey: String): Pair<Int, Int> = when (qualityKey) {
    "economy" -> 32 to 24
    "high" -> 64 to 48
    else -> 48 to 32
}

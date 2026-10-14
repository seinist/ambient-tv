package com.ambient.tv.ui.player

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ambient.tv.ui.display.SdrToHdrRequest
import com.ambient.tv.ui.display.SdrToHdrStatus
import timber.log.Timber
import com.ambient.tv.ui.player.backend.VulkanNativeProbe

/** Main-thread owner shared by VLC and Media3. No decoder settings, media URL or audio changes. */
internal class SdrHdrSurfaceOutput(
    private val disconnect: () -> Unit,
    private val routeInput: (Surface) -> Unit,
    private val restore: (SurfaceView) -> Unit,
    private val unsafeRestore: () -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val readDataSpace: (Surface) -> Int = {
        if (VulkanNativeProbe.available) VulkanNativeProbe.dataSpace(it) else 0
    },
    private val restoreDataSpace: (Surface, Int) -> Boolean = VulkanNativeProbe::restoreDataSpace,
    /** Optional generation probe for tests; production follows SurfaceHolder lifecycle callbacks. */
    private val readGenerationId: (Surface) -> Int? = { null },
    /** Read on every update, so lowering the budget stops an active larger session. */
    private val budget: () -> SdrHdrSizeBudget = { SdrHdrSizeBudget.FULL_HD },
    private val factory: SdrHdrSessionFactory = SdrHdrSessionFactory { request, width, height, fps, ready, presented, failed ->
        if (Build.VERSION.SDK_INT >= 34) SdrHdrGlSession(request, width, height, fps, ready, presented, failed, budget())
        else error("HDR output requires Android 14")
    },
) : SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private var view: SurfaceView? = null
    private var session: SdrHdrSession? = null
    private data class Config(val request: SdrToHdrRequest, val width: Int, val height: Int, val fps: Float)
    private var candidate: Config? = null
    private var sessionSurface: Surface? = null
    private var sessionSurfaceGeneration: Int? = null
    private var quarantinedSurface: Surface? = null
    private var quarantinedGeneration: Int? = null
    private var candidateSince = 0L
    private var generation = 0
    private var closing = false
    private var routed = false
    private var originalDataSpace = 0
    private var released = false
    private var failed = false
    private var status = SdrToHdrStatus.OFF
    private val afterStop = mutableListOf<() -> Unit>()
    val active: Boolean get() = status == SdrToHdrStatus.ACTIVE
    /** Includes asynchronous teardown; another producer must not attach until it completes. */
    val ownsSurface: Boolean get() {
        val blocked = quarantinedSurface
        if (blocked != null && (!blocked.isValid || generationChanged(blocked, quarantinedGeneration)))
            quarantinedSurface = null
        return session != null || closing || quarantinedSurface != null
    }

    fun discontinuity() { session?.discontinuity() }

    fun attach(next: SurfaceView) {
        if (released || view === next) return
        detach()
        // A new window is a new playback target; an earlier failure must not block it forever.
        if (quarantinedSurface == null && !closing) failed = false
        view = next
        next.holder.addCallback(this)
    }

    fun detach() {
        view?.holder?.removeCallback(this)
        view = null // Teardown must not reconnect a disappearing/replaced surface.
        stop(SdrToHdrStatus.OFF)
    }

    fun release() {
        released = true
        afterStop.clear()
        detach()
    }

    fun deferUntilStopped(replacementView: SurfaceView? = null, action: () -> Unit): Boolean {
        if (replacementView != null && quarantinedSurface != null &&
            replacementView.holder.surface !== quarantinedSurface) quarantinedSurface = null
        if (!ownsSurface) return false
        // A failed teardown/colour restore must never admit a new producer on that window.
        // Do not accumulate callbacks which cannot safely run; a fresh surface may recover.
        if (quarantinedSurface != null) return true
        afterStop.add(action)
        stop(SdrToHdrStatus.OFF)
        return true
    }

    fun update(request: SdrToHdrRequest?, eligible: Boolean, width: Int, height: Int, fps: Float): SdrToHdrStatus {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (request == null) {
            stop(SdrToHdrStatus.OFF)
            return status
        }
        val target = view
        val limit = budget()
        if (released || Build.VERSION.SDK_INT < 34 || !eligible || target == null ||
            !target.holder.surface.isValid || !sdrHdrSizeAllowed(width, height, fps, limit) ||
            request.peakNits !in 300..600 ||
            target.display?.displayId != request.displayId || target.display?.mode?.modeId != request.displayModeId) {
            stop(SdrToHdrStatus.UNAVAILABLE)
            return status
        }
        if (failed) return SdrToHdrStatus.FAILED.also { status = it }
        if (closing) return SdrToHdrStatus.WAITING
        val config = Config(request, width, height, fps)
        if (candidate != config) {
            if (session != null) {
                stop(SdrToHdrStatus.WAITING)
                return status
            }
            candidate = config
            candidateSince = clock()
        }
        if (session != null) return status
        status = SdrToHdrStatus.WAITING
        if (clock() - candidateSince < 1000L) return status
        // Verify the existing output BEFORE redirecting it. In particular VLC's Java track API
        // does not expose transfer metadata: never infer SDR from codec names or resolution.
        originalDataSpace = runCatching { readDataSpace(target.holder.surface) }.getOrDefault(0)
        if (sdrHdrTransfer(originalDataSpace) == null) {
            status = SdrToHdrStatus.UNAVAILABLE
            return status
        }
        val token = ++generation
        sessionSurface = target.holder.surface
        sessionSurfaceGeneration = generationOf(target.holder.surface)
        try {
            val worker = factory.create(request, width, height, fps,
                ready = { surface -> main.post {
                    if (token == generation && session != null && view === target && !released) {
                        runCatching {
                            routed = true
                            disconnect()
                            routeInput(surface)
                            session?.connect(target.holder.surface)
                        }.onFailure { fail(it) }
                    }
                } },
                presented = { main.post {
                    if (token == generation && !released && session != null && routed) {
                        status = SdrToHdrStatus.ACTIVE
                        Timber.i("HDR-AUDIT SDR conversion: 10-bit BT.2020/PQ frame submitted (HDMI unverified)")
                    }
                } },
                failed = { error -> main.post { if (token == generation) fail(error) } },
            )
            session = worker
            worker.prepare()
        } catch (error: Exception) {
            fail(error)
        }
        main.postDelayed({
            if (token == generation && status == SdrToHdrStatus.WAITING) fail(IllegalStateException("HDR output confirmation timed out"))
        }, 2500L)
        return status
    }

    private fun fail(error: Throwable) {
        Timber.w(error, "HDR-AUDIT conversion disabled for this playback session")
        failed = true
        stop(SdrToHdrStatus.FAILED)
    }

    private fun stop(nextStatus: SdrToHdrStatus) {
        status = nextStatus
        candidate = null
        val old = session ?: return
        session = null
        generation++
        closing = true
        val restoreView = view
        val wasRouted = routed
        val savedDataSpace = originalDataSpace
        val oldSurface = sessionSurface
        val oldSurfaceGeneration = sessionSurfaceGeneration
        sessionSurface = null
        val closingToken = generation
        var timedOut = false
        routed = false
        var disconnected = true
        if (wasRouted) runCatching(disconnect).onFailure { disconnected = false }
        main.postDelayed({
            if (closing && generation == closingToken) {
                // A wedged driver must never allow a second producer onto the same window.
                timedOut = true
                failed = true
                status = SdrToHdrStatus.FAILED
                afterStop.clear()
                if (wasRouted && !released) runCatching(unsafeRestore)
            }
        }, 2500L)
        val finished: (Boolean) -> Unit = { clean -> main.post {
            if (!closing || generation != closingToken) return@post
            closing = false
            var safe = !timedOut && (!wasRouted || (clean && disconnected))
            if (wasRouted && (timedOut || !clean || !disconnected)) {
                failed = true
                status = SdrToHdrStatus.FAILED
                quarantinedSurface = oldSurface
                quarantinedGeneration = oldSurfaceGeneration
                if (!released) runCatching(unsafeRestore)
            } else if (wasRouted) {
                runCatching {
                    // Even a detached live window needs its original colours before reuse.
                    // Never write the old dataspace onto a destroyed/recreated Surface.
                    if (oldSurface != null && oldSurface.isValid && sameGeneration(oldSurface, oldSurfaceGeneration))
                        check(restoreDataSpace(oldSurface, savedDataSpace))
                    if (!released && restoreView != null && view === restoreView && restoreView.holder.surface.isValid)
                        restore(restoreView)
                }.onFailure {
                    safe = false
                    failed = true
                    status = SdrToHdrStatus.FAILED
                    quarantinedSurface = oldSurface
                    quarantinedGeneration = oldSurfaceGeneration
                    if (!released) runCatching(unsafeRestore)
                }
            }
            val pending = afterStop.toList()
            afterStop.clear()
            if (!released && safe) pending.forEach { it() }
        } }
        runCatching { old.close(finished) }.onFailure {
            Timber.w(it, "HDR-AUDIT GPU teardown threw; window cannot be reused")
            finished(false)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Geometry/display changes require a new stable request and a fresh EGL target.
        if (ownsSurface) stop(SdrToHdrStatus.WAITING)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceEpoch++
        stop(SdrToHdrStatus.OFF)
    }

    /** Counts destroy callbacks of the attached holder; SurfaceView reuses one Surface object. */
    private var surfaceEpoch = 0

    /**
     * Surface.getGenerationId is hidden API and can be blocked for newer target SDKs. Without it,
     * the destroy-callback count of the attached holder tells a recreated window apart.
     */
    private fun generationOf(surface: Surface): Int? =
        readGenerationId(surface) ?: if (surface === view?.holder?.surface) surfaceEpoch else null

    private fun generationChanged(surface: Surface, expected: Int?): Boolean {
        val current = generationOf(surface) ?: return false
        return expected != null && current != expected
    }

    private fun sameGeneration(surface: Surface, expected: Int?): Boolean {
        val current = generationOf(surface) ?: return false
        return expected != null && current == expected
    }

}

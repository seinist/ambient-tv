package com.ambient.tv.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.view.Surface
import android.view.SurfaceView
import android.widget.FrameLayout
import com.ambient.tv.ui.display.SdrToHdrRequest
import com.ambient.tv.ui.display.SdrToHdrStatus

/** Real SurfaceView lifecycle, fake GPU callbacks: exercises ownership without claiming HDR output. */
internal class SdrHdrOutputAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync { runCatching(action).onFailure { error = it } }
        error?.let { throw it }
    }
    private fun drain() = instrumentation.waitForIdleSync()

    private class FakeSession(val ready: (Surface) -> Unit, val presented: () -> Unit,
        val failed: (Throwable) -> Unit, val prepareThrows: Boolean = false, val closeThrows: Boolean = false) : SdrHdrSession {
        var prepared = false
        var connected = false
        var discontinuities = 0
        var closed: ((Boolean) -> Unit)? = null
        override fun prepare() { check(!prepareThrows); prepared = true }
        override fun connect(surface: Surface) { connected = true }
        override fun discontinuity() { discontinuities++ }
        override fun close(done: (Boolean) -> Unit) { check(!closeThrows); check(closed == null); closed = done }
    }

    fun run(activity: Activity) {
        lateinit var view: SurfaceView
        main {
            view = SurfaceView(activity)
            activity.setContentView(FrameLayout(activity).apply {
                addView(view, FrameLayout.LayoutParams(640, 360))
            })
        }
        Thread.sleep(500)
        drain()
        val events = mutableListOf<String>()
        val workers = mutableListOf<FakeSession>()
        var now = 0L
        var dataspace = (1 shl 16) or (3 shl 22) or (2 shl 27)
        var restoreOk = true
        var factoryThrows = false
        var prepareThrows = false
        var closeThrows = false
        val formats = mutableListOf<Triple<Int, Int, Float>>()
        lateinit var output: SdrHdrSurfaceOutput
        lateinit var request: SdrToHdrRequest
        fun newOutput() = SdrHdrSurfaceOutput(
                disconnect = { events += "disconnect" },
                routeInput = { events += "route" },
                restore = { events += "restore" },
                unsafeRestore = { events += "unsafe" },
                clock = { now },
                readDataSpace = { dataspace },
                restoreDataSpace = { _, value -> check(value == dataspace); events += "dataspace"; restoreOk },
                factory = SdrHdrSessionFactory { _, width, height, fps, ready, presented, failed ->
                    check(!factoryThrows)
                    formats += Triple(width, height, fps)
                    FakeSession(ready, presented, failed, prepareThrows, closeThrows).also { workers += it }
                },
            )
        main {
            check(view.holder.surface.isValid)
            check(com.ambient.tv.ui.player.backend.VulkanNativeProbe.available)
            val nativeSpace = com.ambient.tv.ui.player.backend.VulkanNativeProbe.dataSpace(view.holder.surface)
            check(nativeSpace >= 0)
            check(com.ambient.tv.ui.player.backend.VulkanNativeProbe.restoreDataSpace(view.holder.surface, nativeSpace))
            request = SdrToHdrRequest(400, view.display.displayId, view.display.mode.modeId)
            output = newOutput()
            output.attach(view)
            check(output.update(null, true, 1280, 720, 30f) == SdrToHdrStatus.OFF)
            check(workers.isEmpty())
            check(output.update(request, false, 1280, 720, 30f) == SdrToHdrStatus.UNAVAILABLE)
            check(output.update(request, true, 3840, 2160, 30f) == SdrToHdrStatus.UNAVAILABLE)
            check(output.update(request, true, 1280, 720, 30f) == SdrToHdrStatus.WAITING)
            now = 1000
            dataspace = 0
            check(output.update(request, true, 1280, 720, 30f) == SdrToHdrStatus.UNAVAILABLE)
            check(workers.isEmpty())
            dataspace = (1 shl 16) or (3 shl 22) or (2 shl 27)
            check(output.update(request, true, 1280, 720, 30f) == SdrToHdrStatus.WAITING)
            check(workers.single().prepared && !output.active)
            workers.last().ready(view.holder.surface)
        }
        drain()
        main {
            check(events == listOf("disconnect", "route"))
            check(workers.last().connected && !output.active)
            workers.last().presented()
        }
        drain()
        main {
            check(output.active)
            check(output.update(null, true, 1280, 720, 30f) == SdrToHdrStatus.OFF)
            check(!output.active && output.ownsSurface)
            check(events == listOf("disconnect", "route", "disconnect"))
            // A stale frame notification must not reactivate a disabled/replaced session.
            workers.last().presented()
            check(output.deferUntilStopped { events += "deferred" })
            workers.last().closed!!.invoke(true)
        }
        drain()
        main {
            check(!output.active && !output.ownsSurface)
            check(events == listOf("disconnect", "route", "disconnect", "dataspace", "restore", "deferred"))
            output.update(request, true, 1280, 720, 30f)
            now += 1000
            output.update(request, true, 1280, 720, 30f)
            check(workers.size == 2)
            workers.last().ready(view.holder.surface)
        }
        drain()
        main { workers.last().failed(IllegalStateException("injected GPU failure")) }
        drain()
        main {
            check(output.update(request, true, 1280, 720, 30f) == SdrToHdrStatus.FAILED)
            workers.last().closed!!.invoke(true)
        }
        drain()
        main {
            check(!output.ownsSurface && events.last() == "restore")
            check(output.update(request, true, 1280, 720, 30f) == SdrToHdrStatus.FAILED)
            check(workers.size == 2) // no retry loop after a failed GPU path
            output.release()
        }
        log("PASS shared VLC/Media3 output: off/unknown/4K gates, confirmed activation, ordered rollback, stale callbacks and failure latch")
        main {
            events.clear()
            output = newOutput()
            output.attach(view)
            output.update(request, true, 1280, 720, 30f)
            now += 1000
            output.update(request, true, 1280, 720, 30f)
            output.detach() // Surface disappears before asynchronous GPU preparation completes.
            workers.last().ready(view.holder.surface)
            workers.last().presented()
            workers.last().closed!!.invoke(true)
        }
        drain()
        main {
            check(events.isEmpty() && !output.active && !output.ownsSurface)
            output.release()
            output = newOutput()
            output.attach(view)
            output.update(request, true, 1280, 720, 30f)
            now += 1000
            output.update(request, true, 1280, 720, 30f)
            workers.last().ready(view.holder.surface)
        }
        drain()
        main {
            restoreOk = false
            output.update(null, true, 1280, 720, 30f)
            check(output.deferUntilStopped { events += "must-not-run" })
            workers.last().closed!!.invoke(true)
        }
        drain()
        main {
            check(events.last() == "unsafe" && "restore" !in events && "must-not-run" !in events)
            check(output.ownsSurface) // Failed colour restore quarantines this live window.
            check(output.deferUntilStopped { events += "must-not-run" })
            check("must-not-run" !in events)
            check(output.update(request, true, 1280, 720, 30f) == SdrToHdrStatus.FAILED)
            output.release()
        }
        log("PASS surface loss rejects late GPU callbacks; failed colour restoration stops output and cancels pending mutations")

        fun start(width: Int = 1280, height: Int = 720, fps: Float = 30f) {
            main {
                check(output.update(request, true, width, height, fps) == SdrToHdrStatus.WAITING)
                now += 1000
                output.update(request, true, width, height, fps)
                workers.last().ready(view.holder.surface)
            }
            drain()
            main { workers.last().presented() }
            drain()
            main { check(output.active) }
        }
        main {
            events.clear(); formats.clear(); restoreOk = true
            output = newOutput(); output.attach(view)
        }
        start()
        main {
            output.discontinuity()
            check(workers.last().discontinuities == 1 && output.active)
        }
        // Each format change must finish teardown, then build a worker with the new buffers/rate.
        for ((width, height, fps) in listOf(Triple(1280, 720, 25f), Triple(1920, 1080, 25f))) {
            main {
                val count = workers.size
                check(output.update(request, true, width, height, fps) == SdrToHdrStatus.WAITING)
                check(output.ownsSurface && !output.active && workers.size == count)
                workers.last().presented() // Old session cannot confirm the new format.
                workers.last().closed!!.invoke(true)
            }
            drain()
            start(width, height, fps)
        }
        main {
            check(formats == listOf(Triple(1280, 720, 30f), Triple(1280, 720, 25f), Triple(1920, 1080, 25f)))
            events.clear()
            output.detach()
            check(output.deferUntilStopped { events += "reattach" })
            workers.last().closed!!.invoke(true)
        }
        drain()
        main {
            check(events == listOf("disconnect", "dataspace", "reattach"))
            check(!output.ownsSurface && !output.active)
            output.release()
        }
        log("PASS HDR format changes rebuild buffers and rate; discontinuity preserves output; detached window restores colours before reuse")

        for (throwFromClose in listOf(false, true)) {
            main {
                events.clear(); closeThrows = throwFromClose
                output = newOutput(); output.attach(view)
            }
            start()
            main {
                output.update(null, true, 1280, 720, 30f)
                check(output.deferUntilStopped { events += "must-not-run" })
                if (!throwFromClose) workers.last().closed!!.invoke(false)
            }
            drain()
            main {
                check(output.ownsSurface && !output.active && events.last() == "unsafe")
                check("restore" !in events && "must-not-run" !in events)
                check(output.deferUntilStopped { events += "must-not-run" })
                check("must-not-run" !in events)
                output.release()
            }
        }
        main { closeThrows = false }
        for (throwFromFactory in listOf(true, false)) {
            main {
                events.clear(); factoryThrows = throwFromFactory; prepareThrows = !throwFromFactory
                output = newOutput(); output.attach(view)
                output.update(request, true, 1280, 720, 30f)
                now += 1000
                check(output.update(request, true, 1280, 720, 30f) == SdrToHdrStatus.FAILED)
                if (!throwFromFactory) workers.last().closed!!.invoke(true)
            }
            drain()
            main {
                check(!output.ownsSurface && events.isEmpty())
                check(output.update(request, true, 1280, 720, 30f) == SdrToHdrStatus.FAILED)
                output.release()
            }
        }
        log("PASS unsafe GPU close quarantines window and cancels callbacks; synchronous startup failures roll back without retry loops")
    }
}

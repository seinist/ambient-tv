package com.ambient.tv.ui.games

import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.lifecycle.LifecycleObserver
import com.ambient.tv.games.RetroShaderConfig
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.flow.map

/** Keeps the actual GLSurfaceView, observer and native render queue intact.
 * No host-owned wrapper view is inserted into the surface hierarchy.
 */
internal class BundledRetroView(context: Context, config: RetroViewConfig) : RetroViewPort {
    private val raw = GLRetroView(context, GLRetroViewData(context).apply {
        coreFilePath = config.coreFilePath
        if (config.gameVirtualFiles.isNotEmpty()) gameVirtualFiles =
            config.gameVirtualFiles.map { it.toLibretroVirtualFile() }
        else gameFilePath = requireNotNull(config.gameFilePath)
        systemDirectory = config.systemDirectory
        savesDirectory = config.savesDirectory
        variables = config.variables.map { it.toNative() }.toTypedArray()
        saveRAMState = config.saveRAMState
        sdrToHdrReferenceWhiteNits = config.sdrToHdrReferenceWhiteNits
        shader = config.shader.toLibretroShader()
        rumbleEventsEnabled = true
        preferLowLatencyAudio = true
        forceSoftwareTiming = config.forceSoftwareTiming
        preferVulkan = config.preferVulkan
        configuredHdrOutputMode = config.configuredHdrOutputMode
        preferHighPrecisionVulkanFrames = config.preferHighPrecisionVulkanFrames
    })
    override val surface: SurfaceView get() = raw
    override val lifecycleObserver: LifecycleObserver get() = raw
    override val sdrToHdrActive get() = raw.sdrToHdrActive
    override var shader: RetroShaderConfig = config.shader
        set(value) {
            raw.shader = value.toLibretroShader()
            field = value
        }
    override var beforeDetach: (() -> Unit)?
        get() = raw.beforeDetach
        set(value) { raw.beforeDetach = value }
    override var suppressAutoResume: Boolean
        get() = raw.suppressAutoResume
        set(value) { raw.suppressAutoResume = value }
    override var isRewinding: Boolean
        get() = raw.isRewinding
        set(value) { raw.isRewinding = value }
    override var frameSpeed: Int
        get() = raw.frameSpeed
        set(value) { raw.frameSpeed = value }
    override var rewindEnabled: Boolean
        get() = raw.rewindEnabled
        set(value) { raw.rewindEnabled = value }
    override var aspectRatioOverride: Float
        get() = raw.aspectRatioOverride
        set(value) { raw.aspectRatioOverride = value }
    override var integerScalingAuto: Boolean
        get() = raw.integerScalingAuto
        set(value) { raw.integerScalingAuto = value }
    override var integerScaling: Boolean
        get() = raw.integerScaling
        set(value) { raw.integerScaling = value }
    override var sdrToHdrReferenceWhiteNits: Int
        get() = raw.sdrToHdrReferenceWhiteNits
        set(value) { raw.sdrToHdrReferenceWhiteNits = value }
    override var achievementUnlockListener: ((Int) -> Unit)?
        get() = raw.achievementUnlockListener
        set(value) { raw.achievementUnlockListener = value }
    override var netplayTick: (() -> Unit)?
        get() = raw.netplayTick
        set(value) { raw.netplayTick = value }
    override fun pauseEmulation(): Unit { raw.pauseEmulation() }
    override fun resumeEmulation(): Unit { raw.resumeEmulation() }
    override fun destroyNative(onComplete: (Throwable?) -> Unit): Unit { raw.destroyNative(onComplete) }
    override fun reset(): Unit { raw.reset() }
    override fun clearRewindBuffer(): Unit { raw.clearRewindBuffer() }
    override fun initRewindBuffer(maxSlots: Int, budgetBytes: Long): Unit { raw.initRewindBuffer(maxSlots, budgetBytes) }
    override fun destroyRewindBuffer(): Unit { raw.destroyRewindBuffer() }
    override fun getRewindBufferHistoryFrames(): Long = raw.getRewindBufferHistoryFrames()
    override fun previewRewind(target: Long): Long = raw.previewRewind(target)
    override fun serializeState(): ByteArray = raw.serializeState()
    override fun unserializeState(bytes: ByteArray): Boolean = raw.unserializeState(bytes)
    override fun unserializePersistedState(bytes: ByteArray): Boolean = raw.unserializePersistedState(bytes)
    override fun getSerializeSize(): Long = raw.getSerializeSize()
    override fun enablePausedPresentationOnDemand(): Unit { raw.enablePausedPresentationOnDemand() }
    override fun setControllerType(port: Int, type: Int): Unit { raw.setControllerType(port, type) }
    override fun getAvailableDisks(): Int = raw.getAvailableDisks()
    override fun getCurrentDisk(): Int = raw.getCurrentDisk()
    override fun changeDisk(index: Int): Unit { raw.changeDisk(index) }
    override fun queueEvent(task: () -> Unit) { raw.queueEvent { task() } }
    override fun captureRawFrame(maxWidth: Int): Bitmap? = raw.captureRawFrame(maxWidth = maxWidth)
    override fun getVariables(): Array<RetroVariable> =
        raw.getVariables().map { RetroVariable(it.key, it.value, it.description) }.toTypedArray()
    override fun updateVariables(vararg variables: RetroVariable) =
        raw.updateVariables(*variables.map { it.toNative() }.toTypedArray())
    override fun getControllers(): Array<Array<RetroController>> =
        raw.getControllers().map { port -> port.map { RetroController(it.id, it.description) }.toTypedArray() }.toTypedArray()
    override fun getGLRetroEvents() = raw.getGLRetroEvents().map { event ->
        when (event) {
            GLRetroView.GLRetroEvents.SurfaceCreated -> RetroRenderEvent.SurfaceCreated
            GLRetroView.GLRetroEvents.FrameRendered -> RetroRenderEvent.FrameRendered
            else -> RetroRenderEvent.Other
        }
    }
    override fun getGLRetroErrors() = raw.getGLRetroErrors().map {
        RetroViewError(it, it == GLRetroView.ERROR_LOAD_LIBRARY)
    }
    override fun getRumbleEvents() = raw.getRumbleEvents().map {
        RetroRumble(it.port, it.strengthWeak, it.strengthStrong)
    }
    override fun stepForNetplay() { LibretroDroid.stepForNetplay(raw) }

    private fun RetroVariable.toNative() = Variable(key, value)
}

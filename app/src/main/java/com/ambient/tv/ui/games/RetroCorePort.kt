package com.ambient.tv.ui.games

import android.view.Surface

/** Stable primitive JNI control/status boundary. No vendor Java types cross it.
 * These calls retain their existing render-thread or UI-safe atomic scheduling;
 * the port never introduces a dispatcher, allocation of input arrays or lock.
 */
internal interface RetroCorePort {
    fun getVulkanFrameStatus(): Int
    fun getVulkanCoreSourceFormat(): Int
    fun getCoreHdrPixelFormatStatus(): Int
    fun getNativeSdrVulkanStatus(): Int
    fun getContentFps(): Double
    fun getContentAspectRatio(): Float
    fun getContentFrameSize(): Long
    fun getSerializationQuirks(): Long
    fun getInputDescriptorRevision(): Long
    fun getInputDescriptors(): Array<String>
    fun getInputPortSnapshot(port: Int, output: IntArray): Unit
    fun resetCheat(): Unit
    fun setCheat(index: Int, enabled: Boolean, code: String): Unit
    fun serializeSRAM(): ByteArray?
    fun serializeState(): ByteArray?
    fun setGameDisplayTiming(refreshRate: Float, strict: Boolean): Unit
    fun onCompanionPointer(port: Int, kind: Int, index: Int, x: Float, y: Float, flags: Int): Unit
    fun setCompanionSensorReadings(port: Int, mask: Int, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float): Unit
    fun onInputSnapshot(port: Int, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float): Unit
    fun setInputPortSnapshot(port: Int, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float): Unit
    fun setNetplayActive(active: Boolean): Unit
    fun renderFrameOnly(): Unit
    fun setNativeVulkanIntegerScalingAuto(enabled: Boolean): Unit
    fun setNativeVulkanIntegerScaling(enabled: Boolean): Unit
    fun setNativeVulkanFullscreenActive(active: Boolean): Unit
    fun setNativeSdrVulkanSurface(surface: Surface?): Unit
    fun setHdrDisplayInfo(peak: Float, average: Float, minimum: Float, eotfs: Int): Unit
    fun probeVulkanSurfaceFormats(surface: Surface): Int
    fun setNativeVulkanCoreSourceTransfer(transfer: Int): Unit
    fun setNativeVulkanOutputTransfer(transfer: Int): Unit
    fun setNativeVulkanShaderPreset(preset: String): Unit
    fun computeRomHash(path: String, console: Int): String?
    fun clearAchievements(): Unit
    fun initAchievements(achievements: List<RetroAchievementDefinition>, console: Int)
}

internal data class RetroAchievementDefinition(val id: Long, val memory: String)

internal object RetroCoreRuntime {
    @Volatile private var selected: RetroFrontendPort? = null
    @Volatile var isolated = false
        private set
    val core: RetroFrontendPort get() = checkNotNull(selected) { "Libretro frontend not initialized" }

    fun bind(module: com.ambient.tv.modules.SystemModuleRuntime.Loaded?) {
        check(selected == null)
        val external = module?.entrypoint
        val frontend = if (external != null) {
            external as? RetroFrontendPort ?: error("Invalid Libretro frontend entrypoint")
        } else {
            val type = Class.forName("com.ambient.tv.ui.games.BundledRetroCore", true, javaClass.classLoader)
            type.getField("INSTANCE").get(null) as RetroFrontendPort
        }
        isolated = external != null
        if (isolated) frontend.bindSharedShader(0L)
        selected = frontend
    }
}

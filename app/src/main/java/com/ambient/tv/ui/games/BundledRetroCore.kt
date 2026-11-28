package com.ambient.tv.ui.games

import android.view.Surface
import com.swordfish.libretrodroid.LibretroDroid

/** Vendor calls stay here; view-specific ownership remains in the GL frontend. */
internal object BundledRetroCore : RetroCorePort {
    override fun getVulkanFrameStatus(): Int = LibretroDroid.getVulkanFrameStatus()
    override fun getVulkanCoreSourceFormat(): Int = LibretroDroid.getVulkanCoreSourceFormat()
    override fun getCoreHdrPixelFormatStatus(): Int = LibretroDroid.getCoreHdrPixelFormatStatus()
    override fun getNativeSdrVulkanStatus(): Int = LibretroDroid.getNativeSdrVulkanStatus()
    override fun getContentFps(): Double = LibretroDroid.getContentFps()
    override fun getContentAspectRatio(): Float = LibretroDroid.getContentAspectRatio()
    override fun getContentFrameSize(): Long = LibretroDroid.getContentFrameSize()
    override fun getSerializationQuirks(): Long = LibretroDroid.getSerializationQuirks()
    override fun getInputDescriptorRevision(): Long = LibretroDroid.getInputDescriptorRevision()
    override fun getInputDescriptors(): Array<String> = LibretroDroid.getInputDescriptors()
    override fun getInputPortSnapshot(port: Int, output: IntArray): Unit { LibretroDroid.getInputPortSnapshot(port, output) }
    override fun resetCheat(): Unit { LibretroDroid.resetCheat() }
    override fun setCheat(index: Int, enabled: Boolean, code: String): Unit { LibretroDroid.setCheat(index, enabled, code) }
    override fun serializeSRAM(): ByteArray? = LibretroDroid.serializeSRAM()
    override fun serializeState(): ByteArray? = LibretroDroid.serializeState()
    override fun setGameDisplayTiming(refreshRate: Float, strict: Boolean): Unit { LibretroDroid.setGameDisplayTiming(refreshRate, strict) }
    override fun onCompanionPointer(port: Int, kind: Int, index: Int, x: Float, y: Float, flags: Int): Unit { LibretroDroid.onCompanionPointer(port, kind, index, x, y, flags) }
    override fun setCompanionSensorReadings(port: Int, mask: Int, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float): Unit { LibretroDroid.setCompanionSensorReadings(port, mask, gx, gy, gz, ax, ay, az) }
    override fun onInputSnapshot(port: Int, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float): Unit { LibretroDroid.onInputSnapshot(port, buttons, lx, ly, rx, ry, lt, rt) }
    override fun setInputPortSnapshot(port: Int, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float): Unit { LibretroDroid.setInputPortSnapshot(port, buttons, lx, ly, rx, ry, lt, rt) }
    override fun setNetplayActive(active: Boolean): Unit { LibretroDroid.setNetplayActive(active) }
    override fun renderFrameOnly(): Unit { LibretroDroid.renderFrameOnly() }
    override fun setNativeVulkanIntegerScalingAuto(enabled: Boolean): Unit { LibretroDroid.setNativeVulkanIntegerScalingAuto(enabled) }
    override fun setNativeVulkanIntegerScaling(enabled: Boolean): Unit { LibretroDroid.setNativeVulkanIntegerScaling(enabled) }
    override fun setNativeVulkanFullscreenActive(active: Boolean): Unit { LibretroDroid.setNativeVulkanFullscreenActive(active) }
    override fun setNativeSdrVulkanSurface(surface: Surface?): Unit { LibretroDroid.setNativeSdrVulkanSurface(surface) }
    override fun setHdrDisplayInfo(peak: Float, average: Float, minimum: Float, eotfs: Int): Unit { LibretroDroid.setHdrDisplayInfo(peak, average, minimum, eotfs) }
    override fun probeVulkanSurfaceFormats(surface: Surface): Int = LibretroDroid.probeVulkanSurfaceFormats(surface)
    override fun setNativeVulkanCoreSourceTransfer(transfer: Int): Unit { LibretroDroid.setNativeVulkanCoreSourceTransfer(transfer) }
    override fun setNativeVulkanOutputTransfer(transfer: Int): Unit { LibretroDroid.setNativeVulkanOutputTransfer(transfer) }
    override fun setNativeVulkanShaderPreset(preset: String): Unit { LibretroDroid.setNativeVulkanShaderPreset(preset) }
    override fun computeRomHash(path: String, console: Int): String? = LibretroDroid.computeRomHash(path, console)
    override fun clearAchievements(): Unit { LibretroDroid.clearAchievements() }
    override fun initAchievements(achievements: List<RetroAchievementDefinition>, console: Int) {
        LibretroDroid.initAchievements(achievements.map {
            com.swordfish.libretrodroid.AchievementDef(it.id, it.memory)
        }.toTypedArray(), console)
    }
}

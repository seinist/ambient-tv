package com.ambient.tv.ui.games

import com.ambient.tv.modules.SystemModule
import com.ambient.tv.modules.SystemModuleAvailability
import com.ambient.tv.modules.SystemModuleHost

/** One process-wide frontend selection; individual libretro cores remain separate. */
internal object LibretroFrontendAvailability {
    @Volatile private var attempted = false
    val isAvailable: Boolean get() = SystemModuleAvailability.state(SystemModule.LIBRETRODROID).usable

    @Synchronized
    fun probe(): Boolean {
        if (attempted) return isAvailable
        var external = false
        var ready = false
        try {
            val module = if (SystemModule.LIBRETRODROID in SystemModuleHost.supported)
                SystemModuleHost.loadSelected(SystemModule.LIBRETRODROID) else null
            external = module != null
            if (module != null) {
                val native = checkNotNull(module.nativeDirectory)
                check(native.resolve("liblibretrodroid.so").isFile)
                System.setProperty("ambient.libretrodroid.native.directory", native.absolutePath)
            } else {
                if (!SystemModuleAvailability.bundled(SystemModule.LIBRETRODROID)) return false
                System.clearProperty("ambient.libretrodroid.native.directory")
            }
            // Read-only atomic status; no core, surface, or audio session is started.
            RetroCoreRuntime.core.getVulkanFrameStatus()
            ready = true
            return true
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Libretro frontend unavailable")
            return false
        } catch (e: LinkageError) {
            timber.log.Timber.w(e, "Libretro frontend linkage failed")
            return false
        } finally {
            attempted = true
            SystemModuleAvailability.reportProbe(SystemModule.LIBRETRODROID, external, ready)
        }
    }
}

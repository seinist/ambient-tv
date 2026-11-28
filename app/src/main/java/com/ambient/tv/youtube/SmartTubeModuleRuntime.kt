package com.ambient.tv.youtube

import com.ambient.tv.modules.SystemModule
import com.ambient.tv.modules.SystemModuleAvailability
import com.ambient.tv.modules.SystemModuleHost

/**
 * Select once on a worker, before any upstream class initializes.
 * Upstream resources and common libraries remain host-owned.
 */
internal object SmartTubeModuleRuntime {
    @Volatile private var loader: ClassLoader? = null
    @Volatile private var attempted = false

    @Synchronized
    fun probe(): Boolean {
        if (attempted) return loader != null
        var external = false
        try {
            if (SystemModule.SMARTTUBE !in SystemModuleHost.supported) return false
            val module = SystemModuleHost.loadSelected(SystemModule.SMARTTUBE)
            external = module != null
            val selected = if (module != null) {
                checkNotNull(module.classLoader)
            } else {
                if (!SystemModuleAvailability.bundled(SystemModule.SMARTTUBE)) return false
                checkNotNull(javaClass.classLoader)
            }
            Class.forName("com.liskovsoft.youtubeapi.service.YouTubeServiceManager", false, selected)
            Class.forName("com.liskovsoft.sharedutils.prefs.GlobalPreferences", false, selected)
            loader = selected
            return true
        } catch (e: Exception) {
            timber.log.Timber.w("SmartTube module probe failed: %s", e.javaClass.simpleName)
            return false
        } catch (e: LinkageError) {
            timber.log.Timber.w("SmartTube module linkage failed: %s", e.javaClass.simpleName)
            return false
        } finally {
            attempted = true
            SystemModuleAvailability.reportProbe(SystemModule.SMARTTUBE, external, loader != null)
        }
    }

    /** UI callers must not trigger disk or native work. */
    val available: Boolean get() = loader != null && SystemModuleAvailability.smartTubeOffered

    fun type(name: String): Class<*> {
        val selected = loader ?: throw IllegalStateException("SmartTube runtime not ready")
        return Class.forName(name, true, selected)
    }

}

package com.ambient.tv.ui.player.backend

import android.content.Context
import com.ambient.tv.modules.SystemModule
import com.ambient.tv.modules.SystemModuleAvailability
import com.ambient.tv.modules.SystemModuleHost
import org.videolan.libvlc.LibVLC

/** Cold-start selection shared by every LibVLC constructor and capability probe. */
object VlcBackendAvailability {
    @Volatile private var attempted = false
    val isAvailable: Boolean
        get() = SystemModuleAvailability.state(SystemModule.VLC).usable

    /** Run on IO before offering the renderer. No media or network session is opened. */
    @Synchronized
    fun probe(context: Context): Boolean {
        if (attempted) return isAvailable
        var external = false
        var ready = false
        try {
            val module = if (SystemModule.VLC in SystemModuleHost.supported)
                SystemModuleHost.loadSelected(SystemModule.VLC) else null
            external = module != null
            if (module != null) {
                val native = checkNotNull(module.nativeDirectory)
                check(native.resolve("libvlc.so").isFile && native.resolve("libvlcjni.so").isFile)
                // The patched Java loader uses precisely the already verified,
                // host-classloader-owned libraries. No APK copy may be loaded.
                System.setProperty("ambient.vlc.native.directory", native.absolutePath)
            } else {
                if (!SystemModuleAvailability.bundled(SystemModule.VLC)) return false
                System.clearProperty("ambient.vlc.native.directory")
            }
            val instance = LibVLC(context.applicationContext, arrayListOf("--verbose=0"))
            instance.release()
            ready = true
            return true
        } catch (e: Exception) {
            timber.log.Timber.w(e, "VLC initialization failed")
            return false
        } catch (e: LinkageError) {
            timber.log.Timber.w(e, "VLC native linkage failed")
            return false
        } finally {
            attempted = true
            SystemModuleAvailability.reportProbe(SystemModule.VLC, external, ready)
        }
    }

    /** Defensive gate for direct player construction, including diagnostics. */
    internal fun requireReady(context: Context) {
        check(probe(context)) { "VLC runtime unavailable" }
    }
}

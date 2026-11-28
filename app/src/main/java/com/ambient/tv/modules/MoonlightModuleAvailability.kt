package com.ambient.tv.modules

import com.ambient.tv.BuildConfig

/** Process-wide first JNI load. No pairing, socket, decoder or audio session is started here. */
internal object MoonlightModuleAvailability {
    @Volatile private var attempted = false
    val isAvailable: Boolean get() = SystemModuleAvailability.state(SystemModule.MOONLIGHT).usable

    @Synchronized
    fun probe(): Boolean {
        if (attempted) return isAvailable
        var external = false
        var ready = false
        try {
            if (SystemModule.MOONLIGHT !in SystemModuleHost.supported) return false
            val module = SystemModuleHost.loadSelected(SystemModule.MOONLIGHT)
            external = module != null
            if (module != null) {
                val native = checkNotNull(module.nativeDirectory)
                check(native.resolve("libmoonlight-core.so").isFile)
                System.setProperty("ambient.moonlight.native.directory", native.absolutePath)
            } else {
                if (!BuildConfig.MOONLIGHT_MODULE_BUNDLED) return false
                System.clearProperty("ambient.moonlight.native.directory")
            }
            // MoonBridge's static initializer performs the pinned JNI init.
            // A successful class initialization is the cold-start linkage probe.
            Class.forName("com.limelight.nvstream.jni.MoonBridge", true, javaClass.classLoader)
            ready = true
            return true
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Moonlight module unavailable")
            return false
        } catch (e: LinkageError) {
            timber.log.Timber.w(e, "Moonlight module linkage failed")
            return false
        } finally {
            attempted = true
            SystemModuleAvailability.reportProbe(SystemModule.MOONLIGHT, external, ready)
        }
    }
}

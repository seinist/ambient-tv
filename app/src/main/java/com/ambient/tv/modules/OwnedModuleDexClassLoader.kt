package com.ambient.tv.modules

import dalvik.system.DexClassLoader
import java.io.File

/** Only explicitly module-owned classes may resolve from an adapter DEX. */
internal class ModuleDexOwnership(
    private val prefixes: Set<String>,
    private val classes: Set<String>,
) {
    fun owns(name: String): Boolean =
        prefixes.any { name.startsWith(it) } ||
            classes.any { name == it || name.startsWith(it + "$") }

    companion object {
        val MONOGRAM = ModuleDexOwnership(
            setOf("org.monogram.mtproto.", "uniffi.monogram_mtproto.", "com.sun.jna.", "org.rustls."),
            setOf(
                "com.ambient.tv.data.telegram.MonogramModuleAdapter",
                "com.ambient.tv.data.telegram.MonogramNativeBackend",
                "com.ambient.tv.data.telegram.MonogramNativeBackendKt",
                "com.ambient.tv.data.telegram.AmbientMonogramJni",
                "com.ambient.tv.data.telegram.AmbientMonogramJniFailure",
            ),
        )
    }
}

/**
 * No bundled fallback for module-owned classes. Conversely host DTOs, ports
 * and runtime libraries must resolve from the host, never from a second DEX
 * copy. This is linkage isolation, not a security sandbox.
 */
internal class OwnedModuleDexClassLoader(
    dex: List<File>,
    nativeDirectory: File,
    private val host: ClassLoader,
    private val ownership: ModuleDexOwnership,
) : DexClassLoader(dex.joinToString(File.pathSeparator) { it.absolutePath },
    null, nativeDirectory.absolutePath, host) {
    @Synchronized
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!ownership.owns(name)) return host.loadClass(name)
        val type = findLoadedClass(name) ?: findClass(name)
        if (resolve) resolveClass(type)
        return type
    }
}

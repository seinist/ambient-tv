package com.ambient.tv.modules

import dalvik.system.DexClassLoader
import java.io.File

/** Only explicitly module-owned classes may resolve from an adapter DEX. */
internal class ModuleDexOwnership(
    private val prefixes: Set<String>,
    private val classes: Set<String>,
    private val required: Set<String> = emptySet(),
    private val requiredPrefixes: Set<String> = emptySet(),
    private val hostResourceClasses: Boolean = false,
) {
    fun owns(name: String): Boolean =
        !(hostResourceClasses && (name.substringAfterLast('.') == "R" ||
            name.substringAfterLast('.').startsWith("R$"))) &&
        (prefixes.any { name.startsWith(it) } ||
            classes.any { name == it || name.startsWith(it + "$") })

    fun accepts(definitions: Set<String>): Boolean = definitions.containsAll(required) &&
        definitions.all(::owns) && requiredPrefixes.all { prefix -> definitions.any { it.startsWith(prefix) } }

    fun acceptsReferences(definitions: Set<String>, references: Set<String>): Boolean {
        val foreign = listOf(SMARTTUBE, LIBRETRODROID, VLC, MONOGRAM).filter { it !== this }
        return references.none { name ->
            (owns(name) && name !in definitions) || foreign.any { it.owns(name) }
        }
    }

    companion object {
        val SMARTTUBE = ModuleDexOwnership(
            setOf("com.liskovsoft.", "com.eclipsesource.v8.",
                "com.jayway.jsonpath.",
                "net.minidev.",
                "com.grack.nanojson.",
                "com.florianingerl.util.regex.",
                "com.jakewharton.disklrucache.",
            ),
            setOf("com.ambient.tv.youtube.SmartTubeModuleAdapter"),
            required = setOf(
                "com.ambient.tv.youtube.SmartTubeModuleAdapter",
                "com.liskovsoft.youtubeapi.service.YouTubeServiceManager",
                "com.liskovsoft.sharedutils.prefs.GlobalPreferences",
                "com.liskovsoft.youtubeapi.lounge.LoungeService",
                "com.eclipsesource.v8.V8",
                "com.jayway.jsonpath.JsonPath",
                "net.minidev.json.JSONValue",
                "com.grack.nanojson.JsonParser",
                "com.florianingerl.util.regex.Pattern",
                "com.jakewharton.disklrucache.DiskLruCache",
            ),
            hostResourceClasses = true,
        )
        val LIBRETRODROID = ModuleDexOwnership(
            setOf("com.swordfish.libretrodroid."),
            setOf("com.ambient.tv.ui.games.LibretroModuleAdapter",
                "com.ambient.tv.ui.games.BundledRetroCore",
                "com.ambient.tv.ui.games.BundledRetroView",
                "com.ambient.tv.ui.games.BundledRetroContentAdapterKt",
                "com.ambient.tv.ui.games.BundledRetroShaderAdapterKt"),
            required = setOf("com.ambient.tv.ui.games.LibretroModuleAdapter",
                "com.ambient.tv.ui.games.BundledRetroCore",
                "com.ambient.tv.ui.games.BundledRetroView",
                "com.swordfish.libretrodroid.LibretroDroid",
                "com.swordfish.libretrodroid.GLRetroView",
                "com.swordfish.libretrodroid.GLRetroViewData"),
            hostResourceClasses = true,
        )
        val VLC = ModuleDexOwnership(
            setOf("org.videolan.libvlc."),
            setOf("com.ambient.tv.ui.player.backend.VlcModuleAdapter",
                "com.ambient.tv.ui.player.backend.VlcNativeEngine",
                "com.ambient.tv.ui.player.backend.VlcTrackAccess"),
            required = setOf("com.ambient.tv.ui.player.backend.VlcModuleAdapter",
                "com.ambient.tv.ui.player.backend.VlcNativeEngine",
                "com.ambient.tv.ui.player.backend.VlcTrackAccess",
                "org.videolan.libvlc.LibVLC", "org.videolan.libvlc.MediaPlayer",
                "org.videolan.libvlc.Media", "org.videolan.libvlc.AmbientNativeLibraryLoader"),
            hostResourceClasses = true,
        )
        val MONOGRAM = ModuleDexOwnership(
            setOf("org.monogram.mtproto.", "uniffi.monogram_mtproto.", "com.sun.jna.", "org.rustls."),
            setOf(
                "com.ambient.tv.data.telegram.MonogramModuleAdapter",
                "com.ambient.tv.data.telegram.MonogramNativeBackend",
                "com.ambient.tv.data.telegram.MonogramNativeBackendKt",
                "com.ambient.tv.data.telegram.AmbientMonogramJni",
                "com.ambient.tv.data.telegram.AmbientMonogramJniFailure",
            ),
            required = setOf(
                "com.ambient.tv.data.telegram.MonogramModuleAdapter",
                "com.ambient.tv.data.telegram.MonogramNativeBackend",
                "com.ambient.tv.data.telegram.AmbientMonogramJni",
                "org.monogram.mtproto.MtprotoNativeLoader",
                "com.sun.jna.Native",
            ),
            requiredPrefixes = setOf("uniffi.monogram_mtproto.", "org.rustls."),
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

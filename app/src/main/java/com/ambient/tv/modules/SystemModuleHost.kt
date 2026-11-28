package com.ambient.tv.modules

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.ambient.tv.BuildConfig
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Single process owner. Initial registration does not touch disk or load code.
 * The first actual module operation runs on a worker and acquires a process
 * lifetime lease; only an exclusive lease may reclaim old executable trees.
 */
internal object SystemModuleHost {
    const val HOST_API = 1
    @Volatile private var app: Context? = null

    // Only engines with a migrated first-load path are exposed. More contracts
    // are added as their actual adapter/build/release pipeline is migrated.
    private val contracts = mapOf(
        SystemModule.SVP to SystemModuleRuntime.Contract(
            nativeRoots = setOf("libambientsvp.so"),
            bundledOverrides = setOf("libambientsvp.so"),
            hostBindingSha256 = BuildConfig.SVP_MODULE_BINDING_SHA256,
            requireSoname = true,
        ),
        SystemModule.SHADERS to SystemModuleRuntime.Contract(
            nativeRoots = setOf("liblibrashader_capi.so"),
            bundledOverrides = setOf("liblibrashader_capi.so"),
            hostBindingSha256 = BuildConfig.SHADER_MODULE_BINDING_SHA256,
            requireSoname = true,
            dexAlternative = if (BuildConfig.SHADER_RUNTIME_BUNDLED)
                SystemModuleRuntime.Contract(assetOnly = true) else null,
        ),
        SystemModule.SPOTIFY to SystemModuleRuntime.Contract(
            nativeRoots = setOf("libspotify_receiver_core.so"),
            hostLibraries = setOf("libc++_shared.so"),
            bundledOverrides = setOf("libspotify_receiver_core.so"),
        ),
        SystemModule.MPV to SystemModuleRuntime.Contract(
            nativeRoots = setOf("libmpv.so", "libambientmpv.so"),
            hostLibraries = setOf(
                "libc++_shared.so", "libavcodec.so", "libavdevice.so", "libavfilter.so",
                "libavformat.so", "libavutil.so", "libswresample.so", "libswscale.so",
                "libpostproc.so", "libass.so", "libplacebo.so", "libfreetype.so",
                "libharfbuzz.so", "libfribidi.so", "libssl.so", "libcrypto.so",
            ),
            bundledOverrides = setOf("libmpv.so", "libambientmpv.so"),
        ),
        SystemModule.PROJECTM to SystemModuleRuntime.Contract(
            nativeRoots = setOf("libprojectM-4.so", "libambientprojectm.so"),
            hostLibraries = setOf("libc++_shared.so"),
            bundledOverrides = setOf("libprojectM-4.so", "libambientprojectm.so"),
        ),
    ) + mapOf(SystemModule.MONOGRAM to monogramContract()) + smartTubeContracts() + vlcContracts() + libretroContracts() + moonlightContracts()

    // During this migration the host still supplies the pinned Android resource
    // and shared-library graph. A build without those wrappers cannot load this format.
    private fun smartTubeContracts(): Map<SystemModule, SystemModuleRuntime.Contract> =
        if (BuildConfig.SMARTTUBE_HOST_RESOURCES) mapOf(
            SystemModule.SMARTTUBE to SystemModuleRuntime.Contract(
                entryClass = "com.ambient.tv.youtube.SmartTubeModuleAdapter",
                hostBindingSha256 = BuildConfig.SMARTTUBE_ADAPTER_SHA256,
                dexOwnership = ModuleDexOwnership.SMARTTUBE,
                dexNativeRoots = setOf("libj2v8.so"),
            )
        ) else emptyMap()
    
    private fun vlcContracts(): Map<SystemModule, SystemModuleRuntime.Contract> {
        if (BuildConfig.VLC_MODULE_BINDING_SHA256.isBlank()) return emptyMap()
        val dex = SystemModuleRuntime.Contract(
            entryClass = "com.ambient.tv.ui.player.backend.VlcModuleAdapter",
            hostBindingSha256 = BuildConfig.VLC_DEX_BINDING_SHA256,
            dexOwnership = ModuleDexOwnership.VLC,
            dexNativeRoots = setOf("libvlc.so", "libvlcjni.so", "libc++_shared.so"),
        )
        val native = SystemModuleRuntime.Contract(
            nativeRoots = setOf("libvlc.so", "libvlcjni.so"),
            hostLibraries = setOf("libc++_shared.so"),
            bundledOverrides = setOf("libvlc.so", "libvlcjni.so"),
            hostBindingSha256 = BuildConfig.VLC_MODULE_BINDING_SHA256,
        )
        // Opt-in DEX builds cannot activate legacy native-only packages because
        // their Java bindings were stripped. Normal builds retain the old format.
        return mapOf(SystemModule.VLC to if (BuildConfig.VLC_DEX_ENABLED) dex else native)
    }

    private fun libretroContracts(): Map<SystemModule, SystemModuleRuntime.Contract> {
        if (BuildConfig.LIBRETRODROID_MODULE_BINDING_SHA256.isBlank()) return emptyMap()
        val dex = SystemModuleRuntime.Contract(
            entryClass = "com.ambient.tv.ui.games.LibretroModuleAdapter",
            hostBindingSha256 = BuildConfig.LIBRETRODROID_DEX_BINDING_SHA256,
            dexOwnership = ModuleDexOwnership.LIBRETRODROID,
            dexNativeRoots = setOf("liblibretrodroid.so"),
            dexOptionalNativeRoots = setOf("libc++_shared.so", "libadrenotools.so", "liboboe.so"),
        )
        val native = SystemModuleRuntime.Contract(
            nativeRoots = setOf("liblibretrodroid.so"),
            bundledOverrides = setOf("liblibretrodroid.so"),
            hostLibraries = setOf("libc++_shared.so", "libadrenotools.so", "liboboe.so"),
            hostBindingSha256 = BuildConfig.LIBRETRODROID_MODULE_BINDING_SHA256,
        )
        return mapOf(SystemModule.LIBRETRODROID to if (BuildConfig.LIBRETRODROID_DEX_ENABLED) dex else native)
    }

    private fun moonlightContracts(): Map<SystemModule, SystemModuleRuntime.Contract> =
        if (BuildConfig.MOONLIGHT_MODULE_BINDING_SHA256.isNotBlank()) mapOf(
            SystemModule.MOONLIGHT to SystemModuleRuntime.Contract(
                nativeRoots = setOf("libmoonlight-core.so"),
                bundledOverrides = setOf("libmoonlight-core.so"),
                hostBindingSha256 = BuildConfig.MOONLIGHT_MODULE_BINDING_SHA256,
            )
        ) else emptyMap()

    private fun monogramContract(): SystemModuleRuntime.Contract {
        val dex = SystemModuleRuntime.Contract(
            entryClass = "com.ambient.tv.data.telegram.MonogramModuleAdapter",
            hostBindingSha256 = BuildConfig.MONOGRAM_ADAPTER_SHA256,
            dexOwnership = ModuleDexOwnership.MONOGRAM,
            dexNativeRoots = setOf("libmonogram_mtproto.so", "libjnidispatch.so"),
        )
        return if (BuildConfig.MONOGRAM_NATIVE && BuildConfig.MONOGRAM_BINDING_SHA256.isNotBlank())
            SystemModuleRuntime.Contract(
                nativeRoots = setOf("libmonogram_mtproto.so"),
                hostLibraries = setOf("libc++_shared.so"),
                bundledOverrides = setOf("libmonogram_mtproto.so"),
                hostBindingSha256 = BuildConfig.MONOGRAM_BINDING_SHA256,
                dexAlternative = dex,
            )
        else dex
    }
    val supported: Set<SystemModule> get() = contracts.keys

    fun initialize(context: Context) {
        synchronized(this) {
            if (app == null) app = context.applicationContext
        }
    }

    private data class Session(
        val file: RandomAccessFile,
        val lease: FileLock,
        val store: SystemModuleStore,
        val runtime: SystemModuleRuntime,
    )
    private val session: Session by lazy {
        val context = checkNotNull(app) { "System module host not initialized" }
        val root = File(context.noBackupFilesDir, "system-modules")
        if (!root.mkdirs() && !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw IOException("Cannot open module host storage")
        val processAbi = processAbi() ?: throw IOException("Unsupported process ABI")
        val store = SystemModuleStore(File(root, "packages"), BuildConfig.CORES_PUBLIC_KEY,
            processAbi, HOST_API, Build.VERSION.SDK_INT)
        val runtimeRoot = File(root, "runtime")
        val file = RandomAccessFile(File(root, "process.lease"), "rw")
        try {
            // No other process is allowed to be using a package during GC.
            // If another process already holds a shared lease, skip GC safely.
            file.channel.tryLock()?.use {
                runCatching {
                    if (runtimeRoot.exists() && !Files.isSymbolicLink(runtimeRoot.toPath()))
                        runtimeRoot.deleteRecursively()
                    SystemModule.entries.forEach(store::collectUnused)
                }.onFailure { timber.log.Timber.w(it, "Module reclamation deferred") }
            }
            val lease = file.channel.lock(0L, Long.MAX_VALUE, true)
            try {
                val runtime = SystemModuleRuntime(context, store,
                    Os.sysconf(OsConstants._SC_PAGESIZE).takeIf { it > 0 } ?: 16384L,
                    contracts, runtimeRoot)
                Session(file, lease, store, runtime)
            } catch (e: Throwable) {
                lease.release()
                throw e
            }
        } catch (e: Throwable) {
            file.close()
            throw e
        }
    }

    private fun processAbi(): String? {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }

    /** These functions are blocking; callers must dispatch to a worker. */
    fun selected(module: SystemModule): SystemModuleBuild? {
        return try {
            val build = if (app == null || BuildConfig.CORES_PUBLIC_KEY.isBlank()) null
                else session.store.currentForSelection(module)?.build
            SystemModuleAvailability.observeSelection(module, build?.version)
            build
        } catch (e: Exception) {
            SystemModuleAvailability.reportFailure(module)
            throw e
        }
    }

    val downloadsEnabled: Boolean get() = BuildConfig.CORES_PUBLIC_KEY.isNotBlank()

    @Synchronized
    fun installLatest(module: SystemModule, transfer: SystemModuleDownload = SystemModuleDownload()): SystemModuleBuild? {
        require(module in supported && downloadsEnabled)
        val transport = SystemModuleTransport(transfer)
        val old = session.store.selectedMetadata(module)
        val result = transport.installLatest(session.store, module, validateCandidate = {
            session.runtime.validateCandidate(module, it)
        }, compatible = {
            transfer.check()
            session.runtime.matchesHost(module, it)
        })?.build
        if (result != null && (result != old || SystemModuleAvailability.state(module).runtime == ModuleRuntimeState.FAILED))
            markChanged(module, old, result)
        return result
    }

    @Synchronized
    fun install(module: SystemModule, tag: String): SystemModuleBuild? =
        install(module, tag, SystemModuleTransport())

    private fun install(module: SystemModule, tag: String, transport: SystemModuleTransport): SystemModuleBuild? {
        require(module in supported && downloadsEnabled)
        val old = session.store.selectedMetadata(module)
        val result = transport.install(session.store, module, tag, validateCandidate = {
            session.runtime.validateCandidate(module, it)
        }, compatible = { session.runtime.matchesHost(module, it) })?.build
        if (result != null && (result != old || SystemModuleAvailability.state(module).runtime == ModuleRuntimeState.FAILED))
            markChanged(module, old, result)
        return result
    }

    @Synchronized
    fun remove(module: SystemModule) {
        require(module in supported && downloadsEnabled)
        val old = session.store.selectedMetadata(module)
        session.store.remove(module)
        if (old != null) markChanged(module, old, null)
    }

    @Synchronized
    fun rollback(module: SystemModule): SystemModuleBuild? {
        require(module in supported && downloadsEnabled)
        val old = session.store.selectedMetadata(module)
        val result = session.store.rollback(module) { session.runtime.validateCandidate(module, it) }?.build
        if (result != null && result != old) markChanged(module, old, result)
        return result
    }

    private fun markChanged(module: SystemModule, old: SystemModuleBuild?, new: SystemModuleBuild?) {
        // If the previous selection has not been loaded, never activate the
        // new version mid-process. Bundled/no-payload selection remains safe.
        if (module !in processSelections) {
            processSelections[module] = if (old == null) Result.success(null)
                else Result.failure(SystemModuleTrustException("Module selection changed; restart required"))
        }
        SystemModuleAvailability.selectionChanged(module, new?.version)
    }

    // A missing or authenticated-but-incompatible selection may fall back
    // before native execution. Corrupt packages, failed activation and linkage
    // never fall back: native constructors may already have executed.
    private val processSelections = mutableMapOf<SystemModule, Result<SystemModuleRuntime.Loaded?>>()
    @Synchronized
    fun loadSelected(module: SystemModule): SystemModuleRuntime.Loaded? {
        return processSelections.getOrPut(module) {
            runCatching {
                val candidate = selected(module)
                if (candidate == null) null
                else if (!session.store.isHostCompatible(candidate) || !session.runtime.matchesHost(module, candidate)) {
                    SystemModuleAvailability.reportIncompatibleSelection(module, candidate.version)
                    if (SystemModuleAvailability.bundled(module)) null
                    else throw SystemModuleTrustException("Downloaded module is incompatible with installed APK")
                } else session.runtime.load(module)
            }
        }.onFailure { SystemModuleAvailability.reportFailure(module) }.getOrThrow()
    }
}

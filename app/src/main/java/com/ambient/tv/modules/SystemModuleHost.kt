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
        SystemModule.SHADERS to SystemModuleRuntime.Contract(assetOnly = true),
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
    )
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
                else session.store.current(module)?.build
            SystemModuleAvailability.observeSelection(module, build?.version)
            build
        } catch (e: Exception) {
            SystemModuleAvailability.reportFailure(module)
            throw e
        }
    }

    val downloadsEnabled: Boolean get() = BuildConfig.CORES_PUBLIC_KEY.isNotBlank()

    fun installLatest(module: SystemModule): SystemModuleBuild? {
        require(module in supported && downloadsEnabled)
        val transport = SystemModuleTransport()
        val tag = transport.latestTag(module) ?: return null
        return install(module, tag)
    }

    @Synchronized
    fun install(module: SystemModule, tag: String): SystemModuleBuild? {
        require(module in supported && downloadsEnabled)
        val old = session.store.selectedMetadata(module)
        val result = SystemModuleTransport().install(session.store, module, tag, validateCandidate = {
            session.runtime.validateCandidate(module, it)
        })?.build
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
        val result = session.store.rollback(module)?.build
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

    // Only absent packages fall back. Do not mix bundled and downloaded JNI
    // after an override fails: native constructors may already have executed.
    private val processSelections = mutableMapOf<SystemModule, Result<SystemModuleRuntime.Loaded?>>()
    @Synchronized
    fun loadSelected(module: SystemModule): SystemModuleRuntime.Loaded? {
        return processSelections.getOrPut(module) {
            runCatching {
                if (selected(module) == null) null else session.runtime.load(module)
            }
        }.onFailure { SystemModuleAvailability.reportFailure(module) }.getOrThrow()
    }
}

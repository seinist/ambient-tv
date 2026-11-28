package com.ambient.tv.modules

import com.ambient.tv.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Installation and process availability are independent; installing never hot-enables an engine. */
internal enum class ModuleRuntimeState { MISSING, BUNDLED_UNVERIFIED, EXTERNAL_UNVERIFIED, ACTIVE_BUNDLED, ACTIVE_EXTERNAL, FAILED }

internal data class ModuleAvailability(
    val runtime: ModuleRuntimeState,
    val installedVersion: Long? = null,
    val restartRequired: Boolean = false,
) {
    val usable: Boolean get() = runtime == ModuleRuntimeState.ACTIVE_BUNDLED ||
        runtime == ModuleRuntimeState.ACTIVE_EXTERNAL
}

/**
 * UI-safe snapshot: no disk, JNI, networking or class loading from getters.
 * The bundled predicates are migration bridges, not evidence that an engine
 * initialized successfully. Existing engine probes remain authoritative.
 */
internal object SystemModuleAvailability {
    fun bundled(module: SystemModule): Boolean = when (module) {
        SystemModule.LIBRETRODROID -> BuildConfig.LIBRETRODROID_BUNDLED
        SystemModule.VLC -> BuildConfig.VLC_BUNDLED
        SystemModule.SMARTTUBE -> BuildConfig.SMARTTUBE_BUNDLED
        SystemModule.MONOGRAM -> BuildConfig.MONOGRAM_NATIVE && BuildConfig.MODULE_PACKAGING == "full"
        SystemModule.SPOTIFY -> BuildConfig.SPOTIFY_ENABLED && BuildConfig.MODULE_PACKAGING == "full"
        SystemModule.PROJECTM, SystemModule.MPV, SystemModule.SVP -> BuildConfig.MODULE_PACKAGING == "full"
        SystemModule.SHADERS -> BuildConfig.SHADER_RUNTIME_BUNDLED
        SystemModule.MOONLIGHT -> BuildConfig.MOONLIGHT_MODULE_BUNDLED
        // These engines are not yet removed from the base build.
        else -> true
    }

    private val registry = ModuleAvailabilityRegistry(::bundled)
    val states get() = registry.states
    fun state(module: SystemModule) = registry.state(module)
    fun observeSelection(module: SystemModule, version: Long?) = registry.observeSelection(module, version)
    fun selectionChanged(module: SystemModule, version: Long?) = registry.selectionChanged(module, version)
    fun reportProbe(module: SystemModule, external: Boolean, success: Boolean) {
        val offered = state(module).usable
        registry.reportProbe(module, external, success)
        if (module in setOf(SystemModule.SPOTIFY, SystemModule.MONOGRAM, SystemModule.SMARTTUBE, SystemModule.LIBRETRODROID, SystemModule.MOONLIGHT) && offered != state(module).usable)
            com.ambient.tv.data.local.LoginStateSignal.changed()
    }
    fun reportFailure(module: SystemModule) {
        val offered = state(module).usable
        registry.reportFailure(module)
        if (module in setOf(SystemModule.SPOTIFY, SystemModule.MONOGRAM, SystemModule.SMARTTUBE, SystemModule.LIBRETRODROID, SystemModule.MOONLIGHT) && offered != state(module).usable)
            com.ambient.tv.data.local.LoginStateSignal.changed()
    }

    /** Legacy providers retain their compile-time behavior until their loader is migrated. */
    val smartTubeOffered: Boolean get() = state(SystemModule.SMARTTUBE).usable
    val monogramOffered: Boolean get() = state(SystemModule.MONOGRAM).usable
    val spotifyOffered: Boolean get() = state(SystemModule.SPOTIFY).usable
}

internal class ModuleAvailabilityRegistry(private val bundled: (SystemModule) -> Boolean) {
    private val mutable = MutableStateFlow(SystemModule.entries.associateWith {
        ModuleAvailability(if (bundled(it)) ModuleRuntimeState.BUNDLED_UNVERIFIED else ModuleRuntimeState.MISSING)
    })
    val states: StateFlow<Map<SystemModule, ModuleAvailability>> = mutable.asStateFlow()

    fun state(module: SystemModule): ModuleAvailability = mutable.value.getValue(module)

    fun observeSelection(module: SystemModule, version: Long?) = change(module) { previous ->
        if (previous.restartRequired) previous.copy(installedVersion = version)
        else previous.copy(
            installedVersion = version,
            runtime = when (previous.runtime) {
                ModuleRuntimeState.ACTIVE_BUNDLED, ModuleRuntimeState.ACTIVE_EXTERNAL, ModuleRuntimeState.FAILED -> previous.runtime
                else -> if (version != null) ModuleRuntimeState.EXTERNAL_UNVERIFIED
                    else if (bundled(module)) ModuleRuntimeState.BUNDLED_UNVERIFIED else ModuleRuntimeState.MISSING
            },
        )
    }

    fun selectionChanged(module: SystemModule, version: Long?) = change(module) {
        it.copy(installedVersion = version, restartRequired = true)
    }

    fun reportProbe(module: SystemModule, external: Boolean, success: Boolean) = change(module) {
        it.copy(runtime = when {
            success && external -> ModuleRuntimeState.ACTIVE_EXTERNAL
            success -> ModuleRuntimeState.ACTIVE_BUNDLED
            it.runtime == ModuleRuntimeState.FAILED -> ModuleRuntimeState.FAILED
            !external && !bundled(module) && (it.installedVersion == null || it.restartRequired) -> ModuleRuntimeState.MISSING
            else -> ModuleRuntimeState.FAILED
        })
    }

    fun reportFailure(module: SystemModule) = change(module) { it.copy(runtime = ModuleRuntimeState.FAILED) }

    private fun change(module: SystemModule, transform: (ModuleAvailability) -> ModuleAvailability) {
        mutable.update { current -> current + (module to transform(current.getValue(module))) }
    }
}

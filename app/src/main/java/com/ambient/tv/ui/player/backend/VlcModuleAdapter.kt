package com.ambient.tv.ui.player.backend

import android.content.Context
import com.ambient.tv.modules.SystemModuleEntrypoint
import java.io.File

/** Owned by the signed VLC DEX; native loading occurs from this classloader. */
internal class VlcModuleAdapter : SystemModuleEntrypoint, VlcEnginePort by VlcNativeEngine {
    override fun initialize(context: Context, assets: File, dependencies: Map<String, SystemModuleEntrypoint>) {
        check(dependencies.isEmpty())
        check(javaClass.classLoader !== VlcEnginePort::class.java.classLoader)
        // The private DEX namespace resolves both VLC libraries and its private
        // C++ runtime. A previous host absolute-path override is never reused.
        System.clearProperty("ambient.vlc.native.directory")
        val instance = createLibrary(context.applicationContext, arrayListOf("--verbose=0"))
        instance.release()
    }
}

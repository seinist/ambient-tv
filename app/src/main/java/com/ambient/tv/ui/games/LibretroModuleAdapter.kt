package com.ambient.tv.ui.games

import android.content.Context
import com.ambient.tv.modules.SystemModuleEntrypoint
import java.io.File

/** Native initializer and all frontend classes belong to this DEX loader. */
internal class LibretroModuleAdapter : SystemModuleEntrypoint, RetroFrontendPort by BundledRetroCore {
    override fun initialize(context: Context, assets: File, dependencies: Map<String, SystemModuleEntrypoint>) {
        check(dependencies.isEmpty())
        check(javaClass.classLoader !== RetroFrontendPort::class.java.classLoader)
        System.clearProperty("ambient.libretrodroid.native.directory")
        getVulkanFrameStatus()
    }
}

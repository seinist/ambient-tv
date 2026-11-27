package com.ambient.tv.data.telegram

import android.content.Context
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.modules.SystemModuleEntrypoint
import java.io.File

/**
 * Entry class of the downloadable adapter. The Full build compiles the same
 * source to generate the adapter candidate, but only the external classloader
 * is allowed to instantiate it.
 */
class MonogramModuleAdapter : MonogramModuleFactory {
    private var initialized = false

    override fun initialize(context: Context, assets: File, dependencies: Map<String, SystemModuleEntrypoint>) {
        check(!initialized && dependencies.isEmpty())
        val lib = File(checkNotNull(assets.parentFile), "lib")
        val mtproto = File(lib, "libmonogram_mtproto.so")
        val dispatch = File(lib, "libjnidispatch.so")
        check(mtproto.isFile && dispatch.isFile)
        // These JNA settings apply before the child-owned com.sun.jna.Native
        // class initializes. Never unpack or find an unrelated system copy.
        System.setProperty("jna.boot.library.path", lib.absolutePath)
        System.setProperty("jna.nosys", "true")
        System.setProperty("jna.nounpack", "true")
        System.setProperty("uniffi.component.monogram_mtproto.libraryOverride", mtproto.absolutePath)
        initialized = true
    }

    override fun create(context: Context, tokenStore: TokenStore): MonogramAuthClientPort {
        check(initialized)
        return MonogramNativeBackend(context, tokenStore)
    }
}

package com.ambient.tv.youtube

import android.content.Context
import com.ambient.tv.modules.SystemModuleEntrypoint
import java.io.File

/** Executed by the owned DEX loader; no upstream types cross this boundary. */
class SmartTubeModuleAdapter : SystemModuleEntrypoint {
    override fun initialize(context: Context, assets: File, dependencies: Map<String, SystemModuleEntrypoint>) {
        check(dependencies.isEmpty())
        val loader = checkNotNull(javaClass.classLoader)
        val library = File(checkNotNull(assets.parentFile), "lib/libj2v8.so")
        check(library.isFile)
        // The JavaScript runtime must resolve through its own child loader.
        // Do not invoke System.load from the host or load an APK copy first.
        for (name in listOf(
            "com.liskovsoft.youtubeapi.service.YouTubeServiceManager",
            "com.liskovsoft.sharedutils.prefs.GlobalPreferences",
            "com.liskovsoft.youtubeapi.lounge.LoungeService",
            "com.eclipsesource.v8.V8",
        )) check(Class.forName(name, false, loader).classLoader === loader)
    }
}

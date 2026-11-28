package com.ambient.tv.connectivity

import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.local.AppPreferences

/** Retro games are independent of the Files menu toggle, but share its NAS endpoints. */
internal fun AppPreferences.activeConnectivityFileSources(): List<FileSource> =
    synchronized(fileSourceLock) {
        val gameSources = gameLibraryFolders.mapTo(hashSetOf()) { it.sourceId }
        val browserEnabled = filesEnabled
        fileSources.filter { it.id in gameSources || (browserEnabled && it.showInFiles) }
    }

package com.ambient.tv.ui.games

import com.ambient.tv.games.GameVirtualFile
import com.swordfish.libretrodroid.VirtualFile

/** No FD duplication or content copy. GLRetroView remains the detach owner. */
internal fun GameVirtualFile.toLibretroVirtualFile(): VirtualFile =
    VirtualFile(path, fileDescriptor)

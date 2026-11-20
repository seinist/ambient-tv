package com.ambient.tv.games

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where games keep their data: BIOS files, in-game saves (SRAM) and save states. */
@Singleton
class GameStorage @Inject constructor(@ApplicationContext private val context: Context) {
    private val root: File get() = File(context.filesDir, "games")

    /** RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: BIOS and core data. */
    val systemDirectory: File get() = File(root, "system").also { it.mkdirs() }

    /** RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY for cores that write their own files. */
    val savesDirectory: File get() = File(root, "saves").also { it.mkdirs() }

    /** Save states of one game (see GameStateStore). */
    fun statesDirectory(game: PreparedGame): File = File(root, "states/${game.saveKey}").also { it.mkdirs() }

    fun sramFile(game: PreparedGame): File = File(savesDirectory, "${game.saveKey}.srm")

    fun read(file: File): ByteArray? = runCatching { file.takeIf { it.isFile && it.length() > 0 }?.readBytes() }.getOrNull()

    /** Writes atomically, so a crash while saving never leaves half a save behind. */
    fun write(file: File, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, file.name + ".partial")
        partial.writeBytes(bytes)
        if (!partial.renameTo(file)) {
            file.delete()
            partial.renameTo(file)
        }
    }
}

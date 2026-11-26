package com.ambient.tv.games

import android.content.Context
import androidx.core.util.AtomicFile
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

    /**
     * A RomM game keeps its data apart from the local games, per server: firmware comes from the
     * server's platform, saves and states are mirrored to the server (see RommGameData).
     */
    fun remoteRoot(serverKey: String): File {
        require(serverKey.matches(Regex("[0-9a-f]{12}"))) { "Invalid server key" }
        return File(context.filesDir, "romm/$serverKey")
    }

    /** RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY for [game]: the shared one, or its RomM server's. */
    fun systemDirectory(game: PreparedGame): File =
        game.remote?.let { File(remoteRoot(it.serverKey), "system").also { it.mkdirs() } } ?: systemDirectory

    /** RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY for [game]. */
    fun savesDirectory(game: PreparedGame): File = game.experimentalProfile?.let { profile ->
        File(experimentRoot(game, profile), "saves").also { it.mkdirs() }
    } ?: (game.remote?.let { remoteSavesDirectory(it.serverKey) } ?: savesDirectory)

    /** The save folder the cores get for every game of one RomM server. */
    fun remoteSavesDirectory(serverKey: String): File = File(remoteRoot(serverKey), "saves").also { it.mkdirs() }

    /** Save states of one game (see GameStateStore). */
    fun statesDirectory(game: PreparedGame): File = (game.experimentalProfile?.let { File(experimentRoot(game, it), "states/${game.saveKey}") }
        ?: game.remote?.let { File(remoteRoot(it.serverKey), "states/${game.saveKey}") }
        ?: File(root, "states/${game.saveKey}")).also { it.mkdirs() }

    private fun experimentRoot(game: PreparedGame, profile: String): File {
        require(GameCoreSource.entries.any { it.experimentalProfile == profile })
        val base = game.remote?.let { remoteRoot(it.serverKey) } ?: root
        return File(base, "experiments/$profile")
    }

    fun sramFile(game: PreparedGame): File = File(savesDirectory(game), "${game.saveKey}.srm")

    fun read(file: File): ByteArray? = runCatching {
        file.takeIf { it.isFile && it.length() in 1..MAX_READ_BYTES }?.readBytes()
    }.getOrNull()

    /**
     * AndroidX writes a separate file before publishing, including on Android 10 where
     * platform AtomicFile writes into the visible file. Readers keep the complete old
     * save until publication; callers must still serialize writes to the same path.
     */
    fun write(file: File, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (error: Exception) {
            atomic.failWrite(out)
            throw error
        }
    }

    private companion object {
        const val MAX_READ_BYTES = 64L * 1024 * 1024
    }
}

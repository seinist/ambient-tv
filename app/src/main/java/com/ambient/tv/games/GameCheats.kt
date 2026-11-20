package com.ambient.tv.games

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One cheat: Game Genie, Action Replay, GameShark … codes as the core reads them ("+" joins several). */
@Serializable
data class GameCheat(val description: String, val code: String, val enabled: Boolean = false)

/**
 * Cheats per game, stored next to its saves. They come from the libretro cheat database
 * (libretro-database/cht, by system and No-Intro/Redump name) or are typed in.
 */
@Singleton
class GameCheatStore @Inject constructor(private val storage: GameStorage) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(GameCheat.serializer())

    private fun file(game: PreparedGame) = File(storage.savesDirectory, "cheats/${game.saveKey}.json")

    fun load(game: PreparedGame): List<GameCheat> =
        runCatching { json.decodeFromString(serializer, file(game).readText()) }.getOrDefault(emptyList())

    fun save(game: PreparedGame, cheats: List<GameCheat>) {
        storage.write(file(game), json.encodeToString(serializer, cheats).toByteArray())
        if (cheats.isEmpty()) file(game).delete()
    }

    /** Cheats for [game] from the libretro database; empty when it has none under the game's name. */
    suspend fun fromDatabase(game: PreparedGame): List<GameCheat> = withContext(Dispatchers.IO) {
        val folders = databaseFolders(game.system, GameSystems.extension(game.file.name))
        val names = listOf(game.title, game.file.name.substringBeforeLast('.')).distinct()
        for (folder in folders) for (name in names) {
            val url = HttpUrl.Builder().scheme("https").host("raw.githubusercontent.com")
                .addPathSegments("libretro/libretro-database/master/cht")
                .addPathSegment(folder).addPathSegment("$name.cht").build()
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == 404) return@use
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val cheats = parseCht(response.body.string())
                if (cheats.isNotEmpty()) return@withContext cheats
            }
        }
        emptyList()
    }

    companion object {
        /** libretro-database/cht folder names (several for systems that share a core). */
        fun databaseFolders(system: GameSystem, extension: String): List<String> = when (system.id) {
            "nes" -> listOf("Nintendo - Nintendo Entertainment System")
            "snes" -> listOf("Nintendo - Super Nintendo Entertainment System")
            "gb" -> if (extension == "gbc") listOf("Nintendo - Game Boy Color", "Nintendo - Game Boy") else listOf("Nintendo - Game Boy")
            "gba" -> listOf("Nintendo - Game Boy Advance")
            "megadrive" -> when (extension) {
                "sms" -> listOf("Sega - Master System - Mark III")
                "gg" -> listOf("Sega - Game Gear")
                else -> listOf("Sega - Mega Drive - Genesis")
            }
            "pce" -> listOf("NEC - PC Engine - TurboGrafx 16")
            "ngp" -> listOf("SNK - Neo Geo Pocket Color", "SNK - Neo Geo Pocket")
            "wswan" -> listOf("Bandai - WonderSwan Color", "Bandai - WonderSwan")
            "lynx" -> listOf("Atari - Lynx")
            "a2600" -> listOf("Atari - 2600")
            "vb" -> listOf("Nintendo - Virtual Boy")
            "n64" -> listOf("Nintendo - Nintendo 64")
            "psx" -> listOf("Sony - PlayStation")
            "nds" -> listOf("Nintendo - Nintendo DS")
            "n3ds" -> listOf("Nintendo - Nintendo 3DS")
            "gamecube" -> listOf("Nintendo - GameCube")
            "wii" -> listOf("Nintendo - Wii")
            "psp" -> listOf("Sony - PlayStation Portable")
            "dreamcast" -> listOf("Sega - Dreamcast")
            "saturn" -> listOf("Sega - Saturn")
            "c64" -> listOf("Commodore - 64")
            "vic20" -> listOf("Commodore - VIC-20")
            "a5200" -> listOf("Atari - 5200")
            "a7800" -> listOf("Atari - 7800")
            "sega32x" -> listOf("Sega - 32X")
            else -> emptyList()
        }

        /** RetroArch .cht: cheatN_desc / cheatN_code / cheatN_enable. */
        fun parseCht(text: String): List<GameCheat> {
            val values = mutableMapOf<String, String>()
            text.lineSequence().forEach { line ->
                val key = line.substringBefore('=', "").trim()
                if (key.isEmpty()) return@forEach
                values[key] = line.substringAfter('=').trim().removeSurrounding("\"")
            }
            val count = values["cheats"]?.toIntOrNull() ?: return emptyList()
            return (0 until count.coerceAtMost(2000)).mapNotNull { index ->
                val code = values["cheat${index}_code"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GameCheat(
                    description = values["cheat${index}_desc"]?.takeIf { it.isNotBlank() } ?: code,
                    code = code,
                    enabled = values["cheat${index}_enable"] == "true",
                )
            }
        }
    }
}

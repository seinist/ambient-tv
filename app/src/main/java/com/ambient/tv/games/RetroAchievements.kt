package com.ambient.tv.games

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** RetroAchievements console ids (rcheevos rc_consoles.h) by system and file extension. */
object RaConsoles {
    fun forGame(system: GameSystem, extension: String): Int? = when (system.id) {
        "nes" -> if (extension == "fds") 81 else 7
        "snes" -> 3
        "gb" -> if (extension == "gbc") 6 else 4
        "gba" -> 5
        "megadrive" -> when (extension) {
            "sms" -> 11
            "gg" -> 15
            "sg" -> 33
            else -> 1
        }
        "pce" -> 8
        "ngp" -> 14
        "wswan" -> 53
        "lynx" -> 13
        "a2600" -> 25
        "vb" -> 28
        "n64" -> 2
        "psx" -> 12
        else -> null
    }
}

data class RaAccount(val user: String, val token: String)

@Serializable
data class RaAchievement(
    @SerialName("ID") val id: Long,
    @SerialName("MemAddr") val memAddr: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("Points") val points: Int = 0,
    @SerialName("BadgeName") val badge: String? = null,
    /** 3 = core set, 5 = unofficial. */
    @SerialName("Flags") val flags: Int = 3,
) {
    val badgeUrl: String? get() = badge?.let { "https://media.retroachievements.org/Badge/$it.png" }
}

@Serializable
data class RaGame(
    @SerialName("ID") val id: Long,
    @SerialName("Title") val title: String = "",
    @SerialName("ConsoleID") val consoleId: Int = 0,
    @SerialName("Achievements") val achievements: List<RaAchievement> = emptyList(),
)

data class RaSessionStart(val unlocked: Set<Long>, val hardcoreUnlocked: Set<Long>)

class RaException(message: String) : IOException(message)

/**
 * The RetroAchievements server protocol (dorequest.php, as rcheevos' rc_api uses it): login, game
 * lookup by ROM hash, session start with earlier unlocks, achievement definitions, awards, pings.
 * The account token lives in encrypted storage; the password is never kept.
 */
@Singleton
class RetroAchievementsClient @Inject constructor(private val tokens: TokenStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val account: RaAccount?
        get() {
            val user = tokens.retroAchievementsUser ?: return null
            val token = tokens.retroAchievementsToken ?: return null
            return RaAccount(user, token)
        }

    suspend fun login(user: String, password: String): RaAccount {
        val body = request(mapOf("r" to "login2", "u" to user.trim(), "p" to password))
        val response = json.decodeFromString(LoginResponse.serializer(), body)
        if (!response.success || response.token.isNullOrBlank()) throw RaException(response.error ?: "Login failed")
        val account = RaAccount(response.user ?: user.trim(), response.token)
        tokens.retroAchievementsUser = account.user
        tokens.retroAchievementsToken = account.token
        return account
    }

    fun logout() {
        tokens.retroAchievementsUser = null
        tokens.retroAchievementsToken = null
    }

    /** The RetroAchievements game for a ROM hash (rhash), or null when the hash is unknown. */
    suspend fun gameId(hash: String): Long? {
        val response = json.decodeFromString(GameIdResponse.serializer(), request(mapOf("r" to "gameid", "m" to hash)))
        return response.gameId?.takeIf { it > 0 }
    }

    suspend fun game(account: RaAccount, gameId: Long): RaGame {
        val body = request(mapOf("r" to "patch", "u" to account.user, "t" to account.token, "g" to gameId.toString(), "f" to "3"))
        val response = json.decodeFromString(PatchResponse.serializer(), body)
        return response.patchData ?: throw RaException(response.error ?: "No achievement data")
    }

    suspend fun startSession(account: RaAccount, gameId: Long, hash: String, hardcore: Boolean): RaSessionStart {
        val body = request(mapOf(
            "r" to "startsession", "u" to account.user, "t" to account.token, "g" to gameId.toString(),
            "h" to if (hardcore) "1" else "0", "m" to hash, "l" to RCHEEVOS_VERSION,
        ))
        val response = json.decodeFromString(SessionResponse.serializer(), body)
        if (!response.success) throw RaException(response.error ?: "Session refused")
        val hardcoreIds = response.hardcoreUnlocks.map { it.id }.toSet()
        return RaSessionStart(hardcoreIds + response.unlocks.map { it.id }, hardcoreIds)
    }

    /** Reports an unlock; true when the server took it (also when it already knew it). */
    suspend fun award(account: RaAccount, achievementId: Long, hardcore: Boolean): Boolean {
        val flag = if (hardcore) "1" else "0"
        val validation = md5("$achievementId${account.user}$flag")
        val body = request(mapOf(
            "r" to "awardachievement", "u" to account.user, "t" to account.token,
            "a" to achievementId.toString(), "h" to flag, "v" to validation,
        ))
        val response = json.decodeFromString(BaseResponse.serializer(), body)
        return response.success || response.error?.contains("already", ignoreCase = true) == true
    }

    suspend fun ping(account: RaAccount, gameId: Long) {
        runCatching { request(mapOf("r" to "ping", "u" to account.user, "t" to account.token, "g" to gameId.toString())) }
    }

    private suspend fun request(fields: Map<String, String>): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder()
            .url(BASE_URL.toHttpUrl())
            .header("User-Agent", "AmbientTV/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}) rcheevos/$RCHEEVOS_VERSION")
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful && text.isBlank()) throw RaException("HTTP ${response.code}")
            text
        }
    }

    @Serializable private data class LoginResponse(
        @SerialName("Success") val success: Boolean = false,
        @SerialName("User") val user: String? = null,
        @SerialName("Token") val token: String? = null,
        @SerialName("Error") val error: String? = null,
    )
    @Serializable private data class GameIdResponse(@SerialName("GameID") val gameId: Long? = null)
    @Serializable private data class PatchResponse(
        @SerialName("Success") val success: Boolean = false,
        @SerialName("PatchData") val patchData: RaGame? = null,
        @SerialName("Error") val error: String? = null,
    )
    @Serializable private data class Unlock(@SerialName("ID") val id: Long)
    @Serializable private data class SessionResponse(
        @SerialName("Success") val success: Boolean = false,
        @SerialName("Unlocks") val unlocks: List<Unlock> = emptyList(),
        @SerialName("HardcoreUnlocks") val hardcoreUnlocks: List<Unlock> = emptyList(),
        @SerialName("Error") val error: String? = null,
    )
    @Serializable private data class BaseResponse(
        @SerialName("Success") val success: Boolean = false,
        @SerialName("Error") val error: String? = null,
    )

    companion object {
        const val BASE_URL = "https://retroachievements.org/dorequest.php"
        const val RCHEEVOS_VERSION = "12.2.1"

        internal fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Achievements the runtime watches: official ones with a condition, not emulator warnings. */
        internal fun watchable(game: RaGame): List<RaAchievement> = game.achievements.filter {
            it.memAddr.isNotBlank() && it.flags == 3 &&
                !it.title.contains("Unknown Emulator", ignoreCase = true) &&
                !it.title.contains("Emulator Warning", ignoreCase = true)
        }
    }
}

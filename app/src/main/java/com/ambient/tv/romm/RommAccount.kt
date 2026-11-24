package com.ambient.tv.romm

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** How requests prove who is asking. */
sealed interface RommAuth {
    fun header(): String

    /** A RomM client or device token. */
    data class Token(val token: String) : RommAuth {
        override fun header() = "Bearer $token"
        override fun toString() = "Token(…)"
    }

    /** User name and password, sent as HTTP Basic (every RomM version accepts it). */
    data class Basic(val username: String, val password: String) : RommAuth {
        override fun header(): String = Credentials.basic(username, password, Charsets.UTF_8)
        override fun toString() = "Basic($username, …)"
    }
}

/**
 * A signed-in RomM server. [serverKey] separates the data of different servers and accounts on
 * this device (game cache, save states, saves, firmware), so changing the server never mixes them.
 */
data class RommAccount(val baseUrl: HttpUrl, val auth: RommAuth, val username: String) {
    val serverKey: String get() = RommKeys.serverKey(baseUrl, username)
}

/** Game ids of RomM titles: `romm:<serverKey>:<romId>`. Never a Files key, never a media id. */
object RommKeys {
    const val PREFIX = "romm:"

    fun owns(id: String?): Boolean = id != null && id.startsWith(PREFIX)

    fun fileId(serverKey: String, romId: Long): String = "$PREFIX$serverKey:$romId"

    /** (serverKey, romId) of a RomM game id, or null for any other id. */
    fun parse(id: String?): Pair<String, Long>? {
        if (!owns(id)) return null
        val parts = id!!.split(':')
        if (parts.size != 3 || !parts[1].matches(Regex("[0-9a-f]{12}"))) return null
        val rom = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return parts[1] to rom
    }

    fun serverKey(baseUrl: HttpUrl, username: String): String {
        val identity = baseUrl.toString().trimEnd('/').lowercase(Locale.ROOT) + "|" + username.lowercase(Locale.ROOT)
        return MessageDigest.getInstance("SHA-1").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * The address as typed ("romm.local:8080", "https://games.example/romm/") made into the base of
     * the web interface: scheme added, trailing slash and a pasted "/api" removed.
     */
    fun normalizeUrl(input: String): HttpUrl? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        val segments = url.pathSegments.filter(String::isNotBlank).toMutableList()
        if (segments.lastOrNull()?.equals("api", ignoreCase = true) == true) segments.removeAt(segments.lastIndex)
        return url.newBuilder().query(null).fragment(null).encodedPath("/").apply {
            segments.forEach { addPathSegment(it) }
        }.build()
    }
}

/** Settings of the RomM system; the secret is kept in the encrypted [TokenStore]. */
@Singleton
class RommAccountStore @Inject constructor(
    private val preferences: AppPreferences,
    private val tokens: TokenStore,
) {
    private val revision = MutableStateFlow(0L)
    /** Changes whenever the server, the sign-in or the switch changes. */
    val changes: StateFlow<Long> = revision.asStateFlow()

    /** The signed-in server, or null while RomM is off, has no address or no sign-in. */
    fun account(): RommAccount? {
        if (!preferences.rommEnabled) return null
        return configuredAccount()
    }

    /** Like [account], but also while the system is switched off (the settings page tests it). */
    fun configuredAccount(): RommAccount? {
        val url = RommKeys.normalizeUrl(preferences.rommServerUrl) ?: return null
        val auth = when (preferences.rommAuthMode) {
            "token" -> tokens.rommToken?.takeIf(String::isNotBlank)?.let { RommAuth.Token(it) }
            "basic" -> tokens.rommPassword?.let { password ->
                preferences.rommUsername.takeIf(String::isNotBlank)?.let { RommAuth.Basic(it, password) }
            }
            else -> null
        } ?: return null
        return RommAccount(url, auth, preferences.rommUsername)
    }

    val enabled: Boolean get() = preferences.rommEnabled
    val serverUrl: String get() = preferences.rommServerUrl
    val username: String get() = preferences.rommUsername
    val serverVersion: String get() = preferences.rommServerVersion
    val deviceId: String get() = preferences.rommDeviceId

    fun setEnabled(value: Boolean) {
        preferences.rommEnabled = value
        revision.value++
    }

    fun setServerUrl(value: String) {
        if (value.trim() == preferences.rommServerUrl) return
        preferences.rommServerUrl = value
        revision.value++
    }

    /** Stores a verified sign-in. */
    fun signedIn(baseUrl: HttpUrl, auth: RommAuth, username: String, version: String) {
        preferences.rommServerUrl = baseUrl.toString().trimEnd('/')
        preferences.rommUsername = username
        preferences.rommServerVersion = version
        when (auth) {
            is RommAuth.Token -> {
                tokens.rommToken = auth.token
                tokens.rommPassword = null
                preferences.rommAuthMode = "token"
            }
            is RommAuth.Basic -> {
                tokens.rommPassword = auth.password
                tokens.rommToken = null
                preferences.rommAuthMode = "basic"
            }
        }
        preferences.rommEnabled = true
        revision.value++
    }

    fun signOut() {
        tokens.rommToken = null
        tokens.rommPassword = null
        preferences.rommAuthMode = ""
        revision.value++
    }
}

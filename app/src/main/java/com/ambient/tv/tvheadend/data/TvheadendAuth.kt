package com.ambient.tv.tvheadend.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Handles TVHeadend Basic and Digest challenges. Digest is required by many default installs. */
class TvheadendAuthenticator(
    private val credentials: () -> Pair<String, String?>,
) : Authenticator {
    private val random = SecureRandom()

    /** Set once the server challenged with Basic only; cleared again by a Digest challenge. */
    @Volatile var basicAccepted: Boolean = false
        private set

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 3) return null
        val (username, storedPassword) = credentials()
        if (username.isBlank()) return null
        val password = storedPassword.orEmpty()

        val challenges = response.headers("WWW-Authenticate")
        val digest = challenges.firstOrNull { it.trimStart().startsWith("Digest", ignoreCase = true) }
        if (digest != null) {
            basicAccepted = false
            val header = buildDigestAuthorization(response.request, digest, username, password) ?: return null
            return response.request.newBuilder().header("Authorization", header).build()
        }
        if (challenges.any { it.trimStart().startsWith("Basic", ignoreCase = true) }) {
            basicAccepted = true
            return response.request.newBuilder().header("Authorization", Credentials.basic(username, password)).build()
        }
        return null
    }

    private fun buildDigestAuthorization(
        request: Request,
        challenge: String,
        username: String,
        password: String,
    ): String? {
        val params = parseChallenge(challenge.removePrefix("Digest").trim())
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val opaque = params["opaque"]
        val algorithmRaw = params["algorithm"] ?: "MD5"
        val algorithm = algorithmRaw.uppercase(Locale.US)
        if (algorithm !in setOf("MD5", "MD5-SESS", "SHA-256", "SHA-256-SESS", "SHA-512-256", "SHA-512-256-SESS")) return null
        val charset = if (params["charset"].equals("UTF-8", true)) Charsets.UTF_8 else Charsets.ISO_8859_1
        val qopOptions = params["qop"].orEmpty().split(',').map { it.trim().lowercase(Locale.US) }
        val qop = qopOptions.firstOrNull { it == "auth" }
        if (!params["qop"].isNullOrBlank() && qop == null) return null
        val cnonce = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val nc = "00000001"
        val uri = buildString {
            append(request.url.encodedPath)
            request.url.encodedQuery?.let { append('?').append(it) }
        }

        fun h(value: String): String {
            val mdName = when {
                algorithm.startsWith("SHA-256") -> "SHA-256"
                algorithm.startsWith("SHA-512-256") -> "SHA-512/256"
                else -> "MD5"
            }
            return MessageDigest.getInstance(mdName).digest(value.toByteArray(charset))
                .joinToString("") { "%02x".format(it) }
        }

        var ha1 = h("$username:$realm:$password")
        if (algorithm.endsWith("-SESS")) ha1 = h("$ha1:$nonce:$cnonce")
        val ha2 = h("${request.method}:$uri")
        val responseHash = if (qop != null) h("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else h("$ha1:$nonce:$ha2")

        return buildString {
            append("Digest username=\"").append(escape(username)).append("\"")
            append(", realm=\"").append(escape(realm)).append("\"")
            append(", nonce=\"").append(escape(nonce)).append("\"")
            append(", uri=\"").append(escape(uri)).append("\"")
            append(", response=\"").append(responseHash).append("\"")
            append(", algorithm=").append(algorithmRaw)
            if (opaque != null) append(", opaque=\"").append(escape(opaque)).append("\"")
            if (qop != null) {
                append(", qop=").append(qop)
                append(", nc=").append(nc)
                append(", cnonce=\"").append(cnonce).append("\"")
            }
        }
    }

    private fun parseChallenge(input: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val regex = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|([^,\\s]+))")
        regex.findAll(input).forEach { match ->
            val key = match.groupValues[1].lowercase(Locale.US)
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }.replace("\\\"", "\"")
            result[key] = value
        }
        return result
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) { count++; prior = prior.priorResponse }
        return count
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

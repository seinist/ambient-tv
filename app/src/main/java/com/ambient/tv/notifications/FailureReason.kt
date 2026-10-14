package com.ambient.tv.notifications

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Why a server call or login failed, in terms a user can act on. */
enum class FailureKind { AUTH, HTTP, HOST, TLS, UNREACHABLE, TIMEOUT, NOT_LOCAL, VPN, WAKE, OTHER }

data class FailureReason(val kind: FailureKind, val httpCode: Int? = null, val detail: String? = null)

private val HTTP_STATUS = Regex("""\bHTTP\s*(\d{3})\b""", RegexOption.IGNORE_CASE)

/** The HTTP status of a failed call: typed exceptions first (code()/code property), then "HTTP 401" in a message. */
fun failureHttpCode(error: Throwable): Int? {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    causes.forEach { cause ->
        // retrofit2.HttpException and the app's own HTTP exceptions expose the status as code.
        runCatching { cause.javaClass.getMethod("code").invoke(cause) as? Int }.getOrNull()?.takeIf { it in 100..599 }?.let { return it }
        runCatching { cause.javaClass.getMethod("getCode").invoke(cause) as? Int }.getOrNull()?.takeIf { it in 100..599 }?.let { return it }
    }
    return causes.firstNotNullOfOrNull { cause -> cause.message?.let { HTTP_STATUS.find(it)?.groupValues?.get(1)?.toIntOrNull() } }
}

fun classifyFailure(error: Throwable): FailureReason {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    fun has(type: Class<out Throwable>) = causes.any { type.isInstance(it) }
    fun named(fragment: String) = causes.any { it.javaClass.simpleName.contains(fragment, ignoreCase = true) }
    failureHttpCode(error)?.let { code ->
        return FailureReason(if (code == 401 || code == 403) FailureKind.AUTH else FailureKind.HTTP, code)
    }
    val kind = when {
        named("NotLocal") -> FailureKind.NOT_LOCAL
        named("WireGuard") || named("Vpn") -> FailureKind.VPN
        named("WakeTimeout") -> FailureKind.WAKE
        has(UnknownHostException::class.java) -> FailureKind.HOST
        has(SSLException::class.java) || has(CertificateException::class.java) -> FailureKind.TLS
        has(ConnectException::class.java) || has(NoRouteToHostException::class.java) || has(PortUnreachableException::class.java) -> FailureKind.UNREACHABLE
        has(SocketTimeoutException::class.java) || causes.any { it is InterruptedIOException && it.message?.contains("timeout", true) == true } -> FailureKind.TIMEOUT
        else -> FailureKind.OTHER
    }
    return FailureReason(kind, detail = sanitizeFailureDetail(causes.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) } ?: error.javaClass.simpleName))
}

private val SECRET_PARAM = Regex("""(?i)(X-Plex-Token|api_?key|access_token|refresh_token|token|password|passwd|auth)=([^&\s"']+)""")
private val URL_QUERY = Regex("""(https?://[^\s"'?]+)\?[^\s"']*""")

/** Error text safe to show: no query strings, tokens or passwords, one line, bounded length. */
fun sanitizeFailureDetail(text: String): String = text
    .replace(URL_QUERY, "$1")
    .replace(SECRET_PARAM, "$1=…")
    .replace(Regex("""\s+"""), " ")
    .trim()
    .take(160)

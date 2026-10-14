package com.ambient.tv.tvheadend.data

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** TVHeadend answered with an error status. */
class TvheadendHttpException(val code: Int, message: String) : IllegalStateException(message)

/** The configured host is outside the LAN while "local network only" is on. */
class TvheadendNotLocalException(message: String, val host: String = "", val addresses: List<String> = emptyList()) :
    IllegalStateException(message)

/** The WireGuard route TVHeadend is set to use could not be brought up. */
class TvheadendVpnException(message: String, cause: Throwable?) : IllegalStateException(message, cause)

/** Wake-on-LAN was sent but TVHeadend did not come up in time. */
class TvheadendWakeTimeoutException(message: String) : IllegalStateException(message)

/** Why a TVHeadend connection test failed, as the settings page explains it. */
internal enum class TvheadendTestFailure { AUTH, HTTP, HOST, TLS, UNREACHABLE, NOT_LOCAL, VPN, WAKE, OTHER }

internal fun classifyTvheadendTestFailure(error: Throwable): TvheadendTestFailure {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    fun has(type: Class<out Throwable>) = causes.any { type.isInstance(it) }
    causes.filterIsInstance<TvheadendHttpException>().firstOrNull()?.let { http ->
        return if (http.code == 401 || http.code == 403) TvheadendTestFailure.AUTH else TvheadendTestFailure.HTTP
    }
    return when {
        has(TvheadendNotLocalException::class.java) -> TvheadendTestFailure.NOT_LOCAL
        has(TvheadendVpnException::class.java) -> TvheadendTestFailure.VPN
        has(TvheadendWakeTimeoutException::class.java) -> TvheadendTestFailure.WAKE
        has(UnknownHostException::class.java) -> TvheadendTestFailure.HOST
        has(SSLException::class.java) -> TvheadendTestFailure.TLS
        has(ConnectException::class.java) || has(SocketTimeoutException::class.java) ||
            has(NoRouteToHostException::class.java) -> TvheadendTestFailure.UNREACHABLE
        else -> TvheadendTestFailure.OTHER
    }
}

/** HTTP status of a failed test, if it was one. */
internal fun tvheadendFailureHttpCode(error: Throwable): Int? =
    generateSequence(error) { it.cause }.take(8).filterIsInstance<TvheadendHttpException>().firstOrNull()?.code

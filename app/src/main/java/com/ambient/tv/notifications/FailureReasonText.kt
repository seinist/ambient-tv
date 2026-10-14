package com.ambient.tv.notifications

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.util.LocalNetworkAccess

/**
 * One short, localized sentence naming why a call failed ("login rejected (HTTP 401)",
 * "no connection to 192.168.1.5:32400", …). [endpoint] (host or host:port) makes connection
 * failures concrete. Never contains tokens or query strings.
 */
fun failureReasonText(context: Context, error: Throwable, endpoint: String? = null): String {
    val reason = classifyFailure(error)
    val target = endpoint?.takeIf { it.isNotBlank() } ?: context.getString(R.string.reason_target_server)
    // Android 17+: without the local network permission every LAN connection fails the same way.
    if (reason.kind in setOf(FailureKind.UNREACHABLE, FailureKind.TIMEOUT, FailureKind.OTHER) && !LocalNetworkAccess.isGranted(context)) {
        return context.getString(R.string.reason_permission)
    }
    return when (reason.kind) {
        FailureKind.AUTH -> context.getString(R.string.reason_auth, reason.httpCode.toString())
        FailureKind.HTTP -> context.getString(R.string.reason_http, reason.httpCode.toString())
        FailureKind.HOST -> context.getString(R.string.reason_host, target)
        FailureKind.TLS -> context.getString(R.string.reason_tls)
        FailureKind.UNREACHABLE -> context.getString(R.string.reason_unreachable, target)
        FailureKind.TIMEOUT -> context.getString(R.string.reason_timeout, target)
        FailureKind.NOT_LOCAL -> context.getString(R.string.reason_not_local)
        FailureKind.VPN -> context.getString(R.string.reason_vpn, reason.detail.orEmpty())
        FailureKind.WAKE -> context.getString(R.string.reason_wake)
        FailureKind.OTHER -> reason.detail.orEmpty().ifBlank { context.getString(R.string.reason_unknown) }
    }
}

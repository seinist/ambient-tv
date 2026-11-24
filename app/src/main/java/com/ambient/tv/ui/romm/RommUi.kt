package com.ambient.tv.ui.romm

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.romm.RommAuthException
import com.ambient.tv.romm.RommHttpException
import com.ambient.tv.romm.RommSyncState
import com.ambient.tv.romm.RommUnsupportedException

/** The Info panel line for a RomM game's saves and states. */
fun rommSyncLabel(state: RommSyncState?): Int = when (state) {
    RommSyncState.CHECKING -> R.string.romm_sync_checking
    RommSyncState.SYNCED -> R.string.romm_sync_synced
    RommSyncState.UPLOADING -> R.string.romm_sync_uploading
    RommSyncState.PENDING -> R.string.romm_sync_pending
    RommSyncState.SIGN_IN -> R.string.romm_sync_sign_in
    null -> R.string.romm_sync_checking
}

/** A short, plain sentence for a failed RomM request. */
fun rommErrorMessage(context: Context, error: Throwable): String = when (error) {
    is RommAuthException -> context.getString(R.string.romm_error_sign_in)
    is RommUnsupportedException -> context.getString(R.string.romm_error_unsupported)
    is RommHttpException -> context.getString(R.string.romm_error_server, error.code)
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
    is java.net.NoRouteToHostException -> context.getString(R.string.romm_error_unreachable)
    is javax.net.ssl.SSLException -> context.getString(R.string.romm_error_tls)
    is java.io.FileNotFoundException -> context.getString(R.string.romm_error_not_set_up)
    else -> context.getString(R.string.romm_error_unreachable)
}

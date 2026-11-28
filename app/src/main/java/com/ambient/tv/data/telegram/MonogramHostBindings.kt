package com.ambient.tv.data.telegram

import android.content.Context
import com.ambient.tv.BuildConfig
import com.ambient.tv.R
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.modules.SystemModuleEntrypoint

/**
 * Host-owned Monogram DEX boundary. No generated upstream types cross it.
 * The external adapter uses the host's current resources, credentials and
 * diagnostic configuration rather than inlining IDs from its build APK.
 */
interface MonogramModuleFactory : SystemModuleEntrypoint {
    fun create(context: Context, tokenStore: TokenStore): MonogramAuthClientPort
}

object MonogramHostBindings {
    val perfCounters: Boolean get() = BuildConfig.MONOGRAM_PERF_COUNTERS
    val pgoGenerate: Boolean get() = BuildConfig.MONOGRAM_PGO_GENERATE
    val pgoStamp: String get() = BuildConfig.MONOGRAM_PGO_STAMP
    val versionName: String get() = BuildConfig.VERSION_NAME
    val cdnRanges: Boolean get() = BuildConfig.MONOGRAM_CDN_RANGES

    fun error(context: Context, key: String): String = context.getString(when (key) {
        "api_required" -> R.string.monogram_api_required
        "signout_first" -> R.string.monogram_error_signout_first
        "phone" -> R.string.monogram_error_phone
        "code_first" -> R.string.monogram_error_code_first
        "not_authorized" -> R.string.monogram_error_not_authorized
        "channel_left" -> R.string.monogram_error_channel_left
        "account_changed" -> R.string.monogram_error_account_changed
        else -> throw IllegalArgumentException("Unknown Monogram host resource")
    })
}

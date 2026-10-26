package com.ambient.tv.data.metadata

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * Whether the active login of one provider administers its server. The stored answer decides at
 * once (no editing option flashes up or appears late); [refresh] re-checks it in the background.
 * Unknown means "no": a check that fails for another reason keeps the stored answer.
 */
class MetadataEditRights(
    private val preferences: AppPreferences,
    /** provider:profile:user of the active login, null while signed out. */
    private val loginKey: () -> String?,
    /** True when the server confirms administrator rights; a 401/403 counts as false. */
    private val probe: suspend () -> Boolean,
) {
    private val mutex = Mutex()
    @Volatile private var checkedKey: String? = null
    @Volatile private var checkedAtMs = 0L

    fun granted(): Boolean = loginKey()?.let(preferences::isMetadataEditor) == true

    suspend fun refresh(): Boolean = mutex.withLock {
        val key = loginKey() ?: return@withLock false
        val now = System.currentTimeMillis()
        if (key == checkedKey && now - checkedAtMs < CHECK_TTL_MS) return@withLock false
        val allowed = suspendResult { probe() }.recoverCatching { error ->
            if (error is HttpException && error.code() in REFUSED) false else throw error
        }.getOrNull() ?: return@withLock false
        checkedKey = key
        checkedAtMs = now
        preferences.setMetadataEditor(key, allowed)
    }

    /** A write the server refused: the rights were withdrawn since the last check. */
    fun onWriteFailure(error: Throwable) {
        if (error !is HttpException || error.code() !in REFUSED) return
        loginKey()?.let { preferences.setMetadataEditor(it, false) }
        checkedKey = null
    }

    private companion object {
        const val CHECK_TTL_MS = 10 * 60_000L
        val REFUSED = setOf(401, 403)
    }
}

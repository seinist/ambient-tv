package com.ambient.tv.modules

import java.io.IOException
import okhttp3.Call
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ModuleDownloadCancelled : IOException("Module download cancelled")

/** One transfer; cancellation and the irreversible selection commit are serialized. */
internal class SystemModuleDownload {
    companion object {
        const val SEARCHING = -1
        const val VERIFYING = -2
        const val COMMITTING = -3
        const val CANCELLING = -4
    }

    private val mutable = MutableStateFlow(SEARCHING)
    val progress = mutable.asStateFlow()
    private var call: Call? = null
    private var cancelled = false
    private var committing = false

    @Synchronized fun check() {
        if (cancelled) throw ModuleDownloadCancelled()
    }

    @Synchronized fun attach(next: Call): Call {
        if (cancelled) {
            next.cancel()
            throw ModuleDownloadCancelled()
        }
        call = next
        return next
    }

    @Synchronized fun update(value: Int) {
        check()
        if (!committing) mutable.value = value
    }

    @Synchronized fun beginPublication() {
        check()
        committing = true
        mutable.value = COMMITTING
    }

    @Synchronized fun cancel(): Boolean {
        if (committing || cancelled) return false
        cancelled = true
        mutable.value = CANCELLING
        call?.cancel()
        return true
    }

    @Synchronized fun wasCancelled(): Boolean = cancelled
}

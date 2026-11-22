package com.ambient.tv.ui.detail

/**
 * A server responding elsewhere does not make a cached detail current. Both the service and a
 * metadata read from this detail's current account/network scope must have succeeded. Confined
 * to the ViewModel's main thread, like [DetailRefreshPolicy].
 */
internal class DetailConnectionPolicy {
    private var metadataScope: String? = null

    fun confirmMetadata(requestScope: String, currentScope: String, serviceConfirmed: Boolean): Boolean {
        if (requestScope != currentScope || !serviceConfirmed) return false
        metadataScope = currentScope
        return true
    }

    fun isVerified(currentScope: String, serviceVerified: Boolean): Boolean {
        if (!serviceVerified || metadataScope != currentScope) metadataScope = null
        return metadataScope != null
    }

    /** A failed refresh locks the old preview until another fresh metadata read succeeds. */
    fun failed() { metadataScope = null }
}

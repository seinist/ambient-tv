package com.ambient.tv.livetv

/** Invalidating RAM must also bypass the XMLTV disk TTL, while retaining its offline fallback. */
internal class IptvRefreshPolicy {
    var revision: Long = 0L
        private set
    private var guideDownloadRequired = false

    fun invalidate() {
        revision++
        guideDownloadRequired = true
    }

    fun downloadGuide(force: Boolean): Boolean = force || guideDownloadRequired

    fun guideLoaded(revision: Long, fromFallback: Boolean) {
        // An old in-flight response cannot satisfy a newer reconnect.
        if (revision == this.revision) guideDownloadRequired = fromFallback
    }
}

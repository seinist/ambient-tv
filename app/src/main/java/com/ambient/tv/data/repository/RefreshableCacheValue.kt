package com.ambient.tv.data.repository

/** A cleared value cannot be made fresh again by a request that began before the refresh. */
internal class RefreshableCacheValue<T> {
    private var revision = 0L
    private var value: T? = null
    private var storedAtMs = 0L

    @Synchronized fun generation(): Long = revision

    @Synchronized fun fresh(nowMs: Long, maxAgeMs: Long): T? =
        value?.takeIf { nowMs - storedAtMs in 0 until maxAgeMs }

    @Synchronized fun publish(expected: Long, next: T, nowMs: Long): Boolean {
        if (expected != revision) return false
        value = next
        storedAtMs = nowMs
        return true
    }

    @Synchronized fun invalidate() {
        revision++
        value = null
    }
}

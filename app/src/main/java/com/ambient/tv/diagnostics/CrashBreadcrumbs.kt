package com.ambient.tv.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last [CAPACITY] stop/release/hand-off events, kept in memory only and written together
 * with a crash. Cheap and always on; never contains URLs or titles.
 */
object CrashBreadcrumbs {
    const val CAPACITY = 50
    private val entries = ArrayDeque<String>(CAPACITY)
    internal var clock: () -> Long = System::currentTimeMillis

    fun add(tag: String, message: String) {
        val line = runCatching {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(clock()))
            "$time $tag [${Thread.currentThread().name.take(24)}] ${message.take(200)}"
        }.getOrNull() ?: return
        synchronized(entries) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(line)
        }
    }

    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }

    internal fun clearForTest() = synchronized(entries) { entries.clear() }
}

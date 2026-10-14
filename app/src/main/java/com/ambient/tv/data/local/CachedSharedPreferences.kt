package com.ambient.tv.data.local

import android.content.SharedPreferences

/**
 * Read-through cache in front of EncryptedSharedPreferences. Every read there decrypts the key
 * and value, and the server token is read for each API request and artwork URL. Values are kept
 * in memory after the first read; any write through [edit] drops the whole cache. A generation
 * counter keeps a read that raced a write from caching the old value.
 */
internal class CachedSharedPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
    private val lock = Any()
    private val cache = HashMap<String, Any>()
    private var generation = 0L

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, default: T, read: () -> T): T {
        val start: Long
        synchronized(lock) {
            cache[key]?.let { hit -> return if (hit === Absent) default else hit as T }
            start = generation
        }
        val value: Any? = if (delegate.contains(key)) read() else null
        synchronized(lock) {
            if (generation == start) cache[key] = value ?: Absent
        }
        return (value ?: default) as T
    }

    override fun getString(key: String, defValue: String?): String? = cached(key, defValue) { delegate.getString(key, defValue) }
    override fun getLong(key: String, defValue: Long): Long = cached(key, defValue) { delegate.getLong(key, defValue) }
    override fun getInt(key: String, defValue: Int): Int = cached(key, defValue) { delegate.getInt(key, defValue) }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = cached(key, defValue) { delegate.getBoolean(key, defValue) }
    override fun getFloat(key: String, defValue: Float): Float = cached(key, defValue) { delegate.getFloat(key, defValue) }
    override fun contains(key: String): Boolean {
        synchronized(lock) { cache[key]?.let { return it !== Absent } }
        return delegate.contains(key)
    }

    override fun edit(): SharedPreferences.Editor = InvalidatingEditor(delegate.edit())

    private fun invalidate() = synchronized(lock) {
        generation++
        cache.clear()
    }

    private inner class InvalidatingEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { editor.putStringSet(key, values); return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { editor.putFloat(key, value); return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
        override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
        override fun commit(): Boolean = editor.commit().also { invalidate() }
        override fun apply() {
            editor.apply()
            invalidate()
        }
    }

    private object Absent
}

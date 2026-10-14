package com.ambient.tv.data.local

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedSharedPreferencesTest {
    private class FakePrefs : SharedPreferences {
        val values = HashMap<String, Any?>()
        var reads = 0
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String, defValue: String?): String? { reads++; return values[key] as String? ?: defValue }
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int { reads++; return values[key] as Int? ?: defValue }
        override fun getLong(key: String, defValue: Long): Long { reads++; return values[key] as Long? ?: defValue }
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun contains(key: String): Boolean = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            val removed = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { removed.forEach(values::remove); values.putAll(pending) }
        }
    }

    @Test
    fun `repeated reads decrypt once`() {
        val fake = FakePrefs().apply { values["token"] = "abc" }
        val prefs = CachedSharedPreferences(fake)
        repeat(5) { assertEquals("abc", prefs.getString("token", null)) }
        assertEquals(1, fake.reads)
    }

    @Test
    fun `missing keys are cached and return the caller's default`() {
        val fake = FakePrefs()
        val prefs = CachedSharedPreferences(fake)
        assertNull(prefs.getString("token", null))
        assertEquals("d", prefs.getString("token", "d"))
        assertEquals(7L, prefs.getLong("expires", 7L))
        assertFalse(prefs.contains("token"))
        assertEquals(0, fake.reads)
    }

    @Test
    fun `writes through the wrapper are visible immediately`() {
        val fake = FakePrefs().apply { values["token"] = "old" }
        val prefs = CachedSharedPreferences(fake)
        assertEquals("old", prefs.getString("token", null))
        prefs.edit().putString("token", "new").apply()
        assertEquals("new", prefs.getString("token", null))
        prefs.edit().remove("token").commit()
        assertNull(prefs.getString("token", null))
        assertFalse(prefs.contains("token"))
        prefs.edit().putLong("expires", 9L).apply()
        assertTrue(prefs.contains("expires"))
        assertEquals(9L, prefs.getLong("expires", 0L))
    }
}

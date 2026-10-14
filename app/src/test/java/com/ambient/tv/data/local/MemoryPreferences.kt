package com.ambient.tv.data.local

import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** Android-free in-memory preference interface for persistence regression tests. */
internal fun memoryPreferences(): SharedPreferences {
    val values = mutableMapOf<String, Any>()
    return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getAll" -> values.toMap()
            "contains" -> values.containsKey(args!![0] as String)
            "getString", "getInt", "getLong", "getFloat", "getBoolean", "getStringSet" -> values[args!![0] as String] ?: args[1]
            "edit" -> {
                val changes = mutableMapOf<String, Any?>()
                var clear = false
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { editor, operation, inputs ->
                    when (operation.name) {
                        "clear" -> { clear = true; editor }
                        "remove" -> { changes[inputs!![0] as String] = null; editor }
                        "apply", "commit" -> {
                            if (clear) values.clear()
                            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                            true
                        }
                        else -> {
                            check(operation.name.startsWith("put"))
                            changes[inputs!![0] as String] = inputs[1]
                            editor
                        }
                    }
                }
            }
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            else -> error("Unexpected preference operation: ${method.name}")
        }
    } as SharedPreferences
}

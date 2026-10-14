package com.ambient.tv.ui.player.backend

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads optional libVLC members across library versions. `Class.fields`/`methods` copy the whole
 * member table on every call and the VLC stats are read once per second, so each lookup is
 * resolved once per class and name.
 */
internal object ReflectiveMembers {
    private sealed interface Accessor
    private class FieldAccessor(val field: Field) : Accessor
    private class MethodAccessor(val method: Method) : Accessor
    private object Missing : Accessor

    private val accessors = ConcurrentHashMap<Pair<Class<*>, String>, Accessor>()

    /** Public field [name], else its `getX()` getter, else null. */
    fun value(instance: Any, name: String): Any? = when (val accessor = accessor(instance.javaClass, name, property = true)) {
        is FieldAccessor -> runCatching { accessor.field.get(instance) }.getOrNull()
        is MethodAccessor -> runCatching { accessor.method.invoke(instance) }.getOrNull()
        Missing -> null
    }

    /** Invokes the public no-argument method [name], or returns null when it does not exist. */
    fun call(instance: Any, name: String): Any? = when (val accessor = accessor(instance.javaClass, name, property = false)) {
        is MethodAccessor -> runCatching { accessor.method.invoke(instance) }.getOrNull()
        else -> null
    }

    private fun accessor(type: Class<*>, name: String, property: Boolean): Accessor =
        accessors.getOrPut(type to (if (property) "p:" else "m:") + name) {
            if (property) {
                type.fields.firstOrNull { it.name == name }?.let(::FieldAccessor)
                    ?: type.methods.firstOrNull { it.name == "get" + name.replaceFirstChar(Char::uppercase) && it.parameterCount == 0 }
                        ?.let(::MethodAccessor)
                    ?: Missing
            } else {
                type.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let(::MethodAccessor) ?: Missing
            }
        }
}

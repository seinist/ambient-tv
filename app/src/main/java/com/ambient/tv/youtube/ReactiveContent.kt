package com.ambient.tv.youtube

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull

/** Reflection keeps RxJava/upstream types out of the app's public content API. */
internal fun reactiveContent(value: Any): Flow<Any>? {
    val subscribe = value.javaClass.methods.firstOrNull { method ->
        method.name == "subscribe" && method.parameterTypes.map { it.simpleName } ==
            listOf("Consumer", "Consumer", "Action")
    } ?: return null
    return callbackFlow {
        fun callback(type: Class<*>, action: (Any?) -> Unit): Any =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "accept", "run" -> { action(args?.firstOrNull()); null }
                    "toString" -> "StreamingContentCallback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
        val types = subscribe.parameterTypes
        val next = callback(types[0]) { emission ->
            if (emission != null) {
                // A finite feed must not silently drop a row if its consumer is slower.
                val result = trySend(emission)
                if (result.isFailure && !result.isClosed) close(IllegalStateException("Content feed buffer overflow"))
            }
        }
        val error = callback(types[1]) { close(it as? Throwable ?: IllegalStateException("Content feed failed")) }
        val complete = callback(types[2]) { close() }
        val disposable = try {
            subscribe.invoke(value, next, error, complete)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
        // Cancelling a navigation/search disposes the HTTP-backed observable as well.
        awaitClose {
            disposable?.javaClass?.methods?.firstOrNull { it.name == "dispose" && it.parameterCount == 0 }
                ?.invoke(disposable)
        }
    }
}

/**
 * A hot SmartTube observable may not call onComplete after delivering its rows.
 * Bound how long a refresh observes it, but keep every emission collected so far.
 *
 * Returns false when the observation window expires, not when the caller is cancelled.
 */
internal suspend fun <T> Flow<T>.collectBoundedSnapshot(
    timeoutMs: Long,
    maxEmissions: Int,
    onEmission: suspend (T) -> Unit,
): Boolean = withTimeoutOrNull(timeoutMs) {
    take(maxEmissions).collect { onEmission(it) }
    true
} ?: false

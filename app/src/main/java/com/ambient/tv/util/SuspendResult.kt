package com.ambient.tv.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Result boundary for suspending work: cancellation must never become an ordinary UI error. */
suspend inline fun <T> suspendResult(block: () -> T): Result<T> {
    currentCoroutineContext().ensureActive()
    return try {
        val value = block()
        currentCoroutineContext().ensureActive()
        Result.success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
    }
}

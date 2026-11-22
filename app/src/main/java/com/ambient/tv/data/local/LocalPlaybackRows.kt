package com.ambient.tv.data.local

import kotlinx.coroutines.CompletableDeferred

/**
 * Bounded synchronous memory front. SQL never runs in a getter. Mutations that arrive before
 * priming are replayed over the loaded data, so an old disk snapshot cannot overwrite a new edit.
 */
internal class LocalPlaybackRows<T>(
    private val database: LocalPlaybackDatabase,
    private val namespace: String,
    private val limit: Int,
    private val ascending: Boolean = false,
    private val legacy: () -> List<LocalPlaybackRow>,
    private val cleanup: () -> Unit,
    private val decode: (String) -> T?,
    private val encode: (T) -> String,
    private val changed: () -> Unit,
    private val primed: () -> Unit = changed,
) {
    private data class Value<T>(val value: T, val source: String, val scope: String, val order: Long)
    private data class Pending<T>(val serial: Long, val fields: Set<String>, val apply: (MutableMap<String, Value<T>>) -> Unit)
    private val lock = Any()
    private val values = linkedMapOf<String, Value<T>>()
    private val early = linkedMapOf<String, MutableList<Pending<T>>>()
    private var earlyCount = 0
    private var earlySerial = 0L
    private var allowedScopes: Map<String, String>? = null
    private var snapshot: List<T>? = null
    private var sequence = 0L
    private var loaded = false
    private var loading = false
    private var ready = CompletableDeferred<Unit>()

    fun start() {
        val completion = synchronized(lock) {
            if (loaded || loading) return
            loading = true
            // A previous failure may still be publishing its completion outside this lock.
            // Each attempt owns its Deferred, so it cannot poison a newly started retry.
            ready = CompletableDeferred()
            ready
        }
        database.load(namespace, limit, legacy, cleanup, success = { rows ->
            synchronized(lock) {
                val loadedValues = linkedMapOf<String, Value<T>>()
                rows.forEach { row -> decode(row.payload)?.let { value ->
                    loadedValues[row.key] = Value(value, row.sourceId, row.scope, row.order)
                    sequence = maxOf(sequence, row.order)
                } }
                val initial = loadedValues.toMap()
                early.values.flatten().sortedBy { it.serial }.forEach { it.apply(loadedValues) }
                allowedScopes?.let { scopes -> loadedValues.entries.removeAll { scopes[it.value.source] != it.value.scope } }
                early.clear(); earlyCount = 0
                values.clear(); values.putAll(loadedValues)
                loaded = true; loading = false; snapshot = null
                trim()
                persistDifferences(initial, immediate = true)
            }
            // Callbacks run without the row lock: source reconciliation uses the source lock
            // before this row lock, just like a synchronous favorite/progress edit.
            primed()
            completion.complete(Unit)
        }, failure = { error ->
            synchronized(lock) { loading = false }
            timber.log.Timber.w(error, "Local playback state could not be primed")
            completion.completeExceptionally(error)
        })
    }

    suspend fun awaitReady() {
        start()
        synchronized(lock) { ready }.await()
    }

    fun all(): List<T> = synchronized(lock) {
        snapshot ?: values.values.sortedByDescending { it.order }.let { ordered ->
            (if (ascending) ordered.asReversed() else ordered).map { it.value }
        }.also { snapshot = it }
    }

    fun get(key: String): T? = synchronized(lock) { values[key]?.value }

    fun update(key: String, source: String, scope: String, order: Long, immediate: Boolean = false,
               keepOrder: Boolean = false, pendingFields: Set<String> = setOf("*"), edit: (T?) -> T?) {
        synchronized(lock) {
            val action: (MutableMap<String, Value<T>>) -> Unit = { target ->
                val previous = target[key]?.takeIf { it.scope == scope }
                val value = edit(previous?.value)
                if (value == null) target.remove(key)
                else {
                    val nextOrder = if (keepOrder && previous != null) previous.order else {
                        sequence = maxOf(order, sequence + 1)
                        sequence
                    }
                    target[key] = Value(value, source, scope, nextOrder)
                }
            }
            val before = values[key]
            if (!loaded) {
                val edits = early.getOrPut(key) { mutableListOf() }
                val removed = edits.filter { old -> old.fields.isNotEmpty() &&
                    ("*" in pendingFields || pendingFields.containsAll(old.fields)) }
                edits.removeAll(removed.toSet())
                earlyCount -= removed.size
                // Production callers await readiness. Keep accidental cold callers bounded
                // during a persistent disk failure, rather than growing an unbounded queue.
                if (earlyCount >= 4_096) {
                    if (edits.isEmpty()) early.remove(key)
                    error("Local playback state is not ready")
                }
                edits += Pending(++earlySerial, pendingFields, action)
                earlyCount++
            }
            action(values)
            snapshot = null
            if (!loaded) trim()
            else {
                val after = values[key]
                if (after != before) {
                    if (after != null) persist(key, after, immediate)
                    else if (before != null) database.delete(namespace, key, before.scope, immediate)
                }
                // Normal progress replaces one record in O(1); only insertion at the capacity
                // boundary selects an eviction. No full-map copy/JSON serialization per tick.
                while (values.size > limit) {
                    val oldest = values.minBy { it.value.order }
                    values.remove(oldest.key)
                    database.delete(namespace, oldest.key, oldest.value.scope, immediate)
                }
            }
        }
        changed()
    }

    /** Source identity is captured by the caller before entering the row lock. */
    fun retainScopes(scopes: Map<String, String>) {
        synchronized(lock) {
            allowedScopes = scopes
            val removed = values.filterValues { scopes[it.source] != it.scope }
            removed.keys.forEach(values::remove)
            if (removed.isNotEmpty()) snapshot = null
            if (loaded) removed.forEach { (key, value) -> database.delete(namespace, key, value.scope, immediate = true) }
        }
    }

    internal val pendingEditCount: Int get() = synchronized(lock) { earlyCount }

    private fun trim() {
        if (values.size <= limit) return
        values.entries.sortedBy { it.value.order }.take(values.size - limit).forEach { values.remove(it.key) }
    }

    private fun persistDifferences(before: Map<String, Value<T>>, immediate: Boolean) {
        before.forEach { (key, previous) ->
            if (key !in values) database.delete(namespace, key, previous.scope, immediate)
        }
        values.forEach { (key, value) ->
            if (before[key] != value) persist(key, value, immediate)
        }
    }

    private fun persist(key: String, value: Value<T>, immediate: Boolean) = database.put(namespace,
        LocalPlaybackRow(key, value.source, value.scope, value.order, encode(value.value)), immediate)
}

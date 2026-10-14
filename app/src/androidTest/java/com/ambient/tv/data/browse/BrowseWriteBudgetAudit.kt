package com.ambient.tv.data.browse

import android.app.Instrumentation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

internal class BrowseWriteBudgetAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    fun run() = runBlocking {
        check(instrumentation.targetContext.packageName.endsWith(".uiperfaudit"))
        val store = BrowseDiskStore(instrumentation.targetContext)
        val scope = "audit-write-${System.nanoTime()}"
        val key = "entry"
        BrowseWorkBudget.foreground = false
        suspend fun awaitValue(value: String) = withTimeout(15_000) {
            while (store.read(scope, key)[key] != value) delay(25)
        }
        try {
            store.put(scope, key, { true }) { "previous-complete" }
            awaitValue("previous-complete")
            val attempts = AtomicInteger()
            val serialized = AtomicInteger()
            val delegate = ListSerializer(String.serializer())
            val values = List(256) { "metadata-$it-" + "data ".repeat(200) }
            val serializer = object : KSerializer<List<String>> {
                override val descriptor: SerialDescriptor = delegate.descriptor
                override fun deserialize(decoder: Decoder) = delegate.deserialize(decoder)
                override fun serialize(encoder: Encoder, value: List<String>) {
                    val output = encoder.beginCollection(descriptor, value.size)
                    value.forEachIndexed { index, item ->
                        serialized.incrementAndGet()
                        if (index == 32 && attempts.get() == 1) {
                            BrowseWorkBudget.foreground = true
                            BrowseWorkBudget.interact()
                        }
                        output.encodeStringElement(descriptor, index, item)
                    }
                    output.endStructure(descriptor)
                }
            }
            store.put(scope, key, { true }) {
                attempts.incrementAndGet()
                encodeBrowseJson(Json, serializer, values)
            }
            withTimeout(15_000) { while (serialized.get() < 33) delay(25) }
            check(store.read(scope, key)[key] == "previous-complete")
            check(serialized.get() < values.size) { "Serialization ignored input until the entire snapshot was encoded" }
            log("PASS input interrupts serialization after ${serialized.get()}/${values.size} elements; previous entry remains intact")
            BrowseWorkBudget.foreground = false
            awaitValue(Json.encodeToString(delegate, values))
            check(attempts.get() >= 2)
            log("PASS interrupted snapshot retries after idle and persists completely")
            store.put(scope, key, { true }) {
                BrowseWorkBudget.foreground = true
                BrowseWorkBudget.interact()
                store.put(scope, key, { true }) { "newest" }
                encodeBrowseJson(Json, values)
            }
            withTimeout(15_000) { while (!BrowseWorkBudget.foreground) delay(25) }
            store.read(scope, key) // Barrier: finish the interrupted disk task before resuming.
            BrowseWorkBudget.foreground = false
            awaitValue("newest")
            log("PASS retry never replaces a newer queued snapshot")
            val unicode = "a".repeat(32 * 1024 - 1) + "😀tail"
            store.put(scope, key, { true }) { unicode }
            awaitValue(unicode)
            log("PASS chunk boundaries preserve Unicode surrogate pairs")
        } finally {
            BrowseWorkBudget.foreground = false
            store.remove(scope)
        }
    }
}

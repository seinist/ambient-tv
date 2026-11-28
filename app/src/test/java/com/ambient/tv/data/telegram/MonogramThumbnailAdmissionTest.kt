package com.ambient.tv.data.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class MonogramThumbnailAdmissionTest {
    @Test fun demandAndPromotedWaitersBypassSpeculativeQueue() = runBlocking {
        withTimeout(5_000) {
            val admission = MonogramThumbnailAdmission(2, 1)
            val gate = CompletableDeferred<Unit>()
            val speculative = launch(start = CoroutineStart.UNDISPATCHED) {
                admission.withPermit(TelegramTransferPriority(false)) { gate.await() }
            }
            val order = ArrayList<String>()
            val low = launch(start = CoroutineStart.UNDISPATCHED) {
                admission.withPermit(TelegramTransferPriority(false)) { order.add("low") }
            }
            val promoted = TelegramTransferPriority(false)
            val high = launch(start = CoroutineStart.UNDISPATCHED) {
                admission.withPermit(promoted) { order.add("high") }
            }
            assertFalse(high.isCompleted)
            promoted.promote()
            high.join()
            assertEquals(listOf("high"), order)
            gate.complete(Unit)
            speculative.join()
            low.join()
            assertEquals(listOf("high", "low"), order)
        }
    }

    @Test fun cancelledWaiterDoesNotConsumeCapacity() = runBlocking {
        withTimeout(5_000) {
            val admission = MonogramThumbnailAdmission(1, 1)
            val gate = CompletableDeferred<Unit>()
            val running = launch(start = CoroutineStart.UNDISPATCHED) {
                admission.withPermit(TelegramTransferPriority(true)) { gate.await() }
            }
            val abandoned = launch(start = CoroutineStart.UNDISPATCHED) {
                admission.withPermit(TelegramTransferPriority(false)) { error("cancelled waiter ran") }
            }
            abandoned.cancelAndJoin()
            gate.complete(Unit)
            running.join()
            assertEquals(42, admission.withPermit(TelegramTransferPriority(true)) { 42 })
        }
    }
}

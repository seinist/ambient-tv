package com.swordfish.libretrodroid

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestRenderRequestTest {
    private val fifo = ArrayDeque<() -> Unit>()
    private val applied = mutableListOf<String>()
    private val requests = LatestRenderRequest<String>({ fifo.addLast(it) }, { applied += it })
    private fun drain() { while (fifo.isNotEmpty()) fifo.removeFirst().invoke() }

    @Test fun onlyLatestQueuedShaderIsAppliedWithoutReorderingOtherGlWork() {
        requests.submit("shader A")
        fifo.addLast { applied += "surface transition" }
        requests.submit("shader B")
        requests.submit("shader C")
        drain()
        assertEquals(listOf("surface transition", "shader C"), applied)
    }

    @Test fun aRequestDuringApplicationIsNotLost() {
        lateinit var nested: LatestRenderRequest<String>
        nested = LatestRenderRequest({ fifo.addLast(it) }, {
            applied += it
            if (it == "first") nested.submit("second")
        })
        nested.submit("first")
        drain()
        assertEquals(listOf("first", "second"), applied)
    }

    @Test fun separateRendererOwnersDoNotSupersedeEachOther() {
        val other = LatestRenderRequest<String>({ fifo.addLast(it) }, { applied += it })
        requests.submit("first")
        other.submit("other")
        drain()
        assertEquals(listOf("first", "other"), applied)
    }
}

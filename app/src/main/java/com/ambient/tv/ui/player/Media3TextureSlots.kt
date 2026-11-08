package com.ambient.tv.ui.player

/** A pass whose named output can share a same-size, same-format texture with other passes. */
internal data class Media3TexturePass(val inputs: List<String>, val output: String?)

/** A negative slot leaves that pass's output in a separate, caller-owned render target. */
internal class Media3TextureSlotPlan(val slotByPass: IntArray, val slotCount: Int)

/**
 * Plan once, before allocating GL textures. Inputs without an earlier producer are external.
 * Each write is a new value, even when a model reuses an output name such as MODEL1.
 * A slot becomes free only after its last reader: that reader must never render into the
 * texture it is sampling. Null outputs still consume inputs but do not enter the pool.
 */
internal fun media3TextureSlotPlan(passes: List<Media3TexturePass>): Media3TextureSlotPlan {
    val lastUse = IntArray(passes.size) { it }
    val producers = mutableMapOf<String, Int>()
    passes.forEachIndexed { index, pass ->
        pass.inputs.forEach { name ->
            producers[name]?.let { lastUse[it] = index }
        }
        pass.output?.let { producers[it] = index }
    }

    val slotByPass = IntArray(passes.size) { -1 }
    val occupiedUntil = mutableListOf<Int>()
    passes.forEachIndexed { index, pass ->
        if (pass.output != null) {
            val free = occupiedUntil.indexOfFirst { it < index }
            val slot = if (free >= 0) free else occupiedUntil.size
            if (free >= 0) occupiedUntil[slot] = lastUse[index]
            else occupiedUntil += lastUse[index]
            slotByPass[index] = slot
        }
    }
    return Media3TextureSlotPlan(slotByPass, occupiedUntil.size)
}

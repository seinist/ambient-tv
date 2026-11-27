package com.ambient.tv.data.local

/** Device-local opt-ins. The native modules may be packaged without enabling
 * any experimental playback route. Child selections imply their parents;
 * removing a parent clears all dependent selections. */
object VlcExperimentFlags {
    const val AHB = 1
    const val DOVI = 2
    const val FEL = 4
    const val HDR10PLUS = 8
    private const val ALL = AHB or DOVI or FEL or HDR10PLUS

    fun normalize(value: Int): Int {
        val known = value and ALL
        if (known and AHB == 0) return 0
        return if (known and DOVI == 0) known and FEL.inv() else known
    }

    fun enabled(value: Int, flag: Int): Boolean = normalize(value) and flag == flag

    fun toggle(value: Int, flag: Int, enabled: Boolean): Int {
        val old = normalize(value)
        val result = if (enabled) when (flag) {
            AHB -> old or AHB
            DOVI -> old or AHB or DOVI
            FEL -> old or AHB or DOVI or FEL
            HDR10PLUS -> old or AHB or HDR10PLUS
            else -> old
        } else when (flag) {
            AHB -> 0
            DOVI -> old and (DOVI or FEL).inv()
            FEL, HDR10PLUS -> old and flag.inv()
            else -> old
        }
        return normalize(result)
    }
}

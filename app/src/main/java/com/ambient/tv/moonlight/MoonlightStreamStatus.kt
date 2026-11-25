package com.ambient.tv.moonlight

data class MoonlightStreamStatus(
    val phase: MoonlightStreamPhase = MoonlightStreamPhase.IDLE,
    val title: String = "",
    val detail: String = "",
    val error: String? = null,
) {
    val active: Boolean get() = phase in setOf(
        MoonlightStreamPhase.CONNECTING, MoonlightStreamPhase.STREAMING, MoonlightStreamPhase.STOPPING)
}

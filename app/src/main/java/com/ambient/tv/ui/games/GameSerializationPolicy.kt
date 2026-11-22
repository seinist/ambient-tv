package com.ambient.tv.ui.games

/** Core-declared libretro contracts, separate from the catalogue's advertised capabilities. */
internal data class GameSerializationPolicy(val quirks: Long = 0) {
    val mustInitialize: Boolean get() = quirks and MUST_INITIALIZE != 0L
    val persistentStates: Boolean get() = quirks and SINGLE_SESSION == 0L
    // Rewind uses a fixed-size ring; netplay requires frame-exact, portable state.
    val frameExact: Boolean get() = quirks and (INCOMPLETE or VARIABLE_SIZE or SINGLE_SESSION) == 0L

    companion object {
        const val INCOMPLETE = 1L
        const val MUST_INITIALIZE = 1L shl 1
        const val VARIABLE_SIZE = 1L shl 2
        const val SINGLE_SESSION = 1L shl 4
    }
}

package com.ambient.tv.moonlight

/** Result of verified native pairing, not a network reachability probe. */
sealed interface MoonlightPairResult {
    data class Paired(val hostId: String) : MoonlightPairResult
    data object AlreadyPaired : MoonlightPairResult
    data object IncorrectPin : MoonlightPairResult
    data object Busy : MoonlightPairResult
    data object Rejected : MoonlightPairResult
    data object Unavailable : MoonlightPairResult
}

package com.ambient.tv.moonlight

import javax.inject.Inject
import javax.inject.Singleton

/** No fake pairing or playable catalog when native Moonlight is not built. */
@Singleton
class MoonlightSystemTransport @Inject constructor() {
    val available = false
    fun newPairingPin(): String = ""
    fun locallyPaired(hostId: String): Boolean = false
    suspend fun pair(hostId: String, pin: String): MoonlightPairResult = MoonlightPairResult.Unavailable
    suspend fun applications(hostId: String): MoonlightAppCatalog =
        throw UnsupportedOperationException("Moonlight native transport is not included")
    fun forget(hostId: String) = Unit
}

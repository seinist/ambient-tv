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
    suspend fun verify(hostId: String): String = throw UnsupportedOperationException("Moonlight native transport is not included")
    suspend fun artwork(hostId: String, appId: Int): ByteArray =
        throw UnsupportedOperationException("Moonlight native transport not installed")
    suspend fun quitRemoteApp(hostId: String, expectedAppId: Int): Boolean = false
    suspend fun resetClientIdentity() = Unit
    fun forget(hostId: String) = Unit
}

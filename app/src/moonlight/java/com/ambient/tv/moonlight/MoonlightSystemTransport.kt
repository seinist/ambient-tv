package com.ambient.tv.moonlight

import javax.inject.Inject
import javax.inject.Singleton

/** Real GameStream control plane, linked only when MOONLIGHT_NATIVE=1. */
@Singleton
class MoonlightSystemTransport @Inject constructor(
    private val backend: MoonlightNativeTransport,
    private val trust: MoonlightHostTrust,
) {
    val available = true
    fun newPairingPin(): String = backend.pairingPin()
    fun locallyPaired(hostId: String): Boolean = trust.isPaired(hostId)
    suspend fun pair(hostId: String, pin: String): MoonlightPairResult = backend.pair(hostId, pin)
    suspend fun applications(hostId: String): MoonlightAppCatalog = backend.loadApplications(hostId)
    suspend fun verify(hostId: String): String = backend.verifiedHost(hostId).identity
    suspend fun proof(hostId: String): MoonlightHostProof = backend.verifiedHost(hostId).let {
        MoonlightHostProof(it.identity, it.runningAppId)
    }
    suspend fun artwork(hostId: String, appId: Int): ByteArray = backend.loadArtwork(hostId, appId)
    suspend fun quitRemoteApp(hostId: String, expectedAppId: Int): Boolean = backend.quitRemoteApp(hostId, expectedAppId)
    suspend fun resetClientIdentity() = backend.resetClientIdentity()
    fun forget(hostId: String) = backend.forgetHost(hostId)
}

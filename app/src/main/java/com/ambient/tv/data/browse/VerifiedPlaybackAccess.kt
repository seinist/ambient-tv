package com.ambient.tv.data.browse

/**
 * Only a successful uncached read of protected media data may create a proof. Host recovery,
 * public server identity and cached browse data deliberately have no way to grant access here.
 * The caller's key includes the selected endpoint/login, network and latest source outage.
 */
internal class VerifiedPlaybackAccess {
    private val proofs = LinkedHashMap<String, String>()

    @Synchronized fun contains(source: String, currentScope: String): Boolean = proofs[source] == currentScope

    @Synchronized fun confirm(source: String, startedScope: String, currentScope: String): Boolean {
        if (startedScope != currentScope) return false
        proofs[source] = currentScope
        while (proofs.size > 128) proofs.remove(proofs.keys.first())
        return true
    }

    @Synchronized fun invalidate(source: String) { proofs.remove(source) }
}

data class PlaybackAccessResult(
    val allowed: Boolean,
    val system: String,
    val name: String,
    val authRequired: Boolean = false,
)

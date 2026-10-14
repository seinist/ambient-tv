package com.ambient.tv.companion

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Local preferences keyed by the pinned TV identity, never supplied by a remote peer. */
@Serializable
data class CompanionConnectionSettings(
    val wireGuard: Boolean = false,
    val vpnHost: String = "",
    val port: Int = 0,
    val wake: Boolean = false,
    val mac: String = "",
    val broadcast: String = "",
    val wakePort: Int = 9,
) {
    fun valid(): Boolean = (!wireGuard || isPrivateV4(vpnHost)) && port in 0..65535 &&
        (!wake || validCompanionMac(mac)) && (mac.isBlank() || validCompanionMac(mac)) &&
        (broadcast.isBlank() || isPrivateV4(broadcast) || broadcast == "255.255.255.255") && wakePort in 1..65535
}

internal fun validCompanionMac(value: String): Boolean {
    val hex = value.replace(":", "").replace("-", "")
    return hex.matches(Regex("[0-9a-fA-F]{12}")) && hex != "000000000000" &&
        !hex.equals("020000000000", true) && hex.take(2).toInt(16) and 1 == 0
}
internal fun canonicalCompanionMac(value: String): String =
    value.replace(":", "").replace("-", "").uppercase().chunked(2).joinToString(":")

@Singleton
class CompanionConnectionStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("companion_connections_v1", Context.MODE_PRIVATE)
    fun get(pin: String): CompanionConnectionSettings = runCatching {
        companionJson.decodeFromString<CompanionConnectionSettings>(prefs.getString(pin, "{}")!!)
    }.getOrDefault(CompanionConnectionSettings())
    @Synchronized fun put(pin: String, settings: CompanionConnectionSettings) {
        require(pin.matches(Regex("[a-f0-9]{64}")) && settings.valid())
        check(prefs.edit().putString(pin, companionJson.encodeToString(settings)).commit())
    }
    @Synchronized fun learnMac(pin: String, value: String) {
        if (!validCompanionMac(value)) return
        val current = get(pin)
        if (current.mac.isBlank()) put(pin, current.copy(mac = canonicalCompanionMac(value)))
    }
    fun remove(pin: String) { prefs.edit().remove(pin).apply() }
    fun vpnHosts(): List<String> = prefs.all.keys.map(::get).filter { it.wireGuard && it.valid() }.map { it.vpnHost }.distinct()
}

/** Subnet exception is TV-only and never authorizes pairing or bypasses pinned authentication. */
internal fun companionAddressAllowed(sameSubnet: Boolean, television: Boolean, address: String): Boolean =
    isPrivateV4(address) && (sameSubnet || television)

/** Request IDs are scoped to a paired identity. A repeated ID cannot replace a different title. */
internal class CompanionStartHistory(private val limit: Int = 64) {
    private val completed = LinkedHashMap<Pair<String, String>, CompanionMedia>()
    fun alreadyCompleted(pin: String, request: CompanionFrame): Boolean {
        if (request.startRequestId.isEmpty()) return false
        val media = completed[pin to request.startRequestId] ?: return false
        require(media == request.media) { "Start ID reused for different media" }
        return true
    }
    fun complete(pin: String, request: CompanionFrame) {
        if (request.startRequestId.isEmpty()) return
        completed[pin to request.startRequestId] = requireNotNull(request.media)
        while (completed.size > limit) completed.remove(completed.keys.first())
    }
}

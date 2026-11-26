package com.ambient.tv.moonlight

import java.net.IDN
import java.util.Locale
import kotlinx.serialization.Serializable

/** Host metadata only. Pairing certificates and private keys never belong in portable profiles. */
@Serializable
data class MoonlightHost(
    val id: String,
    val host: String,
    val port: Int = MoonlightHostAddress.DEFAULT_HTTP_PORT,
    val name: String = "",
    val macAddress: String = "",
) {
    fun validated(): MoonlightHost {
        val canonical = requireNotNull(MoonlightHostAddress.normalize(host, port)) { "Invalid Moonlight host or port" }
        require(validMoonlightHostId(id))
        require(name.length <= 128 && !name.any { it.isISOControl() })
        val mac = macAddress.trim().uppercase(Locale.ROOT).replace('-', ':')
        require(mac.isEmpty() || Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac))
        return copy(host = canonical, name = name.trim(), macAddress = mac)
    }
}

/** No DNS/network access when accepting input: ports are separate and URLs/credentials are rejected. */
object MoonlightHostAddress {
    const val DEFAULT_HTTP_PORT = 47989
    const val DEFAULT_HTTPS_PORT = 47984
    private val dnsPart = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val numericIpv6 = Regex("[0-9a-f:.]+")

    fun normalize(input: String, port: Int = DEFAULT_HTTP_PORT): String? {
        if (port !in 1..65535) return null
        val host = input.trim()
        if (host.isEmpty() || host.length > 255 || host.any { it.isWhitespace() || it.isISOControl() }) return null
        if (host.any { it == '/' || it == '\\' || it == '@' || it == '?' || it == '#' }) return null
        val bracketed = host.startsWith("[") && host.endsWith("]")
        val bare = if (bracketed) host.substring(1, host.length - 1) else host
        if ('[' in bare || ']' in bare) return null
        if (':' in bare) {
            if (!numericIpv6.matches(bare.lowercase(Locale.ROOT)) || bare.count { it == ':' } < 2) return null
            return try {
                if (java.net.InetAddress.getByName(bare) is java.net.Inet6Address)
                    bare.lowercase(Locale.ROOT) else null
            } catch (_: Exception) { null }
        }
        if (bracketed) return null
        val ascii = try { IDN.toASCII(bare.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
                    catch (_: IllegalArgumentException) { return null }
        if (ascii.isEmpty() || ascii.length > 253 || ascii.split('.').any { !dnsPart.matches(it) }) return null
        return ascii
    }

    fun displayHost(host: String): String = if (':' in host) "[" + host + "]" else host
}

internal fun validMoonlightHostId(id: String): Boolean = id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))

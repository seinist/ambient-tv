package com.ambient.tv.network

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * LAN-only mode at connect time. The request interceptors check a host name before the call,
 * but the socket resolves it again; a DNS answer that switches to a public address in between
 * (DNS rebinding) would otherwise bypass the check. While [lanOnly] is on, only local addresses
 * from the lookup are ever connected to.
 */
class LanOnlyDns(
    private val lanOnly: () -> Boolean,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (!lanOnly()) return addresses
        return addresses.filter(LocalNetworkPolicy::isLocalAddress).ifEmpty {
            throw UnknownHostException("$hostname is blocked by LAN-only mode")
        }
    }
}

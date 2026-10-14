package com.ambient.tv.vpn

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tunnel files as the common providers and tools write them. */
class WgConfigCorpusTest {
    private val k1 = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val k2 = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
    private val k3 = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })

    private fun parse(text: String) = parseWireGuardConfig(text)

    @Test fun `wireguard app and wg-quick export with comments and PostUp`() {
        val cfg = parse("""
            # Home VPN — exported
            [Interface]
            PrivateKey = $k1  # keep secret
            Address = 10.66.66.2/32, fd42:42:42::2/128
            DNS = 10.66.66.1
            MTU = 1380
            ListenPort = 51820
            PostUp = iptables -A FORWARD -i %i -j ACCEPT; ip route add 10.0.0.0/8 dev %i
            PostDown = iptables -D FORWARD -i %i -j ACCEPT
            Table = off
            SaveConfig = true

            [Peer]
            PublicKey = $k2
            PresharedKey = $k3
            Endpoint = vpn.example.org:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
        """.trimIndent())
        assertEquals("10.66.66.2", cfg.profile.address)
        assertEquals(32, cfg.profile.prefix)
        assertEquals("10.66.66.1", cfg.profile.dns)
        assertEquals(1380, cfg.profile.mtu)
        assertEquals("vpn.example.org", cfg.profile.endpointHost)
        assertEquals(listOf("0.0.0.0/0"), cfg.profile.allowedIps)
        assertEquals(k3, cfg.presharedKey)
    }

    @Test fun `fritzbox export with search domain`() {
        val cfg = parse("""
            [Interface]
            PrivateKey = $k1
            Address = 192.168.178.201/24
            DNS = 192.168.178.1, fritz.box

            [Peer]
            PublicKey = $k2
            PresharedKey = $k3
            AllowedIPs = 192.168.178.0/24, 0.0.0.0/0
            Endpoint = abcdefghij0123456789.myfritz.net:54321
            PersistentKeepalive = 25
        """.trimIndent())
        assertEquals("192.168.178.1", cfg.profile.dns)
        assertEquals("abcdefghij0123456789.myfritz.net", cfg.profile.endpointHost)
        assertEquals(54321, cfg.profile.endpointPort)
        assertEquals(24, cfg.profile.prefix)
    }

    @Test fun `mullvad file`() {
        val cfg = parse("""
            [Interface]
            # Device: Calm Fox
            PrivateKey = $k1
            Address = 10.69.12.34/32,fc00:bbbb:bbbb:bb01::6:c22/128
            DNS = 10.64.0.1

            [Peer]
            PublicKey = $k2
            AllowedIPs = 0.0.0.0/0,::0/0
            Endpoint = 185.213.154.68:51820
        """.trimIndent())
        assertEquals("10.69.12.34", cfg.profile.address)
        assertEquals("185.213.154.68", cfg.profile.endpointHost)
        assertEquals(WG_DEFAULT_MTU, cfg.profile.mtu)
    }

    @Test fun `protonvpn file`() {
        val cfg = parse("""
            [Interface]
            # Key for username
            # Bouncing = 4
            # NetShield = 1
            # Moderate NAT = off
            PrivateKey = $k1
            Address = 10.2.0.2/32
            DNS = 10.2.0.1

            [Peer]
            # NL#42
            PublicKey = $k2
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 146.70.1.2:51820
        """.trimIndent())
        assertEquals("10.2.0.1", cfg.profile.dns)
        assertEquals(51820, cfg.profile.endpointPort)
    }

    @Test fun `cloudflare warp wgcf profile with ipv6 and hostname endpoint`() {
        val cfg = parse("""
            [Interface]
            PrivateKey = $k1
            Address = 172.16.0.2/32
            Address = 2606:4700:110:8a36:df92:102a:9602:fa2f/128
            DNS = 1.1.1.1
            MTU = 1280
            [Peer]
            PublicKey = $k2
            AllowedIPs = 0.0.0.0/0
            AllowedIPs = ::/0
            Endpoint = engage.cloudflareclient.com:2408
        """.trimIndent())
        assertEquals("172.16.0.2", cfg.profile.address)
        assertEquals("engage.cloudflareclient.com", cfg.profile.endpointHost)
        assertEquals(2408, cfg.profile.endpointPort)
    }

    @Test fun `ipv6 endpoint literal`() {
        val cfg = parse("[Interface]\nPrivateKey = $k1\nAddress = 10.0.0.2\n[Peer]\nPublicKey = $k2\nAllowedIPs = 10.0.0.0/24\nEndpoint = [2001:db8::1]:51820")
        assertEquals("2001:db8::1", cfg.profile.endpointHost)
        assertEquals(32, cfg.profile.prefix) // address without prefix
    }

    @Test fun `windows line endings, bom, quotes, odd spacing and case`() {
        val text = "﻿[interface]\r\nprivatekey=\"$k1\"\r\nADDRESS =10.0.0.2/24\r\ndns= 9.9.9.9 \r\n\r\n[PEER]\r\nPublicKey= $k2\r\nallowedips = 10.0.0.0/24 , 192.168.0.0/16\r\nendpoint =1.2.3.4:51820\r\npersistentkeepalive = off\r\n"
        val cfg = parse(text)
        assertEquals("10.0.0.2", cfg.profile.address)
        assertEquals(listOf("10.0.0.0/24", "192.168.0.0/16"), cfg.profile.allowedIps)
        assertEquals(0, cfg.profile.keepaliveSeconds)
        assertEquals(k1, cfg.privateKey)
    }

    @Test fun `utf16 files from windows tools decode like utf8`() {
        val text = "[Interface]\r\nPrivateKey = $k1\r\nAddress = 10.0.0.2/32\r\n[Peer]\r\nPublicKey = $k2\r\nAllowedIPs = 10.0.0.0/24\r\nEndpoint = 1.2.3.4:1\r\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        val noMark = text.toByteArray(Charsets.UTF_16LE)
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()
        for (bytes in listOf(le, be, noMark, utf8Bom, text.toByteArray())) {
            assertEquals("1.2.3.4", parse(decodeWireGuardBytes(bytes)).profile.endpointHost)
        }
    }

    @Test fun `keys tolerate wrapped lines and a missing pad`() {
        val unpadded = k1.trimEnd('=')
        assertEquals(32, decodeKey(unpadded).size)
        assertEquals(32, decodeKey(k1.substring(0, 20) + "\n  " + k1.substring(20)).size)
    }

    @Test fun `server style file with several peers uses the one with an endpoint`() {
        val cfg = parse("""
            [Interface]
            PrivateKey = $k1
            Address = 10.0.0.1/24
            ListenPort = 51820
            [Peer]
            PublicKey = $k3
            AllowedIPs = 10.0.0.2/32
            [Peer]
            PublicKey = $k2
            AllowedIPs = 10.0.0.0/24
            Endpoint = hub.example.com:51820
        """.trimIndent())
        assertEquals(k2, cfg.profile.peerPublicKey)
        assertEquals(1, cfg.ignoredPeers)
    }

    @Test fun `unknown sections are ignored and mistakes are named`() {
        val cfg = parse("[Interface]\nPrivateKey = $k1\nAddress = 10.0.0.2/32\n[Socks5]\nBindAddress = 127.0.0.1:1080\n[Peer]\nPublicKey = $k2\nAllowedIPs = 10.0.0.0/24\nEndpoint = 1.2.3.4:51820")
        assertEquals("1.2.3.4", cfg.profile.endpointHost)
        fun why(text: String) = runCatching { parse(text) }.exceptionOrNull()?.message
        assertEquals("[Peer] section is missing", why("[Interface]\nPrivateKey = $k1\nAddress = 10.0.0.2/32"))
        assertEquals("Endpoint is missing", why("[Interface]\nPrivateKey = $k1\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = $k2\nAllowedIPs = 10.0.0.0/24"))
        assertTrue(why("[Interface]\nPrivateKey = $k1\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = $k2\nAllowedIPs = 10.0.0.0/24\nEndpoint = 2001:db8::1:51820")!!.startsWith("Endpoint must be"))
        assertNull(runCatching { parse("garbage") }.getOrNull())
    }
}

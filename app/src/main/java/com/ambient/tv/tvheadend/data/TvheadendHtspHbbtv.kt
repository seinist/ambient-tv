package com.ambient.tv.tvheadend.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Optional, short-lived TVHeadend HTSP metadata probe (port 9982 by default).
 *
 * Unlike /api/raw/export, channelAdd carries the service's decoded HbbTV AIT to ordinary
 * HTSP viewers. No stream subscription is opened; failures must never affect Live TV.
 */
internal object TvheadendHtspHbbtv {
    fun discover(host: String, port: Int, username: String, password: String, timeoutMs: Int = 1_500): Map<String, List<TvhHbbtvApplication>> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = 3_000
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            fun send(seq: Long, method: String, extra: Map<String, Any> = emptyMap()) {
                output.write(tvhHtspEncode(mapOf("seq" to seq, "method" to method) + extra))
                output.flush()
            }
            fun reply(seq: Long): Map<String, Any> {
                repeat(16) {
                    val response = tvhHtspDecode(input)
                    if (response["seq"] == seq) return response
                }
                error("HTSP reply missing")
            }

            send(1, "hello", mapOf("htspversion" to 42L, "clientname" to "Ambient TV",
                "clientversion" to "1"))
            val hello = reply(1)
            val challenge = hello["challenge"] as? ByteArray ?: error("HTSP challenge missing")
            val credentials = if (username.isNotBlank()) {
                val sha1 = MessageDigest.getInstance("SHA-1")
                sha1.update(password.toByteArray(Charsets.UTF_8))
                sha1.update(challenge)
                mapOf("username" to username, "digest" to sha1.digest())
            } else emptyMap()
            send(2, "authenticate", credentials)
            check(reply(2)["noaccess"] != 1L) { "HTSP user has no access" }
            send(3, "enableAsyncMetadata", mapOf("epg" to 0L))
            val appsByChannel = linkedMapOf<String, List<TvhHbbtvApplication>>()
            val deadline = System.nanoTime() + 8_000_000_000L
            repeat(20_000) {
                check(System.nanoTime() < deadline) { "HTSP initial metadata timed out" }
                val message = tvhHtspDecode(input)
                when (message["method"]) {
                    "channelAdd", "channelUpdate" -> {
                        val id = (message["channelIdStr"] as? String)?.takeIf(String::isNotBlank) ?: return@repeat
                        val services = message["services"] as? List<*> ?: return@repeat
                        val applications = services.flatMap { service ->
                            val hbbtv = (service as? Map<*, *>)?.get("hbbtv")
                            tvhHbbtvApplications(tvhHtspServiceJson(hbbtv))
                        }.distinctBy { it.url }
                        if (applications.isNotEmpty()) appsByChannel[id] = applications
                    }
                    "channelDelete" -> (message["channelIdStr"] as? String)?.let(appsByChannel::remove)
                    "initialSyncComplete" -> return appsByChannel
                }
                if (message["seq"] == 3L && message["error"] != null) error("HTSP metadata rejected")
            }
            error("HTSP initial metadata limit exceeded")
        }
    }
}

/** HTSMSG's root is a length-prefixed map; the protocol uses big-endian framing and little-endian integers. */
internal fun tvhHtspEncode(fields: Map<String, Any>): ByteArray {
    val body = ByteArrayOutputStream()
    DataOutputStream(body).use { out ->
        fields.forEach { (name, value) ->
            val key = name.toByteArray(Charsets.UTF_8)
            require(key.size < 256)
            val payload = when (value) {
                is String -> value.toByteArray(Charsets.UTF_8)
                is ByteArray -> value
                is Number -> {
                    var n = value.toLong()
                    val length = if (n < 0) 8 else (1..8).firstOrNull { n ushr (it * 8 - 1) <= 1 } ?: 8
                    ByteArray(length) { index -> (n ushr (index * 8)).toByte() }
                }
                else -> error("Unsupported HTSP argument")
            }
            out.writeByte(when (value) { is String -> 3; is ByteArray -> 4; else -> 2 })
            out.writeByte(key.size)
            out.writeInt(payload.size)
            out.write(key)
            out.write(payload)
        }
    }
    return ByteArrayOutputStream().also { framed ->
        DataOutputStream(framed).writeInt(body.size())
        framed.write(body.toByteArray())
    }.toByteArray()
}

internal fun tvhHtspDecode(input: DataInputStream): Map<String, Any> {
    val length = input.readInt()
    require(length in 0..4_194_304) { "HTSP metadata frame too large" }
    return tvhHtspFields(input.readHtspBytes(length), map = true) as Map<String, Any>
}

/** DataInputStream.readNBytes() is not available on older Android versions. */
private fun DataInputStream.readHtspBytes(length: Int): ByteArray =
    ByteArray(length).also(::readFully)

@Suppress("UNCHECKED_CAST")
private fun tvhHtspFields(data: ByteArray, map: Boolean): Any {
    val input = DataInputStream(data.inputStream())
    val objectFields = linkedMapOf<String, Any>()
    val listFields = mutableListOf<Any>()
    while (input.available() > 0) {
        require(input.available() >= 6) { "Incomplete HTSP field header" }
        val kind = input.readUnsignedByte()
        val nameLength = input.readUnsignedByte()
        val fieldLength = input.readInt()
        require(nameLength <= input.available() && fieldLength >= 0 &&
            fieldLength <= input.available() - nameLength) { "Invalid HTSP field size" }
        val name = input.readHtspBytes(nameLength).toString(Charsets.UTF_8)
        val value = input.readHtspBytes(fieldLength)
        val parsed: Any = when (kind) {
            1 -> tvhHtspFields(value, true)
            5 -> tvhHtspFields(value, false)
            2 -> value.foldIndexed(0L) { index, number, octet ->
                number or ((octet.toLong() and 0xffL) shl (index * 8))
            }
            3 -> value.toString(Charsets.UTF_8)
            4 -> value
            7 -> value.isNotEmpty() && value[0].toInt() != 0
            else -> continue
        }
        if (map) objectFields[name] = parsed else listFields += parsed
    }
    return if (map) objectFields else listFields
}

private fun tvhHtspServiceJson(hbbtv: Any?): kotlinx.serialization.json.JsonObject {
    fun toJson(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        is Map<*, *> -> kotlinx.serialization.json.JsonObject(value.entries
            .filter { it.key is String }.associate { it.key as String to toJson(it.value) })
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map(::toJson))
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        else -> kotlinx.serialization.json.JsonNull
    }
    return kotlinx.serialization.json.JsonObject(mapOf("hbbtv" to toJson(hbbtv)))
}

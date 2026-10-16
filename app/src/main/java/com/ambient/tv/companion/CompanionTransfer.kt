package com.ambient.tv.companion

import java.io.ByteArrayOutputStream
import java.util.Base64

/** One bounded, ordered transfer per authenticated connection. Never deserialize incomplete data. */
internal class CompanionTransfer(val id: String, private val bytes: Int, private val sha256: String, val mode: String, val credentials: Boolean) {
    private val content = ByteArrayOutputStream()
    private var next = 0
    init {
        require(mode !in setOf("nav_sounds", "preferences") || !credentials)
        require(id.matches(Regex("[a-f0-9-]{36}")) && bytes in 1..CompanionBackupPolicy.MAX_BYTES)
        require(sha256.matches(Regex("[a-f0-9]{64}")) && mode in setOf("accounts", "settings", "preferences", "nav_sounds"))
    }
    fun append(index: Int, payload: String) {
        require(index == next && payload.length in 1..6000)
        val chunk = Base64.getDecoder().decode(payload)
        require(chunk.size in 1..4096 && content.size() + chunk.size <= bytes)
        content.write(chunk); next++
    }
    fun finish(): String {
        val value = content.toByteArray()
        require(value.size == bytes && fingerprint(value) == sha256)
        return value.toString(Charsets.UTF_8)
    }
}

/** Companion data transfers are strictly phone to TV, including accounts and sources. */
internal fun companionReceiveModeAllowed(receiverIsTv: Boolean, mode: String): Boolean =
    mode in setOf("accounts", "settings", "preferences", "nav_sounds") && receiverIsTv

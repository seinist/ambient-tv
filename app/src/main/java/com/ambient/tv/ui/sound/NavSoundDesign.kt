package com.ambient.tv.ui.sound

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@Serializable
data class NavSoundDesign(
    val enabled: Boolean = true,
    val voice: String = "sine",
    val pitchHz: Int = 440,
    val lengthMs: Int = 140,
    val attackMs: Int = 15,
    val releaseMs: Int = 100,
    val warmth: Int = 65,
    val detune: Int = 8,
    val reverb: Int = 20,
    val room: Int = 50,
    val tailMs: Int = 800,
    val echo: Int = 0,
    val echoMs: Int = 180,
    val feedback: Int = 25,
    val volume: Int = 70,
) {
    fun normalized() = copy(
        voice = voice.takeIf { it in listOf("sine", "bell", "pad") } ?: "sine",
        pitchHz = pitchHz.coerceIn(110, 880), lengthMs = lengthMs.coerceIn(30, 1500),
        attackMs = attackMs.coerceIn(5, 300), releaseMs = releaseMs.coerceIn(10, 1000),
        warmth = warmth.coerceIn(0, 100), detune = detune.coerceIn(0, 30),
        reverb = reverb.coerceIn(0, 70), room = room.coerceIn(0, 100), tailMs = tailMs.coerceIn(100, 4000),
        echo = echo.coerceIn(0, 60), echoMs = echoMs.coerceIn(40, 600),
        feedback = feedback.coerceIn(0, 60), volume = volume.coerceIn(0, 100),
    )

    companion object {
        val presetNames = listOf("warm", "glass", "deep", "minimal", "wood", "crystal", "sonar", "airy", "tape")

        fun preset(name: String, event: UiSound): NavSoundDesign {
            val base = when (name) {
                "glass" -> NavSoundDesign(voice = "bell", warmth = 45, reverb = 30, tailMs = 1200)
                "deep" -> NavSoundDesign(voice = "pad", pitchHz = 220, lengthMs = 300, attackMs = 60,
                    releaseMs = 250, warmth = 85, reverb = 40, tailMs = 2000, echo = 15)
                "minimal" -> NavSoundDesign(pitchHz = 520, lengthMs = 40, attackMs = 5,
                    releaseMs = 35, warmth = 40, detune = 0, reverb = 0, volume = 55)
                "wood" -> NavSoundDesign(voice = "bell", pitchHz = 330, lengthMs = 45,
                    attackMs = 5, releaseMs = 80, warmth = 95, detune = 0, reverb = 8, room = 15, tailMs = 250)
                "crystal" -> NavSoundDesign(voice = "bell", pitchHz = 660, lengthMs = 110,
                    attackMs = 10, releaseMs = 220, warmth = 15, detune = 3, reverb = 35, room = 70, tailMs = 1400)
                "sonar" -> NavSoundDesign(pitchHz = 580, lengthMs = 100, attackMs = 15,
                    releaseMs = 180, warmth = 35, detune = 0, reverb = 12, tailMs = 600,
                    echo = 35, echoMs = 240, feedback = 35)
                "airy" -> NavSoundDesign(voice = "pad", pitchHz = 550, lengthMs = 400,
                    attackMs = 100, releaseMs = 450, warmth = 30, detune = 20,
                    reverb = 50, room = 90, tailMs = 2600, volume = 55)
                "tape" -> NavSoundDesign(voice = "pad", pitchHz = 280, lengthMs = 180,
                    attackMs = 25, releaseMs = 200, warmth = 90, detune = 16,
                    reverb = 15, room = 35, tailMs = 900, echo = 40, echoMs = 160, feedback = 45)
                else -> NavSoundDesign()
            }
            return when (event) {
                UiSound.FOCUS -> base.copy(lengthMs = min(base.lengthMs, 60), attackMs = min(base.attackMs, 8), releaseMs = min(base.releaseMs, 60), volume = 45)
                UiSound.BACK, UiSound.TOGGLE_OFF -> base.copy(pitchHz = base.pitchHz * 3 / 4)
                UiSound.TOGGLE_ON, UiSound.SUCCESS -> base.copy(pitchHz = base.pitchHz * 5 / 4)
                UiSound.VALUE -> base.copy(lengthMs = min(base.lengthMs, 40), releaseMs = min(base.releaseMs, 40), volume = 40)
                UiSound.ERROR -> base.copy(pitchHz = 165, warmth = 85)
                else -> base
            }
        }
    }
}

@Serializable
data class NavSoundBank(val version: Int = 1, val sounds: Map<String, NavSoundDesign> = emptyMap(),
    val format: String = "ambient-navigation-sounds") {
    fun design(kind: UiSound) = (sounds[kind.name] ?: NavSoundDesign.preset("warm", kind)).normalized()
    fun with(kind: UiSound, design: NavSoundDesign) = copy(sounds = sounds + (kind.name to design.normalized()))
    fun encode(): String = codec.encodeToString(copy(version = 1, sounds = UiSound.entries.associate { it.name to design(it) }))
    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun import(value: String): NavSoundBank {
            require(value.length <= 32768)
            val document = codec.parseToJsonElement(value) as? kotlinx.serialization.json.JsonObject ?: error("Expected object")
            require(document["format"] == kotlinx.serialization.json.JsonPrimitive("ambient-navigation-sounds"))
            require(document["version"] == kotlinx.serialization.json.JsonPrimitive(1))
            val bank = codec.decodeFromString<NavSoundBank>(value)
            require(bank.sounds.isNotEmpty() && bank.sounds.keys.all { name -> UiSound.entries.any { it.name == name } })
            require(bank.sounds.values.all { it == it.normalized() })
            return bank
        }
        fun decode(value: String): NavSoundBank = runCatching {
            require(value.length <= 32768)
            codec.decodeFromString<NavSoundBank>(value).also { require(it.version == 1) }
        }.getOrDefault(NavSoundBank())
    }
}

/** Deterministic offline synthesis. 24 kHz mono keeps even the longest design below 1 MB.
 * All voices have click-free envelopes; the shared low-pass also darkens the effect tails.
 */
internal object NavSoundRenderer {
    const val RATE = 24000
    fun pcm(design: NavSoundDesign, kind: UiSound): ShortArray {
        val d = design.normalized()
        val dryMs = d.lengthMs + d.releaseMs
        val tail = max(if (d.reverb > 0) d.tailMs else 0, if (d.echo > 0) d.echoMs * 8 else 0)
        val samples = FloatArray((dryMs + tail) * RATE / 1000)
        val dryCount = dryMs * RATE / 1000
        var phase = 0.0
        for (i in 0 until dryCount) {
            val ms = i * 1000.0 / RATE
            val attack = min(1.0, ms / d.attackMs)
            val release = if (ms <= d.lengthMs) 1.0 else ((dryMs - ms) / d.releaseMs).coerceIn(0.0, 1.0).pow(2)
            val glide = when (kind) {
                UiSound.SELECT, UiSound.TOGGLE_ON -> 1.0 + .08 * ms / dryMs
                UiSound.BACK, UiSound.TOGGLE_OFF -> 1.0 - .12 * ms / dryMs
                else -> 1.0
            }
            phase += 2 * PI * d.pitchHz * glide / RATE
            val detuned = sin(phase * 2.0.pow(d.detune / 1200.0))
            val voice = when (d.voice) {
                "bell" -> .75 * sin(phase) + .25 * sin(phase * 2.01) * exp(-ms / 100)
                "pad" -> .6 * sin(phase) + .25 * detuned + .15 * sin(phase / 2)
                else -> .85 * sin(phase) + .15 * detuned
            }
            val chord = if (kind == UiSound.SUCCESS) .7 * voice + .3 * sin(phase * 1.5) else voice
            samples[i] = (chord * attack * release * .45).toFloat()
        }
        val dry = samples.copyOf(dryCount)
        if (d.echo > 0) for (tap in 1..8) {
            val offset = d.echoMs * RATE / 1000 * tap
            val gain = d.echo / 100f * (d.feedback / 100f).pow(tap - 1)
            for (i in dry.indices) if (i + offset < samples.size) samples[i + offset] += dry[i] * gain
        }
        if (d.reverb > 0) {
            // Four damped feedback delays produce a diffuse tail instead of a second click.
            for (delayMs in listOf(29, 37, 43, 53)) {
                val offset = ((delayMs + d.room * .45) * RATE / 1000).toInt()
                val buffer = FloatArray(offset)
                val decay = .001.pow(offset.toDouble() / (d.tailMs * RATE / 1000)).toFloat()
                var damp = 0f
                for (i in samples.indices) {
                    val position = i % offset
                    damp += (buffer[position] - damp) * (1f - d.warmth * .006f)
                    buffer[position] = (if (i < dry.size) dry[i] else 0f) + damp * decay
                    samples[i] += damp * d.reverb / 400f
                }
            }
        }
        var filtered = 0f
        val alpha = (1 - exp(-2 * PI * (8000 - d.warmth * 65) / RATE)).toFloat()
        return ShortArray(samples.size) { i ->
            filtered += alpha * (samples[i] - filtered)
            val fade = min(1f, (samples.size - 1 - i) / (RATE * .02f))
            (tanh(filtered.toDouble()) * fade * d.volume / 100 * 32767).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    fun wav(design: NavSoundDesign, kind: UiSound): ByteArray {
        val pcm = pcm(design, kind)
        return ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size * 2)
            pcm.forEach { putShort(it) }
        }.array()
    }
}

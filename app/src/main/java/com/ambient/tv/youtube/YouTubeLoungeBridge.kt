package com.ambient.tv.youtube

import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** One command from a linked phone, read from SmartTube's lounge model. */
data class YouTubeLinkCommand(
    val type: Int,
    val videoId: String?,
    val positionMs: Long,
    val volume: Int,
    val deviceName: String?,
) {
    companion object {
        // Values of SmartTube's mediaserviceinterfaces Command.
        const val OPEN_VIDEO = 0
        const val SEEK = 1
        const val PLAY = 2
        const val PAUSE = 3
        const val GET_STATE = 4
        const val CONNECTED = 5
        const val DISCONNECTED = 6
        const val VOLUME = 10
        const val STOP = 12
    }
}

/** A YouTube video id as the lounge sends it. */
internal fun isYouTubeVideoId(value: String?): Boolean = value != null && Regex("[A-Za-z0-9_-]{11}").matches(value)

/**
 * Reflection boundary to SmartTube's lounge client (MediaServiceCore `LoungeService`), the part
 * behind YouTube's "Watch on TV". Free of Android types so it can be exercised in unit tests.
 * Callers make sure the engine (GlobalPreferences, service manager) is initialized first.
 */
internal object YouTubeLoungeBridge {
    private const val LOUNGE = "com.liskovsoft.youtubeapi.lounge.LoungeService"
    private const val ON_COMMAND = "$LOUNGE\$OnCommand"
    private const val COMMAND = "com.liskovsoft.youtubeapi.service.data.YouTubeCommand"

    fun service(): Any? = runCatching { Class.forName(LOUNGE).getMethod("instance").invoke(null) }.getOrNull()

    /** Blocks until the calling thread is interrupted (the lounge client reconnects on its own). */
    fun listen(lounge: Any, onCommand: (YouTubeLinkCommand) -> Unit) {
        val callbackType = Class.forName(ON_COMMAND)
        val from = Class.forName(COMMAND).methods.first { it.name == "from" && it.parameterTypes.size == 1 }
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "onCommand" -> {
                    runCatching { from.invoke(null, args?.firstOrNull())?.let(::toLinkCommand)?.let(onCommand) }
                    null
                }
                "toString" -> "AmbientTvLoungeListener"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        lounge.javaClass.getMethod("startListening", callbackType).invoke(lounge, callback)
    }

    fun postNowPlaying(lounge: Any, videoId: String, positionMs: Long, durationMs: Long, state: Int) {
        invoke(lounge, "postStartPlaying", videoId, positionMs, durationMs, state)
    }

    fun postState(lounge: Any, positionMs: Long, durationMs: Long, state: Int) {
        invoke(lounge, "postStateChange", positionMs, durationMs, state)
    }

    fun postVolume(lounge: Any, volume: Int) {
        invoke(lounge, "postVolumeChange", volume)
    }

    fun reset(lounge: Any) {
        invoke(lounge, "resetData")
    }

    /** SmartTube's normalized `Command` → the app's own command model. */
    fun toLinkCommand(raw: Any): YouTubeLinkCommand = YouTubeLinkCommand(
        type = (get(raw, "getType") as? Number)?.toInt() ?: -1,
        videoId = get(raw, "getVideoId")?.toString(),
        positionMs = (get(raw, "getCurrentTimeMs") as? Number)?.toLong() ?: 0L,
        volume = (get(raw, "getVolume") as? Number)?.toInt() ?: -1,
        deviceName = get(raw, "getDeviceName")?.toString(),
    )

    private fun get(target: Any, name: String): Any? =
        runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()

    /** Calls a method whose parameters may be primitives (long/int) with boxed arguments. */
    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method: Method = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == args.size &&
                m.parameterTypes.indices.all { i -> args[i] == null || boxed(m.parameterTypes[i]).isInstance(args[i]) }
        } ?: throw NoSuchMethodException("$name/${args.size}")
        return method.invoke(target, *args)
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        else -> type
    }
}

package com.ambient.tv.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ambient.tv.ui.MainActivity
import com.ambient.tv.ui.navigation.Destinations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Launcher rows open the app through an explicit intent carrying only a media key. The route is
 * built here from that key, never taken from the intent, so another app cannot steer navigation.
 */
object LauncherIntents {
    const val ACTION_OPEN_TITLE = "com.ambient.tv.action.OPEN_TITLE"
    private const val EXTRA_KEY = "com.ambient.tv.extra.MEDIA_KEY"
    private const val EXTRA_PLAY = "com.ambient.tv.extra.PLAY"
    private const val EXTRA_CHANNEL = "com.ambient.tv.extra.CHANNEL"
    private const val EXTRA_SOURCE = "com.ambient.tv.extra.SOURCE"
    // Channel and source ids come from the servers (a source id may carry a user name); the
    // route encodes them, so only control characters and overlong values are refused.
    private val CHANNEL = Regex("[^\\p{Cntrl}]{1,300}")
    private val SOURCE = Regex("[^\\p{Cntrl}]{0,300}")
    private val KEY = Regex("[A-Za-z0-9:_.\\-]{1,160}")

    private const val SEARCH_SCHEME = "ambienttv"
    private const val SEARCH_HOST = "title"

    private val pending = MutableStateFlow<String?>(null)
    /** Route waiting for the navigation host; cleared with [consumed]. */
    val requests = pending.asStateFlow()

    fun openTitleUri(context: Context, ratingKey: String, play: Boolean): Uri =
        Uri.parse(
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_TITLE)
                .putExtra(EXTRA_KEY, ratingKey)
                .putExtra(EXTRA_PLAY, play)
                .toUri(Intent.URI_INTENT_SCHEME),
        )

    /** Live TV channel entry: opens the channel's playback, like Home's live row. */
    fun openChannelUri(context: Context, channelUuid: String, sourceId: String): Uri =
        Uri.parse(
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_TITLE)
                .putExtra(EXTRA_CHANNEL, channelUuid)
                .putExtra(EXTRA_SOURCE, sourceId)
                .toUri(Intent.URI_INTENT_SCHEME),
        )

    /** A system-search hit: opens the title's detail page (`ambienttv://title/<key>`). */
    fun searchTitleUri(ratingKey: String): Uri =
        Uri.Builder().scheme(SEARCH_SCHEME).authority(SEARCH_HOST).appendPath(ratingKey).build()

    fun appUri(context: Context): Uri =
        Uri.parse(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).toUri(Intent.URI_INTENT_SCHEME))

    /** A launcher row's request, validated: a media title, or a live channel of a source. */
    sealed interface Request {
        val route: String
        /** [fromSearch]: a system-search hit, which works without the launcher rows. */
        data class Title(val key: String, val play: Boolean, val fromSearch: Boolean = false) : Request {
            override val route: String get() = if (play) Destinations.player(key) else Destinations.detail(key)
        }
        data class Channel(val uuid: String, val sourceId: String) : Request {
            override val route: String get() = Destinations.tvhPlayer("channel", uuid, sourceId.ifBlank { null })
        }
    }

    fun parse(intent: Intent?): Request? {
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == SEARCH_SCHEME && intent.data?.host == SEARCH_HOST) {
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            val key = intent.data?.pathSegments?.singleOrNull()?.takeIf(KEY::matches) ?: return null
            return Request.Title(key, play = false, fromSearch = true)
        }
        if (intent?.action != ACTION_OPEN_TITLE) return null
        // Reopened from Recents after the app was gone: the old entry must not play again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        intent.getStringExtra(EXTRA_CHANNEL)?.let { channel ->
            val uuid = channel.takeIf(CHANNEL::matches) ?: return null
            val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty().takeIf(SOURCE::matches) ?: return null
            return Request.Channel(uuid, source)
        }
        val key = intent.getStringExtra(EXTRA_KEY)?.takeIf(KEY::matches) ?: return null
        return Request.Title(key, intent.getBooleanExtra(EXTRA_PLAY, false))
    }

    /** Queues [route] for the navigation host (after the server is reachable). */
    internal fun post(route: String) {
        pending.value = route
    }

    fun consumed(route: String) {
        pending.compareAndSet(route, null)
    }
}

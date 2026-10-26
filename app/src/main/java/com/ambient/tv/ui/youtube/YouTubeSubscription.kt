package com.ambient.tv.ui.youtube

import com.ambient.tv.R
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.notifyResult
import com.ambient.tv.util.suspendResult
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeChannelInfo
import com.ambient.tv.youtube.YouTubeMetadata

/** Channel of a loaded video; the session's own subscription changes win over cached metadata. */
internal fun SmartTubeEngine.channelOf(metadata: YouTubeMetadata): YouTubeChannelInfo? {
    val channelId = metadata.channelId ?: return null
    return YouTubeChannelInfo(
        channelId = channelId,
        title = metadata.author,
        avatarUrl = metadata.authorImageUrl,
        subscribed = subscriptionOverride(channelId) ?: metadata.subscribed,
    )
}

/**
 * Subscribe/unsubscribe as SmartTube does: the button flips at once ([apply] with the new state)
 * and flips back when YouTube rejects the change.
 */
internal suspend fun SmartTubeEngine.toggleSubscription(channel: YouTubeChannelInfo, apply: (Boolean) -> Unit) {
    // A second press while YouTube still answers the first would race it and could revert the wrong state.
    if (!subscriptionsInFlight.add(channel.channelId)) return
    try {
        changeSubscription(channel, apply)
    } finally {
        subscriptionsInFlight.remove(channel.channelId)
    }
}

/** Applies subscription changes made on other screens to [current] (unknown states stay unknown). */
internal fun YouTubeChannelInfo.withSubscriptionChanges(changes: Map<String, Boolean>): YouTubeChannelInfo =
    changes[channelId]?.takeIf { subscribed != null && it != subscribed }?.let { copy(subscribed = it) } ?: this

private val subscriptionsInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

private suspend fun SmartTubeEngine.changeSubscription(channel: YouTubeChannelInfo, apply: (Boolean) -> Unit) {
    val target = channel.subscribed != true
    apply(target)
    val key = "youtube:subscription:${channel.channelId}"
    suspendResult { setSubscribed(channel.channelId, target) }
        .onSuccess {
            notifyResult(key, "youtube", channel.title,
                message = if (target) R.string.youtube_subscribed else R.string.youtube_unsubscribed, kind = NoticeKind.LIBRARY)
        }
        .onFailure {
            apply(!target)
            notifyResult(key, "youtube", channel.title, success = false, kind = NoticeKind.LIBRARY,
                failureMessage = R.string.youtube_subscribe_failed)
        }
}

/**
 * Start position for the next player of one video (a chapter chosen on the detail page). One-shot,
 * so player routes, the background session and companion mapping keep their plain video routes.
 */
internal object YouTubeStartRequest {
    private val pending = java.util.concurrent.atomic.AtomicReference<Pair<String, Long>?>(null)
    fun request(videoId: String, positionMs: Long) = pending.set(videoId to positionMs)
    fun take(videoId: String): Long? = pending.getAndUpdate { if (it?.first == videoId) null else it }?.takeIf { it.first == videoId }?.second
}

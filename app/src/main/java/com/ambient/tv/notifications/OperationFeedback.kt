package com.ambient.tv.notifications

import androidx.annotation.StringRes
import com.ambient.tv.R
import java.io.IOException
import kotlinx.coroutines.CancellationException

enum class FeedbackPhase { SUCCESS, ERROR, WARNING, INFO }
enum class FeedbackDelivery { INLINE, ACTION, CONTEXTUAL, NOTIFICATION }

/** Safe, localized presentation data. Never retain exceptions or UI callbacks here. */
data class OperationFeedback(
    val key: String,
    val scope: String,
    @StringRes val message: Int,
    val phase: FeedbackPhase = FeedbackPhase.ERROR,
    @StringRes val detail: Int? = null,
    val system: String? = null,
    val subject: String? = null,
    val kind: NoticeKind = NoticeKind.SETTINGS,
    val delivery: FeedbackDelivery = FeedbackDelivery.CONTEXTUAL,
    val args: List<String> = emptyList(),
) {
    fun shouldNotify(visible: Boolean): Boolean = when (delivery) {
        FeedbackDelivery.NOTIFICATION -> true
        FeedbackDelivery.INLINE -> false
        FeedbackDelivery.ACTION -> phase == FeedbackPhase.SUCCESS || !visible
        FeedbackDelivery.CONTEXTUAL -> !visible
    }

    fun notice() = AppNotice(key, message, listOfNotNull(system), subject, when (phase) {
        FeedbackPhase.ERROR -> NoticePhase.ERROR
        FeedbackPhase.SUCCESS -> NoticePhase.SUCCESS
        else -> NoticePhase.INFO
    }, kind, args = args, interruptPlayback = false)
}

/** One reporter per screen ViewModel. Visibility is lifecycle-bound, never inferred from focus. */
class OperationFeedbackReporter(
    private val post: (AppNotice) -> Unit = AppNotifications::post,
) {
    @ConsistentCopyVisibility
    data class Ticket internal constructor(val key: String, val revision: Long)
    private var revision = 0L
    private val latest = mutableMapOf<String, Long>()
    private val visible = mutableMapOf<Any, String>()

    @Synchronized fun visibility(owner: Any, scope: String?) {
        if (scope == null) visible.remove(owner) else visible[owner] = scope
    }

    @Synchronized fun begin(key: String): Ticket = Ticket(key, ++revision).also { latest[key] = it.revision }
    @Synchronized fun cancel(key: String) { latest.remove(key) }

    /** A superseded or already completed attempt cannot update state or send a notification. */
    @Synchronized fun complete(ticket: Ticket, result: OperationFeedback): OperationFeedback? {
        if (latest[ticket.key] != ticket.revision) return null
        latest.remove(ticket.key)
        if (result.shouldNotify(result.scope in visible.values)) post(result.notice().let { notice ->
            // Each explicit test attempt must display even if its result is unchanged.
            if (result.delivery == FeedbackDelivery.NOTIFICATION) notice.copy(key = "${notice.key}:${ticket.revision}") else notice
        })
        return result
    }
}

/** Unknown server strings are deliberately not shown (URLs and credentials may be embedded). */
@StringRes
fun feedbackFailureDetail(error: Throwable): Int {
    if (error is CancellationException) throw error
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is SecurityException } -> R.string.feedback_access_denied
        causes.any { it is IOException } -> R.string.feedback_connection_hint
        else -> R.string.feedback_retry_hint
    }
}

package com.ambient.tv.notifications

import com.ambient.tv.R

fun notifyResult(
    key: String,
    system: String? = null,
    subject: String? = null,
    message: Int = R.string.notice_saved,
    success: Boolean = true,
    kind: NoticeKind = NoticeKind.SETTINGS,
    failureMessage: Int = R.string.notice_failed,
) = AppNotifications.post(AppNotice(
    key = key, systems = listOfNotNull(system), subject = subject,
    message = if (success) message else failureMessage,
    phase = if (success) NoticePhase.SUCCESS else NoticePhase.ERROR, kind = kind,
))

/** [reason] (see [failureReasonText]) turns a failed sign-in into "Sign-in failed: <cause>". */
fun notifyLogin(system: String, phase: NoticePhase, subject: String? = null, user: String? = null, message: Int? = null, reason: String? = null) =
    AppNotifications.post(AppNotice(
        "login:$system", message ?: when (phase) {
            NoticePhase.WORKING -> R.string.notice_signing_in
            NoticePhase.SUCCESS -> if (user.isNullOrBlank()) R.string.notice_signed_in else R.string.notice_signed_in_as
            NoticePhase.ERROR -> if (reason.isNullOrBlank()) R.string.notice_login_failed else R.string.notice_login_failed_reason
            NoticePhase.INFO -> R.string.notice_signed_out
        }, listOf(system), subject, phase, NoticeKind.LOGIN,
        args = when {
            message == null && phase == NoticePhase.SUCCESS && !user.isNullOrBlank() -> listOf(user)
            message == null && phase == NoticePhase.ERROR && !reason.isNullOrBlank() -> listOf(reason)
            else -> emptyList()
        },
    ))

/**
 * The media server did not take the resume point (on stop) or the watched mark. Only for those two
 * moments, never for the periodic position updates, so a flaky link does not repeat it.
 */
fun notifyProgressNotSaved(system: String, mediaId: String, title: String?, watched: Boolean = false) =
    AppNotifications.post(AppNotice(
        key = "progress:$system:$mediaId", message = if (watched) R.string.notice_watched_not_saved else R.string.notice_progress_not_saved,
        systems = listOf(system), subject = title?.takeIf(String::isNotBlank), phase = NoticePhase.ERROR, kind = NoticeKind.PLAYBACK,
        interruptPlayback = false,
    ))

/** The stream URL itself was refused (HTTP 4xx/5xx). Another engine would fetch the same URL, so
 *  playback stops instead of retrying or switching renderers. */
fun notifyStreamHttpError(system: String, title: String?, status: Int) =
    AppNotifications.post(AppNotice(
        key = "playback:http:$system", message = R.string.notice_stream_http_error,
        systems = listOf(system), subject = title?.takeIf(String::isNotBlank), phase = NoticePhase.ERROR,
        kind = NoticeKind.PLAYBACK, args = listOf(status.toString()),
    ))

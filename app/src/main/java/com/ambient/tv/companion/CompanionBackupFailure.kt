package com.ambient.tv.companion

import com.ambient.tv.R

/** Why a settings transfer failed; each reason has its own notification text. */
internal object BackupFailure {
    const val NOT_CONNECTED = "not_connected"
    const val BUSY = "busy"
    const val UNSAVED = "unsaved"
    const val UNSUPPORTED = "unsupported"
    const val TOO_LARGE = "too_large"
    const val TIMEOUT = "timeout"
    const val RECEIVER_BUSY = "consent_pending"
    const val APPLY = "apply"
    const val PREPARE = "prepare"
}

internal fun companionBackupFailureMessage(reason: String): Int = when (reason) {
    BackupFailure.NOT_CONNECTED -> R.string.companion_backup_failed_not_connected
    BackupFailure.BUSY -> R.string.companion_backup_failed_busy
    BackupFailure.UNSAVED -> R.string.companion_backup_failed_unsaved
    BackupFailure.TOO_LARGE -> R.string.companion_backup_failed_too_large
    BackupFailure.TIMEOUT -> R.string.companion_backup_failed_timeout
    BackupFailure.RECEIVER_BUSY -> R.string.companion_backup_failed_receiver_busy
    BackupFailure.APPLY -> R.string.companion_backup_failed_apply
    BackupFailure.PREPARE -> R.string.companion_backup_failed_prepare
    else -> R.string.companion_backup_failed_unsupported
}

/**
 * Error reported back for one command. A lingering playback error on the TV used to be attached to
 * every reply, so each settings-transfer frame looked rejected; transfer frames only carry their
 * own error.
 */
internal fun companionReplyError(commandError: String, playbackPhase: String, commandType: String): String =
    commandError.ifEmpty { if (playbackPhase == "error" && !commandType.startsWith("backup_")) "playback" else "" }

/** A transfer frame counts as accepted unless the receiver named a command error (older TVs may still send "playback"). */
internal fun companionBackupAckOk(replyError: String): Boolean = replyError.isEmpty() || replyError == "playback"

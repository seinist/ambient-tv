package com.ambient.tv.notifications

import com.ambient.tv.R
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OperationFeedbackTest {
    private fun failure(scope: String = "dialog:a") = OperationFeedback("save:a", scope,
        R.string.feedback_recording_failed, delivery = FeedbackDelivery.ACTION)

    @Test fun visibleActionErrorIsInlineButSuccessNotifies() {
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        reporter.visibility(Any(), "dialog:a")
        assertNotNull(reporter.complete(reporter.begin("save"), failure()))
        assertTrue(notices.isEmpty())
        reporter.complete(reporter.begin("save"), failure().copy(phase = FeedbackPhase.SUCCESS))
        assertEquals(NoticePhase.SUCCESS, notices.single().phase)
    }

    @Test fun explicitConnectionTestsNotifyEveryResultOnVisibleAndHiddenPages() {
        val store = NotificationStore()
        val reporter = OperationFeedbackReporter(store::post)
        val owner = Any()
        for (system in listOf("plex", "jellyfin", "emby", "tvheadend", "wireguard", "files")) {
            val scope = "settings:$system"
            for (visible in listOf(true, false)) {
                reporter.visibility(owner, scope.takeIf { visible })
                for (phase in listOf(FeedbackPhase.SUCCESS, FeedbackPhase.ERROR)) {
                    val result = OperationFeedback("test:$system", scope, R.string.notice_test_ok,
                        phase = phase, system = system, delivery = FeedbackDelivery.NOTIFICATION)
                    repeat(2) {
                        val ticket = reporter.begin(result.key)
                        val previousIds = store.state.value.pending.map { it.id }.toSet()
                        assertNotNull(reporter.complete(ticket, result))
                        val entry = store.state.value.pending.last()
                        assertFalse(entry.id in previousIds)
                        assertEquals(listOf(system), entry.notice.systems)
                        assertEquals(if (phase == FeedbackPhase.SUCCESS) NoticePhase.SUCCESS else NoticePhase.ERROR,
                            entry.notice.phase)
                        assertNull(reporter.complete(ticket, result))
                    }
                }
            }
        }
    }

    @Test fun dismissedOrDifferentDialogGetsOneNotification() {
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        val owner = Any()
        reporter.visibility(owner, "dialog:a")
        val ticket = reporter.begin("save")
        reporter.visibility(owner, "dialog:b")
        assertNotNull(reporter.complete(ticket, failure()))
        assertNull(reporter.complete(ticket, failure()))
        assertEquals(1, notices.size)
        reporter.visibility(owner, null)
        reporter.complete(reporter.begin("save"), failure())
        assertEquals(2, notices.size)
    }

    @Test fun supersededAndCancelledAttemptsCannotPublish() {
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        val stale = reporter.begin("save")
        val current = reporter.begin("save")
        reporter.complete(current, failure().copy(phase = FeedbackPhase.SUCCESS))
        assertNull(reporter.complete(stale, failure()))
        val cancelled = reporter.begin("save")
        reporter.cancel("save")
        assertNull(reporter.complete(cancelled, failure()))
        assertEquals(listOf(NoticePhase.SUCCESS), notices.map { it.phase })
    }

    @Test fun validationAndPageFailuresStayInlineEvenWhenHidden() {
        assertFalse(failure().copy(delivery = FeedbackDelivery.INLINE).shouldNotify(false))
        assertFalse(failure().copy(phase = FeedbackPhase.SUCCESS, delivery = FeedbackDelivery.CONTEXTUAL).shouldNotify(true))
    }

    @Test fun overlappingVisibilityOwnersDoNotHideEachOther() {
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        val one = Any()
        reporter.visibility(one, "dialog:a")
        reporter.visibility(Any(), "dialog:a")
        reporter.visibility(one, null)
        reporter.complete(reporter.begin("save"), failure())
        assertTrue(notices.isEmpty())
    }

    @Test fun settingsPageAndGlobalSaveHaveIndependentVisibility() {
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        val page = Any()
        reporter.visibility(Any(), "settings")
        reporter.visibility(page, "settings:BACKUP")
        reporter.complete(reporter.begin("export"), failure("settings:BACKUP"))
        assertTrue(notices.isEmpty())
        reporter.visibility(page, "settings:PLEX")
        reporter.complete(reporter.begin("export"), failure("settings:BACKUP"))
        assertEquals(1, notices.size)
        reporter.complete(reporter.begin("save"), failure("settings"))
        assertEquals(1, notices.size)
    }

    @Test fun formattedMacResultKeepsArgumentsInNotification() {
        val result = OperationFeedback("mac:plex", "settings:PLEX", R.string.settings_status_mac_found,
            phase = FeedbackPhase.SUCCESS, args = listOf("02:11:22:33:44:55"))
        val notice = result.notice()
        assertEquals(result.message, notice.message)
        assertEquals(result.args, notice.args)
        assertFalse(notice.interruptPlayback)
    }

    @Test fun committedActionsNotifySuccessButNeverDuplicateVisibleFailure() {
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        reporter.visibility(Any(), "actions")
        val actions = listOf("save", "import", "export", "source:add", "source:remove",
            "server:remove", "vpn:configuration", "recent-tv")
        actions.forEach { key ->
            val result = failure("actions").copy(key = key)
            reporter.complete(reporter.begin(key), result)
            val ticket = reporter.begin(key)
            reporter.complete(ticket, result.copy(phase = FeedbackPhase.SUCCESS))
            assertNull(reporter.complete(ticket, result))
        }
        assertEquals(actions, notices.map { it.key })
        assertTrue(notices.all { it.phase == NoticePhase.SUCCESS })
    }

    @Test fun partialImportIsAWarningNotASuccessOrFailure() {
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        val owner = Any()
        reporter.visibility(owner, "settings:BACKUP")
        val warning = OperationFeedback("import", "settings:BACKUP", R.string.feedback_import_refresh_warning,
            phase = FeedbackPhase.WARNING)
        reporter.complete(reporter.begin("import"), warning)
        assertTrue(notices.isEmpty())
        reporter.visibility(owner, null)
        reporter.complete(reporter.begin("import"), warning)
        assertEquals(NoticePhase.INFO, notices.single().phase)
    }

    @Test fun errorsExposeOnlyLocalizedCauses() {
        assertEquals(R.string.feedback_retry_hint, feedbackFailureDetail(IllegalStateException("https://user:secret@host")))
        assertEquals(R.string.feedback_connection_hint, feedbackFailureDetail(java.net.SocketTimeoutException()))
        assertEquals(R.string.feedback_access_denied, feedbackFailureDetail(IllegalStateException(SecurityException())))
    }

    @Test(expected = CancellationException::class) fun cancellationIsNotAnError() {
        feedbackFailureDetail(CancellationException())
    }
}

package com.ambient.tv.ui.tvheadend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.livetv.LiveTvTimerEdit
import com.ambient.tv.notifications.OperationFeedback
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.OperationFeedbackContent
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import kotlinx.coroutines.delay

/** Padding steps offered by the timer editor, in minutes. */
internal val LIVE_TV_PADDING_STEPS = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)

/** TVHeadend's DVR priorities in the order its own UI lists them; 6 is "use the profile's". */
internal val TVH_PRIORITIES = listOf(6, 0, 1, 2, 3, 4)

internal fun nextPaddingStep(current: Int, direction: Int): Int {
    val steps = LIVE_TV_PADDING_STEPS
    return if (direction > 0) steps.firstOrNull { it > current } ?: steps.last()
    else steps.lastOrNull { it < current } ?: steps.first()
}

private fun priorityLabel(priority: Int): Int = when (priority) {
    0 -> R.string.tvh_priority_important
    1 -> R.string.tvh_priority_high
    2 -> R.string.tvh_priority_normal
    3 -> R.string.tvh_priority_low
    4 -> R.string.tvh_priority_unimportant
    else -> R.string.tvh_priority_default
}

/**
 * Settings of one timer or series timer: padding before/after, and where the server has them,
 * priority and whether a series rule is active. Only changed values are sent.
 */
@Composable
internal fun LiveTvTimerEditDialog(
    title: String,
    series: Boolean,
    paddingBefore: Int?,
    paddingAfter: Int?,
    priority: Int?,
    enabled: Boolean?,
    /** TVHeadend: 0 minutes means the DVR profile's own padding. */
    zeroIsServerDefault: Boolean,
    busy: Boolean,
    message: OperationFeedback?,
    onSave: (LiveTvTimerEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    var before by remember(title) { mutableStateOf(paddingBefore ?: 0) }
    var after by remember(title) { mutableStateOf(paddingAfter ?: 0) }
    var pri by remember(title) { mutableStateOf(priority) }
    var active by remember(title) { mutableStateOf(enabled) }
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { initialFocus.requestFocus() } }
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(if (series) R.string.tvh_edit_series_timer else R.string.tvh_edit_timer),
        hint = title,
        captureInitialFocus = false,
        widthFraction = if (LocalHandheldUi.current) null else 0.5f,
        minWidth = deviceDp(560.dp, 0.dp),
        maxWidth = 760.dp,
        footer = {
            TvActionButton(
                text = stringResource(R.string.tvh_save),
                onClick = {
                    onSave(LiveTvTimerEdit(
                        paddingBeforeMinutes = before.takeIf { paddingBefore != null && it != paddingBefore },
                        paddingAfterMinutes = after.takeIf { paddingAfter != null && it != paddingAfter },
                        priority = pri.takeIf { it != priority },
                        enabled = active.takeIf { it != enabled },
                    ))
                },
                selected = true,
                loading = busy,
            )
            TvActionButton(text = stringResource(R.string.back), onClick = onDismiss)
        },
    ) {
        val beforeLabel = if (before == 0 && zeroIsServerDefault) stringResource(R.string.tvh_padding_default)
            else stringResource(R.string.tvh_padding_minutes, before)
        val afterLabel = if (after == 0 && zeroIsServerDefault) stringResource(R.string.tvh_padding_default)
            else stringResource(R.string.tvh_padding_minutes, after)
        if (paddingBefore != null) SettingStepper(
            label = stringResource(R.string.tvh_padding_before), value = beforeLabel,
            onPrevious = { before = nextPaddingStep(before, -1) }, onNext = { before = nextPaddingStep(before, 1) },
            modifier = Modifier.focusRequester(initialFocus),
        )
        if (paddingAfter != null) SettingStepper(
            label = stringResource(R.string.tvh_padding_after), value = afterLabel,
            onPrevious = { after = nextPaddingStep(after, -1) }, onNext = { after = nextPaddingStep(after, 1) },
        )
        pri?.let { current ->
            val index = TVH_PRIORITIES.indexOf(current).coerceAtLeast(0)
            SettingStepper(
                label = stringResource(R.string.tvh_priority), value = stringResource(priorityLabel(current)),
                onPrevious = { pri = TVH_PRIORITIES[(index - 1).coerceAtLeast(0)] },
                onNext = { pri = TVH_PRIORITIES[(index + 1).coerceAtMost(TVH_PRIORITIES.lastIndex)] },
            )
        }
        active?.let { on ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.tvh_series_timers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TvActionButton(
                    text = stringResource(if (on) R.string.tvh_series_enabled else R.string.tvh_series_disabled),
                    onClick = { active = !on }, selected = on,
                    modifier = if (paddingBefore == null) Modifier.focusRequester(initialFocus) else Modifier,
                )
            }
        }
        message?.let { OperationFeedbackContent(it, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun SettingStepper(label: String, value: String, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TvActionButton(text = "−", onClick = onPrevious, modifier = modifier)
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.widthIn(min = 120.dp))
        TvActionButton(text = "+", onClick = onNext)
    }
}

/** Delete / cancel needs a second OK: these are server-side and cannot be undone. */
@Composable
internal fun LiveTvConfirmDialog(
    title: String,
    question: String,
    confirmLabel: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { initialFocus.requestFocus() } }
    AppDialog(
        onDismiss = onDismiss,
        title = title,
        hint = question,
        captureInitialFocus = false,
        footer = {
            TvActionButton(text = confirmLabel, onClick = onConfirm, loading = busy)
            // Back first: an accidental double OK must not delete anything.
            TvActionButton(text = stringResource(R.string.back), onClick = onDismiss, modifier = Modifier.focusRequester(initialFocus))
        },
    )
}

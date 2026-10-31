package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.notifications.*
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.isHandheldLandscape
import com.ambient.tv.ui.mobile.isTabletUi
import com.ambient.tv.ui.navigation.LocalSidebarContentPending

enum class FeedbackLayout { FIELD, COMPACT, PAGE }

@Composable
fun OperationFeedbackScope(reporter: OperationFeedbackReporter, scope: String, enabled: Boolean = true) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(reporter, scope, lifecycle, enabled) {
        val owner = Any()
        fun update() = reporter.visibility(owner, scope.takeIf { enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) })
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); reporter.visibility(owner, null) }
    }
}

@Composable
fun OperationFeedbackContent(
    feedback: OperationFeedback,
    modifier: Modifier = Modifier,
    layout: FeedbackLayout = FeedbackLayout.COMPACT,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    requestActionFocus: Boolean = false,
    actionLoading: Boolean = false,
) {
    FeedbackContent(stringResource(feedback.message, *feedback.args.toTypedArray()), feedback.detail?.let { stringResource(it) }, feedback.phase,
        modifier, layout, actionLabel, onAction, actionModifier, secondaryActionLabel, onSecondaryAction, requestActionFocus, actionLoading)
}

/** Shared appearance for field feedback, content cards, and the existing StatePanel API. */
@Composable
internal fun FeedbackContent(
    title: String,
    body: String?,
    phase: FeedbackPhase?,
    modifier: Modifier,
    layout: FeedbackLayout,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    actionModifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    requestActionFocus: Boolean = false,
    actionLoading: Boolean = false,
    icon: ImageVector? = null,
    centered: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val statusColor = when (phase) {
        FeedbackPhase.ERROR -> colors.error
        FeedbackPhase.WARNING -> colors.onSurfaceVariant
        else -> colors.primary
    }
    val leadingIcon = when (phase) {
        FeedbackPhase.SUCCESS -> Icons.Default.CheckCircle
        FeedbackPhase.ERROR -> Icons.Default.ErrorOutline
        FeedbackPhase.WARNING -> Icons.Default.WarningAmber
        FeedbackPhase.INFO -> Icons.Default.Info
        null -> icon
    }
    val focus = remember { FocusRequester() }
    val filterFocus = LocalFilterFocusState.current
    val pending = LocalSidebarContentPending.current
    val handheld = LocalHandheldUi.current
    val wideHandheld = isHandheldLandscape()
    val tablet = isTabletUi()
    LaunchedEffect(requestActionFocus, pending, handheld, filterFocus) {
        if (requestActionFocus && filterFocus?.allowContentAutoFocus != false &&
            layout == FeedbackLayout.PAGE && !pending && !handheld && onAction != null
        ) {
            withFrameNanos { }
            if (filterFocus?.allowContentAutoFocus != false) runCatching { focus.requestFocus() }
        }
    }
    val page = layout == FeedbackLayout.PAGE
    val shape = RoundedCornerShape(14.dp)
    val responsiveBounds = if (page && wideHandheld && !tablet) Modifier.widthIn(max = 560.dp) else Modifier
    val surface = if (layout == FeedbackLayout.FIELD) Modifier else Modifier
        .background(colors.surface.copy(alpha = .9f), shape)
        .border(1.dp, (if (phase == FeedbackPhase.ERROR) statusColor else colors.onSurface).copy(alpha = .16f), shape)
        .padding(if (page) if (handheld) if (wideHandheld) 18.dp else 20.dp else 28.dp else 14.dp)
    Column(responsiveBounds.then(modifier).then(surface).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page && centered) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                leadingIcon?.let { image ->
                    Box(
                        Modifier.size(64.dp).background(statusColor.copy(alpha = .12f), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(image, contentDescription = null, tint = statusColor, modifier = Modifier.size(34.dp))
                    }
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface,
                    textAlign = TextAlign.Center)
                body?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                leadingIcon?.let {
                    Icon(it, contentDescription = null, tint = statusColor, modifier = Modifier.size(if (page) 28.dp else 24.dp))
                }
                Column(Modifier.weight(1f, fill = !page), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = if (page) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
                        color = colors.onSurface)
                    body?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (onAction != null && actionLabel != null || onSecondaryAction != null && secondaryActionLabel != null) {
            Box(if (page && centered) Modifier.fillMaxWidth() else Modifier,
                contentAlignment = if (page && centered) Alignment.Center else Alignment.CenterStart) {
                DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onAction != null && actionLabel != null) TvActionButton(actionLabel, onAction,
                    loading = actionLoading,
                    modifier = actionModifier.then(if (requestActionFocus) Modifier.focusRequester(focus) else Modifier))
                if (onSecondaryAction != null && secondaryActionLabel != null) TvActionButton(secondaryActionLabel, onSecondaryAction)
                }
            }
        }
    }
}

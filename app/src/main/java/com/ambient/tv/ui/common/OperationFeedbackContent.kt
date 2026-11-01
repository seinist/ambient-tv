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

/** Page feedback has its own compact handset geometry; TV and inline feedback stay unchanged. */
internal data class HandheldFeedbackPageMetrics(
    val maxWidthDp: Int,
    val gutterDp: Int,
    val contentPaddingDp: Int,
    val iconFrameDp: Int,
    val iconDp: Int,
)

internal fun handheldFeedbackPageMetrics(tablet: Boolean, landscape: Boolean): HandheldFeedbackPageMetrics =
    when {
        tablet -> HandheldFeedbackPageMetrics(440, 24, 18, 52, 28)
        landscape -> HandheldFeedbackPageMetrics(380, 20, 14, 44, 24)
        else -> HandheldFeedbackPageMetrics(300, 20, 14, 44, 24)
    }

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
    centered: Boolean = false,
) {
    FeedbackContent(stringResource(feedback.message, *feedback.args.toTypedArray()), feedback.detail?.let { stringResource(it) }, feedback.phase,
        modifier, layout, actionLabel, onAction, actionModifier, secondaryActionLabel, onSecondaryAction, requestActionFocus, actionLoading,
        centered = centered)
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
    val handheldPage = if (page && handheld) handheldFeedbackPageMetrics(tablet, wideHandheld) else null
    val shape = RoundedCornerShape(14.dp)
    // Padding is outside the card surface, so even a 320dp phone has visible side margins.
    // The outer width cap also constrains direct centered OperationFeedbackContent callers.
    val responsiveBounds = handheldPage?.let { metrics ->
        Modifier.padding(horizontal = metrics.gutterDp.dp).widthIn(max = metrics.maxWidthDp.dp)
    } ?: Modifier
    val surface = if (layout == FeedbackLayout.FIELD) Modifier else Modifier
        .background(colors.surface.copy(alpha = .9f), shape)
        .border(1.dp, (if (phase == FeedbackPhase.ERROR) statusColor else colors.onSurface).copy(alpha = .16f), shape)
        .padding(if (page) handheldPage?.contentPaddingDp?.dp ?: 28.dp else 14.dp)
    val titleStyle = when {
        handheldPage != null && !tablet -> MaterialTheme.typography.titleMedium
        handheldPage != null -> MaterialTheme.typography.titleLarge
        page -> MaterialTheme.typography.headlineSmall
        else -> MaterialTheme.typography.titleSmall
    }
    Column(responsiveBounds.then(modifier).then(surface).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(if (handheldPage != null) 8.dp else 12.dp)) {
        if (page && centered) {
            Column(
                // Do not stretch short "no stations" messages into full-width cards on phones.
                if (handheldPage != null) Modifier else Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (handheldPage != null) 8.dp else 10.dp),
            ) {
                leadingIcon?.let { image ->
                    Box(
                        Modifier.size(handheldPage?.iconFrameDp?.dp ?: 64.dp)
                            .background(statusColor.copy(alpha = .12f),
                                RoundedCornerShape(if (handheldPage != null) 14.dp else 20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(image, contentDescription = null, tint = statusColor,
                            modifier = Modifier.size(handheldPage?.iconDp?.dp ?: 34.dp))
                    }
                }
                Text(title, style = titleStyle, color = colors.onSurface,
                    textAlign = TextAlign.Center)
                body?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                leadingIcon?.let {
                    Icon(it, contentDescription = null, tint = statusColor,
                        modifier = Modifier.size(if (page) handheldPage?.iconDp?.dp ?: 28.dp else 24.dp))
                }
                Column(Modifier.weight(1f, fill = !page), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = titleStyle, color = colors.onSurface)
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

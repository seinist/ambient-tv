package com.ambient.tv.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** A visible ambient surface takes precedence over hidden playback; screensavers win over projectM. */
internal class AmbientHdrLease(
    val windowOutput: Boolean,
    val priority: Int,
    val canRequest: () -> Boolean = { true },
    val update: (SdrToHdrRequest?) -> SdrToHdrStatus,
)

@Composable
internal fun RegisterAmbientHdr(windowOutput: Boolean, priority: Int, eligible: Boolean = true, update: (SdrToHdrRequest?) -> SdrToHdrStatus) {
    val host = LocalUiHdrState.current
    val current = rememberUpdatedState(update)
    val allowed = rememberUpdatedState(eligible)
    val lease = remember(windowOutput, priority) {
        AmbientHdrLease(windowOutput, priority, { allowed.value }) { current.value(it) }
    }
    DisposableEffect(host, lease) {
        host?.attachAmbient(lease)
        onDispose { host?.detachAmbient(lease) }
    }
}

/** Generated HDR must bootstrap its first frame before the display's measured ratio can rise.
 * This is a requested budget, not a fabricated measurement. The same strict display/peak checks
 * as video conversion are required. The budget uses the same UI cap, not a panel peak.
 */
internal fun ambientHdrHeadroom(facts: UiHdrEligibility, request: SdrToHdrRequest?): Float {
    if (request == null || request.peakNits !in 300..600 ||
        sdrToHdrRequest(facts, true, request.peakNits.toFloat(), request.displayId, request.displayModeId) == null) return 1f
    return facts.headroom?.takeIf { it > 1f }?.coerceAtMost(UI_HDR_HEADROOM_CAP) ?: UI_HDR_HEADROOM_CAP
}

/** Surface output confirms its HDR target before asking the window/display to switch. */
internal fun ambientSurfaceHdrEligibility(
    facts: UiHdrEligibility,
    request: SdrToHdrRequest?,
    status: SdrToHdrStatus,
): UiHdrEligibility {
    val active = request != null && status == SdrToHdrStatus.ACTIVE
    return facts.copy(
        sourceSupported = facts.sourceSupported && active,
        headroom = ambientHdrHeadroom(facts, request.takeIf { active }),
    )
}

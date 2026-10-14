package com.ambient.tv.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.util.QrCode
import kotlin.math.floor

/** Android TV overscan safe area: at least 5% and 27dp on each edge. */
internal const val QrOverscanFraction = 0.05f
internal val QrOverscanMinInset = 27.dp

/**
 * Height fraction for [AppDialog]. The dialog centers itself and splits the leftover as equal
 * top and bottom insets, so this keeps both edges in the safe area. Capped at 90%.
 */
internal fun qrDialogMaxHeightFraction(screenHeight: Dp): Float {
    if (screenHeight <= 0.dp) return 0.72f
    val inset = maxOf(screenHeight * QrOverscanFraction, QrOverscanMinInset)
    val fraction = (screenHeight - inset * 2) / screenHeight
    return fraction.coerceIn(0.40f, 1f - QrOverscanFraction * 2)
}

/** Largest square that fits the measured slot. Never larger than the slot, so modules are not cropped. */
internal fun qrSide(maxWidth: Dp, maxHeight: Dp): Dp {
    if (maxWidth <= 0.dp || maxHeight <= 0.dp) return 0.dp
    if (maxWidth == Dp.Infinity || maxHeight == Dp.Infinity) return 0.dp
    return minOf(maxWidth, maxHeight)
}

/** Full-screen QR padding. Phones stay tight so the code can use the safe area; TV keeps overscan. */
internal fun qrScreenInset(minEdge: Dp, television: Boolean): Dp {
    if (!television) return 12.dp
    if (minEdge <= 0.dp) return QrOverscanMinInset
    return maxOf(minEdge * QrOverscanFraction, QrOverscanMinInset)
}

/**
 * Shared QR graphic for Plex, YouTube, Spotify, and Companion pairing.
 *
 * The square is the minimum of the incoming max constraints. A parent that gives a finite slot
 * (dialog body or a weighted box) never crops modules. An unbounded parent draws nothing rather
 * than a fixed size that overflows the screen. The quiet zone is inside the square, and the
 * canvas is not clipped.
 */
@Composable
fun LoginQrCode(payload: String, modifier: Modifier = Modifier) {
    val qr = remember(payload) { runCatching { QrCode.encode(payload) }.getOrNull() } ?: return
    BoxWithConstraints(modifier) {
        val side = qrSide(maxWidth, maxHeight)
        if (side > 0.dp) {
            Canvas(Modifier.size(side).align(Alignment.Center)) {
                drawRect(Color.White)
                val modules = qr.size + 8 // 4-module quiet zone on every side
                val cell = floor(size.minDimension / modules)
                if (cell < 1f) return@Canvas
                val origin = (size.minDimension - cell * qr.size) / 2f
                for (y in 0 until qr.size) {
                    for (x in 0 until qr.size) {
                        if (qr[x, y]) {
                            drawRect(
                                Color.Black,
                                Offset(origin + x * cell, origin + y * cell),
                                Size(cell, cell),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val LoginWebScheme = Regex("^https?://", RegexOption.IGNORE_CASE)

/**
 * A payload a browser on this device can open. The Companion pairing deep link (`ambienttv://`)
 * is not one: it only means something on the *other* device.
 */
internal fun loginLinkIsWebUrl(payload: String): Boolean =
    LoginWebScheme.containsMatchIn(payload.trim())

/** Address shown next to the code. The scheme is noise on a phone screen. */
internal fun loginLinkLabel(payload: String): String =
    payload.trim().replaceFirst(LoginWebScheme, "").trimEnd('/')

/** Hands the link to the system browser. False when no activity can open it. */
internal fun openLoginLink(context: Context, payload: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(payload.trim()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrDefault(false)

/**
 * The handheld counterpart of [LoginQrCode]: nobody can scan the screen they are holding, so the
 * phone gets the code plus the link itself, opened in the browser on this device. The code stays
 * visible for the providers whose page does not carry it (plex.tv/link), and for typing it by hand
 * if no browser is installed.
 */
@Composable
fun LoginLinkPanel(payload: String, modifier: Modifier = Modifier, code: String? = null) {
    val context = LocalContext.current
    var noBrowser by remember(payload) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        code?.takeIf { it.isNotBlank() }?.let { typed ->
            Text(
                typed,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            loginLinkLabel(payload),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        if (loginLinkIsWebUrl(payload)) {
            TvActionButton(
                text = stringResource(R.string.action_open_in_browser),
                onClick = { noBrowser = !openLoginLink(context, payload) },
                fillWidth = true,
            )
            if (noBrowser) {
                Text(
                    stringResource(R.string.login_link_no_browser),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * QR prompt used by every dialog that shows a code. The panel stays inside the overscan safe
 * area, the body does not scroll (a TV cannot move a non-focusable code into view), and the
 * code is sized to the measured slot. Only the hint scrolls, and only inside 96dp.
 */
@Composable
fun QrCodeDialog(
    title: String,
    hint: String,
    payload: String,
    onDismiss: () -> Unit,
    code: String? = null,
    footer: @Composable RowScope.() -> Unit,
) {
    // A phone cannot scan its own display: it gets the link and the code instead of a QR.
    if (LocalHandheldUi.current) {
        AppDialog(onDismiss = onDismiss, title = title, footer = footer) {
            Text(hint)
            LoginLinkPanel(payload, Modifier.fillMaxWidth(), code)
        }
        return
    }
    val density = LocalDensity.current
    val screenHeight = with(density) {
        LocalContext.current.resources.displayMetrics.heightPixels.toDp()
    }.takeIf { it > 0.dp } ?: LocalConfiguration.current.screenHeightDp.dp
    AppDialog(
        onDismiss = onDismiss,
        title = title,
        fillHeight = true,
        scrollable = false,
        maxHeightFraction = qrDialogMaxHeightFraction(screenHeight),
        footer = footer,
    ) {
        Text(
            hint,
            modifier = Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState()),
        )
        val typed = code?.takeIf { it.isNotBlank() }
        if (typed != null) {
            Text(
                typed,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            LoginQrCode(payload, Modifier.fillMaxSize())
        }
    }
}

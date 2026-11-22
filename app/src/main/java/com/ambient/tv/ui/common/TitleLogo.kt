package com.ambient.tv.ui.common

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import coil3.request.crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Text
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.ambient.tv.network.artworkNetworkAccess
import com.ambient.tv.network.artworkRefreshRevision

/**
 * Title text that swaps in a provider ClearLogo when one loads.
 *
 * A fixed slot keeps metadata steady when items switch between logos and title text.
 * A minimum slot keeps logos bounded while allowing longer text to use the available height.
 * Failed or missing logos keep the original text; there is no placeholder tile.
 */
@Composable
fun TitleLogo(
    title: String,
    logoUrl: String?,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    color: Color = Color.Unspecified,
    maxLogoWidth: Dp = 420.dp,
    /** When true the slot is exactly one line of [textStyle], matching the Home hero title. */
    lockSlotToSingleLine: Boolean = false,
    /** Fixed slot height for logo and text alike, so hero content below never moves. Text drops to the lines that fit. */
    fixedSlotHeight: Dp? = null,
    /** The logo keeps this target height; fallback text can grow within its parent's measured height. */
    minSlotHeight: Dp? = null,
) {
    val density = LocalDensity.current
    val lineHeight = remember(textStyle, density) { textStyle.lineHeightDp(density) }
    val slotHeight = fixedSlotHeight ?: when {
        lockSlotToSingleLine -> lineHeight
        minSlotHeight != null -> minSlotHeight
        else -> lineHeight * maxLines.coerceAtLeast(1)
    }
    val textLines = when {
        lockSlotToSingleLine -> 1
        fixedSlotHeight != null -> (fixedSlotHeight.value / lineHeight.value).toInt().coerceIn(1, maxLines.coerceAtLeast(1))
        else -> maxLines
    }
    val context = LocalContext.current
    val maxWidthPx = with(density) { maxLogoWidth.roundToPx() }
    val maxHeightPx = with(density) { slotHeight.roundToPx() }
    val network = rememberArtworkNetworkAccess(logoUrl)
    val refresh = (context.applicationContext as? com.ambient.tv.AmbientTvApplication)?.mediaArtworkRefresh
    val sourceVersion = refresh?.sources?.collectAsState()?.value
    val address = remember(logoUrl, sourceVersion, refresh) { logoUrl?.let { refresh?.address(it) } }
    val refreshRevision = rememberArtworkRefresh(logoUrl, refresh, address)
    var successfulNetwork by remember(logoUrl, address?.imageKey, refreshRevision) {
        mutableStateOf<com.ambient.tv.network.ArtworkNetworkState?>(null)
    }
    val requestNetwork = successfulNetwork ?: network
    val request = remember(logoUrl, context, maxWidthPx, maxHeightPx, requestNetwork, address?.imageKey, refreshRevision) {
        logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
            // The logo is only drawn once it has loaded, so the request needs its own size:
            // without one Coil suspends until the painter's first draw, which never happens.
            ImageRequest.Builder(context).data(url).size(maxWidthPx, maxHeightPx).crossfade(false)
                .memoryCacheKeyExtra(com.ambient.tv.network.MEDIA_ARTWORK_IDENTITY_EXTRA, address?.imageKey)
                .artworkRefreshRevision(refreshRevision)
                .artworkNetworkAccess(requestNetwork).build()
        }
    }
    // Hero logos follow focus like backdrops; they share the backdrop cache, not the cards'.
    val painter = rememberAsyncImagePainter(model = request, imageLoader = com.ambient.tv.backdropImageLoader(context))
    val painterState by painter.state.collectAsState()
    LaunchedEffect(painterState) {
        when (painterState) {
            is AsyncImagePainter.State.Success -> successfulNetwork = requestNetwork
            is AsyncImagePainter.State.Error -> successfulNetwork = null
            else -> Unit
        }
    }
    val intrinsic = painter.intrinsicSize
    val aspect = if (
        painterState is AsyncImagePainter.State.Success &&
        intrinsic.width.isFinite() &&
        intrinsic.height.isFinite() &&
        intrinsic.width > 0f &&
        intrinsic.height > 0f
    ) {
        intrinsic.width / intrinsic.height
    } else {
        0f
    }
    val showLogo = aspect > 0f

    Box(
        modifier = modifier.then(
            when {
                lockSlotToSingleLine -> Modifier.height(lineHeight)
                fixedSlotHeight != null -> Modifier.height(fixedSlotHeight)
                minSlotHeight != null -> Modifier.heightIn(min = minSlotHeight)
                else -> Modifier.heightIn(min = lineHeight)
            },
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (showLogo) {
            val fittedWidth = slotHeight * aspect
            val width = if (fittedWidth > maxLogoWidth) maxLogoWidth else fittedWidth
            val height = if (fittedWidth > maxLogoWidth) (maxLogoWidth.value / aspect).dp else slotHeight
            Image(
                painter = painter,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier.height(height).width(width),
            )
        } else {
            Text(
                text = title,
                style = textStyle,
                color = color,
                maxLines = textLines,
                overflow = overflow,
            )
        }
    }
}

internal fun TextStyle.lineHeightDp(density: Density): Dp = with(density) {
    val lh = lineHeight
    when (lh.type) {
        TextUnitType.Sp -> lh.toDp()
        TextUnitType.Em -> if (fontSize.type == TextUnitType.Sp) (fontSize * lh.value).toDp() else 30.dp
        else -> if (fontSize.type == TextUnitType.Sp) (fontSize * 1.25f).toDp() else 30.dp
    }
}

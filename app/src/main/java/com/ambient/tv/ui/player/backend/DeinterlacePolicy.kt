package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.DeinterlaceCatalog

internal fun vlcDeinterlaceOptions(mode: String, filter: String): List<String> = buildList {
    val state = when (mode) { "auto" -> -1; "on" -> 1; else -> 0 }
    val method = DeinterlaceCatalog.normalize("vlc", filter)
    add("--deinterlace=$state")
    add("--deinterlace-mode=$method")
    if (state != 0 && method != "auto") add("--no-mediacodec-dr")
}

/**
 * Live TS reports interlaced 576i50/1080i50 (and 1080i60) at the field rate, and the decoder
 * already delivers one picture per field. Anything above 40 fps is such a field-rate input:
 * progressive sources never exceed 30 fps per frame pair, 50p/60p is already the output rate.
 */
internal const val MPV_FIELD_RATE_MIN_FPS = 40.0

internal fun mpvFieldRateInput(sourceFps: Double?): Boolean =
    sourceFps != null && sourceFps.isFinite() && sourceFps > MPV_FIELD_RATE_MIN_FPS

/**
 * Whether the deinterlacer emits two pictures per input picture. "auto" is mpv's built-in
 * deinterlacer, which inserts bwdif/yadif in send_field mode. [singleRate] is the field-rate
 * guard of [mpvDeinterlaceGraph]. IVTC removes frames and is not counted as doubling.
 */
internal fun mpvDeinterlaceDoublesRate(key: String, singleRate: Boolean): Boolean =
    !singleRate && DeinterlaceCatalog.normalize("mpv", key) in setOf(
        "auto", "bwdif2x", "yadif2x", "w3fdif2x", "estdif2x", "nnedi2x", "mcdeint", "mcdeint_bff",
    )

/**
 * Picture rate leaving the deinterlacer: what SVP receives and what the display should match.
 * Field-rate inputs stay at their rate; 25/29.97 fps interlaced frames double to 50/59.94.
 */
internal fun mpvDeinterlacedFps(sourceFps: Double?, deinterlaceEnabled: Boolean, key: String, singleRate: Boolean): Double? {
    val fps = sourceFps?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return if (deinterlaceEnabled && mpvDeinterlaceDoublesRate(key, singleRate)) fps * 2.0 else fps
}

/**
 * A single lavfi graph allows IVTC's field matching, cleanup and decimation to be atomic.
 * [singleRate]: the input already runs at the field rate, so every method emits one picture per
 * input picture (2x variants and mpv's built-in send_field would double 50 to 100 fps).
 * "auto" then becomes an explicit single-rate BWDIF instead of mpv's double-rate built-in.
 */
internal fun mpvDeinterlaceGraph(key: String, automatic: Boolean, weightsPath: String? = null, singleRate: Boolean = false): String? {
    val deint = if (automatic) "interlaced" else "all"
    val normalized = DeinterlaceCatalog.normalize("mpv", key)
    if (singleRate) {
        when (normalized) {
            "auto", "bwdif2x", "mcdeint", "mcdeint_bff" -> return "bwdif=mode=send_frame:parity=auto:deint=$deint"
            "yadif2x" -> return "yadif=mode=send_frame:parity=auto:deint=$deint"
            "w3fdif2x" -> return "w3fdif=mode=frame:parity=auto:deint=$deint"
            "estdif2x" -> return "estdif=mode=frame:parity=auto:deint=$deint"
            "nnedi2x" -> return mpvDeinterlaceGraph("nnedi", automatic, weightsPath)
        }
    }
    return when (normalized) {
        "bwdif", "yadif" -> "$key=mode=send_frame:parity=auto:deint=$deint"
        "bwdif2x", "yadif2x" -> "${key.removeSuffix("2x")}=mode=send_field:parity=auto:deint=$deint"
        "w3fdif", "estdif" -> "$key=mode=frame:parity=auto:deint=$deint"
        "w3fdif2x", "estdif2x" -> "${key.removeSuffix("2x")}=mode=field:parity=auto:deint=$deint"
        "nnedi", "nnedi2x" -> {
            require(!weightsPath.isNullOrBlank()) { "NNEDI weights are required" }
            // Only an app-private, controlled path is accepted, never an arbitrary filter expression.
            require(weightsPath.none { it in "':,[]\\" }) { "Invalid NNEDI weights path" }
            "nnedi=weights=$weightsPath:field=${if (key == "nnedi2x") "af" else "a"}:deint=$deint:nsize=s8x4:nns=n16:qual=fast"
        }
        "kerndeint" -> "kerndeint" // Legacy filter has its own comb threshold and fixed field order.
        "mcdeint", "mcdeint_bff" -> {
            // MCDeint alternates parity, so supply one frame per field in a fixed, matching order.
            val parity = if (key == "mcdeint_bff") "bff" else "tff"
            "yadif=mode=send_field_nospatial:parity=$parity:deint=all,mcdeint=mode=fast:parity=$parity:qp=1"
        }
        "ivtc" -> "fieldmatch=order=auto:combmatch=full,yadif=deint=interlaced,decimate"
        else -> null
    }
}

/**
 * Cadence the live display should match: 576i/1080i50 reported as 25 fps but deinterlaced
 * double-rate is 50 fps; reported at the field rate it stays 50 (never 100). Direct output
 * cannot run the app's deinterlacer, so it keeps the reported rate.
 */
internal fun mpvLiveDisplayFps(containerFps: Double?, gpuFilters: Boolean, deinterlaceMode: String, deinterlaceFilter: String): Double? {
    if (!gpuFilters || deinterlaceMode == "off") return containerFps?.takeIf { it.isFinite() && it > 0.0 }
    return mpvDeinterlacedFps(containerFps, true, deinterlaceFilter, mpvFieldRateInput(containerFps))
}

package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.DeinterlaceCatalog

internal fun vlcDeinterlaceOptions(mode: String, filter: String): List<String> = buildList {
    val state = when (mode) { "auto" -> -1; "on" -> 1; else -> 0 }
    val method = DeinterlaceCatalog.normalize("vlc", filter)
    add("--deinterlace=$state")
    add("--deinterlace-mode=$method")
    if (state != 0 && method != "auto") add("--no-mediacodec-dr")
}

/** A single lavfi graph allows IVTC's field matching, cleanup and decimation to be atomic. */
internal fun mpvDeinterlaceGraph(key: String, automatic: Boolean, weightsPath: String? = null): String? {
    val deint = if (automatic) "interlaced" else "all"
    return when (DeinterlaceCatalog.normalize("mpv", key)) {
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

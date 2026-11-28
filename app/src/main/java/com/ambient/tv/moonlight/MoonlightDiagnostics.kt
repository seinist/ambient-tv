package com.ambient.tv.moonlight

/** Bound by UTF-16 units without splitting a supplementary Unicode character.
 * Reject control characters except the explicitly supported newline and tab. */
internal fun moonlightBoundedText(input: CharSequence): String = buildString {
    var i = 0
    // Bound the amount inspected even if a clipboard contains huge amounts of data.
    val limit = minOf(input.length, 4096)
    while (i < limit && length < 512) {
        val ch = input[i++]
        if (ch.isHighSurrogate()) {
            if (i < limit && input[i].isLowSurrogate()) {
                if (length + 2 > 512) break
                append(ch); append(input[i++])
            }
        } else if (!ch.isLowSurrogate() && (!ch.isISOControl() || ch == '\n' || ch == '\t')) append(ch)
    }
}

/** Explicit, redacted diagnostic snapshot. No account, host, app, or clipboard contents. */
internal fun moonlightDiagnosticReport(metrics: MoonlightStreamMetrics, displayHdr10: Boolean, displayMode: String): String =
    buildString {
        val video = metrics.video
        val audio = metrics.audio
        appendLine("Moonlight diagnostics v1")
        appendLine("display_mode=$displayMode display_hdr10_capable=$displayHdr10")
        appendLine("decoder=${video.decoder} mime=${video.mime} size=${video.width}x${video.height} fps=${video.fps}")
        appendLine("hdr_signaled=${video.hdr} decoder_hdr_evidence=${video.hdrOutputEvidence} color_standard=${video.outputColorStandard} color_transfer=${video.outputColorTransfer} color_range=${video.outputColorRange} hdr_static_bytes=${video.outputHdrStaticBytes}")
        appendLine("received=${video.received} missing=${video.missing} released=${video.rendered} dropped=${video.dropped} presented=${video.presented}")
        appendLine("output_fps=${metrics.outputFps} presented_fps=${metrics.presentedFps} assembly_p95_ms=${video.assemblyP95Ms} release_p95_ms=${video.releaseP95Ms} presentation_p95_ms=${video.presentationP95Ms}")
        appendLine("rtt_ms=${metrics.rttMs} rtt_variance_ms=${metrics.rttVarianceMs} pending_video=${metrics.pendingVideoFrames} input_dispatch_p95_ms=${metrics.inputDispatchP95Ms} pending_inputs=${metrics.pendingInputs}")
        appendLine("audio_rate=${audio.rate} channels=${audio.channels} underruns=${audio.underruns} dropped_samples=${audio.droppedSamples} queue_ms=${audio.estimatedQueuedMs} av_queue_difference_ms=${metrics.avQueueDifferenceMs}")
        appendLine("Decoder/Surface observations only; not physical display or input-to-photon measurements.")
    }

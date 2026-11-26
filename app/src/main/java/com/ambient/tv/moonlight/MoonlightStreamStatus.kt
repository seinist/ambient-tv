package com.ambient.tv.moonlight

data class MoonlightStreamStatus(
    val phase: MoonlightStreamPhase = MoonlightStreamPhase.IDLE,
    val title: String = "",
    val detail: String = "",
    val error: String? = null,
    val metrics: MoonlightStreamMetrics = MoonlightStreamMetrics(),
    val retryable: Boolean = false,
    val owner: Any? = null,
    val generation: Long = 0L,
    val rejectedVideoFormat: Int = 0,
    val adaptNetworkQuality: Boolean = false,
) {
    val active: Boolean get() = phase in setOf(
        MoonlightStreamPhase.CONNECTING, MoonlightStreamPhase.STREAMING, MoonlightStreamPhase.STOPPING)
}

/** Session-local statistics, sampled by the engine rather than recomposed per frame. */
data class MoonlightVideoMetrics(
    val mime: String = "", val decoder: String = "", val width: Int = 0,
    val height: Int = 0, val fps: Int = 0, val hdr: Boolean = false,
    val received: Long = 0, val missing: Long = 0, val rendered: Long = 0,
    val dropped: Long = 0, val decodeMicros: Long = 0,
    val hostTenthsMs: Long = 0, val hostSamples: Long = 0,
    val presented: Long = 0,
    val assemblyP95Ms: Int? = null,
    val releaseP95Ms: Int? = null,
    val presentationP95Ms: Int? = null,
)
data class MoonlightAudioMetrics(
    val channels: Int = 0, val rate: Int = 0, val bufferFrames: Int = 0,
    val underruns: Int = 0, val droppedSamples: Long = 0,
    val estimatedQueuedMs: Double? = null,
    val nativeQueuedMs: Int = 0,
    val recovering: Boolean = false,
)
data class MoonlightStreamMetrics(
    val video: MoonlightVideoMetrics = MoonlightVideoMetrics(),
    val audio: MoonlightAudioMetrics = MoonlightAudioMetrics(),
    val rttMs: Int? = null, val rttVarianceMs: Int? = null,
    val outputFps: Double = 0.0,
    val presentedFps: Double = 0.0,
    val pendingVideoFrames: Int = 0,
    val networkPoor: Boolean = false,
    val inputDispatchP95Ms: Int? = null,
    val pendingInputs: Int = 0,
)

package com.ambient.tv.ui.player.backend

/**
 * Adapt the pinned NIS kernel to GLSL ES 3.1's explicit conversion and constant-expression rules.
 * Desktop GLSL accepts runtime local const initializers and implicit numeric conversions that
 * GLES rejects. Keep global constants, filter tables, unsigned index arithmetic and the original
 * order of integer arithmetic before float conversion. The Vulkan source stays untouched.
 */
internal fun nisOpenGlSource(source: String): String = nisOpenGlConversions.fold(
    source.replace(Regex("""(?m)^([ \t]+)const """), "$1"),
) { adapted, (before, after) -> adapted.replace(before, after) }

private val nisOpenGlConversions = listOf(
    "const float kDetectRatio = 2 * 1127.f / 1024.f;" to "const float kDetectRatio = 2.0 * 1127.f / 1024.f;",
    "#define saturate(x) clamp(x, 0, 1)" to "#define saturate(x) clamp(x, 0.0, 1.0)",
    "float e_0_90 = 0;" to "float e_0_90 = 0.0;",
    "float e_45_135 = 0;" to "float e_45_135 = 0.0;",
    "g_0_90_max + g_45_135_max == 0)" to "g_0_90_max + g_45_135_max == 0.0)",
    "pphase_p45 >= 1)" to "pphase_p45 >= 1.0)",
    "pphase_p45 - 1;" to "pphase_p45 - 1.0;",
    "pphase_p45 * 64)" to "pphase_p45 * 64.0)",
    "pphase_p135 = 1 +" to "pphase_p135 = 1.0 +",
    "pphase_p135 >= 1)" to "pphase_p135 >= 1.0)",
    "pphase_p135 - 1;" to "pphase_p135 - 1.0;",
    "pphase_p135 * 64)" to "pphase_p135 * 64.0)",

    // Signed destination coordinates are converted only after their integer sums.
    "(dstBlockX + 0.5f)" to "(float(dstBlockX) + 0.5f)",
    "(dstBlockY + 0.5f)" to "(float(dstBlockY) + 0.5f)",
    "(dstBlockX + NIS_BLOCK_WIDTH + 0.5f)" to "(float(dstBlockX + NIS_BLOCK_WIDTH) + 0.5f)",
    "(dstBlockY + NIS_BLOCK_HEIGHT + 0.5f)" to "(float(dstBlockY + NIS_BLOCK_HEIGHT) + 0.5f)",
    "0.5f - (kSupportSize - 1) / 2" to "0.5f - float((kSupportSize - 1) / 2)",
    // Desktop GLSL promotes the signed source origin to uint before adding px/py.
    "(srcBlockStartX + px + kShift)" to "(float(uint(srcBlockStartX) + px) + kShift)",
    "(srcBlockStartY + py + kShift)" to "(float(uint(srcBlockStartY) + py) + kShift)",
    "(0.5f + dstX)" to "(0.5f + float(dstX))",
    "floor(srcX) - srcBlockStartX" to "floor(srcX) - float(srcBlockStartX)",
    "fx * kPhaseCount" to "fx * float(kPhaseCount)",
    "(0.5f + dstY)" to "(0.5f + float(dstY))",
    "floor(srcY) - srcBlockStartY" to "floor(srcY) - float(srcBlockStartY)",
    "fy * kPhaseCount" to "fy * float(kPhaseCount)",
    "GetInterpEdgeMap(edge, fx, fy) * NIS_SCALE_INT" to "GetInterpEdgeMap(edge, fx, fy) * float(NIS_SCALE_INT)",
    "float opY = 0;" to "float opY = 0.0;",

    // Keep the workgroup/tile indices unsigned; no uint-to-int rewrite is needed.
    "NIS_BLOCK_WIDTH * blockIdx.x" to "uint(NIS_BLOCK_WIDTH) * blockIdx.x",
    "NIS_BLOCK_HEIGHT * blockIdx.y" to "uint(NIS_BLOCK_HEIGHT) * blockIdx.y",
    "threadIdx * 2;" to "threadIdx * 2u;",
    "i += NIS_THREAD_GROUP_SIZE * 2)" to "i += uint(NIS_THREAD_GROUP_SIZE) * 2u)",
    "(i / numTilePixelsX) * 2;" to "(i / uint(numTilePixelsX)) * 2u;",
    "i % numTilePixelsX;" to "i % uint(numTilePixelsX);",
    "(i / numEdgeMapPixelsX) * 2;" to "(i / uint(numEdgeMapPixelsX)) * 2u;",
    "i % numEdgeMapPixelsX;" to "i % uint(numEdgeMapPixelsX);",
    "uint idx = py * kTilePitch + px;" to "uint idx = py * uint(kTilePitch) + px;",
    "idx + 1]" to "idx + 1u]",
    "idx + kTilePitch]" to "idx + uint(kTilePitch)]",
    "idx + kTilePitch + 1]" to "idx + uint(kTilePitch) + 1u]",
    "uint edgeMapIdx = py * kEdgeMapPitch + px;" to "uint edgeMapIdx = py * uint(kEdgeMapPitch) + px;",
    "(py+1) * kTilePitch + px + 1" to "(py+1u) * uint(kTilePitch) + px + 1u",
    "tileCornerIdx + j * kTilePitch + k" to "tileCornerIdx + uint(j * kTilePitch + k)",
    "edgeMapIdx + 1]" to "edgeMapIdx + 1u]",
    "edgeMapIdx + kEdgeMapPitch]" to "edgeMapIdx + uint(kEdgeMapPitch)]",
    "edgeMapIdx + kEdgeMapPitch + 1]" to "edgeMapIdx + uint(kEdgeMapPitch) + 1u]",
    // The non-gather fallback must compile too, even on drivers which expose gather.
    "tx + k * kSrcNormX, ty + j * kSrcNormY" to "tx + float(k) * kSrcNormX, ty + float(j) * kSrcNormY",
)

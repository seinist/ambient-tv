package com.ambient.tv.ui.player

/**
 * A float precision declaration does not set sampler precision: an unqualified sampler2D
 * otherwise stays lowp. That loses signed FP16 feature values and precision in weight LUTs.
 * Match the existing float precision branches, including the mediump fallback for light
 * GLES2 filters. A default also covers sampler parameters such as SSim's ssimSample.
 * Apply this once, when constructing every Media3 fragment program.
 */
internal fun media3FragmentSamplerPrecision(fragment: String): String = fragment
    .replace("precision highp float;", "precision highp float;\nprecision highp sampler2D;")
    .replace("precision mediump float;", "precision mediump float;\nprecision mediump sampler2D;")

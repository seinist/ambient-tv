package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.AudioPassthroughPolicy
import kotlin.math.log10

internal fun vlcEncodedDevice(codecs: Collection<String>): String {
    val mask = codecs.mapNotNull { AudioPassthroughPolicy.encodingForCodec(it) }
        .fold(0L) { mask, encoding -> mask or (1L shl encoding) }
    return "encoded:$mask"
}

/** Worst coherent peak of VLC simple.c's unnormalised channel matrices. */
internal fun vlcDownmixHeadroomDb(channels: Int?): Float {
    val peak = when (channels) {
        1, 2 -> 1.0
        3 -> 1.5
        4 -> 2.5
        5, 6 -> 1.0 + 2.0 * 0.707107
        8 -> 1.5 + 0.707107
        else -> 2.0 + 2.0 * 0.707107
    }
    return (20.0 * log10(peak)).toFloat()
}

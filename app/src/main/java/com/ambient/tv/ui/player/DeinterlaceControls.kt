package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.DeinterlaceCatalog
import com.ambient.tv.data.local.DeinterlaceGroup
import com.ambient.tv.ui.common.GroupedPlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerDropUpSection
import com.ambient.tv.ui.player.backend.effectiveDeinterlaceFilter
import com.ambient.tv.ui.performance.LocalPerformanceProfile

@Composable
internal fun DeinterlaceMethodChoice(backend: String, key: String, onSelected: (String) -> Unit, showHints: Boolean = true) {
    val methods = DeinterlaceCatalog.methods(backend)
    val selected = DeinterlaceCatalog.normalize(backend, key)
    val sections = methods.groupBy { it.group }.map { (group, entries) ->
        PlayerDropUpSection(stringResource(when (group) {
            DeinterlaceGroup.AUTOMATIC -> R.string.deint_group_auto
            DeinterlaceGroup.LIGHT -> R.string.deint_group_light
            DeinterlaceGroup.BALANCED -> R.string.deint_group_balanced
            DeinterlaceGroup.DOUBLE_RATE -> R.string.deint_group_double
            DeinterlaceGroup.EXPERT -> R.string.deint_group_expert
            DeinterlaceGroup.FILM -> R.string.deint_group_film
        }), entries.map { it.key to it.label })
    }
    GroupedPlayerDropUpSelector(
        label = stringResource(R.string.tvh_deinterlace_method),
        value = methods.firstOrNull { it.key == selected }?.label ?: selected,
        sections = sections, selectedKey = selected, onSelected = onSelected,
    )
    if (!showHints) return
    ImageHint(stringResource(when (selected) {
        "auto" -> R.string.deint_hint_auto
        "discard" -> R.string.deint_hint_discard
        "mean" -> R.string.deint_hint_mean
        "blend" -> R.string.deint_hint_blend
        "bob", "linear" -> R.string.deint_hint_bob
        "bwdif", "yadif" -> R.string.deint_hint_balanced
        "bwdif2x", "yadif2x" -> R.string.deint_hint_double
        "w3fdif", "w3fdif2x" -> R.string.deint_hint_w3fdif
        "estdif", "estdif2x" -> R.string.deint_hint_estdif
        "nnedi", "nnedi2x" -> R.string.deint_hint_nnedi
        "kerndeint" -> R.string.deint_hint_kernel
        "mcdeint", "mcdeint_bff" -> R.string.deint_hint_mcdeint
        "phosphor" -> R.string.deint_hint_phosphor
        "ivtc" -> R.string.deint_hint_ivtc
        else -> R.string.deint_hint_x
    }))
    val effective = effectiveDeinterlaceFilter(selected, LocalPerformanceProfile.current.gateExpensiveVideoVisuals)
    if (effective != selected) ImageHint(stringResource(R.string.deint_low_override,
        methods.firstOrNull { it.key == effective }?.label ?: effective))
}

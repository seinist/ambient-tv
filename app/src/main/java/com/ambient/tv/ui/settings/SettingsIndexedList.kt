package com.ambient.tv.ui.settings

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalSettingsItemIndex = staticCompositionLocalOf { SettingsItemIndex() }

/** Keep the list lazy while recording keys from its real, conditionally built contents. */
internal fun LazyListScope.indexedSettingsItems(index: SettingsItemIndex, content: LazyListScope.() -> Unit) {
    val delegate = this
    val positions = linkedMapOf<String, Int>()
    var position = 0
    val indexed = object : LazyListScope by delegate {
        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            if (key is String) positions[key] = position
            position++
            delegate.item(key, contentType, content)
        }

        override fun items(
            count: Int,
            key: ((Int) -> Any)?,
            contentType: (Int) -> Any?,
            itemContent: @Composable LazyItemScope.(Int) -> Unit,
        ) {
            if (key != null) repeat(count) { offset ->
                (key(offset) as? String)?.let { positions[it] = position + offset }
            }
            position += count
            delegate.items(count, key, contentType, itemContent)
        }
    }
    indexed.content()
    index.replace(positions)
}

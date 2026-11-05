package com.ambient.tv.ui.player

import android.content.res.Configuration
import com.ambient.tv.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** All playback backends share image_deblock; never ship a template expression as its label. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DeblockLabelResourceTest {
    @Test fun deblockLabelIsLocalizedAndContainsNoTemplatePlaceholder() {
        val context = RuntimeEnvironment.getApplication()
        for ((locale, expected) in listOf(
            "de" to "Entblocken",
            "en" to "Deblocking",
            "fr" to "Deblocking", // Default resources are the English fallback.
        )) {
            val config = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            }
            val label = context.createConfigurationContext(config).getString(R.string.image_deblock)
            assertEquals(locale, expected, label)
            assertFalse(locale, label.contains('{') || label.contains('}'))
        }
    }
}

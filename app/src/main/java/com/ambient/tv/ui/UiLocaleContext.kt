package com.ambient.tv.ui

import android.content.Context
import android.os.LocaleList
import com.ambient.tv.data.local.LanguagePreferences
import java.util.Locale

/** Applies only the app UI locale. Metadata/audio Auto continue to use Resources.getSystem(). */
object UiLocaleContext {
    private const val PREFS_NAME = "ambient_settings"
    private const val KEY_UI_LANGUAGE = "ui_language"

    fun wrap(base: Context): Context {
        val key = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_UI_LANGUAGE, LanguagePreferences.AUTO)
            ?: LanguagePreferences.AUTO
        if (key == LanguagePreferences.AUTO) return base

        val locale = Locale.forLanguageTag(key.replace('_', '-'))
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Keep Application-context strings in sync immediately; the Activity itself is recreated. */
    @Suppress("DEPRECATION")
    fun applyToResources(context: Context, languageKey: String) {
        val locale = if (languageKey == LanguagePreferences.AUTO) {
            val system = android.content.res.Resources.getSystem().configuration
            if (android.os.Build.VERSION.SDK_INT >= 24) system.locales[0] else system.locale
        } else {
            Locale.forLanguageTag(languageKey.replace('_', '-'))
        }
        val resources = context.resources
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(locale)
        if (android.os.Build.VERSION.SDK_INT >= 24) config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}

package com.ambient.tv.ui.radio

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

internal fun preferredRadioCountryCode(
    networkCountryIso: String?,
    simCountryIso: String?,
    configuredCountry: String?,
    defaultCountry: String?,
): String? = sequenceOf(networkCountryIso, simCountryIso, configuredCountry, defaultCountry)
    .mapNotNull { it?.trim()?.uppercase(Locale.ROOT)?.takeIf { code -> code.length == 2 && code.all(Char::isLetter) } }
    .firstOrNull()

internal fun preferredRadioCountryCode(context: Context): String? {
    val telephony = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
    val configuredCountry = context.resources.configuration.locales
        .takeIf { !it.isEmpty }?.get(0)?.country
    return preferredRadioCountryCode(
        networkCountryIso = runCatching { telephony?.networkCountryIso }.getOrNull(),
        simCountryIso = runCatching { telephony?.simCountryIso }.getOrNull(),
        configuredCountry = configuredCountry,
        defaultCountry = Locale.getDefault().country,
    )
}

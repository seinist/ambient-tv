package com.ambient.tv.data.local

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RegularPrefs

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EncryptedPrefs

/** Per-title picture settings: own file so the main settings file stays small to load and write. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TitleSettingsPrefs

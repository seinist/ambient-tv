package com.ambient.tv.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ambient.tv.data.local.CachedSharedPreferences
import com.ambient.tv.data.local.EncryptedPrefs
import com.ambient.tv.data.local.RegularPrefs
import com.ambient.tv.data.local.TitleSettingsPrefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    @RegularPrefs
    fun provideRegularPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("ambient_settings", Context.MODE_PRIVATE)

    @Provides
    @Singleton
    @TitleSettingsPrefs
    fun provideTitleSettingsPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("title_video_settings", Context.MODE_PRIVATE)

    @Provides
    @Singleton
    @EncryptedPrefs
    fun provideEncryptedPreferences(@ApplicationContext context: Context): SharedPreferences =
        CachedSharedPreferences(openEncryptedPreferences(context))

    /**
     * A broken Android keystore entry or a keyset that no longer decrypts (restored app data,
     * vendor keystore reset) makes EncryptedSharedPreferences throw on every start. Recover by
     * recreating the store: first the file, then also the master key. Stored logins are lost and
     * have to be entered again, but the app starts.
     */
    // security-crypto is deprecated without a successor. Migrating to Keystore/Tink needs a
    // data migration of the stored logins and is tracked separately.
    @Suppress("DEPRECATION")
    private fun openEncryptedPreferences(context: Context): SharedPreferences {
        fun create(): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        runCatching { return create() }
        // One retry first: a keystore that is briefly busy must not cost the stored logins.
        Thread.sleep(250)
        runCatching { return create() }.onFailure { timber.log.Timber.w(it, "Encrypted settings unreadable; recreating") }
        context.deleteSharedPreferences(ENCRYPTED_PREFS_NAME)
        runCatching { return create() }.onFailure { timber.log.Timber.w(it, "Encrypted settings key unusable; recreating key") }
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
        context.deleteSharedPreferences(ENCRYPTED_PREFS_NAME)
        return create()
    }

    private const val ENCRYPTED_PREFS_NAME = "ambient_secure_settings"
}

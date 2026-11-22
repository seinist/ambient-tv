package com.ambient.tv.games.metadata

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.BuiltInKeys
import com.ambient.tv.data.local.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Portable preferences only. Application/account credentials never live in this object. */
@Serializable
data class GameMetadataOptions(
    val provider: String = GameMetadataProviderIds.LIBRETRO,
    val automatic: Boolean = true,
    val region: String = "eu",
    val language: String = "auto",
) {
    val enabled: Boolean get() = provider != "none"

    fun normalized(): GameMetadataOptions = copy(
        provider = provider.takeIf { it in PROVIDERS } ?: GameMetadataProviderIds.LIBRETRO,
        region = region.lowercase(Locale.ROOT).takeIf { it in REGIONS } ?: "eu",
        language = language.lowercase(Locale.ROOT).takeIf { it in LANGUAGES } ?: "auto",
    )

    companion object {
        val PROVIDERS = setOf("none", GameMetadataProviderIds.LIBRETRO,
            GameMetadataProviderIds.SCREENSCRAPER, GameMetadataProviderIds.THEGAMESDB)
        val REGIONS = listOf("eu", "de", "us", "jp", "wor")
        val LANGUAGES = listOf("auto", "de", "en", "fr", "es", "it", "pt", "ru", "ja")
    }
}

/** Kept in encrypted preferences; exported only through explicit credential sharing. */
@Serializable
data class GameMetadataCredentials(
    val screenScraperDevId: String? = null,
    val screenScraperDevPassword: String? = null,
    val screenScraperUser: String? = null,
    val screenScraperPassword: String? = null,
    val theGamesDbApiKey: String? = null,
) {
    val screenScraperReady: Boolean get() = !screenScraperDevId.isNullOrBlank() && !screenScraperDevPassword.isNullOrBlank()
    val theGamesDbReady: Boolean get() = !theGamesDbApiKey.isNullOrBlank()
    val isEmpty: Boolean get() = this == GameMetadataCredentials()

    fun validated(): GameMetadataCredentials {
        require((screenScraperDevId == null) == (screenScraperDevPassword == null)) { "Incomplete ScreenScraper application credentials" }
        require((screenScraperUser == null) == (screenScraperPassword == null)) { "Incomplete ScreenScraper account credentials" }
        listOf(screenScraperDevId, screenScraperUser, theGamesDbApiKey).forEach { value ->
            require(value == null || value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)) { "Invalid game metadata credentials" }
        }
        listOf(screenScraperDevPassword, screenScraperPassword).forEach { value ->
            require(value == null || value.isNotEmpty() && value.length <= 1024 && value.none(Char::isISOControl)) { "Invalid game metadata credentials" }
        }
        return this
    }

    /** Error/debug output must never disclose keys, even when a containing data class is logged. */
    override fun toString(): String = "GameMetadataCredentials(redacted)"
}

@Singleton
class GameMetadataSettings @Inject constructor(
    private val preferences: AppPreferences,
    private val tokens: TokenStore,
) {
    fun options(): GameMetadataOptions = preferences.gameMetadataOptions
    fun update(options: GameMetadataOptions) { preferences.gameMetadataOptions = options.normalized() }
    val changes: Flow<GameMetadataOptions> get() = preferences.changes().map { options() }
        .onStart { emit(options()) }.distinctUntilChanged()
    fun credentials(): GameMetadataCredentials = tokens.gameMetadataCredentials()
    fun editableCredentials(): GameMetadataCredentials = tokens.exportGameMetadataCredentials() ?: GameMetadataCredentials()
    fun saveCredentials(value: GameMetadataCredentials) = tokens.setGameMetadataCredentials(value.validated())
    val screenScraperBuiltIn: Boolean get() = BuiltInKeys.screenScraper != null
    val theGamesDbBuiltIn: Boolean get() = BuiltInKeys.theGamesDbApiKey != null

    fun language(): String {
        val selected = options().language
        if (selected != "auto") return selected
        val metadataLanguage = preferences.metadataLanguageKey.substringBefore('-').lowercase(Locale.ROOT)
        return metadataLanguage.takeIf { it in GameMetadataOptions.LANGUAGES && it != "auto" }
            ?: Locale.getDefault().language.lowercase(Locale.ROOT).takeIf { it in GameMetadataOptions.LANGUAGES }
            ?: "en"
    }
}
